package com.example.repolens.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.example.repolens.dto.GraphResult;
import com.example.repolens.dto.ParsedCode;
import com.example.repolens.dto.ParsedCode.CallInfo;
import com.example.repolens.dto.ParsedCode.ClassInfo;
import com.example.repolens.dto.ParsedCode.EndpointInfo;
import com.example.repolens.dto.ParsedCode.MethodInfo;
import com.example.repolens.dto.TraceNode;

/**
 * Request-flow tracer. For each endpoint, follows method calls from the controller method
 * through services and repositories with a depth-first search over the method call graph.
 *
 * There's no single destination, so this is reachability (DFS with a depth limit), not shortest path.
 * Calls on an interface are followed into its implementation, like Spring does at runtime.
 * Plain getters and setters are skipped so the trace shows the interesting steps.
 */
@Service
public class RequestFlowService {

    private static final int MAX_DEPTH = 8;
    private static final int MAX_NODES_PER_TRACE = 200;

    /** Returns endpoint key ("Class#signature") -> trace tree. */
    public Map<String, TraceNode> traceAll(ParsedCode parsed, GraphResult graph) {
        Map<String, ClassInfo> byName = new LinkedHashMap<>();
        parsed.classes().forEach(c -> byName.put(c.qualifiedName(), c));

        Map<String, TraceNode> traces = new LinkedHashMap<>();
        for (ClassInfo controller : parsed.classes()) {
            for (EndpointInfo endpoint : controller.endpoints()) {
                String key = endpointKey(controller.qualifiedName(), endpoint.methodSignature());
                if (traces.containsKey(key)) continue;   // a method with two paths is traced once
                controller.methods().stream()
                        .filter(m -> m.signature().equals(endpoint.methodSignature()))
                        .findFirst()
                        .ifPresent(method -> {
                            Tracer tracer = new Tracer(byName, graph);
                            traces.put(key, tracer.visit(controller, method, 0, new HashSet<>()));
                        });
            }
        }
        return traces;
    }

    public static String endpointKey(String className, String signature) {
        return className + "#" + signature;
    }

    private static final class Tracer {
        private final Map<String, ClassInfo> byName;
        private final GraphResult graph;
        private final Map<String, String> layers = new HashMap<>();
        private int budget = MAX_NODES_PER_TRACE;

        Tracer(Map<String, ClassInfo> byName, GraphResult graph) {
            this.byName = byName;
            this.graph = graph;
            graph.nodes().forEach(n -> layers.put(n.qualifiedName(), n.layer()));
        }

        private String layer(String className) {
            return layers.getOrDefault(className, "OTHER");
        }

        TraceNode visit(ClassInfo owner, MethodInfo method, int depth, Set<String> onPath) {
            budget--;
            String key = owner.qualifiedName() + "#" + method.signature();
            List<TraceNode> children = new ArrayList<>();
            boolean truncated = false;

            if (depth >= MAX_DEPTH) {
                truncated = !method.calls().isEmpty();
            } else {
                onPath.add(key);
                for (CallInfo call : method.calls()) {
                    if (budget <= 0) {
                        truncated = true;
                        break;
                    }
                    TraceNode child = follow(call, depth, onPath);
                    if (child != null) children.add(child);
                }
                onPath.remove(key);
            }

            return new TraceNode(owner.qualifiedName(), owner.simpleName(), method.signature(),
                    layer(owner.qualifiedName()), owner.filePath(), method.startLine(),
                    false, truncated, children);
        }

        private TraceNode follow(CallInfo call, int depth, Set<String> onPath) {
            // Calls on an interface go to its implementation, if there is exactly one
            String targetName = graph.interfaceImpls().getOrDefault(call.targetClass(), call.targetClass());
            ClassInfo target = byName.get(targetName);
            Found found = target == null ? null : findMethod(target, call.methodName(), call.argCount(), new HashSet<>());

            // Not declared in the implementation? Try the interface itself (e.g. a Spring Data query method)
            if (found == null && !targetName.equals(call.targetClass())) {
                ClassInfo iface = byName.get(call.targetClass());
                if (iface != null) found = findMethod(iface, call.methodName(), call.argCount(), new HashSet<>());
            }

            if (found == null) {
                // Inherited from a library, e.g. JpaRepository.findById: show it as an external leaf
                ClassInfo shown = byName.get(call.targetClass());
                budget--;
                return new TraceNode(call.targetClass(),
                        shown != null ? shown.simpleName() : call.targetClass(),
                        call.methodName() + "(...)", layer(call.targetClass()),
                        null, null, true, false, List.of());
            }

            if (isAccessor(found.method())) {
                return null;   // skip plain getters and setters, they only add noise
            }

            String key = found.owner().qualifiedName() + "#" + found.method().signature();
            if (onPath.contains(key)) {
                // Recursion: show the call but don't follow it again
                budget--;
                return new TraceNode(found.owner().qualifiedName(), found.owner().simpleName(),
                        found.method().signature(), layer(found.owner().qualifiedName()),
                        found.owner().filePath(), found.method().startLine(), false, true, List.of());
            }
            return visit(found.owner(), found.method(), depth + 1, onPath);
        }

        private static boolean isAccessor(MethodInfo m) {
            return m.name().matches("(get|set|is)[A-Z].*") && m.complexity() == 1
                    && m.calls().isEmpty() && m.lineCount() <= 3;
        }

        // Looks in the class, then in its repo supertypes (e.g. an abstract base service)
        private Found findMethod(ClassInfo cls, String name, int argCount, Set<String> visited) {
            if (!visited.add(cls.qualifiedName())) return null;
            for (MethodInfo m : cls.methods()) {
                if (m.name().equals(name) && m.paramCount() == argCount) return new Found(cls, m);
            }
            for (String superType : cls.superTypes()) {
                ClassInfo parent = byName.get(superType);
                if (parent != null) {
                    Found f = findMethod(parent, name, argCount, visited);
                    if (f != null) return f;
                }
            }
            return null;
        }
    }

    private record Found(ClassInfo owner, MethodInfo method) {}
}
