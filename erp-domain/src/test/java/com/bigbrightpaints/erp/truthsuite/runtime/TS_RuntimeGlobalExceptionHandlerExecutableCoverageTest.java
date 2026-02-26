package com.bigbrightpaints.erp.truthsuite.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.exception.GlobalExceptionHandler;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

@Tag("critical")
class TS_RuntimeGlobalExceptionHandlerExecutableCoverageTest {

    private static final String BULK_VARIANT_PATH = "/api/v1/accounting/catalog/products/bulk-variants";
    private static final String BULK_VARIANT_OPERATION = "catalog-bulk-variants";

    @Test
    void production_conflict_exposes_allowlisted_details_for_canonical_and_prefixed_paths() throws Exception {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        setActiveProfile(handler, "prod");

        ApplicationException ex = bulkVariantConflictException()
                .withDetail("generated", List.of(Map.of("sku", "HB-SKU-RED-1L")))
                .withDetail("conflicts", List.of(Map.of("sku", "HB-SKU-RED-1L", "reason", "SKU_ALREADY_EXISTS")))
                .withDetail("wouldCreate", List.of())
                .withDetail("created", List.of())
                .withDetail("internalLeak", Map.of("sql", "select * from products"));

        MockHttpServletRequest canonicalRequest = new MockHttpServletRequest();
        canonicalRequest.setRequestURI(BULK_VARIANT_PATH + "/");

        ResponseEntity<ApiResponse<Map<String, Object>>> canonicalResponse =
                handler.handleApplicationException(ex, canonicalRequest);

        assertThat(canonicalResponse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        Map<String, Object> canonicalDetails = requireDetails(canonicalResponse);
        assertThat(canonicalDetails)
                .containsOnlyKeys("generated", "conflicts", "wouldCreate", "created", "operation")
                .containsEntry("operation", BULK_VARIANT_OPERATION)
                .doesNotContainKey("internalLeak");

        MockHttpServletRequest prefixedRequest = new MockHttpServletRequest();
        prefixedRequest.setContextPath("/tenant");
        prefixedRequest.setServletPath("/erp");
        prefixedRequest.setPathInfo(BULK_VARIANT_PATH);
        prefixedRequest.setRequestURI("/tenant/erp" + BULK_VARIANT_PATH);

        ResponseEntity<ApiResponse<Map<String, Object>>> prefixedResponse =
                handler.handleApplicationException(ex, prefixedRequest);

        assertThat(prefixedResponse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        Map<String, Object> prefixedDetails = requireDetails(prefixedResponse);
        assertThat(prefixedDetails)
                .containsKey("conflicts")
                .containsEntry("operation", BULK_VARIANT_OPERATION);
    }

    @Test
    void production_conflict_keeps_details_redacted_for_non_matching_operation_or_path() throws Exception {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        setActiveProfile(handler, "prod");

        ApplicationException wrongOperation = bulkVariantConflictException()
                .withDetail("operation", "catalog-bulk-import")
                .withDetail("conflicts", List.of(Map.of("sku", "HB-SKU-RED-1L", "reason", "SKU_ALREADY_EXISTS")));

        MockHttpServletRequest canonicalRequest = new MockHttpServletRequest();
        canonicalRequest.setRequestURI(BULK_VARIANT_PATH);

        ResponseEntity<ApiResponse<Map<String, Object>>> wrongOperationResponse =
                handler.handleApplicationException(wrongOperation, canonicalRequest);
        assertThat(wrongOperationResponse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(requireBodyData(wrongOperationResponse)).doesNotContainKey("details");

        ApplicationException wrongPath = bulkVariantConflictException()
                .withDetail("conflicts", List.of(Map.of("sku", "HB-SKU-RED-1L", "reason", "SKU_ALREADY_EXISTS")));

        MockHttpServletRequest prefixedNonMatchingRequest = new MockHttpServletRequest();
        prefixedNonMatchingRequest.setContextPath("/tenant");
        prefixedNonMatchingRequest.setServletPath("/erp");
        prefixedNonMatchingRequest.setPathInfo("/api/v1/accounting/catalog/products");
        prefixedNonMatchingRequest.setRequestURI("/tenant/erp/api/v1/accounting/catalog/products");

        ResponseEntity<ApiResponse<Map<String, Object>>> wrongPathResponse =
                handler.handleApplicationException(wrongPath, prefixedNonMatchingRequest);
        assertThat(wrongPathResponse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(requireBodyData(wrongPathResponse)).doesNotContainKey("details");
    }

    @Test
    void non_production_keeps_original_details_and_empty_details_fail_closed() throws Exception {
        GlobalExceptionHandler devHandler = new GlobalExceptionHandler();
        setActiveProfile(devHandler, "dev");

        ApplicationException devException = bulkVariantConflictException()
                .withDetail("conflicts", List.of(Map.of("sku", "HB-SKU-RED-1L", "reason", "SKU_ALREADY_EXISTS")))
                .withDetail("internalLeak", Map.of("stack", "trace"));

        MockHttpServletRequest nonMatchingRequest = new MockHttpServletRequest();
        nonMatchingRequest.setRequestURI("/api/v1/accounting/catalog/products");

        ResponseEntity<ApiResponse<Map<String, Object>>> devResponse =
                devHandler.handleApplicationException(devException, nonMatchingRequest);

        assertThat(devResponse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        Map<String, Object> devDetails = requireDetails(devResponse);
        assertThat(devDetails)
                .containsKey("conflicts")
                .containsKey("internalLeak")
                .containsEntry("operation", BULK_VARIANT_OPERATION);

        GlobalExceptionHandler prodHandler = new GlobalExceptionHandler();
        setActiveProfile(prodHandler, "prod");

        ApplicationException emptyDetails = new ApplicationException(
                ErrorCode.CONCURRENCY_CONFLICT,
                "Bulk variant request has SKU conflicts. Resolve conflicts and retry.");
        MockHttpServletRequest matchingRequest = new MockHttpServletRequest();
        matchingRequest.setRequestURI(BULK_VARIANT_PATH);

        ResponseEntity<ApiResponse<Map<String, Object>>> prodResponse =
                prodHandler.handleApplicationException(emptyDetails, matchingRequest);

        assertThat(prodResponse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(requireBodyData(prodResponse)).doesNotContainKey("details");
    }

    @Test
    void helper_guards_cover_null_and_context_stripping_branches() throws Exception {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        setActiveProfile(handler, "prod");

        @SuppressWarnings("unchecked")
        Map<String, Object> noDetails = ReflectionTestUtils.invokeMethod(
                handler,
                "resolveResponseDetails",
                bulkVariantConflictException(),
                request(BULK_VARIANT_PATH),
                null);
        assertThat(noDetails).isEmpty();

        @SuppressWarnings("unchecked")
        Map<String, Object> allowlistedSubset = ReflectionTestUtils.invokeMethod(
                handler,
                "sanitizeBulkVariantConflictDetails",
                bulkVariantConflictException(),
                request(BULK_VARIANT_PATH),
                Map.of("operation", BULK_VARIANT_OPERATION));
        assertThat(allowlistedSubset).containsOnlyKeys("operation");

        Boolean nullExceptionRejected = ReflectionTestUtils.invokeMethod(
                handler,
                "isBulkVariantConflictDetailsSafeToExpose",
                null,
                request(BULK_VARIANT_PATH),
                Map.of("operation", BULK_VARIANT_OPERATION));
        assertThat(nullExceptionRejected).isFalse();

        Boolean nullRequestRejected = ReflectionTestUtils.invokeMethod(
                handler,
                "isBulkVariantConflictDetailsSafeToExpose",
                bulkVariantConflictException(),
                null,
                Map.of("operation", BULK_VARIANT_OPERATION));
        assertThat(nullRequestRejected).isFalse();

        ApplicationException wrongCode = new ApplicationException(
                ErrorCode.VALIDATION_INVALID_INPUT,
                "invalid")
                .withDetail("operation", BULK_VARIANT_OPERATION);
        Boolean wrongCodeRejected = ReflectionTestUtils.invokeMethod(
                handler,
                "isBulkVariantConflictDetailsSafeToExpose",
                wrongCode,
                request(BULK_VARIANT_PATH),
                wrongCode.getDetails());
        assertThat(wrongCodeRejected).isFalse();

        @SuppressWarnings("unchecked")
        List<String> emptyPaths = ReflectionTestUtils.invokeMethod(
                handler,
                "resolveNormalizedRequestPaths",
                (Object) null);
        assertThat(emptyPaths).isEmpty();

        String pathInfoFallback = ReflectionTestUtils.invokeMethod(
                handler,
                "joinServletPathAndPathInfo",
                "/",
                BULK_VARIANT_PATH);
        assertThat(pathInfoFallback).isEqualTo(BULK_VARIANT_PATH);

        String emptyUri = ReflectionTestUtils.invokeMethod(
                handler,
                "stripContextPath",
                "",
                "/tenant");
        assertThat(emptyUri).isEmpty();

        String rootPath = ReflectionTestUtils.invokeMethod(
                handler,
                "stripContextPath",
                "/tenant",
                "/tenant");
        assertThat(rootPath).isEqualTo("/");

        String unchangedUri = ReflectionTestUtils.invokeMethod(
                handler,
                "stripContextPath",
                "/api/v1/accounting/catalog/products",
                "/tenant");
        assertThat(unchangedUri).isEqualTo("/api/v1/accounting/catalog/products");

        Boolean nullPathRejected = ReflectionTestUtils.invokeMethod(
                handler,
                "matchesEndpointPath",
                "",
                BULK_VARIANT_PATH);
        assertThat(nullPathRejected).isFalse();

        Boolean blankEndpointRejected = ReflectionTestUtils.invokeMethod(
                handler,
                "matchesEndpointPath",
                "/erp" + BULK_VARIANT_PATH,
                "");
        assertThat(blankEndpointRejected).isFalse();

        Boolean prefixedPathAccepted = ReflectionTestUtils.invokeMethod(
                handler,
                "matchesEndpointPath",
                "/erp" + BULK_VARIANT_PATH,
                BULK_VARIANT_PATH);
        assertThat(prefixedPathAccepted).isTrue();

        String normalizedWithoutSlash = ReflectionTestUtils.invokeMethod(
                handler,
                "normalizeEndpointPath",
                "api/v1/accounting/catalog/products");
        assertThat(normalizedWithoutSlash).isEqualTo("/api/v1/accounting/catalog/products");

        String normalizedRoot = ReflectionTestUtils.invokeMethod(
                handler,
                "normalizeEndpointPath",
                "/");
        assertThat(normalizedRoot).isEqualTo("/");

        String normalizedTrimmed = ReflectionTestUtils.invokeMethod(
                handler,
                "normalizeEndpointPath",
                " /api/v1/accounting/catalog/products/bulk-variants/ ");
        assertThat(normalizedTrimmed).isEqualTo(BULK_VARIANT_PATH);
    }

    private static ApplicationException bulkVariantConflictException() {
        return new ApplicationException(
                ErrorCode.CONCURRENCY_CONFLICT,
                "Bulk variant request has SKU conflicts. Resolve conflicts and retry.")
                .withDetail("operation", BULK_VARIANT_OPERATION);
    }

    private static Map<String, Object> requireBodyData(ResponseEntity<ApiResponse<Map<String, Object>>> response) {
        ApiResponse<Map<String, Object>> body = response.getBody();
        assertThat(body).isNotNull();
        Map<String, Object> data = body.data();
        assertThat(data).isNotNull();
        return data;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> requireDetails(ResponseEntity<ApiResponse<Map<String, Object>>> response) {
        Map<String, Object> data = requireBodyData(response);
        assertThat(data).containsKey("details");
        assertThat(data.get("details")).isInstanceOf(Map.class);
        return (Map<String, Object>) data.get("details");
    }

    private static void setActiveProfile(GlobalExceptionHandler handler, String value) throws Exception {
        Field field = GlobalExceptionHandler.class.getDeclaredField("activeProfile");
        field.setAccessible(true);
        field.set(handler, value);
    }

    private static MockHttpServletRequest request(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI(uri);
        return request;
    }
}
