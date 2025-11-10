package com.bigbrightpaints.erp;

import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class OpenApiSnapshotIT extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    private static final String COMPANY = "ACME";
    private static final String EMAIL = "openapi@bbp.com";
    private static final String PASSWORD = "openapi";
    private HttpHeaders authHeaders;

    @BeforeEach
    void seedUser() {
        dataSeeder.ensureUser(EMAIL, PASSWORD, "OpenAPI Bot", COMPANY, java.util.List.of("ROLE_ADMIN"));
        String token = loginToken();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.add("X-Company-Id", COMPANY);
        authHeaders = headers;
    }

    @Test
    void export_openapi_specs_to_repository_root() throws IOException {
        ResponseEntity<String> json = rest.exchange("/v3/api-docs", HttpMethod.GET, new HttpEntity<>(authHeaders), String.class);
        assertThat(json.getStatusCode()).isEqualTo(HttpStatus.OK);
        Files.writeString(Path.of("openapi.json"), json.getBody(), StandardCharsets.UTF_8);

        ResponseEntity<String> yaml = rest.exchange("/v3/api-docs.yaml", HttpMethod.GET, new HttpEntity<>(authHeaders), String.class);
        assertThat(yaml.getStatusCode()).isEqualTo(HttpStatus.OK);
        Files.writeString(Path.of("openapi.yaml"), yaml.getBody(), StandardCharsets.UTF_8);
    }

    private String loginToken() {
        Map<String, Object> req = Map.of(
                "email", EMAIL,
                "password", PASSWORD,
                "companyCode", COMPANY
        );
        ResponseEntity<Map> login = rest.postForEntity("/api/v1/auth/login", req, Map.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (String) login.getBody().get("accessToken");
    }
}
