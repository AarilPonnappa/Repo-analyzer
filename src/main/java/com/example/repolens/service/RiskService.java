package com.example.repolens.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.example.repolens.dto.GraphResult;
import com.example.repolens.dto.HistoryResult;
import com.example.repolens.dto.HistoryResult.CoChange;
import com.example.repolens.dto.HistoryResult.FileHistory;
import com.example.repolens.dto.ParsedCode;
import com.example.repolens.dto.ParsedCode.ClassInfo;
import com.example.repolens.dto.ParsedCode.MethodInfo;
import com.example.repolens.dto.RiskResult;
import com.example.repolens.dto.RiskResult.Issue;
import com.example.repolens.dto.RiskResult.MetricValue;

/**
 * Risk radar. Combines code structure (JavaParser), the graph and git history (JGit) into
 * metrics and findings: complex and long methods, large classes, hotspots, bus factor,
 * test gaps, possibly unused code, cycles, layer violations and hidden change coupling.
 */
@Service
public class RiskService {

    private static final int COMPLEX_METHOD = 15;
    private static final int VERY_COMPLEX_METHOD = 25;
    private static final int LONG_METHOD_LINES = 60;
    private static final int LARGE_CLASS_LINES = 500;
    private static final int HUGE_CLASS_LINES = 1000;
    private static final int MANY_METHODS = 30;
    private static final int HOTSPOT_LIMIT = 10;
    private static final int MIN_CHANGES_FOR_OWNERSHIP = 5;
    private static final int MAX_METHOD_METRICS = 300;

    public RiskResult assess(ParsedCode parsed, GraphResult graph, HistoryResult history) {
        List<MetricValue> metrics = new ArrayList<>();
        List<Issue> findings = new ArrayList<>();

        List<ClassInfo> production = parsed.classes().stream().filter(c -> !c.test()).toList();
        List<ClassInfo> tests = parsed.classes().stream().filter(ClassInfo::test).toList();
        Map<String, GraphResult.Node> nodes = new HashMap<>();
        graph.nodes().forEach(n -> nodes.put(n.qualifiedName(), n));

        methodRisks(production, metrics, findings);
        Map<String, Double> hotspotScores = classRisks(production, nodes, history, metrics, findings);
        hotspots(production, hotspotScores, history, findings);
        busFactor(production, history, metrics, findings);
        int testCandidates = testGaps(production, tests, findings);
        deadCode(production, nodes, findings);
        structure(graph, parsed, findings);
        coupling(history, parsed, graph, findings);

        metrics.add(new MetricValue("PROJECT", "project", null, "classes", production.size()));
        metrics.add(new MetricValue("PROJECT", "project", null, "test_classes", tests.size()));
        metrics.add(new MetricValue("PROJECT", "project", null, "commits_scanned", history.commitsScanned()));
        metrics.add(new MetricValue("PROJECT", "project", null, "authors", history.authorCount()));

        String grade = grade(findings, testCandidates);
        return new RiskResult(metrics, findings, grade);
    }

    // ---------------------------------------------------------------- methods

