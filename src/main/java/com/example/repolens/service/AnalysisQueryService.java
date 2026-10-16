package com.example.repolens.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.repolens.dto.AnalysisViews.ClassView;
import com.example.repolens.dto.AnalysisViews.EdgeView;
import com.example.repolens.dto.AnalysisViews.EndpointView;
import com.example.repolens.dto.AnalysisViews.FindingView;
import com.example.repolens.dto.AnalysisViews.GraphView;
import com.example.repolens.dto.AnalysisViews.MetricView;
import com.example.repolens.dto.AnalysisViews.RecentView;
import com.example.repolens.dto.AnalysisViews.RunView;
import com.example.repolens.model.AnalysisRun;
import com.example.repolens.model.ApiEndpoint;
import com.example.repolens.model.ClassNode;
import com.example.repolens.model.Finding;
import com.example.repolens.repository.AnalysisRunRepository;
import com.example.repolens.repository.ApiEndpointRepository;
import com.example.repolens.repository.ClassNodeRepository;
import com.example.repolens.repository.DependencyEdgeRepository;
import com.example.repolens.repository.FindingRepository;
import com.example.repolens.repository.MetricRepository;

import tools.jackson.databind.ObjectMapper;

//Read-only queries for the result page. Each method returns exactly what one GET endpoint needs.
@Service
@Transactional(readOnly = true)
public class AnalysisQueryService {

    private final AnalysisRunRepository runs;
    private final ClassNodeRepository classNodes;
    private final DependencyEdgeRepository edges;
    private final ApiEndpointRepository endpoints;
    private final MetricRepository metrics;
    private final FindingRepository findings;
    private final ObjectMapper objectMapper;

    public AnalysisQueryService(AnalysisRunRepository runs, ClassNodeRepository classNodes,
                                DependencyEdgeRepository edges, ApiEndpointRepository endpoints,
                                MetricRepository metrics, FindingRepository findings, ObjectMapper objectMapper) {
        this.runs = runs;
        this.classNodes = classNodes;
        this.edges = edges;
        this.endpoints = endpoints;
        this.metrics = metrics;
        this.findings = findings;
        this.objectMapper = objectMapper;
    }

    public Optional<RunView> run(long id) {
        return runs.findWithRepository(id).map(r -> new RunView(
                r.getId(), r.getRepository().getOwnerRepo(), r.getBranch(), r.getCommitSha(), r.getStatus().name(),
                r.getErrorMessage(), r.getGrade(), r.getJavaFileCount(), r.getClassCount(), r.getMethodCount(),
                r.getEndpointCount(), r.getCycleCount(), r.getViolationCount(), r.getHotspotCount(),
                r.getParseErrorCount(), r.getCommitsScanned(), r.getCreatedAt(), r.getFinishedAt()));
    }

    // Latest finished analysis per public repo and branch
    public List<RecentView> recent(int limit) {
        List<RecentView> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (AnalysisRun r : runs.findRecentPublic(AnalysisRun.Status.DONE, PageRequest.of(0, limit * 3))) {
            String key = r.getRepository().getOwnerRepo() + "@" + r.getBranch();
            if (seen.add(key)) {
                result.add(new RecentView(r.getId(), r.getRepository().getOwnerRepo(), r.getBranch(),
                        r.getGrade(), r.getFinishedAt()));
            }
            if (result.size() >= limit) break;
        }
        return result;
    }

    public Optional<String> fileTreeJson(long id) {
        return runs.findById(id).map(AnalysisRun::getFileTree);
    }

    public Optional<String> readingOrderJson(long id) {
        return runs.findById(id).map(AnalysisRun::getReadingOrder);
    }

    public GraphView graph(long id, boolean includeTests) {
        List<ClassNode> nodes = classNodes.findByAnalysisRunIdOrderByQualifiedName(id).stream()
                .filter(n -> includeTests || !n.isTest())
                .toList();
        Set<Long> visible = new HashSet<>();
        List<ClassView> nodeViews = new ArrayList<>();
        for (ClassNode n : nodes) {
            visible.add(n.getId());
            nodeViews.add(new ClassView(n.getId(), n.getQualifiedName(), n.getSimpleName(), n.getPackageName(),
                    n.getKind(), n.getLayer(), n.getFilePath(), n.getStartLine(), n.getLineCount(),
                    n.getMethodCount(), n.isTest(), splitAnnotations(n.getAnnotations())));
        }
        List<EdgeView> edgeViews = edges.findByAnalysisRunId(id).stream()
                .filter(e -> visible.contains(e.getFromClassId()) && visible.contains(e.getToClassId()))
                .map(e -> new EdgeView(e.getFromClassId(), e.getToClassId(), e.getKind()))
                .toList();
        return new GraphView(nodeViews, edgeViews);
    }

    public List<EndpointView> endpoints(long id) {
        Map<Long, ClassNode> classesById = new HashMap<>();
        classNodes.findByAnalysisRunIdOrderByQualifiedName(id).forEach(c -> classesById.put(c.getId(), c));
        return endpoints.findByAnalysisRunIdOrderByPathAscHttpMethodAsc(id).stream()
                .map(e -> {
                    ClassNode c = classesById.get(e.getClassId());
                    return new EndpointView(e.getId(), e.getHttpMethod(), e.getPath(),
                            c == null ? null : c.getSimpleName(), e.getMethodName(), e.getMethodSignature(),
                            e.getStartLine(), c == null ? null : c.getFilePath());
                })
                .toList();
    }

    public Optional<String> traceJson(long id, long endpointId) {
        return endpoints.findByIdAndAnalysisRunId(endpointId, id).map(ApiEndpoint::getTrace);
    }

    public List<FindingView> findings(long id, String type) {
        List<Finding> rows = (type == null || type.isBlank())
                ? findings.findByAnalysisRunIdOrderByIdAsc(id)
                : findings.findByAnalysisRunIdAndTypeOrderByIdAsc(id, type.toUpperCase());
        return rows.stream()
                .map(f -> new FindingView(f.getId(), f.getType(), f.getSeverity(), f.getMessage(),
                        f.getFilePath(), f.getLine(),
                        f.getDetails() == null ? null : objectMapper.readValue(f.getDetails(), Object.class)))
                .toList();
    }

    public List<MetricView> topMetrics(long id, String name, int limit) {
        return metrics.findByAnalysisRunIdAndNameOrderByValueDesc(id, name, PageRequest.of(0, limit)).stream()
                .map(m -> new MetricView(m.getTargetType(), m.getTargetName(), m.getFilePath(), m.getName(), m.getValue()))
                .toList();
    }

    private static List<String> splitAnnotations(String value) {
        return value == null || value.isBlank() ? List.of() : Arrays.asList(value.split(","));
    }
}
