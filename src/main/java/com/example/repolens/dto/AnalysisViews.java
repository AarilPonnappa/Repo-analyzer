package com.example.repolens.dto;

import java.time.Instant;
import java.util.List;

/**
 * The shapes of the JSON the GET endpoints return. Spring turns these records into JSON.
 */
public final class AnalysisViews {

    private AnalysisViews() {}

    public record RunView(
            Long id, String repo, String branch, String commitSha, String status, String errorMessage,
            String grade, Integer javaFiles, Integer classes, Integer methods, Integer endpoints,
            Integer cycles, Integer violations, Integer hotspots, Integer parseErrors, Integer commitsScanned,
            Instant createdAt, Instant finishedAt) {}

    public record RecentView(Long id, String repo, String branch, String grade, Instant analyzedAt) {}

    public record ClassView(
            Long id, String name, String simpleName, String packageName, String kind, String layer,
            String filePath, Integer startLine, Integer lineCount, Integer methodCount, boolean test,
            List<String> annotations) {}

    public record EdgeView(Long from, Long to, String kind) {}

    public record GraphView(List<ClassView> nodes, List<EdgeView> edges) {}

    public record EndpointView(Long id, String httpMethod, String path, String className,
                               String methodName, String methodSignature, Integer line, String filePath) {}

    public record FindingView(Long id, String type, String severity, String message,
                              String filePath, Integer line, Object details) {}

    public record MetricView(String targetType, String targetName, String filePath, String name, double value) {}
}
