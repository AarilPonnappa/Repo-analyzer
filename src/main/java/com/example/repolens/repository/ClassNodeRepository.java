package com.example.repolens.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.repolens.model.ClassNode;

public interface ClassNodeRepository extends JpaRepository<ClassNode, Long> {

    List<ClassNode> findByAnalysisRunIdOrderByQualifiedName(Long analysisRunId);
}
