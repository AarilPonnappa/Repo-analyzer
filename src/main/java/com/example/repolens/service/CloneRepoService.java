package com.example.repolens.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.InvalidRemoteException;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class CloneRepoService {

    // logger is an object used to capture, record, and route runtime events, errors, warnings, and informational messages from an application
    // it is a routing framework not a storage container(like an array). It allows developers to log messages at different levels (e.g., debug, info, warn, error) and configure where those messages go (e.g., console, file, remote server).
    private static final Logger log = LoggerFactory.getLogger(CloneRepoService.class);

    private static final int CLONE_TIMEOUT_SECONDS = 120;
    private static final long MAX_JAVA_FILES = 3000;

    /**
     * A record is a special kind of class in Java that is used to model immutable data. It automatically generates boilerplate code like constructors, getters, equals(), hashCode(), and toString() methods.
     * Think of it as it creates private variables for each field, a constructor to initialize those variables, and getter methods to access their values. It even creates functions of the variable name to get the value of the variable. For example, if you have a record with a field called "name", it will automatically create a method called "name()" that returns the value of that field.
     * 
     * AutoCloseable is an interface in Java that allows an object to be used in a try-with-resources statement. When the try block is exited, either normally or because of an exception, the close() method of the AutoCloseable object is automatically called. This is useful for managing resources like file streams or database connections, ensuring they are properly closed after use.
     */
    public record ClonedRepo(Path path, String branch, String commitSha, long javaFileCount)
            implements AutoCloseable {

        // This is how it knows what to close. Its the close method of the AutoCloseable interface thats called automatically and what the close method should do.
        @Override
        public void close() {
            deleteFolder(path);
        }
    }

    /**
     * Clones one branch of a GitHub repo (with its history) into a temporary folder.
     *
     * @param ownerRepo "owner/repo", already validated
     * @param branch    the branch to clone
     * @param token     the user's personal access token for private repos, or null for public repos
     */
    public ClonedRepo cloneRepo(String ownerRepo, String branch, String token) {
        Path workDir;           // A path type is an object that represents a file or directory path in the file system. It can be absolute (starting from the root of the file system) or relative (starting from the current working directory). In this case, workDir will hold the path to a temporary directory where the GitHub repository will be cloned. The data looks like this: /var/folders/0_/1x2y3z4w5v6u7t8s9r0q1p2o3n4m5l6k/T/repolens-1234567890abcdef
        try {
            workDir = Files.createTempDirectory("repolens-");       // this creates a directory and stores value like this: /var/folders/0_/1x2y3z4w5v6u7t8s9r0q1p2o3n4m5l6k/T/repolens-1234567890abcdef
        } catch (IOException e) {
            throw new CloneException("Couldn't create a working folder on the server.", e);
        }

        try {
            String commitSha;

            try (Git git = Git.cloneRepository()
                    .setURI("https://github.com/" + ownerRepo + ".git")
                    .setDirectory(workDir.toFile())
                    .setBranchesToClone(List.of("refs/heads/" + branch))  // only this branch
                    .setBranch(branch)                                     // check it out
                    .setCredentialsProvider(credentials(token))
                    .setTimeout(CLONE_TIMEOUT_SECONDS)
                    .call()) {

                // get the commit SHA of the HEAD of the cloned branch. The HEAD is a reference(think of it as a pointer or keyword) to the latest commit on the current branch. It points to the tip of the branch, which is the most recent commit in that branch's history. In Git, branches are essentially pointers to commits, and HEAD is a special pointer that indicates which branch or commit is currently checked out in your working directory.
                ObjectId head = git.getRepository().resolve("HEAD");        // resolve is a method that takes a string representation of a Git reference (like a branch name, tag, or commit hash) and returns the corresponding ObjectId or a commitID, which is a unique identifier for that object in the Git repository. An object is a commit, tree, or blob. In this case, "HEAD" refers to the latest commit on the currently checked-out branch.
                if (head == null) {
                    throw new CloneException("Branch '" + branch + "' was not found in " + ownerRepo + ".");
                }
                commitSha = head.getName();
            }

            long javaFiles = countJavaFiles(workDir);
            if (javaFiles == 0) {
                throw new CloneException("No .java files were found on branch '" + branch + "'.");
            }
            if (javaFiles > MAX_JAVA_FILES) {
                throw new CloneException("This repository has " + javaFiles
                        + " Java files. RepoLens supports up to " + MAX_JAVA_FILES + ".");
            }

            log.info("Cloned {} ({}) at {} into {} with {} Java files",
                    ownerRepo, branch, commitSha, workDir, javaFiles);

            return new ClonedRepo(workDir, branch, commitSha, javaFiles);

        } catch (CloneException e) {
            deleteFolder(workDir);
            throw e;
        } catch (InvalidRemoteException e) {
            deleteFolder(workDir);
            throw new CloneException("Repository " + ownerRepo + " was not found.", e);
        } catch (TransportException e) {
            deleteFolder(workDir);
            throw new CloneException(transportMessage(e, ownerRepo, branch), e);
        } catch (GitAPIException | IOException e) {
            deleteFolder(workDir);
            throw new CloneException("Couldn't clone " + ownerRepo + ".", e);
        } catch (RuntimeException e) {
            deleteFolder(workDir);
            throw e;
        }
    }

    // GitHub accepts a token as the password, with any non-empty username
    private CredentialsProvider credentials(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        return new UsernamePasswordCredentialsProvider("x-access-token", token);
    }

    // Turns JGit's network errors into messages a user can act on
    private String transportMessage(TransportException e, String ownerRepo, String branch) {
        String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();

        if (msg.contains("not authorized") || msg.contains("authentication")) {
            return "GitHub refused access to " + ownerRepo + ". Check that the token is valid and can read this repo.";
        }
        if (msg.contains("not found") && msg.contains(branch.toLowerCase())) {
            return "Branch '" + branch + "' was not found in " + ownerRepo + ".";
        }
        if (msg.contains("timeout") || msg.contains("timed out")) {
            return "Cloning " + ownerRepo + " took too long. The repository may be too large.";
        }
        return "Couldn't download " + ownerRepo + " from GitHub.";
    }

    // Counts .java files, skipping the .git folder
    private long countJavaFiles(Path root) throws IOException {
        Path gitDir = root.resolve(".git");         // not same as the .resolve used above for objectID. This .resolve is a method of the Path class that combines two paths into one. In this case, it takes the root path (the temporary directory where the repo was cloned) and appends ".git" to it, resulting in the full path to the .git directory within the cloned repository. The .git directory contains all the metadata and history for the Git repository, but we don't want to count any Java files that might be in there, so we skip it when counting .java files.
        try (Stream<Path> files = Files.walk(root)) {
            return files
                    .filter(p -> !p.startsWith(gitDir))
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(Files::isRegularFile)
                    .count();
        }
    }

    // Deletes a folder and everything in it (deepest files first)
    static void deleteFolder(Path folder) {
        if (folder == null || !Files.exists(folder)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(folder)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    p.toFile().setWritable(true);   // git marks some files read-only (needed on Windows)
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (IOException | UncheckedIOException e) {
            log.warn("Couldn't fully delete {}: {}", folder, e.getMessage());
        }
    }
}