package com.example.repolens.service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeMap;

import org.springframework.stereotype.Service;

import com.example.repolens.dto.GraphResult;
import com.example.repolens.dto.GraphResult.Edge;
import com.example.repolens.dto.GraphResult.Node;
import com.example.repolens.dto.GraphResult.ReadingStep;
import com.example.repolens.dto.GraphResult.Violation;
import com.example.repolens.dto.ParsedCode;
import com.example.repolens.dto.ParsedCode.ClassInfo;

/**
 * Builds the class dependency graph and analyzes it:
 *  - interface -> implementation mapping (OrderService -> OrderServiceImpl)
 *  - circular dependencies with Tarjan's strongly-connected-components algorithm
 *  - layer violations (e.g. a controller using a repository directly)
 *  - an onboarding reading order: a topological sort of the cycle-free "condensed" graph
 *
 * Test classes appear as nodes and edges, but are left out of cycles, violations and the reading order.
 */
@Service
public class GraphService {

    // Lower rank = more "foundational"; used to break ties in the reading order
    // Assign a rank to each layer, with ENTITY being the most foundational and TEST being the least. This is used to determine the order in which classes should be read in the reading order.
    // An application architecture layer is a logical grouping of classes that share a common purpose or responsibility. For example, the ENTITY layer contains classes that represent the data model, while the CONTROLLER layer contains classes that handle HTTP requests. 
    // ENTITY is the most foundational layer because it contains the data model that other layers build on. OTHER is a catch-all layer for classes that don't fit into any of the other layers. REPOSITORY is the layer that handles data access, while SERVICE contains business logic. CONFIG contains application setup and configuration, while COMPONENT contains supporting Spring components. CONTROLLER is the web entry point that handles HTTP requests, and TEST contains test classes.
    private static final Map<String, Integer> LAYER_RANK = Map.of(
            "ENTITY", 0, "OTHER", 1, "REPOSITORY", 2, "COMPONENT", 3,
            "SERVICE", 4, "CONFIG", 5, "CONTROLLER", 6, "TEST", 7);

    public GraphResult build(ParsedCode parsed) {
        // Build a map of class names to ClassInfo objects for easy lookup. The ClassInfo object contains information about a class, such as its qualified name, simple name, layer, and dependencies. This map is used to build the graph of class dependencies.
        /*
            byName
            │
            ├── "com.example.OrderController"  →  ClassInfo for OrderController
            │
            ├── "com.example.OrderService"  →  ClassInfo for OrderService
        */
       // We use linked hash map to preserve the order of insertion, which is important for the reading order. The order of insertion is determined by the order in which the classes are parsed from the source code.
        Map<String, ClassInfo> byName = new LinkedHashMap<>();
        for (ClassInfo c : parsed.classes()) {
            byName.put(c.qualifiedName(), c);
        }

        Map<String, String> interfaceImpls = interfaceImpls(byName);    // interface refers to the interface class, and implementation refers to the class that implements the interface. For example, if we have an interface called OrderService and a class called OrderServiceImpl that implements the interface, then the interfaceImpls map would contain an entry with the key "com.example.OrderService" and the value "com.example.OrderServiceImpl". This mapping is used to determine the layer of an interface based on its implementation.
        Map<String, String> layers = layers(byName, interfaceImpls);    // layers refers to the architectural layer of a class, such as ENTITY, REPOSITORY, SERVICE, CONTROLLER, etc. The layers map is used to determine the layer of a class based on its qualified name. For example, if we have a class called OrderController that is in the CONTROLLER layer, then the layers map would contain an entry with the key "com.example.OrderController" and the value "CONTROLLER". This mapping is used to determine the layer of a class based on its qualified name.

        // Edges, plus adjacency lists of production code only
        List<Edge> edges = new ArrayList<>();
        Map<String, List<String>> adjacency = new LinkedHashMap<>();
        Map<String, Integer> fanIn = new HashMap<>();                   // FanIn refers to the number of incoming edges to a class in the dependency graph. It is used to determine the number of classes that depend on a given class. For example, if we have a class called OrderService that is used by two other classes, then the fanIn map would contain an entry with the key "com.example.OrderService" and the value 2. This mapping is used to determine the number of classes that depend on a given class.
        Map<String, Integer> fanOut = new HashMap<>();                  // FanOut refers to the number of outgoing edges from a class in the dependency graph. It is used to determine the number of classes that a given class depends on. For example, if we have a class called OrderService that depends on two other classes, then the fanOut map would contain an entry with the key "com.example.OrderService" and the value 2. This mapping is used to determine the number of classes that a given class depends on.
        List<String> productionClasses = new ArrayList<>();

        for (ClassInfo c : byName.values()) {
            if (!c.test()) {
                productionClasses.add(c.qualifiedName());
                adjacency.put(c.qualifiedName(), new ArrayList<>());
            }
        }
        for (ClassInfo c : byName.values()) {
            for (Map.Entry<String, String> dep : c.dependencies().entrySet()) {
                String target = dep.getKey();
                if (!byName.containsKey(target)) continue;
                edges.add(new Edge(c.qualifiedName(), target, dep.getValue()));
                if (!c.test() && !byName.get(target).test()) {
                    adjacency.get(c.qualifiedName()).add(target);
                    fanOut.merge(c.qualifiedName(), 1, Integer::sum);
                    fanIn.merge(target, 1, Integer::sum);
                }
            }
        }

        List<Node> nodes = byName.values().stream()
                .map(c -> new Node(c.qualifiedName(), layers.get(c.qualifiedName()),
                        fanIn.getOrDefault(c.qualifiedName(), 0), fanOut.getOrDefault(c.qualifiedName(), 0)))
                .toList();

        List<List<String>> components = stronglyConnectedComponents(productionClasses, adjacency);
        List<List<String>> cycles = components.stream().filter(scc -> scc.size() > 1).toList();
        List<Violation> violations = violations(byName, adjacency, layers);
        List<ReadingStep> readingOrder = readingOrder(components, adjacency, layers, byName);

        return new GraphResult(nodes, edges, cycles, violations, readingOrder, interfaceImpls);
    }

