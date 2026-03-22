package com.bigbrightpaints.erp.modules.inventory.controller;

import com.bigbrightpaints.erp.modules.inventory.dto.OpeningStockImportHistoryItem;
import com.bigbrightpaints.erp.modules.inventory.dto.OpeningStockImportResponse;
import com.bigbrightpaints.erp.modules.inventory.service.OpeningStockImportService;
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

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@Tag("critical")
class OpeningStockImportControllerTest {

    @Mock
    private OpeningStockImportService openingStockImportService;

    @Test
    void importOpeningStock_delegatesCanonicalIdempotencyKeyToService() {
        OpeningStockImportController controller = new OpeningStockImportController(openingStockImportService);
        MockMultipartFile file = csvFile();
        OpeningStockImportResponse response = new OpeningStockImportResponse(1, 0, 1, 0, 0, List.of(), List.of());
        when(openingStockImportService.importOpeningStock(file, "import-key")).thenReturn(response);

        controller.importOpeningStock("import-key", file);

        verify(openingStockImportService).importOpeningStock(file, "import-key");
    }

    @Test
    void importHistory_delegatesToServiceWithPagination() {
        OpeningStockImportController controller = new OpeningStockImportController(openingStockImportService);
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
}
