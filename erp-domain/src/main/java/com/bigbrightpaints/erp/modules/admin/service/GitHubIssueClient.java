package com.bigbrightpaints.erp.modules.admin.service;

import com.bigbrightpaints.erp.core.config.GitHubProperties;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.CompanyTime;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

@Component
public class GitHubIssueClient {

    private static final Logger log = LoggerFactory.getLogger(GitHubIssueClient.class);
    private static final String GITHUB_API_BASE = "https://api.github.com";

    private final GitHubProperties gitHubProperties;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public GitHubIssueClient(GitHubProperties gitHubProperties,
                             RestTemplateBuilder restTemplateBuilder,
                             ObjectMapper objectMapper) {
        this.gitHubProperties = gitHubProperties;
        this.restTemplate = restTemplateBuilder.build();
        this.objectMapper = objectMapper;
    }

    public boolean isEnabledAndConfigured() {
        return gitHubProperties.isEnabled() && gitHubProperties.isConfigured();
    }

    public GitHubIssueCreateResult createIssue(String title, String body, List<String> labels) {
        ensureEnabled();
        String owner = gitHubProperties.getRepoOwner().trim();
        String repo = gitHubProperties.getRepoName().trim();
        String url = GITHUB_API_BASE + "/repos/" + owner + "/" + repo + "/issues";

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", title);
        payload.put("body", body);
        if (labels != null && !labels.isEmpty()) {
            payload.put("labels", labels);
        }

        HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(payload, defaultHeaders());
        try {
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, requestEntity, String.class);
            JsonNode root = requireBody(response.getBody(), "create_issue_response");
            long number = root.path("number").asLong(0L);
            if (number <= 0L) {
                throw new ApplicationException(ErrorCode.INTEGRATION_INVALID_RESPONSE,
                        "GitHub create issue response missing issue number");
            }
            String issueUrl = root.path("html_url").asText(null);
            String state = normalizeState(root.path("state").asText(null));
            return new GitHubIssueCreateResult(number, issueUrl, state, CompanyTime.now());
        } catch (HttpStatusCodeException ex) {
            throw mapHttpException("create GitHub issue", ex);
        } catch (RestClientException ex) {
            throw new ApplicationException(ErrorCode.INTEGRATION_CONNECTION_FAILED,
                    "Failed to create GitHub issue", ex);
        }
    }

    public GitHubIssueStateResult fetchIssueState(long issueNumber) {
        ensureEnabled();
        if (issueNumber <= 0L) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "issueNumber must be positive");
        }
        String owner = gitHubProperties.getRepoOwner().trim();
        String repo = gitHubProperties.getRepoName().trim();
        String url = GITHUB_API_BASE + "/repos/" + owner + "/" + repo + "/issues/" + issueNumber;

        HttpEntity<Void> requestEntity = new HttpEntity<>(defaultHeaders());
        try {
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, requestEntity, String.class);
            JsonNode root = requireBody(response.getBody(), "fetch_issue_response");
            String state = normalizeState(root.path("state").asText(null));
            String issueUrl = root.path("html_url").asText(null);
            return new GitHubIssueStateResult(issueNumber, issueUrl, state, CompanyTime.now());
        } catch (HttpStatusCodeException ex) {
            throw mapHttpException("fetch GitHub issue state", ex);
        } catch (RestClientException ex) {
            throw new ApplicationException(ErrorCode.INTEGRATION_CONNECTION_FAILED,
                    "Failed to fetch GitHub issue state", ex);
        }
    }

    private void ensureEnabled() {
        if (!isEnabledAndConfigured()) {
            throw new ApplicationException(ErrorCode.SYSTEM_CONFIGURATION_ERROR,
                    "GitHub support integration is not enabled/configured");
        }
    }

    private HttpHeaders defaultHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(MediaType.parseMediaTypes("application/vnd.github+json"));
        headers.setBearerAuth(gitHubProperties.getToken().trim());
        headers.set("X-GitHub-Api-Version", "2022-11-28");
        headers.set("User-Agent", "bigbright-erp-support-tickets");
        return headers;
    }

    private JsonNode requireBody(String responseBody, String operation) {
        if (!StringUtils.hasText(responseBody)) {
            throw new ApplicationException(ErrorCode.INTEGRATION_INVALID_RESPONSE,
                    "GitHub " + operation + " response body is empty");
        }
        try {
            return objectMapper.readTree(responseBody);
        } catch (Exception ex) {
            log.warn("Failed parsing GitHub response for {}", operation, ex);
            throw new ApplicationException(ErrorCode.INTEGRATION_INVALID_RESPONSE,
                    "GitHub " + operation + " response is not valid JSON", ex);
        }
    }

    private String normalizeState(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "UNKNOWN";
        }
        return raw.trim().toUpperCase();
    }

    private ApplicationException mapHttpException(String operation, HttpStatusCodeException ex) {
        int status = ex.getStatusCode().value();
        if (status == 401 || status == 403) {
            return new ApplicationException(ErrorCode.INTEGRATION_AUTHENTICATION_FAILED,
                    "GitHub authentication failed while attempting to " + operation,
                    ex);
        }
        if (status == 408 || status == 429 || status >= 500) {
            return new ApplicationException(ErrorCode.INTEGRATION_TIMEOUT,
                    "GitHub service unavailable while attempting to " + operation,
                    ex);
        }
        if (status >= 400) {
            return new ApplicationException(ErrorCode.INTEGRATION_INVALID_RESPONSE,
                    "GitHub rejected request while attempting to " + operation,
                    ex);
        }
        return new ApplicationException(ErrorCode.INTEGRATION_CONNECTION_FAILED,
                "Failed to " + operation,
                ex);
    }

    public record GitHubIssueCreateResult(
            long issueNumber,
            String issueUrl,
            String issueState,
            Instant syncedAt
    ) {
    }

    public record GitHubIssueStateResult(
            long issueNumber,
            String issueUrl,
            String issueState,
            Instant syncedAt
    ) {
    }
}
