package com.bigbrightpaints.erp.codered;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

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

import com.bigbrightpaints.erp.test.AbstractIntegrationTest;

@ActiveProfiles(
    value = {"test", "prod"},
    inheritProfiles = false)
@TestPropertySource(
    properties = {
      "jwt.secret=xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
      "spring.mail.host=localhost",
      "spring.mail.port=2525",
      "spring.mail.username=test-smtp-user",
      "spring.mail.password=test-smtp-password",
      "ERP_LICENSE_KEY=test-license-key",
      "ERP_SECURITY_AUDIT_PRIVATE_KEY=test-audit-signing-key",
      "ERP_SECURITY_ENCRYPTION_KEY=12345678901234567890123456789012",
      "ERP_DISPATCH_DEBIT_ACCOUNT_ID=1",
      "ERP_DISPATCH_CREDIT_ACCOUNT_ID=2",
      "management.endpoint.health.validate-group-membership=false",
      "management.server.port=0",
      "erp.environment.validation.enabled=false",
      "management.endpoints.web.exposure.include=health,info,metrics,prometheus",
      "management.endpoint.metrics.access=read-only",
      "management.endpoint.prometheus.access=read-only"
    })
class CR_ActuatorProdHardeningIT extends AbstractIntegrationTest {

  @Autowired private TestRestTemplate rest;

  @Autowired private Environment environment;

  @LocalManagementPort private int managementPort;

  @Test
  @DisplayName("Prod actuator health is minimal")
  void prodActuatorHealthIsMinimal() {
    ResponseEntity<Map> response = rest.getForEntity(managementUrl("/actuator/health"), Map.class);

    assertThat(response.getStatusCode()).isIn(HttpStatus.OK, HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(response.getBody()).containsKey("status");
  }

  @Test
  @DisplayName("Prod exposes metrics and prometheus endpoints")
  void prodActuatorMetricsAndPrometheusAreExposed() {
    ResponseEntity<String> metricsResponse =
        rest.getForEntity(managementUrl("/actuator/metrics"), String.class);
    assertThat(metricsResponse.getStatusCode())
        .isIn(HttpStatus.OK, HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
    if (metricsResponse.getStatusCode() == HttpStatus.OK) {
      assertThat(metricsResponse.getBody()).contains("names");
    }

    ResponseEntity<String> prometheusResponse =
        rest.getForEntity(managementUrl("/actuator/prometheus"), String.class);
    assertThat(prometheusResponse.getStatusCode())
        .isIn(HttpStatus.OK, HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
    if (prometheusResponse.getStatusCode() == HttpStatus.OK) {
      assertThat(prometheusResponse.getBody()).contains("# HELP");
    }
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
