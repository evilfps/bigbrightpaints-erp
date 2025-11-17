package com.bigbrightpaints.erp.smoke;

import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke tests to verify application startup and basic functionality
 * These tests MUST pass before any deployment
 */
@DisplayName("Smoke Tests - Application Startup")
public class ApplicationSmokeTest extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("1. Application starts and all contexts load")
    void applicationContextLoads() {
        assertThat(applicationContext).isNotNull();
        assertThat(applicationContext.getBeanDefinitionCount()).isGreaterThan(0);
    }

    @Test
    @DisplayName("2. Database connection is successful")
    void databaseConnectionSuccessful() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection).isNotNull();
            assertThat(connection.isValid(5)).isTrue();
        }
    }

    @Test
    @DisplayName("3. Health endpoint returns UP")
    void healthEndpointReturnsUp() {
        ResponseEntity<Map> response = rest.getForEntity("/actuator/health", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsKey("status");
        assertThat(response.getBody().get("status")).isEqualTo("UP");
    }

    @Test
    @DisplayName("4. Login with valid credentials returns token")
    void loginWithValidCredentialsReturnsToken() {
        // Seed a test user
        String email = "smoke@test.com";
        String password = "smoke123";
        String companyCode = "SMOKE";

        dataSeeder.ensureUser(email, password, "Smoke Test User", companyCode, List.of("ROLE_DEALER"));

        // Attempt login
        Map<String, Object> loginRequest = Map.of(
                "email", email,
                "password", password,
                "companyCode", companyCode
        );

        ResponseEntity<Map> response = rest.postForEntity("/api/v1/auth/login", loginRequest, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsKey("accessToken");
        assertThat(response.getBody().get("accessToken")).isNotNull();
    }

    @Test
    @DisplayName("5. Swagger/OpenAPI documentation loads")
    void swaggerDocumentationLoads() {
        ResponseEntity<String> response = rest.getForEntity("/v3/api-docs", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("openapi");
        assertThat(response.getBody()).contains("paths");
    }
}
