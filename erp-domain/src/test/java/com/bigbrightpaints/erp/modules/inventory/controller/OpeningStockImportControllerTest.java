package com.bigbrightpaints.erp.modules.inventory.controller;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.modules.inventory.dto.OpeningStockImportHistoryItem;
import com.bigbrightpaints.erp.modules.inventory.dto.OpeningStockImportResponse;
import com.bigbrightpaints.erp.modules.inventory.service.OpeningStockImportService;
import com.bigbrightpaints.erp.modules.production.dto.SkuReadinessDto;
import com.bigbrightpaints.erp.modules.production.service.SkuReadinessService;
import com.bigbrightpaints.erp.shared.dto.PageResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
@Tag("critical")
class OpeningStockImportControllerTest {

    @Mock
    private OpeningStockImportService openingStockImportService;
    @Mock
    private SkuReadinessService skuReadinessService;

    @Test
    void importOpeningStock_delegatesCanonicalIdempotencyKeyToService() {
        OpeningStockImportController controller = new OpeningStockImportController(openingStockImportService, skuReadinessService);
        MockMultipartFile file = csvFile();
        OpeningStockImportResponse response = new OpeningStockImportResponse(
                batchKey("import-key"),
                1,
                1,
                0,
                List.of(),
                List.of());
        when(openingStockImportService.importOpeningStock(file, "import-key", batchKey("import-key"))).thenReturn(response);

        controller.importOpeningStock("import-key", batchKey("import-key"), file, authentication("ROLE_ADMIN"));

        verify(openingStockImportService).importOpeningStock(file, "import-key", batchKey("import-key"));
    }

    @Test
    void importOpeningStock_fallsBackToIdempotencyKeyWhenBatchKeyIsMissing() {
        OpeningStockImportController controller = new OpeningStockImportController(openingStockImportService, skuReadinessService);
        MockMultipartFile file = csvFile();
        OpeningStockImportResponse response = new OpeningStockImportResponse(
                batchKey("legacy-key"),
                1,
                1,
                0,
                List.of(),
                List.of());
        when(openingStockImportService.importOpeningStock(file, "legacy-key", "legacy-key")).thenReturn(response);

        controller.importOpeningStock("legacy-key", null, file, authentication("ROLE_ADMIN"));

        verify(openingStockImportService).importOpeningStock(file, "legacy-key", "legacy-key");
    }

    @Test
    void importOpeningStock_sanitizesReadinessForFactoryUsers() {
        OpeningStockImportController controller = new OpeningStockImportController(openingStockImportService, skuReadinessService);
        MockMultipartFile file = csvFile();
        SkuReadinessDto rawReadiness = readiness(
                List.of("WIP_ACCOUNT_MISSING"),
                List.of("WIP_ACCOUNT_MISSING"),
                List.of("WIP_ACCOUNT_MISSING"),
                List.of());
        SkuReadinessDto sanitizedReadiness = readiness(
                List.of("ACCOUNTING_CONFIGURATION_REQUIRED"),
                List.of("ACCOUNTING_CONFIGURATION_REQUIRED"),
                List.of("ACCOUNTING_CONFIGURATION_REQUIRED"),
                List.of());
        OpeningStockImportResponse response = new OpeningStockImportResponse(
                batchKey("factory-key"),
                0,
                0,
                1,
                List.of(new OpeningStockImportResponse.ImportRowResult(1L, "FG-1", "FINISHED_GOOD", rawReadiness)),
                List.of(new OpeningStockImportResponse.ImportError(
                        2L,
                        "SKU FG-2 is not inventory-ready for opening stock: WIP_ACCOUNT_MISSING",
                        "FG-2",
                        "FINISHED_GOOD",
                        rawReadiness))
        );
        when(openingStockImportService.importOpeningStock(file, "factory-key", batchKey("factory-key"))).thenReturn(response);
        when(skuReadinessService.sanitizeForCatalogViewer(rawReadiness, false)).thenReturn(sanitizedReadiness);

        OpeningStockImportResponse payload = controller
                .importOpeningStock("factory-key", batchKey("factory-key"), file, authentication("ROLE_FACTORY"))
                .getBody()
                .data();

        assertThat(payload.results().getFirst().readiness()).isEqualTo(sanitizedReadiness);
        assertThat(payload.errors().getFirst().readiness()).isEqualTo(sanitizedReadiness);
        assertThat(payload.errors().getFirst().message())
                .isEqualTo("SKU FG-2 is not inventory-ready for opening stock: ACCOUNTING_CONFIGURATION_REQUIRED");
        verify(skuReadinessService, org.mockito.Mockito.times(2)).sanitizeForCatalogViewer(rawReadiness, false);
    }

