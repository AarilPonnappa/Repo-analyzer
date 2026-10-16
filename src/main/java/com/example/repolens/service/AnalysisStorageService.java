package com.example.repolens.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.repolens.dto.AnalysisResult;
import com.example.repolens.dto.GraphResult;
import com.example.repolens.dto.ParsedCode.ClassInfo;
import com.example.repolens.dto.ParsedCode.EndpointInfo;
import com.example.repolens.dto.RiskResult;
import com.example.repolens.dto.TraceNode;
import com.example.repolens.model.AnalysisRun;
import com.example.repolens.model.ApiEndpoint;
import com.example.repolens.model.ClassNode;
import com.example.repolens.model.DependencyEdge;
import com.example.repolens.model.Finding;
import com.example.repolens.model.Metric;
import com.example.repolens.model.RepositoryEntity;
import com.example.repolens.repository.AnalysisRunRepository;
import com.example.repolens.repository.ApiEndpointRepository;
import com.example.repolens.repository.ClassNodeRepository;
import com.example.repolens.repository.DependencyEdgeRepository;
import com.example.repolens.repository.FindingRepository;
import com.example.repolens.repository.MetricRepository;
import com.example.repolens.repository.RepositoryEntityRepository;

import tools.jackson.databind.ObjectMapper;

/**
 * The only place that writes analysis data to the database.
 * The analysis services just compute and return results; this class saves them.
 */
@Service
public class AnalysisStorageService {

    private final RepositoryEntityRepository repositories;
    private final AnalysisRunRepository runs;
    private final ClassNodeRepository classNodes;
    private final DependencyEdgeRepository edges;
    private final ApiEndpointRepository endpoints;
    private final MetricRepository metrics;
    private final FindingRepository findings;
    private final ObjectMapper objectMapper;

    public AnalysisStorageService(RepositoryEntityRepository repositories, AnalysisRunRepository runs,
                                  ClassNodeRepository classNodes, DependencyEdgeRepository edges,
                                  ApiEndpointRepository endpoints, MetricRepository metrics,
                                  FindingRepository findings, ObjectMapper objectMapper) {
        this.repositories = repositories;
        this.runs = runs;
        this.classNodes = classNodes;
        this.edges = edges;
        this.endpoints = endpoints;
        this.metrics = metrics;
        this.findings = findings;
        this.objectMapper = objectMapper;
    }

    // Cache check: a finished analysis of exactly this commit
    @Transactional(readOnly = true)
    public Optional<Long> findCompletedRun(String ownerRepo, String branch, String commitSha) {
        return runs.findFirstByRepository_OwnerRepoAndBranchAndCommitShaAndStatusOrderByFinishedAtDesc(
                        ownerRepo, branch, commitSha, AnalysisRun.Status.DONE)
                .map(AnalysisRun::getId);
    }

    @Transactional
    public long createRun(String ownerRepo, boolean isPrivate, String defaultBranch, String branch) {
        RepositoryEntity repo = repositories.findByOwnerRepo(ownerRepo)
                .orElseGet(() -> repositories.save(new RepositoryEntity(ownerRepo, isPrivate, defaultBranch)));
        repo.setPrivate(isPrivate);
        repo.setDefaultBranch(defaultBranch);
        return runs.save(new AnalysisRun(repo, branch)).getId();
    }

    @Transactional
    public void updateStatus(long runId, AnalysisRun.Status status) {
        AnalysisRun run = runs.findById(runId).orElseThrow();
        run.setStatus(status);
    }

    @Transactional
    public void markFailed(long runId, String message) {
        AnalysisRun run = runs.findById(runId).orElseThrow();
        run.setStatus(AnalysisRun.Status.FAILED);
        run.setErrorMessage(message);
        run.setFinishedAt(Instant.now());
    }

    /** Saves everything in one transaction: either the whole analysis is saved, or nothing is. */
    @Transactional
    public void saveResults(long runId, AnalysisResult result) {
        AnalysisRun run = runs.findById(runId).orElseThrow();
        GraphResult graph = result.graph();
        RiskResult risk = result.risk();

        Map<String, String> layers = new HashMap<>();
        graph.nodes().forEach(n -> layers.put(n.qualifiedName(), n.layer()));

        // 1. Classes. Their generated ids are needed for edges and endpoints.
        List<ClassNode> nodeRows = new ArrayList<>();
        for (ClassInfo c : result.parsed().classes()) {
            nodeRows.add(new ClassNode(runId, c.qualifiedName(), c.simpleName(), c.packageName(), c.kind(),
                    layers.getOrDefault(c.qualifiedName(), c.layer()), c.filePath(), c.startLine(), c.endLine(),
                    c.lineCount(), c.methods().size(), c.test(), String.join(",", c.annotations())));
        }
        Map<String, Long> classIds = new HashMap<>();
        for (ClassNode saved : classNodes.saveAll(nodeRows)) {
            classIds.put(saved.getQualifiedName(), saved.getId());
        }

        // 2. Dependency edges
        List<DependencyEdge> edgeRows = new ArrayList<>();
        for (GraphResult.Edge e : graph.edges()) {
            Long from = classIds.get(e.from());
            Long to = classIds.get(e.to());
            if (from != null && to != null) edgeRows.add(new DependencyEdge(runId, from, to, e.kind()));
        }
        edges.saveAll(edgeRows);

        // 3. Endpoints, each with its traced request flow
        List<ApiEndpoint> endpointRows = new ArrayList<>();
        for (ClassInfo c : result.parsed().classes()) {
            for (EndpointInfo ep : c.endpoints()) {
                TraceNode trace = result.traces().get(RequestFlowService.endpointKey(c.qualifiedName(), ep.methodSignature()));
                endpointRows.add(new ApiEndpoint(runId, classIds.get(c.qualifiedName()), ep.httpMethod(), ep.path(),
                        ep.methodName(), ep.methodSignature(), ep.line(), trace == null ? null : toJson(trace)));
            }
        }
        endpoints.saveAll(endpointRows);

        // 4. Metrics and findings
        metrics.saveAll(risk.metrics().stream()
                .map(m -> new Metric(runId, m.targetType(), m.targetName(), m.filePath(), m.name(), m.value()))
                .toList());
        findings.saveAll(risk.findings().stream()
                .map(f -> new Finding(runId, f.type(), f.severity(), f.message(), f.filePath(), f.line(),
                        f.details() == null || f.details().isEmpty() ? null : toJson(f.details())))
                .toList());

        // 5. Summary on the run itself
        run.setCommitSha(result.commitSha());
        run.setGrade(risk.grade());
        run.setJavaFileCount(result.parsed().javaFileCount());
        run.setClassCount((int) result.parsed().classes().stream().filter(c -> !c.test()).count());
        run.setMethodCount(result.parsed().classes().stream().mapToInt(c -> c.methods().size()).sum());
        run.setEndpointCount(endpointRows.size());
        run.setCycleCount(graph.cycles().size());
        run.setViolationCount(graph.violations().size());
        run.setHotspotCount((int) risk.count("HOTSPOT"));
        run.setParseErrorCount(result.parsed().parseErrors().size());
        run.setCommitsScanned(result.history().commitsScanned());
        run.setFileTree(toJson(result.fileTree()));
        run.setReadingOrder(toJson(graph.readingOrder()));
        run.setStatus(AnalysisRun.Status.DONE);
        run.setFinishedAt(Instant.now());
    }

    private String toJson(Object value) {
        return objectMapper.writeValueAsString(value);
    }
}
