package com.bigbrightpaints.erp.modules.inventory.controller;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.modules.inventory.dto.OpeningStockImportResponse;
import com.bigbrightpaints.erp.modules.inventory.service.OpeningStockImportService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OpeningStockImportControllerTest {

    @Mock
    private OpeningStockImportService openingStockImportService;

    @Test
    void importOpeningStock_appliesLegacyHeaderWhenPrimaryMissing() {
        OpeningStockImportController controller = new OpeningStockImportController(openingStockImportService);
        MockMultipartFile file = csvFile();
        OpeningStockImportResponse response = new OpeningStockImportResponse(1, 1, 1, 0, 0, List.of());
        when(openingStockImportService.importOpeningStock(file, "legacy-key")).thenReturn(response);

        controller.importOpeningStock(null, "legacy-key", file);

        verify(openingStockImportService).importOpeningStock(file, "legacy-key");
    }

    @Test
    void importOpeningStock_rejectsWhenPrimaryLegacyHeadersMismatch() {
        OpeningStockImportController controller = new OpeningStockImportController(openingStockImportService);
        MockMultipartFile file = csvFile();

        assertThatThrownBy(() -> controller.importOpeningStock("primary-key", "legacy-key", file))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Idempotency key mismatch between Idempotency-Key and X-Idempotency-Key headers");
        verifyNoInteractions(openingStockImportService);
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
