package com.example.repolens.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

// A problem worth showing: a cycle, a layer violation, a hotspot, a test gap, ...
// Details that differ by type are stored as JSON text.
@Entity
@Table(name = "finding")
public class Finding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long analysisRunId;
    private String type;
    private String severity;

    @Column(columnDefinition = "text")
    private String message;

    private String filePath;
    private Integer line;

    @Column(columnDefinition = "text")
    private String details;

    protected Finding() {}

    public Finding(Long analysisRunId, String type, String severity, String message,
                   String filePath, Integer line, String details) {
        this.analysisRunId = analysisRunId;
        this.type = type;
        this.severity = severity;
        this.message = message;
        this.filePath = filePath;
        this.line = line;
        this.details = details;
    }

    public Long getId() { return id; }
    public Long getAnalysisRunId() { return analysisRunId; }
    public String getType() { return type; }
    public String getSeverity() { return severity; }
    public String getMessage() { return message; }
    public String getFilePath() { return filePath; }
    public Integer getLine() { return line; }
    public String getDetails() { return details; }
}
