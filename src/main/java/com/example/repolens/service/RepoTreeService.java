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
 * Takes the path of the repo root and the ParsedCode object produced by CodeParserService, and returns a FileTreeNode representing the root of the tree.
 */
@Service
public class RepoTreeService {

    private static final int MAX_NODES = 5000;

    public FileTreeNode build(Path repoRoot, ParsedCode parsed) {
        // file path -> classes declared in that file
        Map<String, List<ClassInfo>> classesByFile = new HashMap<>();       // The key is the relative path of the file, e.g. "src/main/java/org/example/Owner.java". The value is a list of ClassInfo objects for all classes declared in that file.
        for (ClassInfo c : parsed.classes()) {
            classesByFile.computeIfAbsent(c.filePath(), k -> new ArrayList<>()).add(c);     // The computeIfAbsent method checks if the key (the relative path of the file) is already present in the map. If it is not present, it creates a new ArrayList and associates it with that key. Then, it adds the ClassInfo object to the list of classes for that file.
        }                                                                                   // So we now have a map where each key is a file path and its value is a list of ClassInfo objects representing the classes declared in that file. MAP => path → [ClassInfo]. Example: "model/Owner.java" → [Owner] OR "service/OwnerService.java" → [OwnerService]
        int[] budget = {MAX_NODES};
        return buildNode(repoRoot, repoRoot, classesByFile, budget);
    }

    // Recusively builds the tree, returning a FileTreeNode for the given path. If it's a directory, it recurses into children. If it's a file, it looks up classes in classesByFile.
    // budget[0] is decremented for each node. When it reaches 0, the recursion stops and the remaining nodes are not included in the tree. This prevents building a huge tree
    private FileTreeNode buildNode(Path root, Path path, Map<String, List<ClassInfo>> classesByFile, int[] budget) {
        budget[0]--;
        // gets the relative path of the file or directory from the root of the repo. If the path is the same as the root, it returns an empty string. Otherwise, it uses CodeParserService.relative to compute the relative path from the root to the given path.
        String relPath = root.equals(path) ? "" : CodeParserService.relative(root, path);
        String name = root.equals(path) ? "/" : path.getFileName().toString();

        if (Files.isDirectory(path)) {
            List<FileTreeNode> children = new ArrayList<>();
            for (Path child : sortedChildren(path)) {
                if (budget[0] <= 0) break;
                if (Files.isDirectory(child) && CodeParserService.SKIPPED_DIRS.contains(child.getFileName().toString())) continue;      // only when its a directory, we find the children of the directory.
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
    private static List<Path> sortedChildren(Path dir) {            // we first pass the main roots path
        // Files.list(dir) asks the operating system to give us a stream of all the files and directories that are directly inside the directory represented by dir. It does not go into subdirectories; it only lists the immediate children of dir. This how we gets the children of the directory
        // so say first it does Files.list(root) -> the OS gives us all the files and directories inside the root directory like "src", "pom.xml", "README.md", etc. Then for each of those children, if it's a directory, we call sortedChildren(child) to get its children, and so on recursively.
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
     *   src/
     *     main/
     *       OwnerController.java  (CONTROLLER: OwnerController)
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
