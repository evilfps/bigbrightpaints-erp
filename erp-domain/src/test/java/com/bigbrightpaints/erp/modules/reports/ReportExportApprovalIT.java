package com.bigbrightpaints.erp.modules.reports;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import com.bigbrightpaints.erp.modules.admin.domain.ExportRequest;
import com.bigbrightpaints.erp.modules.admin.domain.ExportRequestRepository;
import com.bigbrightpaints.erp.modules.admin.dto.ExportApprovalStatus;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;

@Tag("critical")
class ReportExportApprovalIT extends AbstractIntegrationTest {

  private static final String COMPANY_CODE = "EXPORT-APPROVAL";
  private static final String ADMIN_EMAIL = "export-admin@bbp.com";
  private static final String SUPER_ADMIN_EMAIL = "export-super-admin@bbp.com";
  private static final String ACCOUNTING_EMAIL = "export-accounting@bbp.com";
  private static final String ACCOUNTING_PEER_EMAIL = "export-accounting-peer@bbp.com";
  private static final String PASSWORD = "Export123!";

  @Autowired private TestRestTemplate rest;

  @Autowired private CompanyRepository companyRepository;

  @Autowired private ExportRequestRepository exportRequestRepository;

  private Company company;
  private UserAccount accountingUser;

  @BeforeEach
  void setupUsers() {
    dataSeeder.ensureUser(
        ADMIN_EMAIL, PASSWORD, "Export Admin", COMPANY_CODE, List.of("ROLE_ADMIN"));
    dataSeeder.ensureUser(
        SUPER_ADMIN_EMAIL,
        PASSWORD,
        "Export Super Admin",
        COMPANY_CODE,
        List.of("ROLE_SUPER_ADMIN"));
    accountingUser =
        dataSeeder.ensureUser(
            ACCOUNTING_EMAIL,
            PASSWORD,
            "Export Accounting",
            COMPANY_CODE,
            List.of("ROLE_ACCOUNTING"));
    dataSeeder.ensureUser(
        ACCOUNTING_PEER_EMAIL,
        PASSWORD,
        "Export Accounting Peer",
        COMPANY_CODE,
        List.of("ROLE_ACCOUNTING"));
    company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
  }

