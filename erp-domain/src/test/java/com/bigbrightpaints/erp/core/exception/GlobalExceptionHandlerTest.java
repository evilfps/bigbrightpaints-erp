package com.bigbrightpaints.erp.core.exception;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.audit.IntegrationFailureMetadataSchema;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class GlobalExceptionHandlerTest {

    @Test
    void productionProfilesHideDetails() throws Exception {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        setActiveProfile(handler, "dev, production");

        ApplicationException ex = new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT, "invalid")
                .withDetail("field", "value");

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/test");

        ResponseEntity<ApiResponse<Map<String, Object>>> response = handler.handleApplicationException(ex, request);

        ApiResponse<Map<String, Object>> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.data()).doesNotContainKey("details");
    }

    @Test
    void nonProductionProfilesIncludeDetails() throws Exception {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        setActiveProfile(handler, "dev,qa");

        ApplicationException ex = new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT, "invalid")
                .withDetail("field", "value");

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/test");

        ResponseEntity<ApiResponse<Map<String, Object>>> response = handler.handleApplicationException(ex, request);

        ApiResponse<Map<String, Object>> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.data()).containsKey("details");
        assertThat(body.data().get("details"))
                .isInstanceOf(Map.class);
    }

    @Test
    void blankProfilesFailClosedAndHideDetails() throws Exception {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        setActiveProfile(handler, "   ");

        ApplicationException ex = new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT, "invalid")
                .withDetail("field", "value");

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/test");

        ResponseEntity<ApiResponse<Map<String, Object>>> response = handler.handleApplicationException(ex, request);

        ApiResponse<Map<String, Object>> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.data()).doesNotContainKey("details");
    }

    @Test
    void productionBulkVariantConflictIncludesStructuredDetails() throws Exception {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        setActiveProfile(handler, "prod");

        ApplicationException ex = new ApplicationException(
                ErrorCode.CONCURRENCY_CONFLICT,
                "Catalog product entry has SKU conflicts. Resolve conflicts and retry.")
                .withDetail("operation", "catalog-product-entry")
                .withDetail("generated", List.of(Map.of("sku", "HB-SKU-RED-1L")))
                .withDetail("conflicts", List.of(Map.of("sku", "HB-SKU-RED-1L", "reason", "SKU_ALREADY_EXISTS")))
                .withDetail("wouldCreate", List.of())
                .withDetail("created", List.of());

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/v1/catalog/products");

        ResponseEntity<ApiResponse<Map<String, Object>>> response = handler.handleApplicationException(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        ApiResponse<Map<String, Object>> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.data()).containsKey("details");
        assertThat(body.data().get("details")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) body.data().get("details");
        assertThat(details).containsOnlyKeys("generated", "conflicts", "wouldCreate", "created", "operation");
    }

    @Test
    void productionBulkVariantConflictStripsUnknownDetailKeys() throws Exception {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        setActiveProfile(handler, "prod");

        ApplicationException ex = new ApplicationException(
                ErrorCode.CONCURRENCY_CONFLICT,
                "Catalog product entry has SKU conflicts. Resolve conflicts and retry.")
                .withDetail("operation", "catalog-product-entry")
                .withDetail("generated", List.of(Map.of("sku", "HB-SKU-RED-1L")))
                .withDetail("conflicts", List.of(Map.of("sku", "HB-SKU-RED-1L", "reason", "SKU_ALREADY_EXISTS")))
                .withDetail("wouldCreate", List.of())
                .withDetail("created", List.of())
                .withDetail("internalLeak", Map.of("sql", "select * from products"));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/v1/catalog/products");

        ResponseEntity<ApiResponse<Map<String, Object>>> response = handler.handleApplicationException(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        ApiResponse<Map<String, Object>> body = response.getBody();
        assertThat(body).isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) body.data().get("details");
        assertThat(details)
                .containsOnlyKeys("generated", "conflicts", "wouldCreate", "created", "operation")
                .doesNotContainKey("internalLeak");
    }

    @Test
    void productionBulkVariantConflictWithContextPathStillIncludesDetails() throws Exception {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        setActiveProfile(handler, "prod");

        ApplicationException ex = new ApplicationException(
                ErrorCode.CONCURRENCY_CONFLICT,
                "Catalog product entry has SKU conflicts. Resolve conflicts and retry.")
                .withDetail("operation", "catalog-product-entry")
                .withDetail("conflicts", List.of(Map.of("sku", "HB-SKU-RED-1L", "reason", "SKU_ALREADY_EXISTS")))
                .withDetail("wouldCreate", List.of())
                .withDetail("created", List.of());

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContextPath("/tenant-prefix");
        request.setRequestURI("/tenant-prefix/api/v1/catalog/products");

        ResponseEntity<ApiResponse<Map<String, Object>>> response = handler.handleApplicationException(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        ApiResponse<Map<String, Object>> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.data()).containsKey("details");
    }

    @Test
    void productionCatalogBulkVariantsConflictIncludesStructuredDetails() throws Exception {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        setActiveProfile(handler, "prod");

        ApplicationException ex = new ApplicationException(
                ErrorCode.CONCURRENCY_CONFLICT,
                "Bulk variant creation has SKU conflicts. Resolve conflicts and retry.")
                .withDetail("operation", "catalog-bulk-variants")
                .withDetail("generated", List.of(Map.of("sku", "HB-SKU-RED-1L")))
                .withDetail("conflicts", List.of(Map.of("sku", "HB-SKU-RED-1L", "reason", "SKU_ALREADY_EXISTS")))
                .withDetail("wouldCreate", List.of())
                .withDetail("created", List.of())
                .withDetail("internalLeak", Map.of("sql", "select * from products"));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/v1/catalog/products/bulk-variants");

        ResponseEntity<ApiResponse<Map<String, Object>>> response = handler.handleApplicationException(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        ApiResponse<Map<String, Object>> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.data()).containsKey("details");
        assertThat(body.data().get("details")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) body.data().get("details");
        assertThat(details)
                .containsOnlyKeys("generated", "conflicts", "wouldCreate", "created", "operation")
                .containsEntry("operation", "catalog-bulk-variants")
                .doesNotContainKey("internalLeak");
    }

    @Test
    void productionBulkVariantConflictNonMatchingUriRemainsRedacted() throws Exception {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        setActiveProfile(handler, "prod");

        ApplicationException ex = new ApplicationException(
                ErrorCode.CONCURRENCY_CONFLICT,
                "Catalog product entry has SKU conflicts. Resolve conflicts and retry.")
                .withDetail("operation", "catalog-product-entry")
                .withDetail("conflicts", List.of(Map.of("sku", "HB-SKU-RED-1L", "reason", "SKU_ALREADY_EXISTS")))
                .withDetail("wouldCreate", List.of())
                .withDetail("created", List.of());

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/v1/catalog/brands");

        ResponseEntity<ApiResponse<Map<String, Object>>> response = handler.handleApplicationException(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        ApiResponse<Map<String, Object>> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.data()).doesNotContainKey("details");
    }

    @Test
    void illegalArgumentInProductionReturnsReadableReason() throws Exception {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        setActiveProfile(handler, "prod,seed");

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/v1/hr/employees");

        IllegalArgumentException ex = new IllegalArgumentException(
                "No enum constant com.bigbrightpaints.erp.modules.hr.domain.Employee.PaymentSchedule.");

        ResponseEntity<ApiResponse<Map<String, Object>>> response = handler.handleIllegalArgument(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ApiResponse<Map<String, Object>> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.message()).isEqualTo("Invalid option provided");
        assertThat(body.data()).containsEntry("reason", "Invalid option provided");
    }

    @Test
    void malformedJson_isAuditedAndReturnsValidationPayload() throws Exception {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        setActiveProfile(handler, "dev");
        AuditService auditService = mock(AuditService.class);
        setAuditService(handler, auditService);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/accounting/settlements/suppliers");
        HttpMessageNotReadableException ex = new HttpMessageNotReadableException(
                "JSON parse error", new IllegalArgumentException("Unrecognized field \"payments\""));

        ResponseEntity<Object> response = handler.handleHttpMessageNotReadable(
                ex, new HttpHeaders(), HttpStatus.BAD_REQUEST, new ServletWebRequest(request));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isInstanceOf(ApiResponse.class);
        @SuppressWarnings("unchecked")
        ApiResponse<Map<String, Object>> body = (ApiResponse<Map<String, Object>>) response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.message()).isEqualTo("Failed to read request");
        assertThat(body.data()).containsEntry("code", ErrorCode.VALIDATION_INVALID_INPUT.getCode());
        assertThat(body.data()).containsEntry("reason", "Failed to read request");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditService).logFailure(eq(AuditEvent.INTEGRATION_FAILURE), metadataCaptor.capture());
        Map<String, String> metadata = metadataCaptor.getValue();
        assertThat(metadata).containsEntry("category", "request-parse");
        assertThat(metadata).containsEntry(IntegrationFailureMetadataSchema.KEY_FAILURE_CODE, "MALFORMED_REQUEST_PAYLOAD");
        assertThat(metadata).containsEntry(IntegrationFailureMetadataSchema.KEY_ERROR_CATEGORY, "VALIDATION");
        assertThat(metadata).containsEntry(IntegrationFailureMetadataSchema.KEY_ALERT_ROUTING_VERSION, "INTEGRATION_FAILURE_V1");
        assertThat(metadata).containsEntry(IntegrationFailureMetadataSchema.KEY_ALERT_ROUTE, "SEV3_TICKET");
        assertThat(metadata).containsEntry("requestMethod", "POST");
        assertThat(metadata).containsEntry("requestPath", "/api/v1/accounting/settlements/suppliers");
    }

    @Test
    void malformedJson_auditDetailIsSanitized() throws Exception {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        setActiveProfile(handler, "dev");
        AuditService auditService = mock(AuditService.class);
        setAuditService(handler, auditService);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/accounting/settlements/suppliers");
        HttpMessageNotReadableException ex = new HttpMessageNotReadableException(
                "JSON parse error", new IllegalArgumentException("\nUnrecognized\t field \"payments\"\r\n"));

        handler.handleHttpMessageNotReadable(
                ex, new HttpHeaders(), HttpStatus.BAD_REQUEST, new ServletWebRequest(request));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditService).logFailure(eq(AuditEvent.INTEGRATION_FAILURE), metadataCaptor.capture());
        Map<String, String> metadata = metadataCaptor.getValue();
        assertThat(metadata.get("detail"))
                .isEqualTo("Unrecognized field \"payments\"")
                .doesNotContain("\n")
                .doesNotContain("\r")
                .doesNotContain("\t");
    }

    @Test
    void malformedJson_auditDetailOmittedWhenSanitizedEmpty() throws Exception {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        setActiveProfile(handler, "dev");
        AuditService auditService = mock(AuditService.class);
        setAuditService(handler, auditService);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/accounting/settlements/suppliers");
        HttpMessageNotReadableException ex = new HttpMessageNotReadableException(
                "JSON parse error", new IllegalArgumentException("\u0001\u0002"));

        handler.handleHttpMessageNotReadable(
                ex, new HttpHeaders(), HttpStatus.BAD_REQUEST, new ServletWebRequest(request));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditService).logFailure(eq(AuditEvent.INTEGRATION_FAILURE), metadataCaptor.capture());
        Map<String, String> metadata = metadataCaptor.getValue();
        assertThat(metadata)
                .doesNotContainKey("detail")
                .containsEntry("category", "request-parse")
                .containsEntry(IntegrationFailureMetadataSchema.KEY_FAILURE_CODE, "MALFORMED_REQUEST_PAYLOAD")
                .containsEntry(IntegrationFailureMetadataSchema.KEY_ERROR_CATEGORY, "VALIDATION")
                .containsEntry(IntegrationFailureMetadataSchema.KEY_ALERT_ROUTING_VERSION, "INTEGRATION_FAILURE_V1")
                .containsEntry(IntegrationFailureMetadataSchema.KEY_ALERT_ROUTE, "SEV3_TICKET");
    }

    @Test
    void settlementValidationFailure_isAuditedWithDeterministicTelemetry() throws Exception {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        setActiveProfile(handler, "dev");
        AuditService auditService = mock(AuditService.class);
        setAuditService(handler, auditService);

        ApplicationException ex = new ApplicationException(
                ErrorCode.VALIDATION_INVALID_INPUT,
                "Settlement allocation exceeds invoice outstanding amount");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/accounting/settlements/dealers");

        ResponseEntity<ApiResponse<Map<String, Object>>> response = handler.handleApplicationException(ex, request);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditService).logFailure(eq(AuditEvent.INTEGRATION_FAILURE), metadataCaptor.capture());
        Map<String, String> metadata = metadataCaptor.getValue();
        assertThat(metadata)
                .containsEntry("category", "settlement-failure")
                .containsEntry(IntegrationFailureMetadataSchema.KEY_FAILURE_CODE, "SETTLEMENT_OPERATION_FAILED")
                .containsEntry(IntegrationFailureMetadataSchema.KEY_ERROR_CATEGORY, "VALIDATION")
                .containsEntry("errorCode", ErrorCode.VALIDATION_INVALID_INPUT.getCode())
                .containsEntry("settlementType", "DEALER")
                .containsEntry(IntegrationFailureMetadataSchema.KEY_ALERT_ROUTING_VERSION, "INTEGRATION_FAILURE_V1")
                .containsEntry(IntegrationFailureMetadataSchema.KEY_ALERT_ROUTE, "SEV3_TICKET")
                .containsEntry("requestPath", "/api/v1/accounting/settlements/dealers");
    }

    @Test
    void settlementConcurrencyFailure_isAuditedWithSev2Route() throws Exception {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        setActiveProfile(handler, "dev");
        AuditService auditService = mock(AuditService.class);
        setAuditService(handler, auditService);

        ApplicationException ex = new ApplicationException(
                ErrorCode.CONCURRENCY_CONFLICT,
                "Idempotency key already used");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/accounting/settlements/suppliers");

        ResponseEntity<ApiResponse<Map<String, Object>>> response = handler.handleApplicationException(ex, request);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditService).logFailure(eq(AuditEvent.INTEGRATION_FAILURE), metadataCaptor.capture());
        Map<String, String> metadata = metadataCaptor.getValue();
        assertThat(metadata)
                .containsEntry("category", "settlement-failure")
                .containsEntry(IntegrationFailureMetadataSchema.KEY_FAILURE_CODE, "SETTLEMENT_OPERATION_FAILED")
                .containsEntry(IntegrationFailureMetadataSchema.KEY_ERROR_CATEGORY, "CONCURRENCY")
                .containsEntry("errorCode", ErrorCode.CONCURRENCY_CONFLICT.getCode())
                .containsEntry("settlementType", "SUPPLIER")
                .containsEntry(IntegrationFailureMetadataSchema.KEY_ALERT_ROUTING_VERSION, "INTEGRATION_FAILURE_V1")
                .containsEntry(IntegrationFailureMetadataSchema.KEY_ALERT_ROUTE, "SEV2_URGENT")
                .containsEntry("requestPath", "/api/v1/accounting/settlements/suppliers");
    }

    @Test
    void settlementValidationFailure_propagatesAllowlistedAllocationDetails() throws Exception {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        setActiveProfile(handler, "dev");
        AuditService auditService = mock(AuditService.class);
        setAuditService(handler, auditService);

        ApplicationException ex = new ApplicationException(
                ErrorCode.VALIDATION_INVALID_INPUT,
                "Settlement allocation exceeds invoice outstanding amount")
                .withDetail(IntegrationFailureMetadataSchema.KEY_INVOICE_ID, 42L)
                .withDetail(IntegrationFailureMetadataSchema.KEY_OUTSTANDING_AMOUNT, "120.00")
                .withDetail(IntegrationFailureMetadataSchema.KEY_APPLIED_AMOUNT, "121.00")
                .withDetail("internalLeak", "should-not-propagate");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/accounting/settlements/dealers");

        handler.handleApplicationException(ex, request);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditService).logFailure(eq(AuditEvent.INTEGRATION_FAILURE), metadataCaptor.capture());
        Map<String, String> metadata = metadataCaptor.getValue();
        assertThat(metadata)
                .containsEntry(IntegrationFailureMetadataSchema.KEY_INVOICE_ID, "42")
                .containsEntry(IntegrationFailureMetadataSchema.KEY_OUTSTANDING_AMOUNT, "120.00")
                .containsEntry(IntegrationFailureMetadataSchema.KEY_APPLIED_AMOUNT, "121.00")
                .doesNotContainKey("internalLeak");
    }

    @Test
    void settlementConcurrencyFailure_propagatesAllowlistedIdempotencyDetails() throws Exception {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        setActiveProfile(handler, "dev");
        AuditService auditService = mock(AuditService.class);
        setAuditService(handler, auditService);

        ApplicationException ex = new ApplicationException(
                ErrorCode.INTERNAL_CONCURRENCY_FAILURE,
                "Supplier settlement idempotency key is reserved but allocation not found")
                .withDetail(IntegrationFailureMetadataSchema.KEY_IDEMPOTENCY_KEY, "SUP-SETTLE-KEY-1")
                .withDetail(IntegrationFailureMetadataSchema.KEY_PARTNER_TYPE, "SUPPLIER")
                .withDetail(IntegrationFailureMetadataSchema.KEY_PARTNER_ID, 55L);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/accounting/settlements/suppliers");

        handler.handleApplicationException(ex, request);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditService).logFailure(eq(AuditEvent.INTEGRATION_FAILURE), metadataCaptor.capture());
        Map<String, String> metadata = metadataCaptor.getValue();
        assertThat(metadata)
                .containsEntry(IntegrationFailureMetadataSchema.KEY_IDEMPOTENCY_KEY, "SUP-SETTLE-KEY-1")
                .containsEntry(IntegrationFailureMetadataSchema.KEY_PARTNER_TYPE, "SUPPLIER")
                .containsEntry(IntegrationFailureMetadataSchema.KEY_PARTNER_ID, "55");
    }

    @Test
    void settlementConcurrencyFailure_sanitizesAllowlistedControlCharacters() throws Exception {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        setActiveProfile(handler, "dev");
        AuditService auditService = mock(AuditService.class);
        setAuditService(handler, auditService);

        ApplicationException ex = new ApplicationException(
                ErrorCode.INTERNAL_CONCURRENCY_FAILURE,
                "Supplier settlement idempotency key is reserved but allocation not found")
                .withDetail(IntegrationFailureMetadataSchema.KEY_IDEMPOTENCY_KEY, "\nSUP-SETTLE-KEY-1\t")
                .withDetail(IntegrationFailureMetadataSchema.KEY_PARTNER_TYPE, "SUPPLIER\r\n")
                .withDetail(IntegrationFailureMetadataSchema.KEY_PARTNER_ID, " 55 ");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/accounting/settlements/suppliers");

        handler.handleApplicationException(ex, request);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditService).logFailure(eq(AuditEvent.INTEGRATION_FAILURE), metadataCaptor.capture());
        Map<String, String> metadata = metadataCaptor.getValue();
        assertThat(metadata)
                .containsEntry(IntegrationFailureMetadataSchema.KEY_IDEMPOTENCY_KEY, "SUP-SETTLE-KEY-1")
                .containsEntry(IntegrationFailureMetadataSchema.KEY_PARTNER_TYPE, "SUPPLIER")
                .containsEntry(IntegrationFailureMetadataSchema.KEY_PARTNER_ID, "55");
    }

    @Test
    void settlementConcurrencyFailure_truncatesSanitizedMetadataToLimit() throws Exception {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        setActiveProfile(handler, "dev");
        AuditService auditService = mock(AuditService.class);
        setAuditService(handler, auditService);

        String rawIdempotencyKey = "\n" + "K".repeat(520) + "\t";
        ApplicationException ex = new ApplicationException(
                ErrorCode.INTERNAL_CONCURRENCY_FAILURE,
                "Supplier settlement idempotency key is reserved but allocation not found")
                .withDetail(IntegrationFailureMetadataSchema.KEY_IDEMPOTENCY_KEY, rawIdempotencyKey);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/accounting/settlements/suppliers");

        handler.handleApplicationException(ex, request);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditService).logFailure(eq(AuditEvent.INTEGRATION_FAILURE), metadataCaptor.capture());
        Map<String, String> metadata = metadataCaptor.getValue();
        assertThat(metadata.get(IntegrationFailureMetadataSchema.KEY_IDEMPOTENCY_KEY))
                .hasSize(500)
                .doesNotContain("\n")
                .doesNotContain("\t");
    }

    @Test
    void settlementFailureAllowlist_usesSchemaKeyVocabulary() throws Exception {
        Field field = GlobalExceptionHandler.class.getDeclaredField("SETTLEMENT_FAILURE_DETAIL_ALLOWLIST");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Set<String> allowlist = (Set<String>) field.get(null);
        assertThat(allowlist).containsExactlyInAnyOrder(
                IntegrationFailureMetadataSchema.KEY_IDEMPOTENCY_KEY,
                IntegrationFailureMetadataSchema.KEY_PARTNER_TYPE,
                IntegrationFailureMetadataSchema.KEY_PARTNER_ID,
                IntegrationFailureMetadataSchema.KEY_INVOICE_ID,
                IntegrationFailureMetadataSchema.KEY_PURCHASE_ID,
                IntegrationFailureMetadataSchema.KEY_OUTSTANDING_AMOUNT,
                IntegrationFailureMetadataSchema.KEY_APPLIED_AMOUNT,
                IntegrationFailureMetadataSchema.KEY_ALLOCATION_COUNT);
    }

    private static void setActiveProfile(GlobalExceptionHandler handler, String value) throws Exception {
        Field field = GlobalExceptionHandler.class.getDeclaredField("activeProfile");
        field.setAccessible(true);
        field.set(handler, value);
    }

    private static void setAuditService(GlobalExceptionHandler handler, AuditService auditService) throws Exception {
        Field field = GlobalExceptionHandler.class.getDeclaredField("auditService");
        field.setAccessible(true);
        field.set(handler, auditService);
    }
}
