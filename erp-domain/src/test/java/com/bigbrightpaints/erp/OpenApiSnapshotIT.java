package com.bigbrightpaints.erp;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.context.TestPropertySource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.bigbrightpaints.erp.test.AbstractIntegrationTest;

@TestPropertySource(properties = "erp.security.swagger-public=true")
public class OpenApiSnapshotIT extends AbstractIntegrationTest {

  private static final String SNAPSHOT_VERIFY_PROPERTY = "erp.openapi.snapshot.verify";
  private static final String SNAPSHOT_VERIFY_ENV = "ERP_OPENAPI_SNAPSHOT_VERIFY";
  private static final String SNAPSHOT_REFRESH_PROPERTY = "erp.openapi.snapshot.refresh";
  private static final String SNAPSHOT_REFRESH_ENV = "ERP_OPENAPI_SNAPSHOT_REFRESH";
  private static final ObjectMapper CANONICAL_JSON =
      new ObjectMapper()
          .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
          .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

  @Autowired private TestRestTemplate rest;

  @Test
  void auth_and_admin_contract_paths_preserve_expected_response_shapes() throws IOException {
    JsonNode root = fetchCurrentSpecNode();

    assertOperationContract(
        root,
        "/api/v1/auth/login",
        "post",
        "#/components/schemas/LoginRequest",
        "200",
        "#/components/schemas/AuthResponse");
    assertOperationContract(
        root,
        "/api/v1/auth/refresh-token",
        "post",
        "#/components/schemas/RefreshTokenRequest",
        "200",
        "#/components/schemas/AuthResponse");
    assertOperationContract(root, "/api/v1/auth/logout", "post", null, "204", null);
    assertOperationContract(
        root, "/api/v1/auth/me", "get", null, "200", "#/components/schemas/ApiResponseMeResponse");
    assertOperationContract(
        root,
        "/api/v1/auth/password/change",
        "post",
        "#/components/schemas/ChangePasswordRequest",
        "200",
        "#/components/schemas/ApiResponseString");
    assertOperationContract(
        root,
        "/api/v1/auth/password/forgot",
        "post",
        "#/components/schemas/ForgotPasswordRequest",
        "200",
        "#/components/schemas/ApiResponseString");
    assertOperationMissing(root, "/api/v1/auth/password/forgot/superadmin", "post");
    assertOperationContract(
        root,
        "/api/v1/auth/password/reset",
        "post",
        "#/components/schemas/ResetPasswordRequest",
        "200",
        "#/components/schemas/ApiResponseString");
    assertOperationMissing(root, "/api/v1/auth/profile", "get");
    assertOperationMissing(root, "/api/v1/auth/profile", "put");
    assertThat(root.path("components").path("schemas").has("ProfileResponse")).isFalse();
    assertThat(root.path("components").path("schemas").has("UpdateProfileRequest")).isFalse();
    assertThat(root.path("components").path("schemas").has("ApiResponseProfileResponse")).isFalse();

    assertOperationContract(
        root,
        "/api/v1/admin/settings",
        "get",
        null,
        "200",
        "#/components/schemas/ApiResponseSystemSettingsDto");
    assertOperationContract(
        root,
        "/api/v1/admin/settings",
        "put",
        "#/components/schemas/SystemSettingsUpdateRequest",
        "200",
        "#/components/schemas/ApiResponseSystemSettingsDto");
    assertOperationContract(
        root,
        "/api/v1/admin/approvals",
        "get",
        null,
        "200",
        "#/components/schemas/ApiResponseAdminApprovalsResponse");
    assertOperationContract(
        root,
        "/api/v1/admin/exports/{requestId}/approve",
        "put",
        null,
        "200",
        "#/components/schemas/ApiResponseExportRequestDto");
    assertOperationContract(
        root,
        "/api/v1/admin/exports/{requestId}/reject",
        "put",
        "#/components/schemas/ExportRequestDecisionRequest",
        "200",
        "#/components/schemas/ApiResponseExportRequestDto");
    assertOperationMissing(root, "/api/v1/admin/exports/pending", "get");
    assertOperationContract(
        root,
        "/api/v1/superadmin/tenants/onboard",
        "post",
        "#/components/schemas/TenantOnboardingRequest",
        "200",
        "#/components/schemas/ApiResponseTenantOnboardingResponse");
    assertOperationContract(
        root,
        "/api/v1/superadmin/dashboard",
        "get",
        null,
        "200",
        "#/components/schemas/ApiResponseCompanySuperAdminDashboardDto");
    assertOperationContract(
        root,
        "/api/v1/superadmin/tenants",
        "get",
        null,
        "200",
        "#/components/schemas/ApiResponseListSuperAdminTenantSummaryDto");
    assertQueryParameter(root, "/api/v1/superadmin/tenants", "get", "status");
    assertOperationContract(
        root,
        "/api/v1/superadmin/tenants/{id}",
        "get",
        null,
        "200",
        "#/components/schemas/ApiResponseSuperAdminTenantDetailDto");
    assertOperationContract(
        root,
        "/api/v1/superadmin/tenants/{id}/lifecycle",
        "put",
        "#/components/schemas/CompanyLifecycleStateRequest",
        "200",
        "#/components/schemas/ApiResponseCompanyLifecycleStateDto");
    assertOperationContract(
        root,
        "/api/v1/superadmin/tenants/{id}/limits",
        "put",
        "#/components/schemas/TenantLimitsUpdateRequest",
        "200",
        "#/components/schemas/ApiResponseSuperAdminTenantLimitsDto");
    assertOperationContract(
        root,
        "/api/v1/superadmin/tenants/{id}/modules",
        "put",
        "#/components/schemas/TenantModulesUpdateRequest",
        "200",
        "#/components/schemas/ApiResponseCompanyEnabledModulesDto");
    assertOperationContract(
        root,
        "/api/v1/superadmin/tenants/{id}/support/warnings",
        "post",
        "#/components/schemas/TenantSupportWarningRequest",
        "200",
        "#/components/schemas/ApiResponseCompanySupportWarningDto");
    assertOperationContract(
        root,
        "/api/v1/superadmin/tenants/{id}/support/admin-password-reset",
        "post",
        "#/components/schemas/TenantAdminPasswordResetRequest",
        "200",
        "#/components/schemas/ApiResponseCompanyAdminCredentialResetDto");
    assertOperationContract(
        root,
        "/api/v1/superadmin/tenants/{id}/support/context",
        "put",
        "#/components/schemas/TenantSupportContextUpdateRequest",
        "200",
        "#/components/schemas/ApiResponseSuperAdminTenantSupportContextDto");
    assertOperationContract(
        root,
        "/api/v1/superadmin/tenants/{id}/force-logout",
        "post",
        "#/components/schemas/TenantForceLogoutRequest",
        "200",
        "#/components/schemas/ApiResponseSuperAdminTenantForceLogoutDto");
    assertOperationContract(
        root,
        "/api/v1/superadmin/tenants/{id}/admins/main",
        "put",
        "#/components/schemas/TenantMainAdminUpdateRequest",
        "200",
        "#/components/schemas/ApiResponseMainAdminSummaryDto");
    assertOperationContract(
        root,
        "/api/v1/superadmin/tenants/{id}/admins/{adminId}/email-change/request",
        "post",
        "#/components/schemas/TenantAdminEmailChangeRequest",
        "200",
        "#/components/schemas/ApiResponseSuperAdminTenantAdminEmailChangeRequestDto");
    assertOperationContract(
        root,
        "/api/v1/superadmin/tenants/{id}/admins/{adminId}/email-change/confirm",
        "post",
        "#/components/schemas/TenantAdminEmailChangeConfirmRequest",
        "200",
        "#/components/schemas/ApiResponseSuperAdminTenantAdminEmailChangeConfirmationDto");
    assertOperationContract(
        root,
        "/api/v1/changelog",
        "get",
        null,
        "200",
        "#/components/schemas/ApiResponsePageResponseChangelogEntryResponse");
    assertQueryParameter(root, "/api/v1/changelog", "get", "page");
    assertQueryParameter(root, "/api/v1/changelog", "get", "size");
    assertOperationContract(
        root,
        "/api/v1/changelog/latest-highlighted",
        "get",
        null,
        "200",
        "#/components/schemas/ApiResponseChangelogEntryResponse");
    assertOperationContract(
        root,
        "/api/v1/accounting/periods/{periodId}/request-close",
        "post",
        "#/components/schemas/PeriodCloseRequestActionRequest",
        "200",
        "#/components/schemas/ApiResponsePeriodCloseRequestDto");
    assertOperationContract(
        root,
        "/api/v1/accounting/periods/{periodId}/approve-close",
        "post",
        "#/components/schemas/PeriodCloseRequestActionRequest",
        "200",
        "#/components/schemas/ApiResponseAccountingPeriodDto");
    assertOperationContract(
        root,
        "/api/v1/accounting/periods/{periodId}/reject-close",
        "post",
        "#/components/schemas/PeriodCloseRequestActionRequest",
        "200",
        "#/components/schemas/ApiResponsePeriodCloseRequestDto");
    assertOperationContract(
        root,
        "/api/v1/accounting/periods/{periodId}/reopen",
        "post",
        "#/components/schemas/AccountingPeriodReopenRequest",
        "200",
        "#/components/schemas/ApiResponseAccountingPeriodDto");
    assertOperationContract(
        root,
        "/api/v1/exports/request",
        "post",
        "#/components/schemas/ExportRequestCreateRequest",
        "201",
        "#/components/schemas/ApiResponseExportRequestDto");
    assertBinaryOperationResponse(root, "/api/v1/exports/{requestId}/download", "get", "200");
    assertOperationContract(
        root,
        "/api/v1/superadmin/changelog",
        "post",
        "#/components/schemas/ChangelogEntryRequest",
        "200",
        "#/components/schemas/ApiResponseChangelogEntryResponse");
    assertOperationContract(
        root,
        "/api/v1/superadmin/changelog/{id}",
        "put",
        "#/components/schemas/ChangelogEntryRequest",
        "200",
        "#/components/schemas/ApiResponseChangelogEntryResponse");
    assertOperationContract(root, "/api/v1/superadmin/changelog/{id}", "delete", null, "204", null);
    assertOperationMissing(root, "/api/v1/admin/tenant-runtime/metrics", "get");
    assertOperationMissing(root, "/api/v1/admin/tenant-runtime/policy", "put");
    assertOperationMissing(root, "/api/v1/admin/changelog", "post");
    assertOperationMissing(root, "/api/v1/admin/changelog/{id}", "put");
    assertOperationMissing(root, "/api/v1/admin/changelog/{id}", "delete");
    assertOperationMissing(root, "/api/v1/companies", "post");
    assertOperationMissing(root, "/api/v1/companies/{id}", "delete");
    assertOperationMissing(root, "/api/v1/companies/{id}/lifecycle-state", "put");
    assertOperationMissing(root, "/api/v1/companies/{id}/tenant-metrics", "get");
    assertOperationMissing(root, "/api/v1/companies/{id}/tenant-runtime/policy", "put");
    assertOperationMissing(root, "/api/v1/companies/{id}/support/admin-password-reset", "post");
    assertOperationMissing(root, "/api/v1/companies/{id}/support/warnings", "post");
    assertOperationMissing(root, "/api/v1/companies/superadmin/tenants", "post");
    assertOperationMissing(root, "/api/v1/companies/superadmin/tenants/{id}", "put");
    assertOperationMissing(root, "/api/v1/superadmin/tenants/{id}/usage", "get");
    assertOperationMissing(root, "/api/v1/superadmin/tenants/{id}/activate", "post");
    assertOperationMissing(root, "/api/v1/superadmin/tenants/{id}/deactivate", "post");
    assertOperationMissing(root, "/api/v1/superadmin/tenants/{id}/suspend", "post");
    assertOperationMissing(root, "/api/v1/superadmin/tenants/{id}/lifecycle-state", "put");
    assertOperationContract(
        root,
        "/api/v1/admin/users/{userId}/force-reset-password",
        "post",
        null,
        "200",
        "#/components/schemas/ApiResponseString");
    assertOperationContract(
        root,
        "/api/v1/admin/users/{userId}/status",
        "put",
        "#/components/schemas/UpdateUserStatusRequest",
        "200",
        "#/components/schemas/ApiResponseUserDto");
    assertOperationContract(root, "/api/v1/admin/users/{id}/suspend", "patch", null, "204", null);
    assertOperationContract(root, "/api/v1/admin/users/{id}/unsuspend", "patch", null, "204", null);
    assertOperationContract(
        root, "/api/v1/admin/users/{id}/mfa/disable", "patch", null, "204", null);
    assertOperationContract(root, "/api/v1/admin/users/{id}", "delete", null, "204", null);

    assertOperationContract(
        root,
        "/api/v1/dealer-portal/credit-limit-requests",
        "post",
        "#/components/schemas/DealerPortalCreditLimitRequestCreateRequest",
        "201",
        "#/components/schemas/ApiResponseCreditLimitRequestDto");

    assertOperationContract(
        root,
        "/api/v1/sales/orders",
        "post",
        "#/components/schemas/SalesOrderRequest",
        "200",
        "#/components/schemas/ApiResponseSalesOrderDto");
    assertOperationResponse(
        root,
        "/api/v1/sales/orders",
        "post",
        "201",
        "#/components/schemas/ApiResponseSalesOrderDto");
    assertOperationResponse(
        root,
        "/api/v1/sales/orders",
        "post",
        "422",
        "#/components/schemas/ApiResponseMapStringObject");
  }