  @Test
  void exportRequest_and_adminApproval_flow_enforcesDownloadGate() {
    HttpHeaders superAdminHeaders = authHeaders(SUPER_ADMIN_EMAIL);
    HttpHeaders adminHeaders = authHeaders(ADMIN_EMAIL);
    ResponseEntity<Map> enableGate =
        rest.exchange(
            "/api/v1/admin/settings",
            HttpMethod.PUT,
            new HttpEntity<>(
                Map.of("exportApprovalRequired", true), jsonHeaders(superAdminHeaders)),
            Map.class);
    assertThat(enableGate.getStatusCode()).isEqualTo(HttpStatus.OK);

    HttpHeaders accountingHeaders = authHeaders(ACCOUNTING_EMAIL);

    ResponseEntity<Map> createResponse =
        rest.exchange(
            "/api/v1/exports/request",
            HttpMethod.POST,
            new HttpEntity<>(
                Map.of(
                    "reportType", "trial-balance",
                    "parameters", "periodId=10"),
                jsonHeaders(accountingHeaders)),
            Map.class);

    assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<?, ?> createBody = createResponse.getBody();
    assertThat(createBody).isNotNull();
    Map<?, ?> createData = (Map<?, ?>) createBody.get("data");
    assertThat(createData).isNotNull();
    Number requestId = (Number) createData.get("id");
    assertThat(requestId).isNotNull();

    ResponseEntity<Map> pendingResponse =
        rest.exchange(
            "/api/v1/admin/approvals", HttpMethod.GET, new HttpEntity<>(adminHeaders), Map.class);
    assertThat(pendingResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(pendingResponse.getBody()).isNotNull();
    Map<?, ?> pendingData = (Map<?, ?>) pendingResponse.getBody().get("data");
    assertThat(pendingData).isNotNull();
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> exportRequests =
        (List<Map<String, Object>>) pendingData.get("exportRequests");
    assertThat(exportRequests)
        .anySatisfy(
            row -> {
              assertThat(row.get("originType")).isEqualTo("EXPORT_REQUEST");
              assertThat(row.get("reportType")).isEqualTo("TRIAL-BALANCE");
              assertThat(row.get("parameters")).isEqualTo("periodId=10");
              assertThat(row.get("requesterEmail")).isEqualTo(ACCOUNTING_EMAIL);
              assertThat(String.valueOf(row.get("reference"))).startsWith("EXP-");
            });

    ResponseEntity<Map> accountingInboxResponse =
        rest.exchange(
            "/api/v1/admin/approvals",
            HttpMethod.GET,
            new HttpEntity<>(accountingHeaders),
            Map.class);
    assertThat(accountingInboxResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(accountingInboxResponse.getBody()).isNotNull();
    Map<?, ?> accountingInboxData = (Map<?, ?>) accountingInboxResponse.getBody().get("data");
    assertThat(accountingInboxData).isNotNull();
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> accountingExportRequests =
        (List<Map<String, Object>>) accountingInboxData.get("exportRequests");
    assertThat(accountingExportRequests)
        .anySatisfy(
            row -> {
              assertThat(row.get("originType")).isEqualTo("EXPORT_REQUEST");
              assertThat(row.get("reportType")).isEqualTo("TRIAL-BALANCE");
              assertThat(String.valueOf(row.get("summary"))).contains("report TRIAL-BALANCE");
              assertThat(String.valueOf(row.get("summary"))).doesNotContain(ACCOUNTING_EMAIL);
              assertThat(row.containsKey("parameters")).isFalse();
              assertThat(row.containsKey("requesterEmail")).isFalse();
              assertThat(row.containsKey("requesterUserId")).isFalse();
            });

    ResponseEntity<Map> downloadBeforeApproval =
        rest.exchange(
            "/api/v1/exports/" + requestId.longValue() + "/download",
            HttpMethod.GET,
            new HttpEntity<>(accountingHeaders),
            Map.class);
    assertThat(downloadBeforeApproval.getStatusCode().is4xxClientError()).isTrue();

    ResponseEntity<Map> approveResponse =
        rest.exchange(
            "/api/v1/admin/exports/" + requestId.longValue() + "/approve",
            HttpMethod.PUT,
            new HttpEntity<>(adminHeaders),
            Map.class);
    assertThat(approveResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

    ResponseEntity<Map> adminDownloadAfterApproval =
        rest.exchange(
            "/api/v1/exports/" + requestId.longValue() + "/download",
            HttpMethod.GET,
            new HttpEntity<>(adminHeaders),
            Map.class);
    assertThat(adminDownloadAfterApproval.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

    ResponseEntity<Map> downloadAfterApproval =
        rest.exchange(
            "/api/v1/exports/" + requestId.longValue() + "/download",
            HttpMethod.GET,
            new HttpEntity<>(accountingHeaders),
            Map.class);
    assertThat(downloadAfterApproval.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(downloadAfterApproval.getBody()).isNotNull();
    @SuppressWarnings("unchecked")
    Map<String, Object> downloadData =
        (Map<String, Object>) downloadAfterApproval.getBody().get("data");
    assertThat(downloadData).isNotNull();
    assertThat(downloadData)
        .containsEntry("requestId", requestId.intValue())
        .containsEntry("status", "APPROVED")
        .containsEntry("reportType", "TRIAL-BALANCE")
        .containsEntry("parameters", "periodId=10")
        .containsEntry("message", "Export request approved for download");
    assertThat(downloadData).doesNotContainKeys("downloadUrl", "fileName");
  }

  @Test
  void superAdmin_is_blocked_from_tenant_export_request_surface() {
    HttpHeaders superAdminHeaders = authHeaders(SUPER_ADMIN_EMAIL);

    ResponseEntity<Map> createResponse =
        rest.exchange(
            "/api/v1/exports/request",
            HttpMethod.POST,
            new HttpEntity<>(
                Map.of(
                    "reportType", "trial-balance",
                    "parameters", "periodId=10"),
                jsonHeaders(superAdminHeaders)),
            Map.class);

    assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  void superAdmin_is_blocked_from_tenant_export_approval_surfaces() {
    ExportRequest request = new ExportRequest();
    request.setCompany(company);
    request.setUserId(accountingUser.getId());
    request.setReportType("TRIAL-BALANCE");
    request.setParameters("periodId=6");
    request.setStatus(ExportApprovalStatus.PENDING);
    request = exportRequestRepository.save(request);

    HttpHeaders superAdminHeaders = authHeaders(SUPER_ADMIN_EMAIL);

    ResponseEntity<Map> approvalsResponse =
        rest.exchange(
            "/api/v1/admin/approvals",
            HttpMethod.GET,
            new HttpEntity<>(superAdminHeaders),
            Map.class);
    assertThat(approvalsResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

    ResponseEntity<Map> approveResponse =
        rest.exchange(
            "/api/v1/admin/exports/" + request.getId() + "/approve",
            HttpMethod.PUT,
            new HttpEntity<>(superAdminHeaders),
            Map.class);
    assertThat(approveResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

    ResponseEntity<Map> rejectResponse =
        rest.exchange(
            "/api/v1/admin/exports/" + request.getId() + "/reject",
            HttpMethod.PUT,
            new HttpEntity<>(Map.of("reason", "forbidden"), jsonHeaders(superAdminHeaders)),
            Map.class);
    assertThat(rejectResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  void exportDownload_bypassesApprovalWhenSettingDisabled() {
    HttpHeaders superAdminHeaders = authHeaders(SUPER_ADMIN_EMAIL);
    ResponseEntity<Map> settingResponse =
        rest.exchange(
            "/api/v1/admin/settings",
            HttpMethod.PUT,
            new HttpEntity<>(
                Map.of("exportApprovalRequired", false), jsonHeaders(superAdminHeaders)),
            Map.class);
    assertThat(settingResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

    ExportRequest request = new ExportRequest();
    request.setCompany(company);
    request.setUserId(accountingUser.getId());
    request.setReportType("TRIAL-BALANCE");
    request.setParameters("periodId=2");
    request.setStatus(ExportApprovalStatus.REJECTED);
    request = exportRequestRepository.save(request);

    HttpHeaders accountingHeaders = authHeaders(ACCOUNTING_EMAIL);
    ResponseEntity<Map> download =
        rest.exchange(
            "/api/v1/exports/" + request.getId() + "/download",
            HttpMethod.GET,
            new HttpEntity<>(accountingHeaders),
            Map.class);

    assertThat(download.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(download.getBody()).isNotNull();
    @SuppressWarnings("unchecked")
    Map<String, Object> data = (Map<String, Object>) download.getBody().get("data");
    assertThat(data).isNotNull();
    assertThat(data)
        .containsEntry("requestId", request.getId().intValue())
        .containsEntry("status", "REJECTED")
        .containsEntry("reportType", "TRIAL-BALANCE")
        .containsEntry("parameters", "periodId=2");
    assertThat(String.valueOf(data.get("message"))).contains("disabled");
    assertThat(data).doesNotContainKeys("downloadUrl", "fileName");
  }

  @Test
  void exportDownload_requiresRequestOwnershipAfterApproval() {
    HttpHeaders superAdminHeaders = authHeaders(SUPER_ADMIN_EMAIL);
    ResponseEntity<Map> enableGate =
        rest.exchange(
            "/api/v1/admin/settings",
            HttpMethod.PUT,
            new HttpEntity<>(
                Map.of("exportApprovalRequired", true), jsonHeaders(superAdminHeaders)),
            Map.class);
    assertThat(enableGate.getStatusCode()).isEqualTo(HttpStatus.OK);

    HttpHeaders accountingHeaders = authHeaders(ACCOUNTING_EMAIL);
    ResponseEntity<Map> createResponse =
        rest.exchange(
            "/api/v1/exports/request",
            HttpMethod.POST,
            new HttpEntity<>(
                Map.of(
                    "reportType", "trial-balance",
                    "parameters", "periodId=11"),
                jsonHeaders(accountingHeaders)),
            Map.class);
    assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    Number requestId = (Number) ((Map<?, ?>) createResponse.getBody().get("data")).get("id");
    assertThat(requestId).isNotNull();

    HttpHeaders adminHeaders = authHeaders(ADMIN_EMAIL);
    ResponseEntity<Map> approveResponse =
        rest.exchange(
            "/api/v1/admin/exports/" + requestId.longValue() + "/approve",
            HttpMethod.PUT,
            new HttpEntity<>(adminHeaders),
            Map.class);
    assertThat(approveResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

    HttpHeaders peerHeaders = authHeaders(ACCOUNTING_PEER_EMAIL);
    ResponseEntity<Map> peerDownload =
        rest.exchange(
            "/api/v1/exports/" + requestId.longValue() + "/download",
            HttpMethod.GET,
            new HttpEntity<>(peerHeaders),
            Map.class);
    assertThat(peerDownload.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void adminApprovals_includesPendingExportRequests() {
    ExportRequest request = new ExportRequest();
    request.setCompany(company);
    request.setUserId(accountingUser.getId());
    request.setReportType("AGED-DEBTORS");
    request.setStatus(ExportApprovalStatus.PENDING);
    request.setParameters("periodId=5");
    request = exportRequestRepository.save(request);

    HttpHeaders adminHeaders = authHeaders(ADMIN_EMAIL);
    ResponseEntity<Map> approvals =
        rest.exchange(
            "/api/v1/admin/approvals", HttpMethod.GET, new HttpEntity<>(adminHeaders), Map.class);

    assertThat(approvals.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(approvals.getBody()).isNotNull();
    Map<?, ?> data = (Map<?, ?>) approvals.getBody().get("data");
    assertThat(data).isNotNull();
    Object exportRequests = data.get("exportRequests");
    assertThat(exportRequests).isInstanceOf(List.class);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rows = (List<Map<String, Object>>) exportRequests;
    assertThat(rows)
        .anySatisfy(
            row -> {
              assertThat(row.get("originType")).isEqualTo("EXPORT_REQUEST");
              assertThat(row.get("ownerType")).isEqualTo("REPORTS");
              assertThat(row.get("reportType")).isEqualTo("AGED-DEBTORS");
              assertThat(row.get("parameters")).isEqualTo("periodId=5");
              assertThat(row.get("requesterEmail")).isEqualTo(ACCOUNTING_EMAIL);
              assertThat(String.valueOf(row.get("reference"))).startsWith("EXP-");
            });
  }

  @Test
  void retired_export_pending_alias_is_not_exposed() {
    HttpHeaders adminHeaders = authHeaders(ADMIN_EMAIL);

    ResponseEntity<Map> response =
        rest.exchange(
            "/api/v1/admin/exports/pending",
            HttpMethod.GET,
            new HttpEntity<>(adminHeaders),
            Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
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

  private HttpHeaders jsonHeaders(HttpHeaders baseHeaders) {
    HttpHeaders headers = new HttpHeaders();
    headers.putAll(baseHeaders);
    headers.setContentType(MediaType.APPLICATION_JSON);
    return headers;
  }
}
