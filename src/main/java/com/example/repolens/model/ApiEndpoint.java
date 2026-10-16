package com.example.repolens.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

// One HTTP endpoint, e.g. POST /owners/new, plus its traced request flow as JSON.
@Entity
@Table(name = "endpoint")
public class ApiEndpoint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long analysisRunId;
    private Long classId;
    private String httpMethod;
    private String path;
    private String methodName;
    private String methodSignature;
    private Integer startLine;

    @Column(columnDefinition = "text")
    private String trace;

    protected ApiEndpoint() {}

    public ApiEndpoint(Long analysisRunId, Long classId, String httpMethod, String path,
                       String methodName, String methodSignature, Integer startLine, String trace) {
        this.analysisRunId = analysisRunId;
        this.classId = classId;
        this.httpMethod = httpMethod;
        this.path = path;
        this.methodName = methodName;
        this.methodSignature = methodSignature;
        this.startLine = startLine;
        this.trace = trace;
    }

    public Long getId() { return id; }
    public Long getAnalysisRunId() { return analysisRunId; }
    public Long getClassId() { return classId; }
    public String getHttpMethod() { return httpMethod; }
    public String getPath() { return path; }
    public String getMethodName() { return methodName; }
    public String getMethodSignature() { return methodSignature; }
    public Integer getStartLine() { return startLine; }
    public String getTrace() { return trace; }
}