  @Test
  void auth_and_tenant_control_docs_match_the_hard_cut_route_story() throws IOException {
    String modulesAuth = readRepoFile("docs/modules/auth.md");
    assertThat(modulesAuth).contains("`GET /api/v1/auth/me`");
    assertThat(modulesAuth)
        .contains("`/api/v1/superadmin/tenants/{id}/support/admin-password-reset`");
    assertThat(modulesAuth)
        .doesNotContain("### UserProfileController — `/api/v1/auth/profile`")
        .doesNotContain("| GET | `/api/v1/auth/profile` |")
        .doesNotContain("| PUT | `/api/v1/auth/profile` |")
        .doesNotContain("| GET/HEAD | `/api/v1/auth/me`, `/api/v1/auth/profile` |");

    String flowAuthIdentity = readRepoFile("docs/flows/auth-identity.md");
    assertThat(flowAuthIdentity).contains("| `/me` | GET | `/api/v1/auth/me` |");
    assertThat(flowAuthIdentity)
        .contains(
            "| Super-admin support reset | POST |"
                + " `/api/v1/superadmin/tenants/{id}/support/admin-password-reset` |")
        .doesNotContain("| Profile read | GET | `/api/v1/auth/profile` |")
        .doesNotContain("| Profile update | PUT | `/api/v1/auth/profile` |")
        .doesNotContain("PUT `/api/v1/auth/profile`")
        .doesNotContain("`POST /api/v1/companies/{id}/support/admin-password-reset`");

    String codeReviewControlPlane =
        readRepoFile("docs/code-review/flows/company-tenant-control-plane.md");
    assertThat(codeReviewControlPlane)
        .contains("`PUT /api/v1/superadmin/tenants/{id}/lifecycle`")
        .contains("`PUT /api/v1/superadmin/tenants/{id}/limits`")
        .contains("`POST /api/v1/superadmin/tenants/{id}/support/admin-password-reset`")
        .doesNotContain("`GET /api/v1/admin/tenant-runtime/metrics`")
        .doesNotContain("`PUT /api/v1/admin/tenant-runtime/policy`")
        .doesNotContain("`PUT /api/v1/companies/{id}/tenant-runtime/policy`")
        .doesNotContain("CompanyService.updateTenantRuntimePolicy(...)")
        .doesNotContain("suspend, activate, deactivate, list usage");

    String authHardening = readRepoFile(".factory/library/auth-hardening.md");
    assertThat(authHardening)
        .contains("`GET /api/v1/auth/me`")
        .contains("`PUT /api/v1/superadmin/tenants/{id}/limits`")
        .doesNotContain("`GET /auth/profile`")
        .doesNotContain("`PUT /api/v1/companies/{id}/tenant-runtime/policy`")
        .doesNotContain("`PUT /api/v1/admin/tenant-runtime/policy`");

    String frontendHandoff = readRepoFile(".factory/library/frontend-handoff.md");
    assertThat(frontendHandoff)
        .contains("| GET | `/api/v1/auth/me` |")
        .contains("| PUT | `/api/v1/superadmin/tenants/{id}/lifecycle` |")
        .contains("| PUT | `/api/v1/superadmin/tenants/{id}/limits` |")
        .doesNotContain("| GET | `/api/v1/auth/profile` |")
        .doesNotContain("| PUT | `/api/v1/auth/profile` |")
        .doesNotContain("GET /api/v1/auth/profile")
        .doesNotContain("PUT /api/v1/auth/profile")
        .doesNotContain("`PUT /api/v1/companies/{id}/tenant-runtime/policy`")
        .doesNotContain("`PUT /api/v1/admin/tenant-runtime/policy`")
        .doesNotContain("`POST /api/v1/superadmin/tenants/{id}/suspend`")
        .doesNotContain("`POST /api/v1/superadmin/tenants/{id}/activate`")
        .doesNotContain("`POST /api/v1/superadmin/tenants/{id}/deactivate`");

    String frontendV2 = readRepoFile(".factory/library/frontend-v2.md");
    assertThat(frontendV2)
        .contains("`PUT /api/v1/superadmin/tenants/{id}/limits`")
        .doesNotContain("`PUT /api/v1/companies/{id}/tenant-runtime/policy`");

    String runtimeControlPlane = readRepoFile(".factory/library/tenant-runtime-control-plane.md");
    assertThat(runtimeControlPlane)
        .contains(
            "Canonical control-plane mutation path: `PUT /api/v1/superadmin/tenants/{id}/limits`")
        .doesNotContain("Public mutation path: `PUT /api/v1/companies/{id}/tenant-runtime/policy`");
  }