    private void methodRisks(List<ClassInfo> classes, List<MetricValue> metrics, List<Issue> findings) {
        record Scored(ClassInfo owner, MethodInfo method) {}
        List<Scored> all = new ArrayList<>();
        for (ClassInfo c : classes) {
            for (MethodInfo m : c.methods()) all.add(new Scored(c, m));
        }
        // Only the most complex methods get metric rows, so big repos don't produce huge tables
        all.sort(Comparator.comparingInt((Scored s) -> s.method().complexity()).reversed());
        for (int i = 0; i < Math.min(all.size(), MAX_METHOD_METRICS); i++) {
            Scored s = all.get(i);
            String name = s.owner().simpleName() + "." + s.method().signature();
            metrics.add(new MetricValue("METHOD", name, s.owner().filePath(), "complexity", s.method().complexity()));
            metrics.add(new MetricValue("METHOD", name, s.owner().filePath(), "lines", s.method().lineCount()));
        }

        for (Scored s : all) {
            MethodInfo m = s.method();
            String name = s.owner().simpleName() + "." + m.name() + "()";
            if (m.complexity() >= COMPLEX_METHOD) {
                findings.add(new Issue("COMPLEX_METHOD", m.complexity() >= VERY_COMPLEX_METHOD ? "HIGH" : "MEDIUM",
                        name + " has cyclomatic complexity " + m.complexity() + ", which makes it hard to test and change.",
                        s.owner().filePath(), m.startLine(), Map.of("complexity", m.complexity())));
            }
            if (m.lineCount() > LONG_METHOD_LINES) {
                findings.add(new Issue("LONG_METHOD", "LOW",
                        name + " is " + m.lineCount() + " lines long.",
                        s.owner().filePath(), m.startLine(), Map.of("lines", m.lineCount())));
            }
        }
    }

    // ---------------------------------------------------------------- classes

    private Map<String, Double> classRisks(List<ClassInfo> classes, Map<String, GraphResult.Node> nodes,
                                           HistoryResult history, List<MetricValue> metrics, List<Issue> findings) {
        int maxChanges = 1;
        int maxComplexity = 1;
        for (ClassInfo c : classes) {
            maxChanges = Math.max(maxChanges, changes(history, c));
            maxComplexity = Math.max(maxComplexity, c.totalComplexity());
        }

        Map<String, Double> scores = new HashMap<>();
        for (ClassInfo c : classes) {
            GraphResult.Node node = nodes.get(c.qualifiedName());
            int changes = changes(history, c);
            FileHistory fh = history.files().get(c.filePath());
            // Hotspot = changes often AND is complex (both scaled to 0..1)
            double score = ((double) changes / maxChanges) * ((double) c.totalComplexity() / maxComplexity);
            scores.put(c.qualifiedName(), score);

            String n = c.simpleName();
            metrics.add(new MetricValue("CLASS", n, c.filePath(), "lines", c.lineCount()));
            metrics.add(new MetricValue("CLASS", n, c.filePath(), "methods", c.methods().size()));
            metrics.add(new MetricValue("CLASS", n, c.filePath(), "complexity", c.totalComplexity()));
            metrics.add(new MetricValue("CLASS", n, c.filePath(), "fan_in", node == null ? 0 : node.fanIn()));
            metrics.add(new MetricValue("CLASS", n, c.filePath(), "fan_out", node == null ? 0 : node.fanOut()));
            metrics.add(new MetricValue("CLASS", n, c.filePath(), "changes", changes));
            metrics.add(new MetricValue("CLASS", n, c.filePath(), "authors", fh == null ? 0 : fh.authorCount()));
            metrics.add(new MetricValue("CLASS", n, c.filePath(), "hotspot_score", round(score)));

            if (c.lineCount() > LARGE_CLASS_LINES || c.methods().size() > MANY_METHODS) {
                findings.add(new Issue("LARGE_CLASS", c.lineCount() > HUGE_CLASS_LINES ? "HIGH" : "MEDIUM",
                        n + " is " + c.lineCount() + " lines with " + c.methods().size() + " methods. Consider splitting it.",
                        c.filePath(), c.startLine(), Map.of("lines", c.lineCount(), "methods", c.methods().size())));
            }
        }
        return scores;
    }

