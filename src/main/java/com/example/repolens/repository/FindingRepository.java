package com.example.repolens.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.repolens.model.Finding;

public interface FindingRepository extends JpaRepository<Finding, Long> {

    List<Finding> findByAnalysisRunIdOrderByIdAsc(Long analysisRunId);

    List<Finding> findByAnalysisRunIdAndTypeOrderByIdAsc(Long analysisRunId, String type);
}