  @Test
  void catalog_surface_contract_exposes_only_canonical_public_routes() throws IOException {
    JsonNode root = fetchCurrentSpecNode();

    assertOperationContract(
        root,
        "/api/v1/catalog/brands",
        "get",
        null,
        "200",
        "#/components/schemas/ApiResponseListCatalogBrandDto");
    assertQueryParameter(root, "/api/v1/catalog/brands", "get", "active");
    assertOperationContract(
        root,
        "/api/v1/catalog/brands",
        "post",
        "#/components/schemas/CatalogBrandRequest",
        "200",
        "#/components/schemas/ApiResponseCatalogBrandDto");

    assertMultipartBinaryRequest(root, "/api/v1/catalog/import", "post", "file");
    assertOperationResponse(
        root,
        "/api/v1/catalog/import",
        "post",
        "200",
        "#/components/schemas/ApiResponseCatalogImportResponse");
    assertOperationContract(
        root,
        "/api/v1/catalog/items",
        "get",
        null,
        "200",
        "#/components/schemas/ApiResponsePageResponseCatalogItemDto");
    assertQueryParameter(root, "/api/v1/catalog/items", "get", "q");
    assertQueryParameter(root, "/api/v1/catalog/items", "get", "itemClass");
    assertOperationContract(
        root,
        "/api/v1/catalog/items",
        "post",
        "#/components/schemas/CatalogItemRequest",
        "200",
        "#/components/schemas/ApiResponseCatalogItemDto");
    assertOperationContract(
        root,
        "/api/v1/catalog/items/{itemId}",
        "get",
        null,
        "200",
        "#/components/schemas/ApiResponseCatalogItemDto");
    assertOperationContract(
        root,
        "/api/v1/catalog/items/{itemId}",
        "put",
        "#/components/schemas/CatalogItemRequest",
        "200",
        "#/components/schemas/ApiResponseCatalogItemDto");
    assertOperationContract(
        root,
        "/api/v1/catalog/items/{itemId}",
        "delete",
        null,
        "200",
        "#/components/schemas/ApiResponseCatalogItemDto");
    assertOperationMissing(root, "/api/v1/catalog/products", "get");
    assertOperationMissing(root, "/api/v1/catalog/products", "post");
    assertOperationMissing(root, "/api/v1/catalog/products/single", "post");
    assertOperationMissing(root, "/api/v1/catalog/products/bulk-variants", "post");

    assertOperationMissing(root, "/api/v1/accounting/catalog/import", "post");
    assertOperationMissing(root, "/api/v1/accounting/catalog/products", "get");
    assertOperationMissing(root, "/api/v1/accounting/catalog/products", "post");
    assertOperationMissing(root, "/api/v1/accounting/catalog/products/{id}", "put");
    assertOperationMissing(root, "/api/v1/accounting/catalog/products/bulk-variants", "post");
    assertOperationMissing(root, "/api/v1/production/brands", "get");
    assertOperationMissing(root, "/api/v1/production/brands/{brandId}/products", "get");
  }

