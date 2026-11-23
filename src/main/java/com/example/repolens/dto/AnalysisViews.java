package com.example.repolens.dto;

import java.time.Instant;
import java.util.List;

/**
 * The shapes of the JSON the API returns. Spring turns these records into JSON.
 */
public final class AnalysisViews {

    private AnalysisViews() {}

    public record RunView(
            Long id, String repo, String branch, String commitSha, String status, String errorMessage,
            String grade, boolean isPrivate, Integer javaFiles, Integer classes, Integer methods, Integer endpoints,
            Integer cycles, Integer violations, Integer hotspots, Integer testGaps, Integer testClasses,
            Integer parseErrors, Integer commitsScanned, Integer totalComplexity, Integer totalLines,
            Integer chunks, Integer embeddedChunks, Instant createdAt, Instant startedAt, Instant finishedAt) {

        public boolean finished() {
            return "DONE".equals(status) || "FAILED".equals(status);
        }
    }

    // Sent over Server-Sent Events while an analysis runs
    public record ProgressEvent(Long id, String status, String errorMessage, boolean finished) {}

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

    // ---- AI features

    public record ExplanationView(String explanation, String source, List<String> removedClaims) {}

    public record Citation(int n, String filePath, int startLine, int endLine, String className,
                           String methodName, String snippet) {}

    public record AnswerView(String question, String answer, List<Citation> citations,
                             List<String> unverified, String source, boolean verified) {}

    // ---- change tracking

    public record HistoryPoint(Long id, String branch, String commitSha, Instant finishedAt, String grade,
                               Integer classes, Integer endpoints, Integer cycles, Integer violations,
                               Integer hotspots, Integer testGaps, Integer totalComplexity, Integer totalLines) {}

    public record ComplexityChange(String className, String filePath, double before, double after) {}

    public record CompareView(HistoryPoint base, HistoryPoint head,
                              List<String> addedClasses, List<String> removedClasses,
                              List<List<String>> newCycles, List<List<String>> resolvedCycles,
                              List<String> newViolations, List<String> resolvedViolations,
                              List<String> newHotspots, List<String> resolvedHotspots,
                              List<ComplexityChange> complexityChanges) {}
}