    @Test
    void importOpeningStock_keepsAccountingMetadataForAccountingUsers() {
        OpeningStockImportController controller = new OpeningStockImportController(openingStockImportService, skuReadinessService);
        MockMultipartFile file = csvFile();
        SkuReadinessDto rawReadiness = readiness(
                List.of("WIP_ACCOUNT_MISSING"),
                List.of("WIP_ACCOUNT_MISSING"),
                List.of("WIP_ACCOUNT_MISSING"),
                List.of());
        OpeningStockImportResponse response = new OpeningStockImportResponse(
                batchKey("accounting-key"),
                0,
                0,
                1,
                List.of(new OpeningStockImportResponse.ImportRowResult(1L, "FG-1", "FINISHED_GOOD", rawReadiness)),
                List.of(new OpeningStockImportResponse.ImportError(
                        2L,
                        "SKU FG-2 is not inventory-ready for opening stock: WIP_ACCOUNT_MISSING",
                        "FG-2",
                        "FINISHED_GOOD",
                        rawReadiness))
        );
        when(openingStockImportService.importOpeningStock(file, "accounting-key", batchKey("accounting-key"))).thenReturn(response);

        OpeningStockImportResponse payload = controller
                .importOpeningStock("accounting-key", batchKey("accounting-key"), file, authentication("ROLE_ACCOUNTING"))
                .getBody()
                .data();

        assertThat(payload).isSameAs(response);
        verifyNoInteractions(skuReadinessService);
    }

    @Test
    void importOpeningStock_returnsNullPayloadWhenServiceReturnsNull() {
        OpeningStockImportController controller = new OpeningStockImportController(openingStockImportService, skuReadinessService);
        MockMultipartFile file = csvFile();
        when(openingStockImportService.importOpeningStock(file, "null-key", batchKey("null-key"))).thenReturn(null);

        OpeningStockImportResponse payload = controller
                .importOpeningStock("null-key", batchKey("null-key"), file, null)
                .getBody()
                .data();

        assertThat(payload).isNull();
        verifyNoInteractions(skuReadinessService);
    }

    @Test
    void importOpeningStock_sanitizesAccountingSetupExceptionsForFactoryUsers() {
        OpeningStockImportController controller = new OpeningStockImportController(openingStockImportService, skuReadinessService);
        MockMultipartFile file = csvFile();
        ApplicationException failure = new ApplicationException(
                ErrorCode.VALIDATION_INVALID_REFERENCE,
                "Opening balance account OPEN-BAL is missing; complete company defaults and repair seeded accounts before importing opening stock");
        when(openingStockImportService.importOpeningStock(file, "factory-failure", batchKey("factory-failure"))).thenThrow(failure);

        assertThatThrownBy(() -> controller.importOpeningStock(
                "factory-failure",
                batchKey("factory-failure"),
                file,
                authentication("ROLE_FACTORY")))
                .isInstanceOf(ApplicationException.class)
                .extracting("errorCode", "userMessage")
                .containsExactly(
                        ErrorCode.VALIDATION_INVALID_REFERENCE,
                        "Opening stock import requires accounting configuration to be completed before retrying.");
        verifyNoInteractions(skuReadinessService);
    }

    @Test
    void importOpeningStock_keepsAccountingSetupExceptionsForAccountingUsers() {
        OpeningStockImportController controller = new OpeningStockImportController(openingStockImportService, skuReadinessService);
        MockMultipartFile file = csvFile();
        ApplicationException failure = new ApplicationException(
                ErrorCode.VALIDATION_INVALID_REFERENCE,
                "Opening balance account OPEN-BAL must be an equity account");
        when(openingStockImportService.importOpeningStock(file, "accounting-failure", batchKey("accounting-failure"))).thenThrow(failure);

        assertThatThrownBy(() -> controller.importOpeningStock(
                "accounting-failure",
                batchKey("accounting-failure"),
                file,
                authentication("ROLE_ACCOUNTING")))
                .isSameAs(failure);
        verifyNoInteractions(skuReadinessService);
    }