  @Test
  void report_contract_paths_use_canonical_namespace_only() throws IOException {
    JsonNode root = fetchCurrentSpecNode();

    assertOperationContract(
        root,
        "/api/v1/reports/aged-debtors",
        "get",
        null,
        "200",
        "#/components/schemas/ApiResponseListAgedDebtorDto");
    assertOperationContract(
        root,
        "/api/v1/reports/balance-sheet/hierarchy",
        "get",
        null,
        "200",
        "#/components/schemas/ApiResponseBalanceSheetHierarchy");
    assertOperationContract(
        root,
        "/api/v1/reports/income-statement/hierarchy",
        "get",
        null,
        "200",
        "#/components/schemas/ApiResponseIncomeStatementHierarchy");
    assertOperationContract(
        root,
        "/api/v1/reports/aging/receivables",
        "get",
        null,
        "200",
        "#/components/schemas/ApiResponseAgedReceivablesReport");

    assertOperationMissing(root, "/api/v1/accounting/reports/aged-debtors", "get");
    assertOperationMissing(root, "/api/v1/accounting/reports/balance-sheet/hierarchy", "get");
    assertOperationMissing(root, "/api/v1/accounting/reports/income-statement/hierarchy", "get");
    assertOperationMissing(root, "/api/v1/accounting/reports/aging/receivables", "get");
    assertOperationMissing(root, "/api/v1/accounting/reports/aging/dealer/{dealerId}", "get");
    assertOperationMissing(
        root, "/api/v1/accounting/reports/aging/dealer/{dealerId}/detailed", "get");
    assertOperationMissing(root, "/api/v1/accounting/reports/dso/dealer/{dealerId}", "get");
    assertOperationMissing(root, "/api/v1/reports/aging/dealer/{dealerId}", "get");
    assertOperationMissing(root, "/api/v1/reports/aging/dealer/{dealerId}/detailed", "get");
    assertOperationMissing(root, "/api/v1/reports/dso/dealer/{dealerId}", "get");
  }

  @Test
  void orchestrator_contract_exposes_only_canonical_runtime_routes() throws IOException {
    JsonNode root = fetchCurrentSpecNode();

    JsonNode approveOperation =
        root.path("paths").path("/api/v1/orchestrator/orders/{orderId}/approve").path("post");
    assertThat(approveOperation.isMissingNode()).isFalse();
    assertThat(
            approveOperation
                .path("requestBody")
                .path("content")
                .path("application/json")
                .path("schema")
                .path("$ref")
                .asText())
        .isEqualTo("#/components/schemas/ApproveOrderRequest");
    assertThat(approveOperation.path("responses").has("200")).isTrue();
    JsonNode approveResponseSchema =
        approveOperation.path("responses").path("200").path("content").path("*/*").path("schema");
    assertThat(approveResponseSchema.path("type").asText()).isEqualTo("object");
    assertThat(approveResponseSchema.path("additionalProperties").path("type").asText())
        .isEqualTo("object");

    JsonNode fulfillmentOperation =
        root.path("paths").path("/api/v1/orchestrator/orders/{orderId}/fulfillment").path("post");
    assertThat(fulfillmentOperation.isMissingNode()).isFalse();
    assertThat(
            fulfillmentOperation
                .path("requestBody")
                .path("content")
                .path("application/json")
                .path("schema")
                .path("$ref")
                .asText())
        .isEqualTo("#/components/schemas/OrderFulfillmentRequest");
    assertThat(fulfillmentOperation.path("responses").has("200")).isTrue();
    JsonNode fulfillmentResponseSchema =
        fulfillmentOperation
            .path("responses")
            .path("200")
            .path("content")
            .path("*/*")
            .path("schema");
    assertThat(fulfillmentResponseSchema.path("type").asText()).isEqualTo("object");
    assertThat(fulfillmentResponseSchema.path("additionalProperties").path("type").asText())
        .isEqualTo("object");
    assertThat(
            root.path("paths")
                .path("/api/v1/orchestrator/traces/{traceId}")
                .path("get")
                .isMissingNode())
        .isFalse();
    assertThat(
            root.path("paths")
                .path("/api/v1/orchestrator/traces/{traceId}")
                .path("get")
                .path("responses")
                .has("200"))
        .isTrue();
    assertThat(
            root.path("paths")
                .path("/api/v1/orchestrator/health/integrations")
                .path("get")
                .isMissingNode())
        .isFalse();
    assertThat(
            root.path("paths")
                .path("/api/v1/orchestrator/health/integrations")
                .path("get")
                .path("responses")
                .has("200"))
        .isTrue();
    assertThat(
            root.path("paths")
                .path("/api/v1/orchestrator/health/events")
                .path("get")
                .isMissingNode())
        .isFalse();
    assertThat(
            root.path("paths")
                .path("/api/v1/orchestrator/health/events")
                .path("get")
                .path("responses")
                .has("200"))
        .isTrue();

    assertOperationMissing(root, "/api/v1/orchestrator/dispatch", "post");
    assertOperationMissing(root, "/api/v1/orchestrator/dispatch/{orderId}", "post");
    assertOperationMissing(root, "/api/v1/orchestrator/factory/dispatch/{batchId}", "post");
    assertOperationMissing(root, "/api/v1/orchestrator/payroll/run", "post");
  }

  @Test
  void portal_finance_contract_paths_expose_only_canonical_namespace() throws IOException {
    JsonNode root = fetchCurrentSpecNode();

    assertOperationContract(
        root,
        "/api/v1/portal/finance/ledger",
        "get",
        null,
        "200",
        "#/components/schemas/ApiResponseMapStringObject");
    assertQueryParameter(root, "/api/v1/portal/finance/ledger", "get", "dealerId");
    assertOperationContract(
        root,
        "/api/v1/portal/finance/invoices",
        "get",
        null,
        "200",
        "#/components/schemas/ApiResponseMapStringObject");
    assertQueryParameter(root, "/api/v1/portal/finance/invoices", "get", "dealerId");
    assertOperationContract(
        root,
        "/api/v1/portal/finance/aging",
        "get",
        null,
        "200",
        "#/components/schemas/ApiResponseMapStringObject");
    assertQueryParameter(root, "/api/v1/portal/finance/aging", "get", "dealerId");

    assertOperationMissing(root, "/api/v1/dealers/{dealerId}/ledger", "get");
    assertOperationMissing(root, "/api/v1/dealers/{dealerId}/invoices", "get");
    assertOperationMissing(root, "/api/v1/dealers/{dealerId}/aging", "get");
    assertOperationMissing(root, "/api/v1/dealers/{dealerId}/credit-utilization", "get");
    assertOperationMissing(root, "/api/v1/invoices/dealers/{dealerId}", "get");
    assertOperationMissing(root, "/api/v1/accounting/aging/dealers/{dealerId}", "get");
    assertOperationMissing(root, "/api/v1/accounting/aging/dealers/{dealerId}/pdf", "get");
    assertOperationMissing(root, "/api/v1/accounting/statements/dealers/{dealerId}", "get");
    assertOperationMissing(root, "/api/v1/accounting/statements/dealers/{dealerId}/pdf", "get");
  }

