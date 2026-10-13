package com.example.repolens.controllers;

import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.client.HttpClientErrorException;

import com.example.repolens.service.GithubRepoService;


@Controller
public class RepolensController {

    // Matches "owner/repo" after the input is cleaned up
    private static final Pattern OWNER_REPO =
            Pattern.compile("^[A-Za-z0-9-_.]+/[A-Za-z0-9-_.]+$");

    private final GithubRepoService githubRepoService;

    RepolensController(GithubRepoService githubRepoService) {
        this.githubRepoService = githubRepoService;
    }

    // The JSON the frontend sends
    // This is a record, which is a new feature in Java 16. It is a concise way to define a class that is just a data carrier. It automatically generates the constructor, getters, equals, hashCode, and toString methods.
    // records are immutable, meaning their fields cannot be changed after they are created. This is useful for data transfer objects (DTOs) that are used to send data between the frontend and backend.
    // Allows us to use request.repo(), request.branch(), request.visibility(), and request.token() to get the values of the fields.
    public record AnalyzeRequest(String repo, String branch, String visibility, String token) {}

    private static final long MAX_REPO_SIZE_KB = 50 * 1024;

    @GetMapping("/checkoutrepo")
    public String checkoutRepo() {
        return "checkoutrepo";
    }

    @PostMapping("/api/analyses")
    @ResponseBody
    // next time always check the kind of data an api returns. Its not always a string or int. It can be an object too. So we use object to avoid type mismatch errors
    // object is the most generic type in java. So we can use it to store any kind of data. But we need to cast it to the specific type when we want to use it.
    public ResponseEntity<Map<String, Object>> analyzeRepo(@RequestBody AnalyzeRequest request) {       // picked object as github returns object type and not a specific type like string or int, so we use object to avoid type mismatch errors

        // Visibility must be public or private
        String visibility = request.visibility();
        if (!"public".equals(visibility) && !"private".equals(visibility)) {
            return error(400, "Choose public or private.");
        }

        // Clean up and validate the repo
        String ownerRepo = RepoFormat(request.repo());
        if (ownerRepo == null) {
            return error(400, "Enter a valid repo, like owner/repo or a GitHub URL.");
        }

        // Token is required only for private repos, and is never stored
        String token = null;
        if ("private".equals(visibility)) {
            if (request.token() == null || request.token().isBlank()) {
                return error(400, "A private repo needs a personal access token.");
            }
            token = request.token().trim();
        }

        // Ask GitHub about the repo
        Map<String, Object> repoInfo;
        try {
            repoInfo = githubRepoService.getRepoInfo(ownerRepo, token);
        } catch (HttpClientErrorException.NotFound e) {
            return error(404, "Repository not found, or the token can't access it.");
        } catch (HttpClientErrorException.Unauthorized e) {
            return error(401, "The token is invalid or expired.");
        } catch (HttpClientErrorException.Forbidden e) {
            return error(403, "GitHub refused the request. You may have hit the rate limit, try again later.");
        }

        // Use the typed branch, or the repo's default branch if it was left empty
        String branch = (request.branch() == null || request.branch().isBlank())
                ? Objects.toString(repoInfo.get("default_branch"), "main")
                : request.branch().trim();

        // check the repo size. GitHub returns the size in KB, so I convert it to MB for the error message.
        long sizeKb = ((Number) Objects.requireNonNullElse(repoInfo.get("size"), 0)).longValue();
        if (sizeKb > MAX_REPO_SIZE_KB) {
            return error(413, String.format(
                    "This repository is %.1f MB. RepoLens supports repositories up to %d MB.",
                    sizeKb / 1024.0, MAX_REPO_SIZE_KB / 1024));
        }

        // Only Java projects are supported
        String language = Objects.toString(repoInfo.get("language"), "");
        if (!"Java".equals(language)) {
            return error(400, "RepoLens only supports Java repositories. This one is "
                    + (language.isEmpty() ? "an unknown language" : language) + ".");
        }

        // To be returned
        return ResponseEntity.ok(Map.of(
                "repo", ownerRepo,
                "branch", branch,
                "size", Objects.requireNonNullElse(repoInfo.get("size"), 0),
                "language", Objects.toString(repoInfo.get("language"), "unknown"),
                "private", Objects.requireNonNullElse(repoInfo.get("private"), false)
        ));
    }

    // Turns "https://github.com/owner/repo/tree/main" and "owner/repo.git", etc. into "owner/repo"
    private String RepoFormat(String input) {
        if (input == null) return null;

        String value = input.trim()
                .replaceFirst("^https?://", "")
                .replaceFirst("^(www\\.)?github\\.com/", "")
                .replaceAll("/+$", "");

        String[] parts = value.split("/");
        if (parts.length < 2) return null;

        String candidate = parts[0] + "/" + parts[1].replaceFirst("\\.git$", "");
        return OWNER_REPO.matcher(candidate).matches() ? candidate : null;
    }

    private ResponseEntity<Map<String, Object>> error(int status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }
}