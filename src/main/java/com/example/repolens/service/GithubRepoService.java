package com.example.repolens.service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Service for fetching repository information from GitHub's API, with caching and ETag support.
 * We use Etags to avoid unnecessary requests to GitHub's API, which can help us stay within their rate limits and improve performance.
 * Using a ConcurrentHashMap for caching allows us to safely store and retrieve cached responses in a multi-threaded environment and allow multiple requests/users simultaneously.
 */
@Service
public class GithubRepoService {

    private static final int MAX_CACHED = 500;

    private final RestClient restClient;
    private final String serverToken;

    // An ETag (Entity Tag) is an identifier that a server sends with a RESPONSE so a client can check whether the data has changed since it last retrieved it
    // Its the server (say github) that generates the ETag and sends it in the response header when the client first sends a request. The entire response may look like this:
    /* 
    HTTP/1.1 200 OK
    ETag: "abc123"
    { response body }
    */
    // The client (say repolens) stores the ETag and sends it back in the request header for subsequent requests to check if the data has changed. If the data hasn't changed, the server responds with 304 Not Modified, which saves bandwidth and processing time.
    // So instead of requesting the entire response again, the client can just check if the ETag has changed. If it hasn't, the client can use the cached response instead of requesting the entire response again.
    private final Map<String, Cached> etagCache = new ConcurrentHashMap<>();        // concurrent hashmap is used to store the cached responses with their ETags. The key is the cache key (which includes the owner/repo and whether a user token was used), and the value is a Cached object that contains the ETag and the response body. We use a concurrent hashmap because multiple threads may access the cache at the same time, and we want to avoid race conditions and ensure thread safety.


    // This record creates a class that looks like this:
    // private static class Cached {
    //     private final String etag;
    //     private final Map<String, Object> body;
    //     public Cached(String etag, Map<String, Object> body) {
    //         this.etag = etag;
    //         this.body = body;
    //     }
    //     public String etag() { return etag; }
    //     public Map<String, Object> body() { return body; }
    // }
    private record Cached(String etag, Map<String, Object> body) {}

    // This constructor is called by Spring, which injects the values from application.properties or environment variables.
    // It is used to create a RestClient that talks to the GitHub API, with a timeout and default headers.
    // without it, the RestClient would be created with default settings, which may not work for GitHub's API.
    public GithubRepoService(@Value("${github.api-url:https://api.github.com}") String apiUrl,
                             @Value("${github.token:}") String serverToken) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(20));
        this.restClient = RestClient.builder()
                .baseUrl(apiUrl)
                .requestFactory(factory)
                .defaultHeader("Accept", "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .build();
        this.serverToken = serverToken == null || serverToken.isBlank() ? null : serverToken.trim();
    }

    // Works for public repos (token = null) and private repos (token = the user's PAT).
    // Throws HttpClientErrorException.NotFound / Unauthorized / Forbidden / TooManyRequests on errors.
    public Map<String, Object> getRepoInfo(String ownerRepo, String token) {
        String authToken = token != null ? token : serverToken;
        // The cache key includes whether a user token was used, so private data is never served to someone else
        String cacheKey = (token != null ? "user:" + Integer.toHexString(token.hashCode()) + ":" : "") + ownerRepo;     // cache saves each repository's ETag and response body using a cache key that depends on the repository and, when provided, the user's token
        Cached cached = etagCache.get(cacheKey);

        ResponseEntity<Map<String, Object>> response = restClient.get()
                .uri("/repos/" + ownerRepo)
                .headers(h -> {
                    if (authToken != null) {
                        h.setBearerAuth(authToken);
                    }
                    if (cached != null) {
                        h.setIfNoneMatch(cached.etag());        // This sets the If-None-Match header to the cached ETag, so that GitHub can respond with 304 Not Modified if the repo hasn't changed since the last request. This saves bandwidth and processing time, as we don't have to download the entire response again.
                    }
                })
                .retrieve()
                .toEntity(new ParameterizedTypeReference<Map<String, Object>>() {});        // The response body is a JSON object, which is deserialized into a Map<String, Object>. So for example, the github response {"name":"spring-petclinic","private":false} becomes a map with keys "name" and "private".

        if (response.getStatusCode().value() == HttpStatus.NOT_MODIFIED.value() && cached != null) {
            return cached.body();
        }
        Map<String, Object> body = response.getBody();
        if (body == null) {
            throw HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found", new HttpHeaders(), new byte[0], StandardCharsets.UTF_8);
        }
        String etag = response.getHeaders().getETag();
        if (etag != null) {
            if (etagCache.size() >= MAX_CACHED) {
                etagCache.clear();
            }
            etagCache.put(cacheKey, new Cached(etag, body));
        }
        return body;
    }

    // A readable message for 403/429 answers, which are usually GitHub's rate limit
    public static String rateLimitMessage(HttpClientErrorException e) {
        String remaining = e.getResponseHeaders() == null ? null : e.getResponseHeaders().getFirst("X-RateLimit-Remaining");
        String reset = e.getResponseHeaders() == null ? null : e.getResponseHeaders().getFirst("X-RateLimit-Reset");
        if ("0".equals(remaining) && reset != null) {
            try {
                long minutes = Math.max(1, (Long.parseLong(reset) - System.currentTimeMillis() / 1000 + 59) / 60);
                if (minutes <= 120) {
                    return "GitHub's API limit was reached. Try again in " + minutes + " minute" + (minutes == 1 ? "" : "s") + ".";
                }
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        if (e.getStatusCode().value() == 429 || "0".equals(remaining)) {
            return "GitHub's API limit was reached. Try again later.";
        }
        return "GitHub refused the request. If the repo is private, check that the token can read it.";
    }
}