  @Test
  void support_ticket_contract_paths_expose_only_split_hosts() throws IOException {
    JsonNode root = fetchCurrentSpecNode();

    assertOperationContract(
        root,
        "/api/v1/portal/support/tickets",
        "get",
        null,
        "200",
        "#/components/schemas/ApiResponseSupportTicketListResponse");
    assertOperationContract(
        root,
        "/api/v1/portal/support/tickets",
        "post",
        "#/components/schemas/SupportTicketCreateRequest",
        "200",
        "#/components/schemas/ApiResponseSupportTicketResponse");
    assertOperationContract(
        root,
        "/api/v1/portal/support/tickets/{ticketId}",
        "get",
        null,
        "200",
        "#/components/schemas/ApiResponseSupportTicketResponse");

    assertOperationContract(
        root,
        "/api/v1/dealer-portal/support/tickets",
        "get",
        null,
        "200",
        "#/components/schemas/ApiResponseSupportTicketListResponse");
    assertOperationContract(
        root,
        "/api/v1/dealer-portal/support/tickets",
        "post",
        "#/components/schemas/SupportTicketCreateRequest",
        "200",
        "#/components/schemas/ApiResponseSupportTicketResponse");
    assertOperationContract(
        root,
        "/api/v1/dealer-portal/support/tickets/{ticketId}",
        "get",
        null,
        "200",
        "#/components/schemas/ApiResponseSupportTicketResponse");

    assertOperationMissing(root, "/api/v1/support/tickets", "get");
    assertOperationMissing(root, "/api/v1/support/tickets", "post");
    assertOperationMissing(root, "/api/v1/support/tickets/{ticketId}", "get");
  }

  @Test
  void
      inventory_contract_requires_explicit_opening_stock_batch_key_and_removes_retired_bulk_pack_request()
          throws IOException {
    JsonNode root = fetchCurrentSpecNode();

    assertMultipartBinaryRequest(root, "/api/v1/inventory/opening-stock", "post", "file");

    JsonNode openingStockParameters =
        root.path("paths").path("/api/v1/inventory/opening-stock").path("post").path("parameters");
    JsonNode openingStockBatchKey = null;
    for (JsonNode parameter : openingStockParameters) {
      if ("openingStockBatchKey".equals(parameter.path("name").asText())) {
        openingStockBatchKey = parameter;
        break;
      }
    }

    assertThat(openingStockBatchKey)
        .withFailMessage(
            "Expected query parameter 'openingStockBatchKey' on POST"
                + " /api/v1/inventory/opening-stock")
        .isNotNull();
    assertThat(openingStockBatchKey.path("in").asText()).isEqualTo("query");
    assertThat(openingStockBatchKey.path("required").asBoolean()).isTrue();

    assertThat(root.path("components").path("schemas").has("BulkPackRequest"))
        .withFailMessage(
            "BulkPackRequest schema must be absent after removing retired bulk mutation surface")
        .isFalse();
  }

  @Test
  void production_log_contract_stays_ready_to_pack_and_removes_dead_request_toggles()
      throws IOException {
    JsonNode root = fetchCurrentSpecNode();

    assertOperationMissing(root, "/api/v1/factory/production-batches", "get");
    assertOperationMissing(root, "/api/v1/factory/production-batches", "post");
    assertOperationContract(
        root,
        "/api/v1/factory/production/logs",
        "post",
        "#/components/schemas/ProductionLogRequest",
        "200",
        "#/components/schemas/ApiResponseProductionLogDetailDto");
    assertThat(root.path("components").path("schemas").has("ProductionBatchRequest")).isFalse();
    assertThat(root.path("components").path("schemas").has("ProductionBatchDto")).isFalse();

    JsonNode productionLogRequest =
        root.path("components").path("schemas").path("ProductionLogRequest");
    JsonNode requestProperties = productionLogRequest.path("properties");
    assertThat(requestProperties.has("brandId")).isTrue();
    assertThat(requestProperties.has("productId")).isTrue();
    assertThat(requestProperties.has("mixedQuantity")).isTrue();
    assertThat(requestProperties.has("materials")).isTrue();
    assertThat(requestProperties.has("addToFinishedGoods"))
        .withFailMessage("ProductionLogRequest must not expose retired addToFinishedGoods toggle")
        .isFalse();

    JsonNode detailDto = root.path("components").path("schemas").path("ProductionLogDetailDto");
    JsonNode detailProperties = detailDto.path("properties");
    assertThat(detailProperties.has("id")).isTrue();
    assertThat(detailProperties.has("publicId")).isTrue();
    assertThat(detailProperties.has("productionCode")).isTrue();
    assertThat(detailProperties.has("productFamilyName")).isTrue();
    assertThat(detailProperties.has("outputBatchCode")).isTrue();
    assertThat(detailProperties.has("outputQuantity")).isTrue();
    assertThat(detailProperties.has("totalPackedQuantity")).isTrue();
    assertThat(detailProperties.has("status")).isTrue();
    assertThat(detailProperties.has("allowedSellableSizes")).isTrue();

    JsonNode unpackedBatchDto = root.path("components").path("schemas").path("UnpackedBatchDto");
    JsonNode unpackedProperties = unpackedBatchDto.path("properties");
    assertThat(unpackedProperties.has("productFamilyName")).isTrue();
    assertThat(unpackedProperties.has("allowedSellableSizes")).isTrue();

    JsonNode packingLineRequest =
        root.path("components").path("schemas").path("PackingLineRequest");
    List<String> packingLineRequired = new ArrayList<>();
    packingLineRequest.path("required").forEach(node -> packingLineRequired.add(node.asText()));
    assertThat(packingLineRequired).contains("childFinishedGoodId");
  }

