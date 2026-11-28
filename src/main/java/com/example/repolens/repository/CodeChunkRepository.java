package com.example.repolens.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.repolens.model.CodeChunk;

public interface CodeChunkRepository extends JpaRepository<CodeChunk, Long> {

    List<CodeChunk> findByAnalysisRunIdOrderByIdAsc(Long analysisRunId);
}
