package com.example.repolens.dto;

import java.util.List;
import java.util.Map;

/**
 * The dependency graph and what was found in it. Produced by GraphService.
 */
public record GraphResult(
        List<Node> nodes,
        List<Edge> edges,
        List<List<String>> cycles,          // each cycle is a group of classes that depend on each other
        List<Violation> violations,         // e.g. a controller using a repository directly
        List<ReadingStep> readingOrder,     // onboarding order: foundations first
        Map<String, String> interfaceImpls  // interface -> its only implementation, e.g. OrderService -> OrderServiceImpl
) {

    public record Node(String qualifiedName, String layer, int fanIn, int fanOut) {}

    public record Edge(String from, String to, String kind) {}

    public record Violation(String from, String to, String fromLayer, String toLayer, String message) {}

    public record ReadingStep(int step, List<String> classes, String filePath, String reason) {}

    public String layerOf(String qualifiedName) {
        return nodes.stream()
                .filter(n -> n.qualifiedName().equals(qualifiedName))
                .map(Node::layer)
                .findFirst()
                .orElse("OTHER");
    }
}
