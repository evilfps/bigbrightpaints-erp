package com.bigbrightpaints.erp.modules.sales.controller;

import com.bigbrightpaints.erp.modules.sales.service.DealerPortalService;
import com.bigbrightpaints.erp.modules.sales.service.DealerService;
import com.bigbrightpaints.erp.modules.sales.service.DunningService;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@Tag("critical")
class DealerControllerTest {

    @Mock private DealerService dealerService;
    @Mock private DunningService dunningService;
    @Mock private DealerPortalService dealerPortalService;

    @Test
    void dealerAging_routesThroughDealerPortalService() {
        DealerController controller = new DealerController(dealerService, dunningService, dealerPortalService);
        Map<String, Object> payload = Map.of("dealerId", 42L, "status", "NEAR_LIMIT");
        when(dealerPortalService.getAgingForDealer(42L)).thenReturn(payload);

        ResponseEntity<ApiResponse<Map<String, Object>>> response = controller.dealerAging(42L);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isNotNull();
        verify(dealerPortalService).getAgingForDealer(42L);
        verify(dealerService, never()).agingSummary(42L);
    }
}
