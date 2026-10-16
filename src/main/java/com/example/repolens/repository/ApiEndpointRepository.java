package com.example.repolens.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.repolens.model.ApiEndpoint;

public interface ApiEndpointRepository extends JpaRepository<ApiEndpoint, Long> {

    List<ApiEndpoint> findByAnalysisRunIdOrderByPathAscHttpMethodAsc(Long analysisRunId);

    Optional<ApiEndpoint> findByIdAndAnalysisRunId(Long id, Long analysisRunId);
}
