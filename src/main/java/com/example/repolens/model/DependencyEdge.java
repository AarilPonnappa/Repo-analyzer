package com.example.repolens.model;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

// "Class A depends on class B". These are the arrows of the architecture map.
@Entity
@Table(name = "dependency_edge")
public class DependencyEdge {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long analysisRunId;
    private Long fromClassId;
    private Long toClassId;
    private String kind;

    protected DependencyEdge() {}

    public DependencyEdge(Long analysisRunId, Long fromClassId, Long toClassId, String kind) {
        this.analysisRunId = analysisRunId;
        this.fromClassId = fromClassId;
        this.toClassId = toClassId;
        this.kind = kind;
    }

    public Long getId() { return id; }
    public Long getAnalysisRunId() { return analysisRunId; }
    public Long getFromClassId() { return fromClassId; }
    public Long getToClassId() { return toClassId; }
    public String getKind() { return kind; }
}
