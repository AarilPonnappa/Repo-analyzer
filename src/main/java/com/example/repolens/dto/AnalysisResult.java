package com.example.repolens.dto;

import java.util.Map;

/**
 * Everything one analysis produced, gathered by AnalysisPipelineService and saved by AnalysisStorageService.
 */
public record AnalysisResult(
        String commitSha,
        ParsedCode parsed,
        FileTreeNode fileTree,
        GraphResult graph,
        Map<String, TraceNode> traces,   // endpoint key "Class#signature" -> request flow
        HistoryResult history,
        RiskResult risk
) {}
