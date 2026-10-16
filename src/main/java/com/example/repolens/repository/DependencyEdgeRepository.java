package com.example.repolens.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.repolens.model.DependencyEdge;

public interface DependencyEdgeRepository extends JpaRepository<DependencyEdge, Long> {

    List<DependencyEdge> findByAnalysisRunId(Long analysisRunId);
}
