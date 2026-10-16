package com.example.repolens.model;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

// One analysis of one repo, branch and commit. Every other table points at this one.
@Entity
@Table(name = "analysis_run")
public class AnalysisRun {

    public enum Status { QUEUED, CLONING, PARSING, GRAPH, HISTORY, RISK, SAVING, DONE, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "repository_id")
    private RepositoryEntity repository;

    private String branch;
    private String commitSha;

    @Enumerated(EnumType.STRING)
    private Status status;

    @Column(columnDefinition = "text")
    private String errorMessage;

    private String grade;
    private Integer javaFileCount;
    private Integer classCount;
    private Integer methodCount;
    private Integer endpointCount;
    private Integer cycleCount;
    private Integer violationCount;
    private Integer hotspotCount;
    private Integer parseErrorCount;
    private Integer commitsScanned;

    @Column(columnDefinition = "text")
    private String fileTree;        // JSON

    @Column(columnDefinition = "text")
    private String readingOrder;    // JSON

    private Instant createdAt = Instant.now();
    private Instant finishedAt;

    protected AnalysisRun() {}

    public AnalysisRun(RepositoryEntity repository, String branch) {
        this.repository = repository;
        this.branch = branch;
        this.status = Status.QUEUED;
    }

    public Long getId() { return id; }
    public RepositoryEntity getRepository() { return repository; }
    public String getBranch() { return branch; }
    public String getCommitSha() { return commitSha; }
    public void setCommitSha(String commitSha) { this.commitSha = commitSha; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public String getGrade() { return grade; }
    public void setGrade(String grade) { this.grade = grade; }
    public Integer getJavaFileCount() { return javaFileCount; }
    public void setJavaFileCount(Integer javaFileCount) { this.javaFileCount = javaFileCount; }
    public Integer getClassCount() { return classCount; }
    public void setClassCount(Integer classCount) { this.classCount = classCount; }
    public Integer getMethodCount() { return methodCount; }
    public void setMethodCount(Integer methodCount) { this.methodCount = methodCount; }
    public Integer getEndpointCount() { return endpointCount; }
    public void setEndpointCount(Integer endpointCount) { this.endpointCount = endpointCount; }
    public Integer getCycleCount() { return cycleCount; }
    public void setCycleCount(Integer cycleCount) { this.cycleCount = cycleCount; }
    public Integer getViolationCount() { return violationCount; }
    public void setViolationCount(Integer violationCount) { this.violationCount = violationCount; }
    public Integer getHotspotCount() { return hotspotCount; }
    public void setHotspotCount(Integer hotspotCount) { this.hotspotCount = hotspotCount; }
    public Integer getParseErrorCount() { return parseErrorCount; }
    public void setParseErrorCount(Integer parseErrorCount) { this.parseErrorCount = parseErrorCount; }
    public Integer getCommitsScanned() { return commitsScanned; }
    public void setCommitsScanned(Integer commitsScanned) { this.commitsScanned = commitsScanned; }
    public String getFileTree() { return fileTree; }
    public void setFileTree(String fileTree) { this.fileTree = fileTree; }
    public String getReadingOrder() { return readingOrder; }
    public void setReadingOrder(String readingOrder) { this.readingOrder = readingOrder; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }
}