    @Test
    void importHistory_delegatesToServiceWithPagination() {
        OpeningStockImportController controller = new OpeningStockImportController(openingStockImportService, skuReadinessService);
        PageResponse<OpeningStockImportHistoryItem> page = PageResponse.of(
                List.of(new OpeningStockImportHistoryItem(
                        9L,
                        "idem-1",
                        "OPEN-STOCK-ACME-001",
                        "OPEN-STOCK-ACME-REF-001",
                        "opening.csv",
                        55L,
                        2,
                        1,
                        1,
                        0,
                        Instant.parse("2026-02-03T10:15:30Z")
                )),
                1,
                0,
                20
        );
        when(openingStockImportService.listImportHistory(0, 20)).thenReturn(page);

        controller.importHistory(0, 20);

        verify(openingStockImportService).listImportHistory(0, 20);
    }

    @Test
    void stageFromOpeningStockErrorMessage_detectsSupportedStagesOnly() {
        OpeningStockImportController controller = new OpeningStockImportController(openingStockImportService, skuReadinessService);

        assertThat(stageFromOpeningStockErrorMessage(controller, null)).isNull();
        assertThat(stageFromOpeningStockErrorMessage(controller, "plain validation failure")).isNull();
        assertThat(stageFromOpeningStockErrorMessage(
                controller,
                "SKU FG-1 is not catalog-ready for opening stock: MISSING")).isEqualTo("catalog");
        assertThat(stageFromOpeningStockErrorMessage(
                controller,
                "SKU FG-1 is not inventory-ready for opening stock: MISSING")).isEqualTo("inventory");
        assertThat(stageFromOpeningStockErrorMessage(
                controller,
                "SKU FG-1 is not production-ready for opening stock: MISSING")).isEqualTo("production");
        assertThat(stageFromOpeningStockErrorMessage(
                controller,
                "SKU FG-1 is not sales-ready for opening stock: MISSING")).isEqualTo("sales");
        assertThat(stageFromOpeningStockErrorMessage(
                controller,
                "SKU FG-1 is not finance-ready for opening stock: MISSING")).isNull();
    }

    @Test
    void blockersForStage_returnsStageSpecificBlockersAndHandlesMissingStages() {
        OpeningStockImportController controller = new OpeningStockImportController(openingStockImportService, skuReadinessService);
        SkuReadinessDto populated = new SkuReadinessDto(
                "FG-1",
                new SkuReadinessDto.Stage(false, List.of("CATALOG_BLOCKER")),
                new SkuReadinessDto.Stage(false, List.of("INVENTORY_BLOCKER")),
                new SkuReadinessDto.Stage(false, List.of("PRODUCTION_BLOCKER")),
                new SkuReadinessDto.Stage(true, List.of()),
                new SkuReadinessDto.Stage(false, List.of("SALES_BLOCKER")),
                new SkuReadinessDto.Stage(false, List.of("ACCOUNTING_BLOCKER"))
        );
        SkuReadinessDto missing = new SkuReadinessDto("FG-1", null, null, null, null, null, null);

        assertThat(blockersForStage(controller, populated, "catalog")).containsExactly("CATALOG_BLOCKER");
        assertThat(blockersForStage(controller, populated, "inventory")).containsExactly("INVENTORY_BLOCKER");
        assertThat(blockersForStage(controller, populated, "production")).containsExactly("PRODUCTION_BLOCKER");
        assertThat(blockersForStage(controller, populated, "sales")).containsExactly("SALES_BLOCKER");
        assertThat(blockersForStage(controller, populated, "unknown")).isEmpty();
        assertThat(blockersForStage(controller, missing, "catalog")).isEmpty();
        assertThat(blockersForStage(controller, missing, "inventory")).isEmpty();
        assertThat(blockersForStage(controller, missing, "production")).isEmpty();
        assertThat(blockersForStage(controller, missing, "sales")).isEmpty();
    }

