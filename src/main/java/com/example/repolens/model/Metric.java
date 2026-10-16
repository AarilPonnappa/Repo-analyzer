package com.example.repolens.model;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

// Any number about a class, method or the whole project: complexity, lines, changes, authors, ...
// One generic table, so a new measurement is a new row, not a new table.
@Entity
@Table(name = "metric")
public class Metric {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long analysisRunId;
    private String targetType;
    private String targetName;
    private String filePath;
    private String name;
    private double value;

    protected Metric() {}

    public Metric(Long analysisRunId, String targetType, String targetName, String filePath, String name, double value) {
        this.analysisRunId = analysisRunId;
        this.targetType = targetType;
        this.targetName = targetName;
        this.filePath = filePath;
        this.name = name;
        this.value = value;
    }

    public Long getId() { return id; }
    public Long getAnalysisRunId() { return analysisRunId; }
    public String getTargetType() { return targetType; }
    public String getTargetName() { return targetName; }
    public String getFilePath() { return filePath; }
    public String getName() { return name; }
    public double getValue() { return value; }
}
