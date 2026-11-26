package com.example.repolens.service;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevSort;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.util.io.DisabledOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.example.repolens.dto.HistoryResult;
import com.example.repolens.dto.HistoryResult.CoChange;
import com.example.repolens.dto.HistoryResult.FileHistory;

/**
 * Reads git history with JGit in a single pass over the commits (newest first) and collects,
 * for every .java file: how often it changed, lines added/removed, who changed it, and when.
 * It also finds files that keep changing in the same commits (change coupling).
 *
 * Merge commits, bot commits and huge mechanical commits are left out so they don't distort the numbers.
 * Renames are followed, so a moved file keeps its history.
 */
@Service
public class HistoryService {

    private static final Logger log = LoggerFactory.getLogger(HistoryService.class);

    private static final int MAX_COMMITS = 2000;          // never scan more than this
    private static final int MIN_COMMITS = 300;           // always scan at least this many, even if they're old
    private static final Duration WINDOW = Duration.ofDays(365);
    private static final int MAX_FILES_PER_COMMIT = 50;   // bigger commits are reformatting or bulk renames
    private static final int MAX_FILES_FOR_COUPLING = 10; // wide commits say little about which files belong together
    private static final int MIN_TOGETHER = 5;            // co-change needs at least this many shared commits
    private static final double MIN_COUPLING = 0.6;

    public HistoryResult read(Path repoDir) {
        try (Git git = Git.open(repoDir.toFile());
             DiffFormatter diff = new DiffFormatter(DisabledOutputStream.INSTANCE)) {

            Repository repo = git.getRepository();
            diff.setRepository(repo);
            diff.setDiffComparator(RawTextComparator.DEFAULT);
            diff.setDetectRenames(true);

            ObjectId head = repo.resolve("HEAD");
            if (head == null) return HistoryResult.empty();

            Map<String, FileStats> stats = new HashMap<>();
            Map<String, String> aliases = new HashMap<>();          // old path -> current path (renames)
            Map<String, Integer> repoCommitsByAuthor = new HashMap<>();
            Map<String, Integer> pairCounts = new HashMap<>();
            Instant cutoff = Instant.now().minus(WINDOW);
            int scanned = 0;

            try (RevWalk walk = new RevWalk(repo); ObjectReader reader = repo.newObjectReader()) {
                walk.sort(RevSort.COMMIT_TIME_DESC);
                walk.markStart(walk.parseCommit(head));

                for (RevCommit commit : walk) {
                    Instant when = Instant.ofEpochSecond(commit.getCommitTime());
                    if (scanned >= MAX_COMMITS) break;
                    if (scanned >= MIN_COMMITS && when.isBefore(cutoff)) break;
                    if (commit.getParentCount() > 1) continue;     // merge commit: its changes are counted elsewhere

                    String author = authorName(commit.getAuthorIdent());
                    if (author == null) continue;                  // bot
                    scanned++;

                    AbstractTreeIterator oldTree = commit.getParentCount() == 0
                            ? new EmptyTreeIterator()
                            : new CanonicalTreeParser(null, reader, walk.parseCommit(commit.getParent(0)).getTree());
                    AbstractTreeIterator newTree = new CanonicalTreeParser(null, reader, commit.getTree());
                    List<DiffEntry> changes = diff.scan(oldTree, newTree);

                    Set<String> touched = new LinkedHashSet<>();
                    for (DiffEntry change : changes) {
                        if (change.getChangeType() == DiffEntry.ChangeType.DELETE) continue;
                        String newPath = change.getNewPath();
                        String current = aliases.getOrDefault(newPath, newPath);
                        if (change.getChangeType() == DiffEntry.ChangeType.RENAME) {
                            // older commits know this file by its old name
                            aliases.put(change.getOldPath(), current);
                        }
                        if (!current.endsWith(".java")) continue;

                        int added = 0;
                        int removed = 0;
                        if (changes.size() <= MAX_FILES_PER_COMMIT) {
                            for (Edit edit : diff.toFileHeader(change).toEditList()) {
                                added += edit.getLengthB();
                                removed += edit.getLengthA();
                            }
                        }
                        stats.computeIfAbsent(current, FileStats::new).record(author, when, added, removed);
                        touched.add(current);
                    }

                    if (!touched.isEmpty()) {
                        repoCommitsByAuthor.merge(author, 1, Integer::sum);
                    }
                    if (touched.size() >= 2 && changes.size() <= MAX_FILES_FOR_COUPLING) {
                        List<String> files = touched.stream().sorted().toList();
                        for (int i = 0; i < files.size(); i++) {
                            for (int j = i + 1; j < files.size(); j++) {
                                pairCounts.merge(files.get(i) + "|" + files.get(j), 1, Integer::sum);
                            }
                        }
                    }
                }
            }

            Map<String, FileHistory> files = new HashMap<>();
            stats.forEach((path, s) -> files.put(path, s.toHistory()));

            log.info("Scanned {} commits, {} Java files with history", scanned, files.size());
            return new HistoryResult(files, scanned, repoCommitsByAuthor.size(), repoCommitsByAuthor,
                    coChanges(pairCounts, files));

        } catch (IOException e) {
            log.warn("Couldn't read git history: {}", e.getMessage());
            return HistoryResult.empty();
        }
    }

    // Pairs of files that changed together in most of their commits
    private List<CoChange> coChanges(Map<String, Integer> pairCounts, Map<String, FileHistory> files) {
        List<CoChange> result = new ArrayList<>();
        pairCounts.forEach((pair, together) -> {
            if (together < MIN_TOGETHER) return;
            String[] p = pair.split("\\|");
            int fewer = Math.min(files.get(p[0]).commits(), files.get(p[1]).commits());
            double ratio = fewer == 0 ? 0 : (double) together / fewer;
            if (ratio >= MIN_COUPLING) result.add(new CoChange(p[0], p[1], together, ratio));
        });
        result.sort(Comparator.comparingInt(CoChange::together).reversed());
        return result.size() > 10 ? result.subList(0, 10) : result;
    }

    // Same person with two emails counts once (grouped by name). Bots return null.
    private static String authorName(PersonIdent ident) {
        String name = ident.getName() == null ? "" : ident.getName().trim();
        String email = ident.getEmailAddress() == null ? "" : ident.getEmailAddress().toLowerCase();
        String lower = name.toLowerCase();
        if (lower.contains("[bot]") || email.contains("[bot]") || lower.startsWith("dependabot")
                || lower.startsWith("renovate") || lower.equals("github-actions")) {
            return null;
        }
        return name.isEmpty() ? email : name;
    }

    private static final class FileStats {
        private final String path;
        private int commits;
        private int added;
        private int removed;
        private final Map<String, Integer> byAuthor = new HashMap<>();
        private Instant first;
        private Instant last;

        FileStats(String path) {
            this.path = path;
        }

        void record(String author, Instant when, int added, int removed) {
            commits++;
            this.added += added;
            this.removed += removed;
            byAuthor.merge(author, 1, Integer::sum);
            if (last == null || when.isAfter(last)) last = when;
            if (first == null || when.isBefore(first)) first = when;
        }

        FileHistory toHistory() {
            return new FileHistory(path, commits, added, removed, Map.copyOf(byAuthor), first, last);
        }
    }
}