    // ---------------------------------------------------------------- interfaces and layers

    // Interfaces with exactly one production implementation
    // The repository files are usually interfaces but also extend(inheritance) JPA
    // , and the service files are usually implementations. The interfaceImpls map is used to determine the layer of an interface based on its implementation. For example, if we have an interface called OrderService and a class called OrderServiceImpl that implements the interface, then the interfaceImpls map would contain an entry with the key "com.example.OrderService" and the value "com.example.OrderServiceImpl". This mapping is used to determine the layer of an interface based on its implementation.
    private Map<String, String> interfaceImpls(Map<String, ClassInfo> byName) {
        Map<String, List<String>> implsOf = new HashMap<>();
        for (ClassInfo c : byName.values()) {
            if (c.test() || c.kind().equals("INTERFACE")) continue;
            for (String superType : c.superTypes()) {
                ClassInfo parent = byName.get(superType);
                if (parent != null && parent.kind().equals("INTERFACE")) {
                    implsOf.computeIfAbsent(superType, k -> new ArrayList<>()).add(c.qualifiedName());
                }
            }
        }
        Map<String, String> result = new TreeMap<>();
        implsOf.forEach((iface, impls) -> {
            if (impls.size() == 1) result.put(iface, impls.get(0));
        });
        return result;
    }

    // An interface with no layer of its own takes its implementation's layer (OrderService -> SERVICE)
    private Map<String, String> layers(Map<String, ClassInfo> byName, Map<String, String> interfaceImpls) {
        Map<String, String> layers = new HashMap<>();
        for (ClassInfo c : byName.values()) {
            String layer = c.layer();
            String impl = interfaceImpls.get(c.qualifiedName());
            if (layer.equals("OTHER") && impl != null) {
                layer = byName.get(impl).layer();
            }
            layers.put(c.qualifiedName(), layer);
        }
        return layers;
    }

    // ---------------------------------------------------------------- Tarjan's SCC

    /**
     * Tarjan's algorithm. Returns every strongly connected component; a component with more than
     * one class is a cycle. Components come out in "dependencies first" order: a component is only
     * emitted after everything it depends on has been emitted.
     */
    List<List<String>> stronglyConnectedComponents(List<String> nodes, Map<String, List<String>> adjacency) {
        Tarjan tarjan = new Tarjan(adjacency);
        for (String node : nodes) {
            if (!tarjan.index.containsKey(node)) {
                tarjan.strongConnect(node);
            }
        }
        return tarjan.components;
    }

    private static final class Tarjan {
        private final Map<String, List<String>> adjacency;
        private final Map<String, Integer> index = new HashMap<>();
        private final Map<String, Integer> lowLink = new HashMap<>();
        private final Deque<String> stack = new ArrayDeque<>();
        private final Set<String> onStack = new HashSet<>();
        private final List<List<String>> components = new ArrayList<>();
        private int counter = 0;

        Tarjan(Map<String, List<String>> adjacency) {
            this.adjacency = adjacency;
        }

        void strongConnect(String v) {
            index.put(v, counter);
            lowLink.put(v, counter);
            counter++;
            stack.push(v);
            onStack.add(v);

            for (String w : adjacency.getOrDefault(v, List.of())) {
                if (!index.containsKey(w)) {
                    strongConnect(w);
                    lowLink.put(v, Math.min(lowLink.get(v), lowLink.get(w)));
                } else if (onStack.contains(w)) {
                    lowLink.put(v, Math.min(lowLink.get(v), index.get(w)));
                }
            }

            // v is the root of a component: pop the whole component off the stack
            if (lowLink.get(v).equals(index.get(v))) {
                List<String> component = new ArrayList<>();
                String w;
                do {
                    w = stack.pop();
                    onStack.remove(w);
                    component.add(w);
                } while (!w.equals(v));
                component.sort(Comparator.naturalOrder());
                components.add(component);
            }
        }
    }