  @Test
  void packing_contract_keeps_only_canonical_write_surface_and_header_only_idempotency()
      throws IOException {
    JsonNode root = fetchCurrentSpecNode();

    assertOperationContract(
        root,
        "/api/v1/factory/packing-records",
        "post",
        "#/components/schemas/PackingRequest",
        "200",
        "#/components/schemas/ApiResponseProductionLogDetailDto");
    assertOperationMissing(
        root, "/api/v1/factory/packing-records/{productionLogId}/complete", "post");
    assertOperationMissing(root, "/api/v1/factory/pack", "post");

    JsonNode parameters =
        root.path("paths").path("/api/v1/factory/packing-records").path("post").path("parameters");
    List<String> parameterNames = new ArrayList<>();
    parameters.forEach(parameter -> parameterNames.add(parameter.path("name").asText()));
    assertThat(parameterNames).containsExactly("Idempotency-Key");
    assertThat(parameters.get(0).path("required").asBoolean()).isTrue();

    JsonNode packingRequest = root.path("components").path("schemas").path("PackingRequest");
    assertThat(packingRequest.path("properties").has("closeResidualWastage"))
        .withFailMessage(
            "PackingRequest must expose closeResidualWastage on the canonical packing route")
        .isTrue();
    assertThat(packingRequest.path("properties").has("idempotencyKey"))
        .withFailMessage("PackingRequest must not expose idempotencyKey in the request body")
        .isFalse();
  }

  @Test
  void accounting_manual_journal_and_receipt_settlement_contracts_are_hard_cut()
      throws IOException {
    JsonNode root = fetchCurrentSpecNode();

    assertOperationContract(
        root,
        "/api/v1/accounting/journal-entries",
        "post",
        "#/components/schemas/JournalEntryRequest",
        "200",
        "#/components/schemas/ApiResponseJournalEntryDto");
    assertOperationMissing(root, "/api/v1/accounting/journals/manual", "post");
    assertOperationMissing(root, "/api/v1/accounting/journals/{entryId}/reverse", "post");
    assertOperationMissing(
        root, "/api/v1/accounting/journal-entries/{entryId}/cascade-reverse", "post");
    assertOperationMissing(root, "/api/v1/accounting/periods/{periodId}/close", "post");

    assertHeaderParameters(root, "/api/v1/accounting/receipts/dealer", "post", "Idempotency-Key");
    assertHeaderParameters(
        root, "/api/v1/accounting/receipts/dealer/hybrid", "post", "Idempotency-Key");
    assertHeaderParameters(
        root, "/api/v1/accounting/settlements/dealers", "post", "Idempotency-Key");
    assertHeaderParameters(
        root, "/api/v1/accounting/dealers/{dealerId}/auto-settle", "post", "Idempotency-Key");
    assertHeaderParameters(
        root, "/api/v1/accounting/settlements/suppliers", "post", "Idempotency-Key");
    assertHeaderParameters(
        root, "/api/v1/accounting/suppliers/{supplierId}/auto-settle", "post", "Idempotency-Key");

    assertOperationContract(
        root,
        "/api/v1/accounting/settlements/dealers",
        "post",
        "#/components/schemas/PartnerSettlementRequest",
        "200",
        "#/components/schemas/ApiResponsePartnerSettlementResponse");
    assertOperationContract(
        root,
        "/api/v1/accounting/settlements/suppliers",
        "post",
        "#/components/schemas/PartnerSettlementRequest",
        "200",
        "#/components/schemas/ApiResponsePartnerSettlementResponse");
    assertOperationContract(
        root,
        "/api/v1/accounting/periods",
        "post",
        "#/components/schemas/AccountingPeriodRequest",
        "200",
        "#/components/schemas/ApiResponseAccountingPeriodDto");
    assertOperationContract(
        root,
        "/api/v1/accounting/periods/{periodId}",
        "put",
        "#/components/schemas/AccountingPeriodRequest",
        "200",
        "#/components/schemas/ApiResponseAccountingPeriodDto");

    assertSchemaPresence(root, "PartnerSettlementRequest", true);
    assertSchemaPresence(root, "AccountingPeriodRequest", true);
    assertSchemaPresence(root, "DealerSettlementRequest", false);
    assertSchemaPresence(root, "SupplierSettlementRequest", false);
    assertSchemaPresence(root, "AccountingPeriodUpsertRequest", false);
    assertSchemaPresence(root, "AccountingPeriodUpdateRequest", false);
    assertSchemaPresence(root, "AccountingPeriodCloseRequest", false);
    assertSchemaPresence(root, "AccountingPeriodLockRequest", false);
  }

  @Test
  void legacy_idempotency_headers_are_hidden_on_hard_cut_sales_and_inventory_writes()
      throws IOException {
    JsonNode root = fetchCurrentSpecNode();

    assertHeaderParameters(root, "/api/v1/sales/orders", "post", "Idempotency-Key");
    assertHeaderParameters(root, "/api/v1/inventory/adjustments", "post", "Idempotency-Key");
    assertHeaderParameters(
        root, "/api/v1/inventory/raw-materials/adjustments", "post", "Idempotency-Key");
    assertHeaderParameters(
        root, "/api/v1/purchasing/raw-material-purchases", "post", "Idempotency-Key");
    assertHeaderParameters(
        root, "/api/v1/purchasing/raw-material-purchases/returns", "post", "Idempotency-Key");
  }

