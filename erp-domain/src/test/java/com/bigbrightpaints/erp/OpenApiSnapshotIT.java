package com.bigbrightpaints.erp;

import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@TestPropertySource(properties = "erp.security.swagger-public=true")
public class OpenApiSnapshotIT extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    @Test
    void export_openapi_specs_to_repository_root() throws IOException {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(30_000);
        requestFactory.setReadTimeout(120_000);
        rest.getRestTemplate().setRequestFactory(requestFactory);

        ResponseEntity<String> json = rest.getForEntity("/v3/api-docs", String.class);
        assertThat(json.getStatusCode()).isEqualTo(HttpStatus.OK);
        Path moduleRoot = Path.of("").toAbsolutePath().normalize();
        Path repoRoot = moduleRoot;
        if (moduleRoot.getFileName() != null && "erp-domain".equals(moduleRoot.getFileName().toString())) {
            repoRoot = moduleRoot.getParent();
        }
        if (repoRoot == null) {
            repoRoot = moduleRoot;
        }
        Files.writeString(repoRoot.resolve("openapi.json"), json.getBody(), StandardCharsets.UTF_8);
    }
}
