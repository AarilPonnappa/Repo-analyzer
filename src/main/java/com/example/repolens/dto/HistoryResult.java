package com.example.repolens.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * What git history says about each .java file. Produced by HistoryService.
 */
public record HistoryResult(
        Map<String, FileHistory> files,   // keyed by current file path
        int commitsScanned,
        int authorCount,
        Map<String, Integer> commitsByAuthor,   // across the whole repo, for the project bus factor
        List<CoChange> coChanges          // files that keep changing together
) {

    public record FileHistory(
            String path,
            int commits,
            int linesAdded,
            int linesRemoved,
            Map<String, Integer> commitsByAuthor,
            Instant firstSeen,        // oldest commit in the scanned window that touched this file
            Instant lastChanged
    ) {
        public int authorCount() {
            return commitsByAuthor.size();
        }
    }

    public record CoChange(String fileA, String fileB, int together, double ratio) {}

    public static HistoryResult empty() {
        return new HistoryResult(Map.of(), 0, 0, Map.of(), List.of());
    }
}