  @Test
  void openapi_snapshot_matches_repository_contract() throws IOException {
    Path openApiSnapshotPath = resolveRepoRoot().resolve("openapi.json");
    String currentSpec = canonicalizeJson(fetchCurrentSpecNode().toString());
    if (refreshRequested()) {
      assertThat(verifyRequested())
          .withFailMessage(
              "Refresh requires verify mode. Set -D%s=true (or %s=true) together with "
                  + "-D%s=true (or %s=true).",
              SNAPSHOT_VERIFY_PROPERTY,
              SNAPSHOT_VERIFY_ENV,
              SNAPSHOT_REFRESH_PROPERTY,
              SNAPSHOT_REFRESH_ENV)
          .isTrue();
      Files.writeString(openApiSnapshotPath, currentSpec, StandardCharsets.UTF_8);
      return;
    }

    assertThat(Files.exists(openApiSnapshotPath))
        .withFailMessage(
            "Missing OpenAPI snapshot at %s. Remediation: rerun intentionally with -D%s=true "
                + "or %s=true (with -D%s=true or %s=true) to generate it.",
            openApiSnapshotPath,
            SNAPSHOT_REFRESH_PROPERTY,
            SNAPSHOT_REFRESH_ENV,
            SNAPSHOT_VERIFY_PROPERTY,
            SNAPSHOT_VERIFY_ENV)
        .isTrue();

    String snapshotSpec =
        canonicalizeJson(Files.readString(openApiSnapshotPath, StandardCharsets.UTF_8));
    String currentSpecHash = sha256Hex(currentSpec);
    String snapshotSpecHash = sha256Hex(snapshotSpec);
    List<String> currentOps = extractOperationSignatures(currentSpec);
    List<String> snapshotOps = extractOperationSignatures(snapshotSpec);
    List<String> missingSnapshotOps = new ArrayList<>(snapshotOps);
    missingSnapshotOps.removeAll(currentOps);
    String missingSnapshotOpsPreview =
        missingSnapshotOps.stream().limit(12).collect(Collectors.joining(", "));

    assertThat(snapshotOps)
        .withFailMessage(
            "OpenAPI snapshot at %s has no operations. Refresh snapshot intentionally with "
                + "-D%s=true (or %s=true) and -D%s=true (or %s=true).",
            openApiSnapshotPath,
            SNAPSHOT_VERIFY_PROPERTY,
            SNAPSHOT_VERIFY_ENV,
            SNAPSHOT_REFRESH_PROPERTY,
            SNAPSHOT_REFRESH_ENV)
        .isNotEmpty();

    assertThat(currentOps)
        .withFailMessage(
            "OpenAPI breaking operation drift detected at %s. currentOps=%d snapshotOps=%d"
                + " (delta=%d) currentHash=%s snapshotHash=%s. Snapshot operations must remain"
                + " present in the runtime contract. missingSnapshotOpsCount=%d"
                + " missingSnapshotOpsPreview=[%s]. For full parity (including additive drift),"
                + " rerun with -D%s=true (or %s=true) and refresh using -D%s=true (or %s=true).",
            openApiSnapshotPath,
            currentOps.size(),
            snapshotOps.size(),
            currentOps.size() - snapshotOps.size(),
            currentSpecHash,
            snapshotSpecHash,
            missingSnapshotOps.size(),
            missingSnapshotOpsPreview,
            SNAPSHOT_VERIFY_PROPERTY,
            SNAPSHOT_VERIFY_ENV,
            SNAPSHOT_REFRESH_PROPERTY,
            SNAPSHOT_REFRESH_ENV)
        .containsAll(snapshotOps);

    if (!verifyRequested()) {
      return;
    }

    assertThat(currentSpec)
        .withFailMessage(
            "OpenAPI snapshot drift detected at %s. Verify mode is non-mutating unless refresh is"
                + " enabled. Parity signal (sha256) current=%s snapshot=%s. Remediation: rerun"
                + " intentionally with -D%s=true (or %s=true) and -D%s=true (or %s=true), then"
                + " commit updated openapi.json.",
            openApiSnapshotPath,
            currentSpecHash,
            snapshotSpecHash,
            SNAPSHOT_VERIFY_PROPERTY,
            SNAPSHOT_VERIFY_ENV,
            SNAPSHOT_REFRESH_PROPERTY,
            SNAPSHOT_REFRESH_ENV)
        .isEqualTo(snapshotSpec);
  }

  private static boolean verifyRequested() {
    return Boolean.parseBoolean(
        System.getProperty(
            SNAPSHOT_VERIFY_PROPERTY, System.getenv().getOrDefault(SNAPSHOT_VERIFY_ENV, "false")));
  }

  private static boolean refreshRequested() {
    return Boolean.parseBoolean(
        System.getProperty(
            SNAPSHOT_REFRESH_PROPERTY,
            System.getenv().getOrDefault(SNAPSHOT_REFRESH_ENV, "false")));
  }

  private static Path resolveRepoRoot() {
    Path moduleRoot = Path.of("").toAbsolutePath().normalize();
    if (moduleRoot.getFileName() != null
        && "erp-domain".equals(moduleRoot.getFileName().toString())) {
      Path parent = moduleRoot.getParent();
      if (parent != null) {
        return parent;
      }
    }
    return moduleRoot;
  }

  private static String canonicalizeJson(String spec) throws IOException {
    JsonNode parsedSpec = CANONICAL_JSON.readTree(spec);
    return CANONICAL_JSON.writeValueAsString(canonicalizeNode(parsedSpec));
  }

  private void assertHeaderParameters(
      JsonNode root, String path, String method, String... expectedHeaderNames) {
    JsonNode parameters = root.path("paths").path(path).path(method).path("parameters");
    List<String> parameterNames = new ArrayList<>();
    parameters.forEach(
        parameter -> {
          if ("header".equals(parameter.path("in").asText())) {
            parameterNames.add(parameter.path("name").asText());
          }
        });
    assertThat(parameterNames).containsExactly(expectedHeaderNames);
  }

  private void assertSchemaPresence(JsonNode root, String schemaName, boolean expectedPresence) {
    assertThat(root.path("components").path("schemas").has(schemaName))
        .withFailMessage(
            "Expected schema %s presence=%s in generated OpenAPI spec",
            schemaName, expectedPresence)
        .isEqualTo(expectedPresence);
  }

  private static List<String> extractOperationSignatures(String spec) throws IOException {
    JsonNode root = CANONICAL_JSON.readTree(spec);
    JsonNode paths = root.get("paths");
    List<String> operations = new ArrayList<>();
    if (paths == null || !paths.isObject()) {
      return operations;
    }
    paths
        .fields()
        .forEachRemaining(
            pathEntry -> {
              JsonNode methods = pathEntry.getValue();
              if (methods == null || !methods.isObject()) {
                return;
              }
              methods
                  .fieldNames()
                  .forEachRemaining(
                      method -> {
                        if (isHttpMethod(method)) {
                          operations.add(method.toUpperCase() + " " + pathEntry.getKey());
                        }
                      });
            });
    Collections.sort(operations);
    return operations;
  }

  private static boolean isHttpMethod(String method) {
    return "get".equalsIgnoreCase(method)
        || "put".equalsIgnoreCase(method)
        || "post".equalsIgnoreCase(method)
        || "delete".equalsIgnoreCase(method)
        || "patch".equalsIgnoreCase(method)
        || "options".equalsIgnoreCase(method)
        || "head".equalsIgnoreCase(method)
        || "trace".equalsIgnoreCase(method);
  }

  private static JsonNode canonicalizeNode(JsonNode node) {
    if (node == null || node.isNull() || node.isValueNode()) {
      return node;
    }
    if (node.isArray()) {
      ArrayNode canonicalArray = CANONICAL_JSON.createArrayNode();
      for (JsonNode item : node) {
        canonicalArray.add(canonicalizeNode(item));
      }
      return canonicalArray;
    }
    if (node.isObject()) {
      ObjectNode canonicalObject = CANONICAL_JSON.createObjectNode();
      List<String> fieldNames = new ArrayList<>();
      node.fieldNames().forEachRemaining(fieldNames::add);
      Collections.sort(fieldNames);
      for (String fieldName : fieldNames) {
        canonicalObject.set(fieldName, canonicalizeNode(node.get(fieldName)));
      }
      return canonicalObject;
    }
    return node;
  }

