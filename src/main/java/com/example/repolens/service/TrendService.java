package com.example.repolens.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.repolens.dto.AnalysisViews.CompareView;
import com.example.repolens.dto.AnalysisViews.ComplexityChange;
import com.example.repolens.dto.AnalysisViews.HistoryPoint;
import com.example.repolens.model.AnalysisRun;
import com.example.repolens.model.ClassNode;
import com.example.repolens.model.Finding;
import com.example.repolens.model.Metric;
import com.example.repolens.repository.AnalysisRunRepository;
import com.example.repolens.repository.ClassNodeRepository;
import com.example.repolens.repository.FindingRepository;
import com.example.repolens.repository.MetricRepository;

import tools.jackson.databind.ObjectMapper;

/**
 * Change tracking: every analysis is kept, so a repo's numbers can be followed over time,
 * and two analyses can be compared to see what a change introduced or fixed.
 */
@Service
@Transactional(readOnly = true)
public class TrendService {

    private static final int MAX_LISTED = 50;

    private final AnalysisRunRepository runs;
    private final ClassNodeRepository classNodes;
    private final FindingRepository findings;
    private final MetricRepository metrics;
    private final ObjectMapper objectMapper;

    public TrendService(AnalysisRunRepository runs, ClassNodeRepository classNodes, FindingRepository findings,
                        MetricRepository metrics, ObjectMapper objectMapper) {
        this.runs = runs;
        this.classNodes = classNodes;
        this.findings = findings;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
    }

    /** Every finished analysis of the repo, oldest first. */
    public List<HistoryPoint> history(String ownerRepo) {
        return runs.findHistory(ownerRepo, AnalysisRun.Status.DONE).stream().map(TrendService::point).toList();
    }

    /** What changed from base to head. */
    public CompareView compare(AnalysisRun base, AnalysisRun head) {
        long b = base.getId();
        long h = head.getId();

        Set<String> baseClasses = classNames(b);
        Set<String> headClasses = classNames(h);

        Set<List<String>> baseCycles = cycles(b);
        Set<List<String>> headCycles = cycles(h);

        Set<String> baseViolations = messages(b, "LAYER_VIOLATION");
        Set<String> headViolations = messages(h, "LAYER_VIOLATION");

        Set<String> baseHotspots = hotspotClasses(b);
        Set<String> headHotspots = hotspotClasses(h);

        Map<String, Double> baseComplexity = classComplexity(b);
        Map<String, Double> headComplexity = classComplexity(h);
        List<ComplexityChange> changes = new ArrayList<>();
        for (Map.Entry<String, Double> e : headComplexity.entrySet()) {
            double before = baseComplexity.getOrDefault(e.getKey(), 0.0);
            if (before != e.getValue()) {
                String[] parts = e.getKey().split("\\|", 2);
                changes.add(new ComplexityChange(parts[0], parts[1], before, e.getValue()));
            }
        }
        for (Map.Entry<String, Double> e : baseComplexity.entrySet()) {
            if (!headComplexity.containsKey(e.getKey())) {
                String[] parts = e.getKey().split("\\|", 2);
                changes.add(new ComplexityChange(parts[0], parts[1], e.getValue(), 0));
            }
        }
        changes.sort(Comparator.comparingDouble((ComplexityChange c) -> Math.abs(c.after() - c.before())).reversed());

        return new CompareView(point(base), point(head),
                limit(minus(headClasses, baseClasses)), limit(minus(baseClasses, headClasses)),
                new ArrayList<>(minus(headCycles, baseCycles)), new ArrayList<>(minus(baseCycles, headCycles)),
                limit(minus(headViolations, baseViolations)), limit(minus(baseViolations, headViolations)),
                limit(minus(headHotspots, baseHotspots)), limit(minus(baseHotspots, headHotspots)),
                changes.size() > 15 ? changes.subList(0, 15) : changes);
    }

    static HistoryPoint point(AnalysisRun r) {
        return new HistoryPoint(r.getId(), r.getBranch(), r.getCommitSha(), r.getFinishedAt(), r.getGrade(),
                r.getClassCount(), r.getEndpointCount(), r.getCycleCount(), r.getViolationCount(),
                r.getHotspotCount(), r.getTestGapCount(), r.getTotalComplexity(), r.getTotalLines());
    }

    private Set<String> classNames(long runId) {
        Set<String> names = new TreeSet<>();
        for (ClassNode c : classNodes.findByAnalysisRunIdOrderByQualifiedName(runId)) {
            if (!c.isTest()) names.add(c.getQualifiedName());
        }
        return names;
    }

    @SuppressWarnings("unchecked")
    private Set<List<String>> cycles(long runId) {
        Set<List<String>> cycles = new LinkedHashSet<>();
        for (Finding f : findings.findByAnalysisRunIdAndTypeOrderByIdAsc(runId, "CYCLE")) {
            if (f.getDetails() == null) continue;
            Map<String, Object> details = objectMapper.readValue(f.getDetails(), Map.class);
            if (details.get("classes") instanceof List<?> list) {
                List<String> names = new ArrayList<>();
                for (Object o : list) names.add(simple(String.valueOf(o)));
                names.sort(String::compareTo);
                cycles.add(names);
            }
        }
        return cycles;
    }

    private Set<String> messages(long runId, String type) {
        Set<String> set = new TreeSet<>();
        findings.findByAnalysisRunIdAndTypeOrderByIdAsc(runId, type).forEach(f -> set.add(f.getMessage()));
        return set;
    }

    @SuppressWarnings("unchecked")
    private Set<String> hotspotClasses(long runId) {
        Set<String> set = new TreeSet<>();
        for (Finding f : findings.findByAnalysisRunIdAndTypeOrderByIdAsc(runId, "HOTSPOT")) {
            String name = null;
            if (f.getDetails() != null) {
                Object c = objectMapper.readValue(f.getDetails(), Map.class).get("class");
                if (c != null) name = String.valueOf(c);
            }
            set.add(name != null ? name : f.getFilePath());
        }
        return set;
    }

    // "SimpleName|path/to/File.java" -> total complexity
    private Map<String, Double> classComplexity(long runId) {
        Map<String, Double> map = new HashMap<>();
        for (Metric m : metrics.findByAnalysisRunIdAndNameAndTargetType(runId, "complexity", "CLASS")) {
            map.put(m.getTargetName() + "|" + m.getFilePath(), m.getValue());
        }
        return map;
    }

    private static <T> Set<T> minus(Set<T> a, Set<T> b) {
        Set<T> result = new LinkedHashSet<>(a);
        result.removeAll(b);
        return result;
    }

    private static List<String> limit(Set<String> set) {
        List<String> list = new ArrayList<>(set);
        return list.size() > MAX_LISTED ? list.subList(0, MAX_LISTED) : list;
    }

    private static String simple(String qualified) {
        return qualified.substring(qualified.lastIndexOf('.') + 1);
    }
}