    private void hotspots(List<ClassInfo> classes, Map<String, Double> scores, HistoryResult history, List<Issue> findings) {
        List<ClassInfo> ranked = classes.stream()
                .filter(c -> changes(history, c) >= 3 && scores.get(c.qualifiedName()) >= 0.05)
                .sorted(Comparator.comparingDouble((ClassInfo c) -> scores.get(c.qualifiedName())).reversed())
                .limit(HOTSPOT_LIMIT)
                .toList();

        int rank = 1;
        for (ClassInfo c : ranked) {
            double score = scores.get(c.qualifiedName());
            FileHistory fh = history.files().get(c.filePath());
            String owners = fh.authorCount() == 1
                    ? " Only one person has changed it."
                    : " " + fh.authorCount() + " people have changed it.";
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("rank", rank++);
            details.put("score", round(score));
            details.put("changes", fh.commits());
            details.put("complexity", c.totalComplexity());
            details.put("lines", c.lineCount());
            details.put("authors", fh.authorCount());
            findings.add(new Issue("HOTSPOT", score >= 0.4 ? "HIGH" : "MEDIUM",
                    c.simpleName() + " (" + c.lineCount() + " lines, complexity " + c.totalComplexity()
                            + ") changed " + fh.commits() + " times." + owners,
                    c.filePath(), c.startLine(), details));
        }
    }

    // ---------------------------------------------------------------- people

    private void busFactor(List<ClassInfo> classes, HistoryResult history, List<MetricValue> metrics, List<Issue> findings) {
        for (ClassInfo c : classes) {
            FileHistory fh = history.files().get(c.filePath());
            if (fh == null || fh.commits() < MIN_CHANGES_FOR_OWNERSHIP || fh.authorCount() != 1) continue;
            String author = fh.commitsByAuthor().keySet().iterator().next();
            findings.add(new Issue("BUS_FACTOR", "MEDIUM",
                    "Only " + author + " has changed " + c.simpleName() + " (" + fh.commits() + " commits).",
                    c.filePath(), c.startLine(), Map.of("author", author, "changes", fh.commits())));
        }

        // Project bus factor: the fewest people who together made at least half of the changes
        List<Integer> counts = history.commitsByAuthor().values().stream().sorted(Comparator.reverseOrder()).toList();
        int total = counts.stream().mapToInt(Integer::intValue).sum();
        int people = 0;
        int covered = 0;
        for (int count : counts) {
            if (covered * 2 >= total) break;
            covered += count;
            people++;
        }
        metrics.add(new MetricValue("PROJECT", "project", null, "bus_factor", people));
    }

    // ---------------------------------------------------------------- tests and unused code

    private int testGaps(List<ClassInfo> classes, List<ClassInfo> tests, List<Issue> findings) {
        // A class counts as tested if a test is named after it or uses it
        Set<String> testedNames = new HashSet<>();
        Set<String> usedByTests = new HashSet<>();
        for (ClassInfo t : tests) {
            testedNames.add(t.simpleName().replaceAll("(Tests?|IT)$", ""));
            usedByTests.addAll(t.dependencies().keySet());
        }

        int candidates = 0;
        for (ClassInfo c : classes) {
            boolean important = !c.kind().equals("INTERFACE") && !c.kind().equals("ANNOTATION")
                    && (Set.of("SERVICE", "CONTROLLER", "COMPONENT").contains(c.layer()) || c.totalComplexity() >= 20);
            if (!important) continue;
            candidates++;
            if (testedNames.contains(c.simpleName()) || usedByTests.contains(c.qualifiedName())) continue;
            findings.add(new Issue("TEST_GAP", c.totalComplexity() >= 30 ? "HIGH" : "MEDIUM",
                    c.simpleName() + " has no matching test class and isn't used by any test.",
                    c.filePath(), c.startLine(), Map.of("layer", c.layer(), "complexity", c.totalComplexity())));
        }
        return candidates;
    }

    private void deadCode(List<ClassInfo> classes, Map<String, GraphResult.Node> nodes, List<Issue> findings) {
        for (ClassInfo c : classes) {
            GraphResult.Node node = nodes.get(c.qualifiedName());
            if (c.entryPoint() || node == null || node.fanIn() > 0) continue;
            if (c.kind().equals("ANNOTATION")) continue;
            findings.add(new Issue("DEAD_CODE", "LOW",
                    c.simpleName() + " is possibly unused: no other class in the repo refers to it, "
                            + "and it isn't a Spring bean or entry point.",
                    c.filePath(), c.startLine(), Map.of()));
        }
    }