  private static String sha256Hex(String value) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder builder = new StringBuilder(digest.length * 2);
      for (byte b : digest) {
        builder.append(Character.forDigit((b >>> 4) & 0x0f, 16));
        builder.append(Character.forDigit(b & 0x0f, 16));
      }
      return builder.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 algorithm is unavailable", e);
    }
  }

  private JsonNode fetchCurrentSpecNode() throws IOException {
    SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(30_000);
    requestFactory.setReadTimeout(120_000);
    rest.getRestTemplate().setRequestFactory(requestFactory);

    ResponseEntity<String> json = rest.getForEntity("/v3/api-docs", String.class);
    assertThat(json.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(json.getBody()).as("OpenAPI payload").isNotBlank();
    return CANONICAL_JSON.readTree(json.getBody());
  }

  private String readRepoFile(String relativePath) throws IOException {
    return Files.readString(resolveRepoRoot().resolve(relativePath), StandardCharsets.UTF_8);
  }

  private void assertOperationContract(
      JsonNode root,
      String path,
      String method,
      String expectedRequestRef,
      String expectedResponseCode,
      String expectedResponseRef) {
    JsonNode operation = root.path("paths").path(path).path(method);
    assertThat(operation.isMissingNode())
        .withFailMessage("Missing %s %s from generated OpenAPI spec", method.toUpperCase(), path)
        .isFalse();

    if (expectedRequestRef == null) {
      assertThat(operation.path("requestBody").isMissingNode())
          .withFailMessage("Did not expect a request body for %s %s", method.toUpperCase(), path)
          .isTrue();
    } else {
      assertThat(
              operation
                  .path("requestBody")
                  .path("content")
                  .path("application/json")
                  .path("schema")
                  .path("$ref")
                  .asText())
          .withFailMessage("Unexpected request contract for %s %s", method.toUpperCase(), path)
          .isEqualTo(expectedRequestRef);
    }

    JsonNode responses = operation.path("responses");
    List<String> documentedResponseCodes = new ArrayList<>();
    responses.fieldNames().forEachRemaining(documentedResponseCodes::add);
    assertThat(responses.has(expectedResponseCode))
        .withFailMessage(
            "Expected %s response for %s %s but found %s",
            expectedResponseCode, method.toUpperCase(), path, documentedResponseCodes)
        .isTrue();

    JsonNode response = responses.path(expectedResponseCode);
    if (expectedResponseRef == null) {
      assertThat(response.path("content").isMissingNode() || response.path("content").isEmpty())
          .withFailMessage(
              "Did not expect a response body for %s %s %s",
              expectedResponseCode, method.toUpperCase(), path)
          .isTrue();
      return;
    }

    JsonNode content = response.path("content");
    JsonNode schema = content.path("*/*").path("schema");
    if (schema.isMissingNode()) {
      schema = content.path("application/json").path("schema");
    }
    assertThat(schema.path("$ref").asText())
        .withFailMessage(
            "Unexpected response contract for %s %s %s",
            expectedResponseCode, method.toUpperCase(), path)
        .isEqualTo(expectedResponseRef);
  }

  private void assertOperationMissing(JsonNode root, String path, String method) {
    JsonNode operation = root.path("paths").path(path).path(method);
    assertThat(operation.isMissingNode())
        .withFailMessage(
            "Did not expect %s %s in generated OpenAPI spec", method.toUpperCase(), path)
        .isTrue();
  }

  private void assertMultipartBinaryRequest(
      JsonNode root, String path, String method, String partName) {
    JsonNode operation = root.path("paths").path(path).path(method);
    assertThat(operation.isMissingNode())
        .withFailMessage("Missing %s %s from generated OpenAPI spec", method.toUpperCase(), path)
        .isFalse();

    JsonNode schema =
        operation.path("requestBody").path("content").path("multipart/form-data").path("schema");
    assertThat(schema.path("type").asText())
        .withFailMessage(
            "Expected multipart/form-data request schema for %s %s", method.toUpperCase(), path)
        .isEqualTo("object");
    assertThat(schema.path("properties").path(partName).path("type").asText())
        .withFailMessage(
            "Expected multipart part %s on %s %s", partName, method.toUpperCase(), path)
        .isEqualTo("string");
    assertThat(schema.path("properties").path(partName).path("format").asText())
        .withFailMessage(
            "Expected multipart part %s to be binary on %s %s",
            partName, method.toUpperCase(), path)
        .isEqualTo("binary");
  }

  private void assertOperationResponse(
      JsonNode root,
      String path,
      String method,
      String expectedResponseCode,
      String expectedResponseRef) {
    JsonNode operation = root.path("paths").path(path).path(method);
    assertThat(operation.isMissingNode())
        .withFailMessage("Missing %s %s from generated OpenAPI spec", method.toUpperCase(), path)
        .isFalse();

    JsonNode responses = operation.path("responses");
    List<String> documentedResponseCodes = new ArrayList<>();
    responses.fieldNames().forEachRemaining(documentedResponseCodes::add);
    assertThat(responses.has(expectedResponseCode))
        .withFailMessage(
            "Expected %s response for %s %s but found %s",
            expectedResponseCode, method.toUpperCase(), path, documentedResponseCodes)
        .isTrue();

    JsonNode content = responses.path(expectedResponseCode).path("content");
    JsonNode schema = content.path("*/*").path("schema");
    if (schema.isMissingNode()) {
      schema = content.path("application/json").path("schema");
    }
    assertThat(schema.path("$ref").asText())
        .withFailMessage(
            "Unexpected response contract for %s %s %s",
            expectedResponseCode, method.toUpperCase(), path)
        .isEqualTo(expectedResponseRef);
  }

  private void assertBinaryOperationResponse(
      JsonNode root, String path, String method, String expectedResponseCode) {
    JsonNode operation = root.path("paths").path(path).path(method);
    assertThat(operation.isMissingNode())
        .withFailMessage("Missing %s %s from generated OpenAPI spec", method.toUpperCase(), path)
        .isFalse();

    JsonNode responses = operation.path("responses");
    assertThat(responses.has(expectedResponseCode))
        .withFailMessage(
            "Expected %s response for %s %s", expectedResponseCode, method.toUpperCase(), path)
        .isTrue();

    JsonNode response = responses.path(expectedResponseCode);
    JsonNode content = response.path("content");
    JsonNode schema = content.path("application/pdf").path("schema");
    if (schema.isMissingNode() || schema.isEmpty()) {
      schema = content.path("*/*").path("schema");
    }
    assertThat(schema.path("type").asText())
        .withFailMessage(
            "Expected binary response schema type for %s %s %s",
            expectedResponseCode, method.toUpperCase(), path)
        .isEqualTo("string");
    assertThat(schema.path("format").asText())
        .withFailMessage(
            "Expected binary response schema format for %s %s %s",
            expectedResponseCode, method.toUpperCase(), path)
        .isIn("binary", "byte", "");
  }

  private void assertQueryParameter(
      JsonNode root, String path, String method, String parameterName) {
    JsonNode parameters = root.path("paths").path(path).path(method).path("parameters");
    assertThat(parameters.isArray())
        .withFailMessage("Expected query/header parameters on %s %s", method.toUpperCase(), path)
        .isTrue();

    boolean found = false;
    for (JsonNode parameter : parameters) {
      if (parameterName.equals(parameter.path("name").asText())) {
        found = true;
        break;
      }
    }

    assertThat(found)
        .withFailMessage(
            "Expected parameter '%s' on %s %s", parameterName, method.toUpperCase(), path)
        .isTrue();
  }
}
