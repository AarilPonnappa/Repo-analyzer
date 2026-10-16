package com.example.repolens.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;

import com.example.repolens.dto.FileTreeNode;
import com.example.repolens.dto.ParsedCode;
import com.example.repolens.dto.ParsedCode.ClassInfo;

/**
 * Builds the repo's folder/file tree. For .java files it adds what JavaParser found inside
 * (class names and layer), using the ParsedCode so no file is parsed twice.
 */
@Service
public class RepoTreeService {

    private static final int MAX_NODES = 5000;

    public FileTreeNode build(Path repoRoot, ParsedCode parsed) {
        // file path -> classes declared in that file
        Map<String, List<ClassInfo>> classesByFile = new HashMap<>();
        for (ClassInfo c : parsed.classes()) {
            classesByFile.computeIfAbsent(c.filePath(), k -> new ArrayList<>()).add(c);
        }
        int[] budget = {MAX_NODES};
        return buildNode(repoRoot, repoRoot, classesByFile, budget);
    }

    private FileTreeNode buildNode(Path root, Path path, Map<String, List<ClassInfo>> classesByFile, int[] budget) {
        budget[0]--;
        String relPath = root.equals(path) ? "" : CodeParserService.relative(root, path);
        String name = root.equals(path) ? "/" : path.getFileName().toString();

        if (Files.isDirectory(path)) {
            List<FileTreeNode> children = new ArrayList<>();
            for (Path child : sortedChildren(path)) {
                if (budget[0] <= 0) break;
                if (Files.isDirectory(child) && CodeParserService.SKIPPED_DIRS.contains(child.getFileName().toString())) continue;
                children.add(buildNode(root, child, classesByFile, budget));
            }
            return new FileTreeNode(name, relPath, true, null, null, null, children);
        }

        if (name.endsWith(".java")) {
            List<ClassInfo> classes = classesByFile.getOrDefault(relPath, List.of());
            List<String> types = classes.stream().map(ClassInfo::simpleName).toList();
            String layer = classes.isEmpty() ? null : classes.get(0).layer();
            return new FileTreeNode(name, relPath, false, countLines(path), types, layer, null);
        }
        return new FileTreeNode(name, relPath, false, null, null, null, null);
    }

    // Folders first, then files, alphabetically
    private static List<Path> sortedChildren(Path dir) {
        try (Stream<Path> children = Files.list(dir)) {
            return children.sorted(Comparator
                            .comparing((Path p) -> !Files.isDirectory(p))
                            .thenComparing(p -> p.getFileName().toString().toLowerCase()))
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static Integer countLines(Path file) {
        try (Stream<String> lines = Files.lines(file)) {
            return (int) lines.count();
        } catch (IOException | RuntimeException e) {
            return null;   // unreadable or not UTF-8
        }
    }

    /**
     * Prints the tree as text, handy for logs and debugging:
     *
     *   📁 src/
     *     📁 main/
     *       📄 OwnerController.java  (CONTROLLER: OwnerController)
     */
    public String toText(FileTreeNode root) {
        StringBuilder out = new StringBuilder();
        appendText(root, 0, out);
        return out.toString();
    }

    private void appendText(FileTreeNode node, int depth, StringBuilder out) {
        if (depth > 0) {
            String indent = "  ".repeat(depth - 1);
            if (node.directory()) {
                out.append(indent).append("📁 ").append(node.name()).append("/\n");
            } else {
                out.append(indent).append("📄 ").append(node.name());
                if (node.types() != null && !node.types().isEmpty()) {
                    out.append("  (").append(node.layer()).append(": ").append(String.join(", ", node.types())).append(")");
                }
                out.append("\n");
            }
        }
        if (node.children() != null) {
            for (FileTreeNode child : node.children()) {
                appendText(child, depth + 1, out);
            }
        }
    }
}
