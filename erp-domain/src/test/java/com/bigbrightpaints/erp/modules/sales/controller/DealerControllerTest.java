package com.bigbrightpaints.erp.modules.sales.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import com.bigbrightpaints.erp.modules.sales.service.DealerService;
import com.bigbrightpaints.erp.modules.sales.service.DunningService;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;

@ExtendWith(MockitoExtension.class)
@Tag("critical")
class DealerControllerTest {

  @Mock private DealerService dealerService;
  @Mock private DunningService dunningService;

  @Test
  void holdIfOverdue_routesThroughDunningService() {
    DealerController controller = new DealerController(dealerService, dunningService);
    ResponseEntity<ApiResponse<Map<String, Object>>> response =
        controller.holdIfOverdue(42L, 30, new BigDecimal("5000.00"));

    assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
    assertThat(response.getBody()).isNotNull();
    verify(dunningService).evaluateDealerHold(42L, 30, new BigDecimal("5000.00"));
  }
}
