package com.example.repolens.dto;

import java.util.List;

/**
 * One folder or file in the repo's file tree. Produced by RepoTreeService.
 * For .java files, types and layer say what JavaParser found inside.
 */
public record FileTreeNode(
        String name,              // "OwnerController.java"
        String path,              // "src/main/java/org/example/owner/OwnerController.java"
        boolean directory,
        Integer lines,            // line count for .java files, null otherwise
        List<String> types,       // classes declared in this file, e.g. ["OwnerController"]
        String layer,             // layer of the main class in this file, null for non-Java files
        List<FileTreeNode> children
) {}
