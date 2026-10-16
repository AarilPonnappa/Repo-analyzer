package com.example.repolens.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

// One Java class, interface, enum or record found in the repo. These are the nodes of the architecture map.
@Entity
@Table(name = "class_node")
public class ClassNode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long analysisRunId;
    private String qualifiedName;
    private String simpleName;
    private String packageName;
    private String kind;
    private String layer;
    private String filePath;
    private Integer startLine;
    private Integer endLine;
    private Integer lineCount;
    private Integer methodCount;
    private boolean isTest;

    @Column(columnDefinition = "text")
    private String annotations;

    protected ClassNode() {}

    public ClassNode(Long analysisRunId, String qualifiedName, String simpleName, String packageName,
                     String kind, String layer, String filePath, Integer startLine, Integer endLine,
                     Integer lineCount, Integer methodCount, boolean isTest, String annotations) {
        this.analysisRunId = analysisRunId;
        this.qualifiedName = qualifiedName;
        this.simpleName = simpleName;
        this.packageName = packageName;
        this.kind = kind;
        this.layer = layer;
        this.filePath = filePath;
        this.startLine = startLine;
        this.endLine = endLine;
        this.lineCount = lineCount;
        this.methodCount = methodCount;
        this.isTest = isTest;
        this.annotations = annotations;
    }

    public Long getId() { return id; }
    public Long getAnalysisRunId() { return analysisRunId; }
    public String getQualifiedName() { return qualifiedName; }
    public String getSimpleName() { return simpleName; }
    public String getPackageName() { return packageName; }
    public String getKind() { return kind; }
    public String getLayer() { return layer; }
    public String getFilePath() { return filePath; }
    public Integer getStartLine() { return startLine; }
    public Integer getEndLine() { return endLine; }
    public Integer getLineCount() { return lineCount; }
    public Integer getMethodCount() { return methodCount; }
    public boolean isTest() { return isTest; }
    public String getAnnotations() { return annotations; }
}
