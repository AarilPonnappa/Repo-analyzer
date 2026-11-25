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

    public record ChunkDraft(String kind, String className, String methodName, String filePath, int startLine, int endLine, String content) {       // kind is one of CLASS, METHOD, FILE. className and methodName are null for FILE chunks. filePath is relative to the repo root. startLine and endLine are the line numbers of the chunk. content is the actual source code of the chunk.

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

        // Project files first: they answer "what database / framework / version" questions. projectfiles gives us only the metadata info containing files. not the actual source code files
        // projectFiles returns a list of paths to the project files (README, pom.xml, build.gradle, application.properties, etc.) that are not in test folders. It filters out any files that are in .git, /test/, node_modules/, /target/, or /build/ directories. It also limits the number of project files to 12. it also doesnt contain the source code of the classes and methods in the repo. It only contains the project files that are used to build and configure the project. The source code of the classes and methods is contained in the ParsedCode object, which is passed to the build method as the parsed parameter. The build method uses the ParsedCode object to create chunks for each class and method in the repo.
        // The metadata files are added to the chunks list as FILE chunks, with the kind set to "FILE", the className and methodName set to null, the filePath set to the relative path of the file, the startLine set to 1, the endLine set to the minimum of the number of lines in the file and MAX_FILE_LINES, and the content set to the first MAX_FILE_LINES lines of the file. The join method is used to join the lines of the file into a single string, with a maximum length of MAX_CHUNK_CHARS. If the content exceeds MAX_CHUNK_CHARS, it is truncated and a comment is added at the end indicating that it has been truncated.
        for (Path file : projectFiles(repoRoot)) {
            List<String> lines = readLines(file);
            if (lines.isEmpty()) continue;
            int end = Math.min(lines.size(), MAX_FILE_LINES);
            chunks.add(new ChunkDraft("FILE", null, null, CodeParserService.relative(repoRoot, file),
                    1, end, join(lines, 1, end)));
        }

        // We loop through the repoRoot which is the file path of the cloned repo and not the parsed code as the parsed code does not contain the project files. ParsedCode knows that a particular method starts at line 45 and finishes at line 62, but it doesn't contain the complete source code text of that method. repoRoot allows you to open the original Java file and extract those lines. 
        for (ClassInfo c : parsed.classes()) {
            if (c.test() || chunks.size() >= maxChunks) continue;                           // skip test classes and stop if we have enough chunks
            
            // A lambda expression itself doesnt loop through every item in the provided object. But when the lambda expression is used in a stream, and called with operations like forEach, map, filter, etc., the stream will iterate through each item in the provided object and apply the lambda expression to each item. In this case, the stream is iterating through each ClassInfo object in the parsed.classes() list and applying the lambda expression to each ClassInfo object. The lambda expression is checking if the class is a test class or if we have already reached the maximum number of chunks. If either of these conditions is true, it will skip that class and move on to the next one. If both conditions are false, it will proceed to create chunks for that class.
            // The lambda expression in a regular function alows us to define a function, data field or variable that can be passed as an argument to another function or method. In this case, the lambda expression is used to define a function that takes a ClassInfo object as input and returns a boolean value indicating whether the class is a test class or if we have already reached the maximum number of chunks. This function is then passed as an argument to the forEach method, which applies the function to each ClassInfo object in the parsed.classes() list.
            // This doesnt loop through every item in the provided object as in case or a stream/map, but instead assigns the first arguement (key) to the lambda expression p and pass that p to a function
            List<String> lines = fileCache.computeIfAbsent(c.filePath(), p -> readLines(repoRoot.resolve(p)));
            // The computeIfAbsent method checks if the fileCache map already contains an entry for the given file path (c.filePath()). If it does, it returns the existing list of lines for that file. If it doesn't, it calls the provided lambda expression (p -> readLines(repoRoot.resolve(p))) to read the lines from the file and store them in the map. The lambda expression takes the file path (p) as input, resolves it against the repoRoot to get the full path to the file, and then calls the readLines method to read the lines from that file. The result is a list of strings representing the lines of code in that file, which is then stored in the fileCache map for future use. This way, we avoid reading the same file multiple times and improve performance.


            if (lines.isEmpty()) continue;

            int start = Math.max(1, c.startLine());
            int end = Math.min(lines.size(), Math.max(start, c.endLine()));

            // If the Class/file is very small where the number of lines is less than or equal to 60 lines, we create a single chunk for the entire class/file. We also create a single chunk for classes that have no methods, as they are likely to be small utility classes or data classes. In both cases, we limit the end line of the chunk to be at most 150 lines from the start line, to avoid creating very large chunks that may be difficult for the AI model to process. The join method is used to concatenate the lines of code into a single string, which is stored in the content field of the ChunkDraft object.
            if (c.lineCount() <= SMALL_CLASS_LINES || c.methods().isEmpty()) {
                int cappedEnd = Math.min(end, start + MAX_CHUNK_LINES - 1);
                chunks.add(new ChunkDraft("CLASS", c.simpleName(), null, c.filePath(), start, cappedEnd,
                        join(lines, start, cappedEnd)));
                continue;
            }

            // When the class is larger than 60 lines and has methods, we create a header chunk that contains the class declaration and any fields, followed by one chunk for each method. The header chunk is created by finding the line number of the first method in the class and using that to determine the end line of the header chunk. The header chunk is limited to a maximum of 40 lines, to avoid creating very large chunks that may be difficult for the AI model to process. Each method chunk is created by using the start and end line numbers of the method, and limiting the end line to be at most 150 lines from the start line. The join method is used to concatenate the lines of code into a single string, which is stored in the content field of each ChunkDraft object.
            // Header: from the class declaration to just before the first method (at most 40 lines)
            int firstMethod = c.methods().stream().mapToInt(MethodInfo::startLine).filter(l -> l > start).min().orElse(end);        // this line of code is finding the line number of the first method in the class that comes after the start line of the class. It does this by creating a stream of the start line numbers of all the methods in the class, filtering out any methods that start before the start line of the class, and then finding the minimum start line number among the remaining methods. If there are no methods that start after the start line of the class, it returns the end line of the class instead. This value is used to determine where to end the header chunk for the class.
            int headerEnd = Math.max(start, Math.min(firstMethod - 1, start + HEADER_LINES - 1));
            chunks.add(new ChunkDraft("CLASS", c.simpleName(), null, c.filePath(), start, headerEnd,
                    join(lines, start, headerEnd)));

            // Method chunks: one per method (at most 150 lines each)
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
                    // how .filer works is that every item passing through the stream is assigned to variable like p -> or e -> and then the lambda expression is evaluated for each item. The filer has a function that returns a boolean value, if the function returns true then the item is kept in the stream and if it returns false then the item is filtered out of the stream. In this case, the filter is checking if the initializer expression is an ObjectCreationExpr (i.e., a "new" expression). If it is, then it keeps it in the stream; otherwise, it filters it out.
                    .filter(Files::isRegularFile)                       // filters out directories and only KEEPS only regular files. p -> something is a lambda expression every item passing through the stream is assigned to p one at a time as the pipeline runs
                    .filter(p -> {
                        String rel = CodeParserService.relative(root, p);
                        if (rel.startsWith(".git/") || rel.contains("/test/") || rel.contains("node_modules/") || rel.contains("/target/") || rel.contains("/build/")) 
                            return false;                                       // filters these out
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
