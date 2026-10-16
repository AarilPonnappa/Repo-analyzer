package com.example.repolens.dto;

import java.util.List;
import java.util.Map;

/**
 * Numbers and problems found by RiskService.
 */
public record RiskResult(
        List<MetricValue> metrics,
        List<Issue> findings,
        String grade              // A-F, a simple overall score
) {

    public record MetricValue(String targetType, String targetName, String filePath, String name, double value) {}

    public record Issue(String type, String severity, String message, String filePath, Integer line,
                        Map<String, Object> details) {}

    public long count(String type) {
        return findings.stream().filter(f -> f.type().equals(type)).count();
    }
}