    // ---------------------------------------------------------------- graph findings

    private void structure(GraphResult graph, ParsedCode parsed, List<Issue> findings) {
        Map<String, ClassInfo> byName = new HashMap<>();
        parsed.classes().forEach(c -> byName.put(c.qualifiedName(), c));

        for (List<String> cycle : graph.cycles()) {
            List<String> names = cycle.stream().map(q -> byName.get(q).simpleName()).toList();
            ClassInfo first = byName.get(cycle.get(0));
            findings.add(new Issue("CYCLE", "HIGH",
                    "Circular dependency between " + String.join(", ", names) + ".",
                    first.filePath(), first.startLine(), Map.of("classes", cycle)));
        }
        for (GraphResult.Violation v : graph.violations()) {
            ClassInfo from = byName.get(v.from());
            findings.add(new Issue("LAYER_VIOLATION", "MEDIUM", v.message(), from.filePath(), from.startLine(),
                    Map.of("from", v.from(), "to", v.to(), "fromLayer", v.fromLayer(), "toLayer", v.toLayer())));
        }
    }

    // Files that keep changing together but have no dependency between them: a hidden connection
    private void coupling(HistoryResult history, ParsedCode parsed, GraphResult graph, List<Issue> findings) {
        Map<String, String> classByFile = new HashMap<>();
        parsed.classes().forEach(c -> classByFile.putIfAbsent(c.filePath(), c.qualifiedName()));
        Set<String> linked = new HashSet<>();
        graph.edges().forEach(e -> {
            linked.add(e.from() + "|" + e.to());
            linked.add(e.to() + "|" + e.from());
        });

        for (CoChange cc : history.coChanges()) {
            String a = classByFile.get(cc.fileA());
            String b = classByFile.get(cc.fileB());
            if (a == null || b == null || linked.contains(a + "|" + b)) continue;
            if (CodeParserService.isTestFile(cc.fileA(), "") || CodeParserService.isTestFile(cc.fileB(), "")) continue;
            findings.add(new Issue("CHANGE_COUPLING", "LOW",
                    simple(a) + " and " + simple(b) + " changed together in " + cc.together()
                            + " commits but don't depend on each other.",
                    cc.fileA(), null, Map.of("fileA", cc.fileA(), "fileB", cc.fileB(),
                            "together", cc.together(), "ratio", round(cc.ratio()))));
        }
    }

    // ---------------------------------------------------------------- grade

    // A simple, explainable score from 100 down. Not a standard, just a summary for the badge.
    private String grade(List<Issue> findings, int testCandidates) {
        long cycles = count(findings, "CYCLE", null);
        long violations = count(findings, "LAYER_VIOLATION", null);
        long highHotspots = count(findings, "HOTSPOT", "HIGH");
        long otherHotspots = count(findings, "HOTSPOT", "MEDIUM");
        long complex = count(findings, "COMPLEX_METHOD", null);
        long gaps = count(findings, "TEST_GAP", null);

        double score = 100
                - Math.min(30, 10 * cycles)
                - Math.min(15, 3 * violations)
                - Math.min(20, 4 * highHotspots + 2 * otherHotspots)
                - Math.min(15, complex)
                - (testCandidates == 0 ? 0 : 10.0 * gaps / testCandidates);

        if (score >= 90) return "A";
        if (score >= 80) return "B";
        if (score >= 70) return "C";
        if (score >= 60) return "D";
        return "F";
    }

    private static long count(List<Issue> findings, String type, String severity) {
        return findings.stream()
                .filter(f -> f.type().equals(type) && (severity == null || f.severity().equals(severity)))
                .count();
    }

    private static int changes(HistoryResult history, ClassInfo c) {
        FileHistory fh = history.files().get(c.filePath());
        return fh == null ? 0 : fh.commits();
    }

    private static String simple(String qualifiedName) {
        return qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1);
    }

    private static double round(double value) {
        return Math.round(value * 1000) / 1000.0;
    }
}
