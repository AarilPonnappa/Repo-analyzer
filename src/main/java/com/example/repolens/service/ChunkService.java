package com.example.repolens.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.MalformedInputException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.example.repolens.dto.ParsedCode;
import com.example.repolens.dto.ParsedCode.ClassInfo;
import com.example.repolens.dto.ParsedCode.MethodInfo;

/**
 * Splits the repo into pieces for "Ask the repo":
 *  - small classes (up to 60 lines) become one chunk
 *  - larger classes become a header chunk (declaration and fields) plus one chunk per method
 *  - README, build files and application config become FILE chunks
 *
 * Must run while the cloned folder still exists, because it reads the source text.
 * Test classes are left out so answers point at the real code.
 * 
 * ChunkService takes the actual source code and divides it into smaller pieces that an AI model can retrieve and understand.
 */
@Service
public class ChunkService {

    private static final int SMALL_CLASS_LINES = 60;
    private static final int HEADER_LINES = 40;
    private static final int MAX_CHUNK_LINES = 150;
    private static final int MAX_CHUNK_CHARS = 6000;
    private static final int MAX_FILE_LINES = 200;

    public record ChunkDraft(String kind, String className, String methodName, String filePath,
                             int startLine, int endLine, String content) {

        // this function is called whenever the record is created, and it returns a string that contains the file path, class name, method name, and content of the chunk. This string is used to create an embedding for the chunk, which is a numerical representation of the chunk's content that can be used for machine learning tasks.
        
        /* embeddingText() produces something like:
        File: src/main/java/service/OwnerService.java
        Class: OwnerService
        Method: getOwner

        public Owner getOwner(Long id) {
            return ownerRepository.findById(id).orElseThrow();
        }
        */

        public String embeddingText() {
            StringBuilder sb = new StringBuilder("File: ").append(filePath).append('\n');
            if (className != null) sb.append("Class: ").append(className).append('\n');
            if (methodName != null) sb.append("Method: ").append(methodName).append('\n');
            return sb.append(content).toString();
        }
    }

    private final int maxChunks;

    public ChunkService(@Value("${repolens.max-chunks:1500}") int maxChunks) {
        this.maxChunks = maxChunks;
    }

    public List<ChunkDraft> build(Path repoRoot, ParsedCode parsed) {
        List<ChunkDraft> chunks = new ArrayList<>();
        Map<String, List<String>> fileCache = new HashMap<>();

        // Project files first: they answer "what database / framework / version" questions
        for (Path file : projectFiles(repoRoot)) {
            List<String> lines = readLines(file);
            if (lines.isEmpty()) continue;
            int end = Math.min(lines.size(), MAX_FILE_LINES);
            chunks.add(new ChunkDraft("FILE", null, null, CodeParserService.relative(repoRoot, file),
                    1, end, join(lines, 1, end)));
        }

        for (ClassInfo c : parsed.classes()) {
            if (c.test() || chunks.size() >= maxChunks) continue;
            List<String> lines = fileCache.computeIfAbsent(c.filePath(), p -> readLines(repoRoot.resolve(p)));
            if (lines.isEmpty()) continue;

            int start = Math.max(1, c.startLine());
            int end = Math.min(lines.size(), Math.max(start, c.endLine()));

            if (c.lineCount() <= SMALL_CLASS_LINES || c.methods().isEmpty()) {
                int cappedEnd = Math.min(end, start + MAX_CHUNK_LINES - 1);
                chunks.add(new ChunkDraft("CLASS", c.simpleName(), null, c.filePath(), start, cappedEnd,
                        join(lines, start, cappedEnd)));
                continue;
            }

            // Header: from the class declaration to just before the first method (at most 40 lines)
            int firstMethod = c.methods().stream().mapToInt(MethodInfo::startLine).filter(l -> l > start).min().orElse(end);
            int headerEnd = Math.max(start, Math.min(firstMethod - 1, start + HEADER_LINES - 1));
            chunks.add(new ChunkDraft("CLASS", c.simpleName(), null, c.filePath(), start, headerEnd,
                    join(lines, start, headerEnd)));

            for (MethodInfo m : c.methods()) {
                if (chunks.size() >= maxChunks) break;
                int mStart = Math.max(1, m.startLine());
                int mEnd = Math.min(lines.size(), Math.min(m.endLine(), mStart + MAX_CHUNK_LINES - 1));
                if (mEnd < mStart) continue;
                chunks.add(new ChunkDraft("METHOD", c.simpleName(), m.name(), c.filePath(), mStart, mEnd,
                        join(lines, mStart, mEnd)));
            }
        }
        return chunks.size() > maxChunks ? new ArrayList<>(chunks.subList(0, maxChunks)) : chunks;
    }

    // README, pom.xml / build.gradle, and application.properties / .yml outside test folders
    private static List<Path> projectFiles(Path root) {
        try (Stream<Path> paths = Files.walk(root, 6)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String rel = CodeParserService.relative(root, p);
                        if (rel.startsWith(".git/") || rel.contains("/test/") || rel.contains("node_modules/")
                                || rel.contains("/target/") || rel.contains("/build/")) return false;
                        String name = p.getFileName().toString().toLowerCase();
                        boolean topLevel = !rel.contains("/");
                        return (topLevel && name.startsWith("readme"))
                                || name.equals("pom.xml") || name.equals("build.gradle") || name.equals("build.gradle.kts")
                                || name.matches("application(-[\\w-]+)?\\.(properties|ya?ml)");
                    })
                    .sorted()
                    .limit(12)
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static List<String> readLines(Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (MalformedInputException e) {
            try {
                return Files.readAllLines(file, StandardCharsets.ISO_8859_1);
            } catch (IOException ex) {
                return List.of();
            }
        } catch (IOException e) {
            return List.of();
        }
    }

    // Lines are 1-based and inclusive
    private static String join(List<String> lines, int start, int end) {
        String text = String.join("\n", lines.subList(start - 1, end));
        return text.length() > MAX_CHUNK_CHARS ? text.substring(0, MAX_CHUNK_CHARS) + "\n// ..." : text;
        
    }
}