    @Test
    void sanitizeErrorMessage_preservesOriginalMessageWhenStageOrSkuContextIsMissing() {
        OpeningStockImportController controller = new OpeningStockImportController(openingStockImportService, skuReadinessService);
        String plainMessage = "CSV row is malformed";
        String inventoryMessage = "SKU FG-1 is not inventory-ready for opening stock: WIP_ACCOUNT_MISSING";
        SkuReadinessDto noSkuReadiness = new SkuReadinessDto(
                " ",
                new SkuReadinessDto.Stage(true, List.of()),
                new SkuReadinessDto.Stage(false, List.of("ACCOUNTING_CONFIGURATION_REQUIRED")),
                new SkuReadinessDto.Stage(true, List.of()),
                new SkuReadinessDto.Stage(true, List.of()),
                new SkuReadinessDto.Stage(true, List.of()),
                new SkuReadinessDto.Stage(false, List.of("ACCOUNTING_CONFIGURATION_REQUIRED"))
        );

        assertThat(sanitizeErrorMessage(controller, plainMessage, "FG-1", readiness(
                List.of("ACCOUNTING_CONFIGURATION_REQUIRED"),
                List.of(),
                List.of(),
                List.of()))).isEqualTo(plainMessage);
        assertThat(sanitizeErrorMessage(controller, inventoryMessage, "FG-1", null)).isEqualTo(inventoryMessage);
        assertThat(sanitizeErrorMessage(controller, inventoryMessage, " ", noSkuReadiness)).isEqualTo(inventoryMessage);
    }

    @Test
    void sanitizeErrorMessage_usesReadinessSkuAndUnknownWhenBlockersAreUnavailable() {
        OpeningStockImportController controller = new OpeningStockImportController(openingStockImportService, skuReadinessService);
        SkuReadinessDto unknownBlockers = new SkuReadinessDto(
                "FG-9",
                new SkuReadinessDto.Stage(true, List.of()),
                new SkuReadinessDto.Stage(false, null),
                new SkuReadinessDto.Stage(true, List.of()),
                new SkuReadinessDto.Stage(true, List.of()),
                new SkuReadinessDto.Stage(true, List.of()),
                new SkuReadinessDto.Stage(false, null)
        );

        assertThat(sanitizeErrorMessage(
                controller,
                "SKU FG-9 is not inventory-ready for opening stock: WIP_ACCOUNT_MISSING",
                " ",
                unknownBlockers)).isEqualTo(
                        "SKU FG-9 is not inventory-ready for opening stock: UNKNOWN");
    }

    @Test
    void sanitizeErrorMessage_usesUnknownWhenStageBlockersAreEmpty() {
        OpeningStockImportController controller = new OpeningStockImportController(openingStockImportService, skuReadinessService);
        SkuReadinessDto emptyProductionBlockers = new SkuReadinessDto(
                "FG-11",
                new SkuReadinessDto.Stage(true, List.of()),
                new SkuReadinessDto.Stage(true, List.of()),
                new SkuReadinessDto.Stage(false, List.of()),
                new SkuReadinessDto.Stage(true, List.of()),
                new SkuReadinessDto.Stage(true, List.of()),
                new SkuReadinessDto.Stage(true, List.of())
        );

        assertThat(sanitizeErrorMessage(
                controller,
                "SKU FG-11 is not production-ready for opening stock: LABOR_APPLIED_ACCOUNT_MISSING",
                "FG-11",
                emptyProductionBlockers)).isEqualTo(
                        "SKU FG-11 is not production-ready for opening stock: UNKNOWN");
    }

    @Test
    void sanitizeImportException_preservesNullAndNonAccountingFailures() {
        OpeningStockImportController controller = new OpeningStockImportController(openingStockImportService, skuReadinessService);
        ApplicationException nonAccountingFailure =
                new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE, "SKU FG-1 is not inventory-ready for opening stock");

