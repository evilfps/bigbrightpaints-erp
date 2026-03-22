package com.bigbrightpaints.erp.modules.inventory.controller;

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
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
        OpeningStockImportResponse response = new OpeningStockImportResponse(1, 0, 1, 0, 0, List.of(), List.of());
        when(openingStockImportService.importOpeningStock(file, "import-key")).thenReturn(response);

        controller.importOpeningStock("import-key", file, authentication("ROLE_ADMIN"));

        verify(openingStockImportService).importOpeningStock(file, "import-key");
    }

    @Test
    void importOpeningStock_sanitizesReadinessForFactoryUsers() {
        OpeningStockImportController controller = new OpeningStockImportController(openingStockImportService, skuReadinessService);
        MockMultipartFile file = csvFile();
        SkuReadinessDto rawReadiness = readiness(false, List.of("WIP_ACCOUNT_MISSING"));
        SkuReadinessDto sanitizedReadiness = readiness(false, List.of("ACCOUNTING_CONFIGURATION_REQUIRED"));
        OpeningStockImportResponse response = new OpeningStockImportResponse(
                1,
                0,
                0,
                0,
                1,
                List.of(new OpeningStockImportResponse.ImportRowResult(1L, "FG-1", "FINISHED_GOOD", rawReadiness)),
                List.of(new OpeningStockImportResponse.ImportError(2L, "blocked", "FG-2", "FINISHED_GOOD", rawReadiness))
        );
        when(openingStockImportService.importOpeningStock(file, "factory-key")).thenReturn(response);
        when(skuReadinessService.sanitizeForCatalogViewer(rawReadiness, false)).thenReturn(sanitizedReadiness);

        OpeningStockImportResponse payload = controller
                .importOpeningStock("factory-key", file, authentication("ROLE_FACTORY"))
                .getBody()
                .data();

        assertThat(payload.results().getFirst().readiness()).isEqualTo(sanitizedReadiness);
        assertThat(payload.errors().getFirst().readiness()).isEqualTo(sanitizedReadiness);
        verify(skuReadinessService, org.mockito.Mockito.times(2)).sanitizeForCatalogViewer(rawReadiness, false);
    }

    @Test
    void importHistory_delegatesToServiceWithPagination() {
        OpeningStockImportController controller = new OpeningStockImportController(openingStockImportService, skuReadinessService);
        PageResponse<OpeningStockImportHistoryItem> page = PageResponse.of(
                List.of(new OpeningStockImportHistoryItem(
                        9L,
                        "idem-1",
                        "OPEN-STOCK-ACME-001",
                        "opening.csv",
                        55L,
                        2,
                        1,
                        1,
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

    private SkuReadinessDto readiness(boolean salesReady, List<String> salesBlockers) {
        return new SkuReadinessDto(
                "FG-1",
                new SkuReadinessDto.Stage(true, List.of()),
                new SkuReadinessDto.Stage(true, List.of()),
                new SkuReadinessDto.Stage(true, List.of()),
                new SkuReadinessDto.Stage(salesReady, salesBlockers)
        );
    }
}
