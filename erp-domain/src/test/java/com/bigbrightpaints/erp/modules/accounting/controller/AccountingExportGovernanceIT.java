package com.bigbrightpaints.erp.modules.accounting.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;

class AccountingExportGovernanceIT extends AbstractIntegrationTest {

  private static final String COMPANY_CODE = "ACC-EXPORT-GOV";
  private static final String ADMIN_EMAIL = "acc-export-admin@bbp.com";
  private static final String SUPER_ADMIN_EMAIL = "acc-export-super-admin@bbp.com";
  private static final String ACCOUNTING_EMAIL = "acc-export-accounting@bbp.com";
  private static final String PASSWORD = "ExportGov123!";

  @Autowired private TestRestTemplate rest;

  @Autowired private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void setupUsers() {
    dataSeeder.ensureUser(
        ADMIN_EMAIL, PASSWORD, "Export Governance Admin", COMPANY_CODE, List.of("ROLE_ADMIN"));
    dataSeeder.ensureUser(
        SUPER_ADMIN_EMAIL,
        PASSWORD,
        "Export Governance Super Admin",
        COMPANY_CODE,
        List.of("ROLE_SUPER_ADMIN"));
    dataSeeder.ensureUser(
        ACCOUNTING_EMAIL,
        PASSWORD,
        "Export Governance Accounting",
        COMPANY_CODE,
        List.of("ROLE_ACCOUNTING"));
  }

  @Test
  void exportEndpoints_areFailClosedToAdminRoleOnly() throws Exception {
    assertMethodIsAdminOnly("supplierStatementPdf", Long.class, String.class, String.class);
    assertMethodIsAdminOnly("supplierAgingPdf", Long.class, String.class, String.class);
  }

  @Test
  void supplierStatementPdf_requiresAdminAndLogsExport() throws Exception {
    Long supplierId = createSupplier(authHeaders(ADMIN_EMAIL));
    HttpHeaders accountingHeaders = authHeaders(ACCOUNTING_EMAIL);
    ResponseEntity<byte[]> accountingResponse =
        rest.exchange(
            "/api/v1/accounting/statements/suppliers/"
                + supplierId
                + "/pdf?from=2026-01-01&to=2026-01-31",
            HttpMethod.GET,
            new HttpEntity<>(accountingHeaders),
            byte[].class);
    assertThat(accountingResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

    HttpHeaders adminHeaders = authHeaders(ADMIN_EMAIL);
    ResponseEntity<byte[]> adminResponse =
        rest.exchange(
            "/api/v1/accounting/statements/suppliers/"
                + supplierId
                + "/pdf?from=2026-01-01&to=2026-01-31",
            HttpMethod.GET,
            new HttpEntity<>(adminHeaders),
            byte[].class);
    assertThat(adminResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(adminResponse.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
        .contains("supplier-statement.pdf");

    assertExportAuditMetadata(ADMIN_EMAIL, "ACCOUNTING_SUPPLIER_STATEMENT", "EXPORT", "pdf");
  }

  @Test
  void supplierStatementPdf_blocksSuperAdminFromTenantAccountingExport() {
    Long supplierId = createSupplier(authHeaders(ADMIN_EMAIL));
    HttpHeaders superAdminHeaders = authHeaders(SUPER_ADMIN_EMAIL);

    ResponseEntity<byte[]> response =
        rest.exchange(
            "/api/v1/accounting/statements/suppliers/"
                + supplierId
                + "/pdf?from=2026-01-01&to=2026-01-31",
            HttpMethod.GET,
            new HttpEntity<>(superAdminHeaders),
            byte[].class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }

  private void assertMethodIsAdminOnly(String methodName, Class<?>... parameterTypes)
      throws Exception {
    Method method = StatementReportController.class.getMethod(methodName, parameterTypes);
    PreAuthorize preAuthorize = method.getAnnotation(PreAuthorize.class);
    assertThat(preAuthorize).isNotNull();
    assertThat(preAuthorize.value()).isEqualTo("hasAuthority('ROLE_ADMIN')");
  }

  private HttpHeaders authHeaders(String email) {
    Map<String, Object> payload =
        Map.of(
            "email", email,
            "password", PASSWORD,
            "companyCode", COMPANY_CODE);
    ResponseEntity<Map> login = rest.postForEntity("/api/v1/auth/login", payload, Map.class);
    assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(login.getBody()).isNotNull();

    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth((String) login.getBody().get("accessToken"));
    headers.set("X-Company-Code", COMPANY_CODE);
    return headers;
  }

  @SuppressWarnings("unchecked")
  private Long createSupplier(HttpHeaders headers) {
    Map<String, Object> request =
        Map.of(
            "name", "Export Governance Supplier",
            "code", "SUP-" + System.nanoTime(),
            "contactEmail", "acc-export-supplier@bbp.com",
            "stateCode", "27",
            "creditLimit", new BigDecimal("10000.00"));

    ResponseEntity<Map> createResponse =
        rest.exchange(
            "/api/v1/suppliers", HttpMethod.POST, new HttpEntity<>(request, headers), Map.class);
    assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    Map<String, Object> createBody = createResponse.getBody();
    assertThat(createBody).isNotNull();
    Long supplierId =
        ((Number) ((Map<String, Object>) createBody.get("data")).get("id")).longValue();

    ResponseEntity<Map> approveResponse =
        rest.exchange(
            "/api/v1/suppliers/" + supplierId + "/approve",
            HttpMethod.POST,
            new HttpEntity<>(headers),
            Map.class);
    assertThat(approveResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

    ResponseEntity<Map> activateResponse =
        rest.exchange(
            "/api/v1/suppliers/" + supplierId + "/activate",
            HttpMethod.POST,
            new HttpEntity<>(headers),
            Map.class);
    assertThat(activateResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    return supplierId;
  }

  private void assertExportAuditMetadata(
      String username, String resourceType, String operation, String format)
      throws InterruptedException {
    for (int attempt = 0; attempt < 30; attempt++) {
      Integer matchCount =
          jdbcTemplate.queryForObject(
              """
              SELECT COUNT(*)
              FROM audit_logs al
              JOIN audit_log_metadata resource_type
                ON resource_type.audit_log_id = al.id
               AND resource_type.metadata_key = 'resourceType'
               AND resource_type.metadata_value = ?
              JOIN audit_log_metadata operation_type
                ON operation_type.audit_log_id = al.id
               AND operation_type.metadata_key = 'operation'
               AND operation_type.metadata_value = ?
              JOIN audit_log_metadata export_format
                ON export_format.audit_log_id = al.id
               AND export_format.metadata_key = 'format'
               AND lower(export_format.metadata_value) = lower(?)
              WHERE al.event_type = ?
                AND lower(al.username) = lower(?)
              """,
              Integer.class,
              resourceType,
              operation,
              format,
              AuditEvent.DATA_EXPORT.name(),
              username);
      if (matchCount != null && matchCount > 0) {
        return;
      }
      Thread.sleep(100);
    }
    fail("Expected DATA_EXPORT audit log metadata for " + resourceType + " by " + username);
  }
}
