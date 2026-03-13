package com.bigbrightpaints.erp.modules.purchasing.controller;

import com.bigbrightpaints.erp.modules.purchasing.dto.PurchaseReturnPreviewDto;
import com.bigbrightpaints.erp.modules.purchasing.dto.PurchaseReturnRequest;
import com.bigbrightpaints.erp.modules.purchasing.service.PurchasingService;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RawMaterialPurchaseControllerTest {

    @Test
    void previewPurchaseReturn_wrapsServiceResult() {
        PurchasingService purchasingService = mock(PurchasingService.class);
        RawMaterialPurchaseController controller = new RawMaterialPurchaseController(purchasingService);
        PurchaseReturnRequest request = new PurchaseReturnRequest(
                10L,
                20L,
                30L,
                BigDecimal.ONE,
                new BigDecimal("5.00"),
                "PRN-1",
                LocalDate.of(2026, 3, 12),
                "Damaged"
        );
        PurchaseReturnPreviewDto preview = new PurchaseReturnPreviewDto(
                20L,
                "PINV-20",
                30L,
                "Titanium White",
                BigDecimal.ONE,
                new BigDecimal("4.00"),
                new BigDecimal("5.00"),
                BigDecimal.ZERO,
                new BigDecimal("5.00"),
                LocalDate.of(2026, 3, 12),
                "PRN-1"
        );
        when(purchasingService.previewPurchaseReturn(request)).thenReturn(preview);

        var response = controller.previewPurchaseReturn(request);

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().success()).isTrue();
        assertThat(response.getBody().message()).isEqualTo("Purchase return preview");
        assertThat(response.getBody().data()).isEqualTo(preview);
        verify(purchasingService).previewPurchaseReturn(request);
    }
}
