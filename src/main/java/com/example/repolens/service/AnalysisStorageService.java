package com.example.repolens.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.repolens.dto.AnalysisResult;
import com.example.repolens.dto.GraphResult;
import com.example.repolens.dto.ParsedCode.ClassInfo;
import com.example.repolens.dto.ParsedCode.EndpointInfo;
import com.example.repolens.dto.RiskResult;
import com.example.repolens.dto.TraceNode;
import com.example.repolens.service.ChunkService.ChunkDraft;
import com.example.repolens.model.AnalysisRun;
import com.example.repolens.model.ApiEndpoint;
import com.example.repolens.model.ClassNode;
import com.example.repolens.model.CodeChunk;
import com.example.repolens.model.DependencyEdge;
import com.example.repolens.model.Finding;
import com.example.repolens.model.Metric;
import com.example.repolens.model.RepositoryEntity;
import com.example.repolens.repository.AnalysisRunRepository;
import com.example.repolens.repository.ApiEndpointRepository;
import com.example.repolens.repository.ClassNodeRepository;
import com.example.repolens.repository.CodeChunkRepository;
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
    private final CodeChunkRepository chunks;
    private final ObjectMapper objectMapper;

    /** A run's id plus its access key (null for public repos). */
    public record RunRef(long id, String accessKey) {}

    /** What's needed to restart an unfinished run after a server restart. */
    public record UnfinishedRun(long id, String ownerRepo, String branch, boolean isPrivate, String defaultBranch) {}

    private static final List<AnalysisRun.Status> FINISHED = List.of(AnalysisRun.Status.DONE, AnalysisRun.Status.FAILED);

    public AnalysisStorageService(RepositoryEntityRepository repositories, AnalysisRunRepository runs,
                                  ClassNodeRepository classNodes, DependencyEdgeRepository edges,
                                  ApiEndpointRepository endpoints, MetricRepository metrics,
                                  FindingRepository findings, CodeChunkRepository chunks, ObjectMapper objectMapper) {
        this.repositories = repositories;
        this.runs = runs;
        this.classNodes = classNodes;
        this.edges = edges;
        this.endpoints = endpoints;
        this.metrics = metrics;
        this.findings = findings;
        this.chunks = chunks;
        this.objectMapper = objectMapper;
    }

    // Cache check: a finished analysis of exactly this commit
    @Transactional(readOnly = true)
    public Optional<RunRef> findCompletedRun(String ownerRepo, String branch, String commitSha) {
        return runs.findFirstByRepository_OwnerRepoAndBranchAndCommitShaAndStatusOrderByFinishedAtDesc(
                        ownerRepo, branch, commitSha, AnalysisRun.Status.DONE)
                .map(r -> new RunRef(r.getId(), r.getAccessKey()));
    }

    // An analysis of this repo and branch that is queued or running right now
    @Transactional(readOnly = true)
    public Optional<RunRef> findActiveRun(String ownerRepo, String branch) {
        return runs.findActive(ownerRepo, branch, FINISHED).stream()
                .findFirst()
                .map(r -> new RunRef(r.getId(), r.getAccessKey()));
    }

    @Transactional(readOnly = true)
    public List<UnfinishedRun> findUnfinishedRuns() {
        return runs.findUnfinished(FINISHED).stream()
                .map(r -> new UnfinishedRun(r.getId(), r.getRepository().getOwnerRepo(), r.getBranch(),
                        r.getRepository().isPrivate(), r.getRepository().getDefaultBranch()))
                .toList();
    }

    /** Creates a QUEUED run. Private repos get a random access key that's needed to read the results. */
    @Transactional
    public RunRef createRun(String ownerRepo, boolean isPrivate, String defaultBranch, String branch, String commitSha) {
        RepositoryEntity repo = repositories.findByOwnerRepo(ownerRepo)
                .orElseGet(() -> repositories.save(new RepositoryEntity(ownerRepo, isPrivate, defaultBranch)));
        repo.setPrivate(isPrivate);
        repo.setDefaultBranch(defaultBranch);
        // One key per private repo: every analysis of it (history, compare) opens with the same link key
        String accessKey = !isPrivate ? null
                : runs.findFirstByRepository_OwnerRepoAndAccessKeyIsNotNullOrderByCreatedAtDesc(ownerRepo)
                        .map(AnalysisRun::getAccessKey)
                        .orElseGet(() -> UUID.randomUUID().toString().replace("-", ""));
        AnalysisRun run = runs.save(new AnalysisRun(repo, branch, commitSha, accessKey));
        return new RunRef(run.getId(), accessKey);
    }

    @Transactional
    public void updateStatus(long runId, AnalysisRun.Status status) {
        AnalysisRun run = runs.findById(runId).orElseThrow();
        run.setStatus(status);
        if (status == AnalysisRun.Status.CLONING && run.getStartedAt() == null) {
            run.setStartedAt(Instant.now());
        }
    }

    @Transactional
    public void markFailed(long runId, String message) {
        AnalysisRun run = runs.findById(runId).orElseThrow();
        run.setStatus(AnalysisRun.Status.FAILED);
        run.setErrorMessage(message);
        run.setFinishedAt(Instant.now());
    }

    @Transactional
    public void markDone(long runId) {
        AnalysisRun run = runs.findById(runId).orElseThrow();
        run.setStatus(AnalysisRun.Status.DONE);
        run.setFinishedAt(Instant.now());
    }

    /** Saves the code chunks for "Ask the repo". vectors may be null when embeddings are off or failed. */
    @Transactional
    public void saveChunks(long runId, List<ChunkDraft> drafts, List<float[]> vectors) {
        List<CodeChunk> rows = new ArrayList<>(drafts.size());
        int embedded = 0;
        for (int i = 0; i < drafts.size(); i++) {
            ChunkDraft d = drafts.get(i);
            float[] vector = vectors == null ? null : vectors.get(i);
            if (vector != null) embedded++;
            rows.add(new CodeChunk(runId, d.kind(), d.className(), d.methodName(), d.filePath(),
                    d.startLine(), d.endLine(), d.content(), vector == null ? null : GeminiClient.toBytes(vector)));
        }
        chunks.saveAll(rows);
        AnalysisRun run = runs.findById(runId).orElseThrow();
        run.setChunkCount(rows.size());
        run.setEmbeddedChunkCount(embedded);
    }

    @Transactional
    public void saveReport(long runId, String reportJson) {
        runs.findById(runId).orElseThrow().setReport(reportJson);
    }

    @Transactional
    public void saveExplanation(long endpointId, String explanationJson) {
        endpoints.findById(endpointId).ifPresent(e -> e.setExplanation(explanationJson));
    }

    /** Saves the analysis results in one transaction: either all of them are saved, or none are. */
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
        run.setMethodCount(result.parsed().classes().stream().filter(c -> !c.test()).mapToInt(c -> c.methods().size()).sum());
        run.setEndpointCount(endpointRows.size());
        run.setCycleCount(graph.cycles().size());
        run.setViolationCount(graph.violations().size());
        run.setHotspotCount((int) risk.count("HOTSPOT"));
        run.setParseErrorCount(result.parsed().parseErrors().size());
        run.setCommitsScanned(result.history().commitsScanned());
        run.setFileTree(toJson(result.fileTree()));
        run.setReadingOrder(toJson(graph.readingOrder()));
        List<ClassInfo> production = result.parsed().classes().stream().filter(c -> !c.test()).toList();
        run.setTotalComplexity(production.stream().mapToInt(ClassInfo::totalComplexity).sum());
        run.setTotalLines(production.stream().mapToInt(ClassInfo::lineCount).sum());
        run.setTestClassCount(result.parsed().classes().size() - production.size());
        run.setTestGapCount((int) risk.count("TEST_GAP"));
        // DONE is set by markDone() after the code index and report are saved
    }

    private String toJson(Object value) {
        return objectMapper.writeValueAsString(value);
    }
}
