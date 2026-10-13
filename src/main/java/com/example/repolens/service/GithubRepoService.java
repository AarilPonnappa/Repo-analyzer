package com.example.repolens.service;

import java.util.Map;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class GithubRepoService {

    private final RestClient restClient = RestClient.builder()
            .baseUrl("https://api.github.com")
            .defaultHeader("Accept", "application/vnd.github+json")
            .build();

    // Works for public repos (token = null) and private repos (token = the user's PAT)
    public Map<String, Object> getRepoInfo(String ownerRepo, String token) {
        return restClient.get()
                .uri("/repos/" + ownerRepo)
                .headers(h -> {
                    if (token != null) {
                        h.setBearerAuth(token);
                    }
                })
                .retrieve()
                .body(new ParameterizedTypeReference<Map<String, Object>>() {});
    }
}