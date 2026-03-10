package com.bigbrightpaints.erp.modules.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

class SuperAdminTenantWorkflowIsolationIT extends AbstractIntegrationTest {

    private static final String TENANT = "SUPERADMIN-ISOLATION";
    private static final String SUPER_ADMIN_EMAIL = "workflow-superadmin@bbp.com";
    private static final String PASSWORD = "changeme";

    @Autowired
    private TestRestTemplate rest;

    @BeforeEach
    void setup() {
        dataSeeder.ensureUser(SUPER_ADMIN_EMAIL, PASSWORD, "Workflow Super Admin", TENANT,
                List.of("ROLE_SUPER_ADMIN"));
    }

    @Test
    void superAdmin_cannotExecuteTenantSalesTargetWorkflow() {
        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/sales/targets",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "name", "Blocked Target",
                        "periodStart", "2026-01-01",
                        "periodEnd", "2026-12-31",
                        "targetAmount", 125000,
                        "assignee", SUPER_ADMIN_EMAIL,
                        "changeReason", "super-admin-business-isolation"
                ), jsonHeaders()),
                Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void superAdmin_cannotReadTenantPortalDashboard() {
        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/portal/dashboard",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders()),
                Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void superAdmin_cannotApproveTenantCreditOverrideWorkflow() {
        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/credit/override-requests/999999/approve",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("reason", "platform-only-super-admin"), jsonHeaders()),
                Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("message")).isEqualTo("Access denied");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
        assertThat(data).isNotNull();
        assertThat(data.get("message")).isEqualTo("Access denied");
        assertThat(data.get("reason")).isEqualTo("SUPER_ADMIN_PLATFORM_ONLY");
        assertThat(data.get("reasonDetail"))
                .isEqualTo("Super Admin is limited to platform control-plane operations and cannot execute tenant business workflows");
    }

    @Test
    void superAdmin_cannotReadTenantFactoryDashboard() {
        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/factory/dashboard",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders()),
                Map.class
        );

        assertPlatformOnlyForbidden(response);
    }

    @Test
    void superAdmin_cannotCreateTenantHrEmployee() {
        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/hr/employees",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "employeeCode", "HR-BLOCKED-1",
                        "fullName", "Blocked Employee",
                        "email", "blocked.hr@example.com",
                        "department", "Operations",
                        "designation", "Operator",
                        "dateOfJoining", "2026-01-01"
                ), jsonHeaders()),
                Map.class
        );

        assertPlatformOnlyForbidden(response);
    }

    @Test
    void superAdmin_cannotApproveTenantPayrollWorkflow() {
        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/payroll/runs/999999/approve",
                HttpMethod.POST,
                new HttpEntity<>(jsonHeaders()),
                Map.class
        );

        assertPlatformOnlyForbidden(response);
    }

    @Test
    void superAdmin_cannotExecuteTenantInventoryWorkflows() {
        ResponseEntity<Map> adjustmentResponse = rest.exchange(
                "/api/v1/inventory/adjustments",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "adjustmentDate", "2026-01-01",
                        "type", "IN",
                        "adjustmentAccountId", 1,
                        "reason", "platform-only-super-admin",
                        "idempotencyKey", "super-admin-inventory-adjustment",
                        "lines", List.of()
                ), jsonHeaders()),
                Map.class
        );

        ResponseEntity<Map> openingStockResponse = rest.exchange(
                "/api/v1/inventory/opening-stock?page=0&size=5",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders()),
                Map.class
        );

        assertPlatformOnlyForbidden(adjustmentResponse);
        assertPlatformOnlyForbidden(openingStockResponse);
    }

    @Test
    void superAdmin_cannotAccessTenantFinishedGoodsAndCatalogWorkflows() {
        ResponseEntity<Map> finishedGoodsResponse = rest.exchange(
                "/api/v1/finished-goods",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders()),
                Map.class
        );

        ResponseEntity<Map> catalogResponse = rest.exchange(
                "/api/v1/catalog/products?page=0&pageSize=5",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders()),
                Map.class
        );

        ResponseEntity<Map> productionCatalogResponse = rest.exchange(
                "/api/v1/production/brands",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders()),
                Map.class
        );

        ResponseEntity<Map> accountingCatalogResponse = rest.exchange(
                "/api/v1/accounting/catalog/products",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders()),
                Map.class
        );

        assertPlatformOnlyForbidden(finishedGoodsResponse);
        assertPlatformOnlyForbidden(catalogResponse);
        assertPlatformOnlyForbidden(productionCatalogResponse);
        assertPlatformOnlyForbidden(accountingCatalogResponse);
    }

    @Test
    void superAdmin_cannotAccessTenantAdminApprovalsOrUserManagementWorkflows() {
        ResponseEntity<Map> approvalsResponse = rest.exchange(
                "/api/v1/admin/approvals",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders()),
                Map.class
        );

        ResponseEntity<Map> usersResponse = rest.exchange(
                "/api/v1/admin/users",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders()),
                Map.class
        );

        assertPlatformOnlyForbidden(approvalsResponse);
        assertPlatformOnlyForbidden(usersResponse);
    }

    @Test
    void superAdmin_cannotExecuteTenantAdminExportApprovalWorkflows() {
        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/admin/exports/999999/approve",
                HttpMethod.PUT,
                new HttpEntity<>(authHeaders()),
                Map.class
        );

        assertPlatformOnlyForbidden(response);
    }

    @Test
    void superAdmin_cannotExecuteTenantRawMaterialIntakeWorkflow() {
        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/raw-materials/intake",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "rawMaterialId", 999999,
                        "batchCode", "RM-SUPER-BLOCKED",
                        "quantity", 5,
                        "unit", "KG",
                        "costPerUnit", 12.50,
                        "supplierId", 999999,
                        "notes", "platform-only-super-admin"
                ), jsonHeaders()),
                Map.class
        );

        assertPlatformOnlyForbidden(response);
    }

    @Test
    void superAdmin_cannotExecuteTenantRawMaterialBatchWorkflow() {
        HttpHeaders headers = jsonHeaders();
        headers.set("Idempotency-Key", "super-admin-raw-material-batch");

        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/raw-material-batches/999999",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "batchCode", "RM-BATCH-SUPER-BLOCKED",
                        "quantity", 2,
                        "unit", "KG",
                        "costPerUnit", 11.75,
                        "supplierId", 999999,
                        "notes", "platform-only-super-admin"
                ), headers),
                Map.class
        );

        assertPlatformOnlyForbidden(response);
    }

    @Test
    void superAdmin_cannotExecuteTenantMigrationImportWorkflow() {
        ResponseEntity<Map> response = importTally(sampleTallyXml(), "super-admin-tally.xml", authHeaders());

        assertPlatformOnlyForbidden(response);
    }

    @Test
    void superAdmin_keepsPlatformAdminSettingsAccess() {
        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/admin/settings",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders()),
                Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("success")).isEqualTo(Boolean.TRUE);
    }

    private void assertPlatformOnlyForbidden(ResponseEntity<Map> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("message")).isEqualTo("Access denied");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
        assertThat(data).isNotNull();
        assertThat(data.get("message")).isEqualTo("Access denied");
        assertThat(data.get("reason")).isEqualTo("SUPER_ADMIN_PLATFORM_ONLY");
        assertThat(data.get("reasonDetail"))
                .isEqualTo("Super Admin is limited to platform control-plane operations and cannot execute tenant business workflows");
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = authHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private HttpHeaders authHeaders() {
        Map<String, Object> loginPayload = Map.of(
                "email", SUPER_ADMIN_EMAIL,
                "password", PASSWORD,
                "companyCode", TENANT
        );
        ResponseEntity<Map> login = rest.postForEntity("/api/v1/auth/login", loginPayload, Map.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(login.getBody()).isNotNull();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth((String) login.getBody().get("accessToken"));
        headers.set("X-Company-Code", TENANT);
        return headers;
    }

    private ResponseEntity<Map> importTally(String xml, String fileName, HttpHeaders headers) {
        org.springframework.util.MultiValueMap<String, Object> body = new org.springframework.util.LinkedMultiValueMap<>();
        HttpHeaders fileHeaders = new HttpHeaders();
        fileHeaders.setContentType(MediaType.parseMediaType("application/xml"));
        body.add("file", new HttpEntity<>(xmlResource(fileName, xml), fileHeaders));

        HttpHeaders requestHeaders = new HttpHeaders();
        requestHeaders.putAll(headers);
        requestHeaders.setContentType(MediaType.MULTIPART_FORM_DATA);

        return rest.exchange(
                "/api/v1/migration/tally-import",
                HttpMethod.POST,
                new HttpEntity<>(body, requestHeaders),
                Map.class);
    }

    private org.springframework.core.io.ByteArrayResource xmlResource(String fileName, String xml) {
        return new org.springframework.core.io.ByteArrayResource(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return fileName;
            }
        };
    }

    private String sampleTallyXml() {
        return """
                <ENVELOPE>
                  <BODY>
                    <DATA>
                      <TALLYMESSAGE>
                        <LEDGER NAME=\"Customer A\">
                          <PARENT>Sundry Debtors</PARENT>
                        </LEDGER>
                      </TALLYMESSAGE>
                      <TALLYMESSAGE>
                        <LEDGER NAME=\"Supplier B\">
                          <PARENT>Sundry Creditors</PARENT>
                        </LEDGER>
                      </TALLYMESSAGE>
                      <TALLYMESSAGE>
                        <VOUCHER VCHTYPE=\"Opening Balance\" VOUCHERTYPENAME=\"Opening Balance\">
                          <ALLLEDGERENTRIES.LIST>
                            <LEDGERNAME>Customer A</LEDGERNAME>
                            <AMOUNT>1200.00</AMOUNT>
                          </ALLLEDGERENTRIES.LIST>
                          <ALLLEDGERENTRIES.LIST>
                            <LEDGERNAME>Supplier B</LEDGERNAME>
                            <AMOUNT>-1200.00</AMOUNT>
                          </ALLLEDGERENTRIES.LIST>
                        </VOUCHER>
                      </TALLYMESSAGE>
                    </DATA>
                  </BODY>
                </ENVELOPE>
                """;
    }
}