    // ---------------------------------------------------------------- layer violations

    private List<Violation> violations(Map<String, ClassInfo> byName, Map<String, List<String>> adjacency,
                                       Map<String, String> layers) {
        // A controller using a repository directly is only a problem if the project has a service layer
        boolean hasServiceLayer = layers.values().stream().anyMatch("SERVICE"::equals);

        List<Violation> result = new ArrayList<>();
        adjacency.forEach((from, targets) -> {
            String fromLayer = layers.get(from);
            for (String to : targets) {
                String toLayer = layers.get(to);
                String message = violationMessage(fromLayer, toLayer, hasServiceLayer);
                if (message != null) {
                    result.add(new Violation(from, to, fromLayer, toLayer,
                            byName.get(from).simpleName() + " " + message + " " + byName.get(to).simpleName() + "."));
                }
            }
        });
        return result;
    }

    private static String violationMessage(String from, String to, boolean hasServiceLayer) {
        if (from.equals("CONTROLLER") && to.equals("REPOSITORY") && hasServiceLayer) {
            return "(controller) uses repository directly, skipping the service layer:";
        }
        if (!from.equals("CONTROLLER") && !from.equals("CONFIG") && to.equals("CONTROLLER")) {
            return "(" + from.toLowerCase() + ") depends on the web layer:";
        }
        if (from.equals("REPOSITORY") && to.equals("SERVICE")) {
            return "(repository) depends on a service:";
        }
        if (from.equals("ENTITY") && (to.equals("SERVICE") || to.equals("REPOSITORY"))) {
            return "(entity) depends on " + to.toLowerCase() + ":";
        }
        return null;
    }

    // ---------------------------------------------------------------- reading order

    /**
     * Topological sort of the condensed graph (each cycle collapsed into one node), with Kahn's
     * algorithm. A class comes after everything it depends on, so foundations are read first.
     * Ties are broken by layer: entities, then repositories, services, controllers.
     */
    private List<ReadingStep> readingOrder(List<List<String>> components, Map<String, List<String>> adjacency,
                                           Map<String, String> layers, Map<String, ClassInfo> byName) {
        Map<String, Integer> componentOf = new HashMap<>();
        for (int i = 0; i < components.size(); i++) {
            for (String c : components.get(i)) componentOf.put(c, i);
        }

        // For each component: which components it depends on, and which depend on it
        List<Set<Integer>> dependsOn = new ArrayList<>();
        List<Set<Integer>> dependents = new ArrayList<>();
        for (int i = 0; i < components.size(); i++) {
            dependsOn.add(new HashSet<>());
            dependents.add(new HashSet<>());
        }
        adjacency.forEach((from, targets) -> {
            for (String to : targets) {
                int a = componentOf.get(from);
                int b = componentOf.get(to);
                if (a != b) {
                    dependsOn.get(a).add(b);
                    dependents.get(b).add(a);
                }
            }
        });

        int[] remaining = new int[components.size()];
        PriorityQueue<Integer> ready = new PriorityQueue<>(Comparator
                .comparingInt((Integer i) -> components.get(i).stream()
                        .mapToInt(c -> LAYER_RANK.getOrDefault(layers.get(c), 1)).min().orElse(1))
                .thenComparing(i -> byName.get(components.get(i).get(0)).simpleName()));
        for (int i = 0; i < components.size(); i++) {
            remaining[i] = dependsOn.get(i).size();
            if (remaining[i] == 0) ready.add(i);
        }

        List<ReadingStep> steps = new ArrayList<>();
        while (!ready.isEmpty()) {
            int i = ready.poll();
            List<String> group = components.get(i);
            steps.add(new ReadingStep(steps.size() + 1, group, byName.get(group.get(0)).filePath(),
                    reason(group, dependsOn.get(i), components, layers, byName)));
            for (int dependent : dependents.get(i)) {
                if (--remaining[dependent] == 0) ready.add(dependent);
            }
        }
        return steps;
    }

    private static String reason(List<String> group, Set<Integer> deps, List<List<String>> components,
                                 Map<String, String> layers, Map<String, ClassInfo> byName) {
        String base;
        if (group.size() > 1) {
            base = "These classes depend on each other in a cycle, so read them together.";
        } else {
            base = switch (layers.get(group.get(0))) {
                case "ENTITY" -> "Data model that other code builds on.";
                case "REPOSITORY" -> "Data access: how data is loaded and saved.";
                case "SERVICE" -> "Business logic.";
                case "CONTROLLER" -> "Web entry point that handles HTTP requests.";
                case "CONFIG" -> "Application setup and configuration.";
                case "COMPONENT" -> "Supporting Spring component.";
                default -> "Supporting code.";
            };
        }
        List<String> uses = deps.stream()
                .flatMap(d -> components.get(d).stream())
                .map(c -> byName.get(c).simpleName())
                .sorted()
                .limit(3)
                .toList();
        return uses.isEmpty() ? base : base + " Builds on " + String.join(", ", uses) + ".";
    }
}
