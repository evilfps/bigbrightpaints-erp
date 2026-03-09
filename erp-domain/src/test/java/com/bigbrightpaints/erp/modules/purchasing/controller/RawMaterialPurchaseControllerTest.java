package com.bigbrightpaints.erp.modules.purchasing.controller;

import com.bigbrightpaints.erp.modules.purchasing.dto.PurchaseReturnPreviewDto;
import com.bigbrightpaints.erp.modules.purchasing.dto.PurchaseReturnRequest;
import com.bigbrightpaints.erp.modules.purchasing.service.PurchasingService;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RawMaterialPurchaseControllerTest {

    @Test
    void recordPurchaseReturn_delegatesToPurchasingService() {
        PurchasingService purchasingService = mock(PurchasingService.class);
        RawMaterialPurchaseController controller = new RawMaterialPurchaseController(purchasingService);
        PurchaseReturnRequest request = request();
        when(purchasingService.recordPurchaseReturn(request)).thenReturn(null);

        ApiResponse<?> body = controller.recordPurchaseReturn(request).getBody();

        assertThat(body).isNotNull();
        assertThat(body.success()).isTrue();
        verify(purchasingService).recordPurchaseReturn(request);
    }

    @Test
    void previewPurchaseReturn_delegatesToPurchasingService() {
        PurchasingService purchasingService = mock(PurchasingService.class);
        RawMaterialPurchaseController controller = new RawMaterialPurchaseController(purchasingService);
        PurchaseReturnRequest request = request();
        PurchaseReturnPreviewDto preview = new PurchaseReturnPreviewDto(
                20L,
                "PINV-20",
                30L,
                "Resin",
                new BigDecimal("1.00"),
                new BigDecimal("4.00"),
                new BigDecimal("10.00"),
                BigDecimal.ZERO,
                new BigDecimal("10.00"),
                LocalDate.of(2026, 3, 10),
                "PRN-20"
        );
        when(purchasingService.previewPurchaseReturn(request)).thenReturn(preview);

        ApiResponse<PurchaseReturnPreviewDto> body = controller.previewPurchaseReturn(request).getBody();

        assertThat(body).isNotNull();
        assertThat(body.data()).isEqualTo(preview);
        verify(purchasingService).previewPurchaseReturn(request);
    }

    private PurchaseReturnRequest request() {
        return new PurchaseReturnRequest(
                10L,
                20L,
                30L,
                new BigDecimal("1.00"),
                new BigDecimal("10.00"),
                "PRN-20",
                LocalDate.of(2026, 3, 10),
                "Damaged"
        );
    }
}
