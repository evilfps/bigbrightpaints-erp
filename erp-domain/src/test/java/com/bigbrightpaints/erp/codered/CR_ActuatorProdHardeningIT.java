package com.bigbrightpaints.erp.codered;

import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles(value = {"test", "prod"}, inheritProfiles = false)
@TestPropertySource(properties = {
        "jwt.secret=test-secret-should-be-at-least-32-bytes-long-1234",
        "spring.mail.password=test-smtp-password",
        "ERP_LICENSE_KEY=test-license-key",
        "ERP_DISPATCH_DEBIT_ACCOUNT_ID=1",
        "ERP_DISPATCH_CREDIT_ACCOUNT_ID=2",
        "management.endpoint.health.validate-group-membership=false",
        "management.server.port=0",
        "erp.environment.validation.enabled=false"
})
class CR_ActuatorProdHardeningIT extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private Environment environment;

    @LocalManagementPort
    private int managementPort;

    @Test
    @DisplayName("Prod actuator health is minimal")
    void prodActuatorHealthIsMinimal() {
        ResponseEntity<Map> response = rest.getForEntity(managementUrl("/actuator/health"), Map.class);

        assertThat(response.getStatusCode()).isIn(
                HttpStatus.OK,
                HttpStatus.SERVICE_UNAVAILABLE
        );
        assertThat(response.getBody()).containsKey("status");
        assertThat(response.getBody()).doesNotContainKey("components");
    }

    @Test
    @DisplayName("Prod does not expose metrics actuator endpoint")
    void prodActuatorMetricsNotExposed() {
        ResponseEntity<String> response = rest.getForEntity(managementUrl("/actuator/metrics"), String.class);

        assertThat(response.getStatusCode()).isIn(
                HttpStatus.NOT_FOUND,
                HttpStatus.UNAUTHORIZED,
                HttpStatus.FORBIDDEN
        );
    }

    @Test
    @DisplayName("Prod disables environment info in actuator info")
    void prodActuatorInfoEnvDisabled() {
        Boolean enabled = environment.getProperty("management.info.env.enabled", Boolean.class, false);
        assertThat(enabled).isFalse();
    }

    private String managementUrl(String path) {
        return "http://localhost:" + managementPort + path;
    }
}