        assertThat(sanitizeImportException(controller, null)).isNull();
        assertThat(sanitizeImportException(controller, nonAccountingFailure)).isSameAs(nonAccountingFailure);
    }

    @Test
    void isAccountingSensitiveImportFailure_detectsEveryTrackedKeyword() {
        OpeningStockImportController controller = new OpeningStockImportController(openingStockImportService, skuReadinessService);

        assertThat(isAccountingSensitiveImportFailure(controller, null)).isFalse();
        assertThat(isAccountingSensitiveImportFailure(controller, "   ")).isFalse();
        assertThat(isAccountingSensitiveImportFailure(controller, "plain validation failure")).isFalse();
        assertThat(isAccountingSensitiveImportFailure(controller, " open-bal missing ")).isTrue();
        assertThat(isAccountingSensitiveImportFailure(controller, "inventory account missing")).isTrue();
        assertThat(isAccountingSensitiveImportFailure(controller, "valuation account missing")).isTrue();
        assertThat(isAccountingSensitiveImportFailure(controller, "cogs account missing")).isTrue();
        assertThat(isAccountingSensitiveImportFailure(controller, "revenue account missing")).isTrue();
        assertThat(isAccountingSensitiveImportFailure(controller, "tax account missing")).isTrue();
        assertThat(isAccountingSensitiveImportFailure(controller, "gst output account missing")).isTrue();
        assertThat(isAccountingSensitiveImportFailure(controller, "discount account missing")).isTrue();
        assertThat(isAccountingSensitiveImportFailure(controller, "wip account missing")).isTrue();
        assertThat(isAccountingSensitiveImportFailure(controller, "labor applied account missing")).isTrue();
        assertThat(isAccountingSensitiveImportFailure(controller, "overhead applied account missing")).isTrue();
    }

    @Test
    void canViewAccountingMetadata_allowsOnlyAdminAndAccountingAuthorities() {
        OpeningStockImportController controller = new OpeningStockImportController(openingStockImportService, skuReadinessService);
        Authentication authenticationWithNullAuthorities = mock(Authentication.class);
        when(authenticationWithNullAuthorities.getAuthorities()).thenReturn(null);

        assertThat(canViewAccountingMetadata(controller, null)).isFalse();
        assertThat(canViewAccountingMetadata(controller, authenticationWithNullAuthorities)).isFalse();
        assertThat(canViewAccountingMetadata(controller, authentication("ROLE_FACTORY"))).isFalse();
        assertThat(canViewAccountingMetadata(controller, authentication("ROLE_ADMIN"))).isTrue();
        assertThat(canViewAccountingMetadata(controller, authentication("ROLE_ACCOUNTING"))).isTrue();
    }

    private MockMultipartFile csvFile() {
        return new MockMultipartFile(
                "file",
                "opening-stock.csv",
                "text/csv",
                "sku,qty\nRM-1,10\n".getBytes(StandardCharsets.UTF_8)
        );
    }

    private UsernamePasswordAuthenticationToken authentication(String authority) {
        return new UsernamePasswordAuthenticationToken(
                "user",
                "n/a",
                List.of(new SimpleGrantedAuthority(authority))
        );
    }

    private SkuReadinessDto readiness(List<String> inventoryBlockers,
                                      List<String> productionBlockers,
                                      List<String> salesBlockers,
                                      List<String> catalogBlockers) {
        return new SkuReadinessDto(
                "FG-1",
                new SkuReadinessDto.Stage(catalogBlockers.isEmpty(), catalogBlockers),
                new SkuReadinessDto.Stage(inventoryBlockers.isEmpty(), inventoryBlockers),
                new SkuReadinessDto.Stage(productionBlockers.isEmpty(), productionBlockers),
                new SkuReadinessDto.Stage(true, List.of()),
                new SkuReadinessDto.Stage(salesBlockers.isEmpty(), salesBlockers),
                new SkuReadinessDto.Stage(inventoryBlockers.isEmpty(), inventoryBlockers)
        );
    }

    private String batchKey(String idempotencyKey) {
        return "OPEN-STOCK-" + idempotencyKey;
    }

    @SuppressWarnings("unchecked")
    private List<String> blockersForStage(OpeningStockImportController controller,
                                          SkuReadinessDto readiness,
                                          String stage) {
        return (List<String>) ReflectionTestUtils.invokeMethod(controller, "blockersForStage", readiness, stage);
    }

    private String sanitizeErrorMessage(OpeningStockImportController controller,
                                        String message,
                                        String sku,
                                        SkuReadinessDto readiness) {
        return ReflectionTestUtils.invokeMethod(controller, "sanitizeErrorMessage", message, sku, readiness);
    }

    private String stageFromOpeningStockErrorMessage(OpeningStockImportController controller, String message) {
        return ReflectionTestUtils.invokeMethod(controller, "stageFromOpeningStockErrorMessage", message);
    }

    private boolean canViewAccountingMetadata(OpeningStockImportController controller, Authentication authentication) {
        Boolean result = ReflectionTestUtils.invokeMethod(controller, "canViewAccountingMetadata", authentication);
        return Boolean.TRUE.equals(result);
    }

    private ApplicationException sanitizeImportException(OpeningStockImportController controller, ApplicationException ex) {
        return ReflectionTestUtils.invokeMethod(controller, "sanitizeImportException", ex);
    }

    private boolean isAccountingSensitiveImportFailure(OpeningStockImportController controller, String message) {
        Boolean result = ReflectionTestUtils.invokeMethod(controller, "isAccountingSensitiveImportFailure", message);
        return Boolean.TRUE.equals(result);
    }
}
