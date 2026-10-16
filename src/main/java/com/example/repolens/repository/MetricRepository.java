package com.example.repolens.repository;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.example.repolens.model.Metric;

public interface MetricRepository extends JpaRepository<Metric, Long> {

    // e.g. the 20 most complex methods: name = "complexity", PageRequest.of(0, 20)
    List<Metric> findByAnalysisRunIdAndNameOrderByValueDesc(Long analysisRunId, String name, Pageable pageable);

    List<Metric> findByAnalysisRunIdAndTargetTypeOrderByNameAsc(Long analysisRunId, String targetType);
}
