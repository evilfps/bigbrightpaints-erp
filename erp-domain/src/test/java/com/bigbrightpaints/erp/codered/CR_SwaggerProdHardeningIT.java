package com.bigbrightpaints.erp.codered;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
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
      "jwt.secret=placeholder-placeholder-placeholder-000000",
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
      "erp.environment.validation.enabled=false"
    })
class CR_SwaggerProdHardeningIT extends AbstractIntegrationTest {

  @Autowired private TestRestTemplate rest;

  @Test
  @DisplayName("Prod blocks Swagger UI and OpenAPI docs")
  void prodBlocksSwaggerAndOpenApiDocs() {
    ResponseEntity<String> apiDocs = rest.getForEntity("/v3/api-docs", String.class);
    assertThat(apiDocs.getStatusCode())
        .isIn(HttpStatus.NOT_FOUND, HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);

    ResponseEntity<String> swaggerUi = rest.getForEntity("/swagger-ui/index.html", String.class);
    assertThat(swaggerUi.getStatusCode())
        .isIn(HttpStatus.NOT_FOUND, HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
  }
}
