package com.example.repolens.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

// A class- or method-sized piece of code, used by "Ask the repo".
// The embedding is a float vector stored as bytes (4 bytes per number).
@Entity
@Table(name = "code_chunk")
public class CodeChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long analysisRunId;
    private String kind;            // CLASS, METHOD, FILE
    private String className;       // simple class name, null for FILE chunks
    private String methodName;
    private String filePath;
    private int startLine;
    private int endLine;

    @Column(columnDefinition = "text")
    private String content;

    private byte[] embedding;

    protected CodeChunk() {}

    public CodeChunk(Long analysisRunId, String kind, String className, String methodName, String filePath,
                     int startLine, int endLine, String content, byte[] embedding) {
        this.analysisRunId = analysisRunId;
        this.kind = kind;
        this.className = className;
        this.methodName = methodName;
        this.filePath = filePath;
        this.startLine = startLine;
        this.endLine = endLine;
        this.content = content;
        this.embedding = embedding;
    }

    public Long getId() { return id; }
    public Long getAnalysisRunId() { return analysisRunId; }
    public String getKind() { return kind; }
    public String getClassName() { return className; }
    public String getMethodName() { return methodName; }
    public String getFilePath() { return filePath; }
    public int getStartLine() { return startLine; }
    public int getEndLine() { return endLine; }
    public String getContent() { return content; }
    public byte[] getEmbedding() { return embedding; }
}
