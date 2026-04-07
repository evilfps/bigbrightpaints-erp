package com.bigbrightpaints.erp.modules.sales.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

import com.bigbrightpaints.erp.modules.sales.dto.DealerDunningHoldResponse;
import com.bigbrightpaints.erp.modules.sales.dto.DealerImportResponse;
import com.bigbrightpaints.erp.modules.sales.service.DealerImportService;
import com.bigbrightpaints.erp.modules.sales.service.DealerService;
import com.bigbrightpaints.erp.modules.sales.service.DunningService;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;

@ExtendWith(MockitoExtension.class)
@Tag("critical")
class DealerControllerTest {

  @Mock private DealerService dealerService;
  @Mock private DealerImportService dealerImportService;
  @Mock private DunningService dunningService;

  @Test
  void placeDunningHold_routesThroughDunningService() {
    DealerController controller =
        new DealerController(dealerService, dealerImportService, dunningService);
    when(dunningService.placeDealerOnHold(42L)).thenReturn(true);
    ResponseEntity<ApiResponse<DealerDunningHoldResponse>> response =
        controller.placeDunningHold(42L);

    assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().data()).isNotNull();
    assertThat(response.getBody().data().status()).isEqualTo("ON_HOLD");
    verify(dunningService).placeDealerOnHold(42L);
  }

  @Test
  void importDealers_routesThroughDealerImportService() {
    DealerController controller =
        new DealerController(dealerService, dealerImportService, dunningService);
    MockMultipartFile file =
        new MockMultipartFile(
            "file",
            "dealers.csv",
            "text/csv",
            "name,email,creditLimit,region,paymentTerms\nDealer,dealer@example.com,1000,NORTH,NET_30\n"
                .getBytes(StandardCharsets.UTF_8));
    DealerImportResponse payload =
        new DealerImportResponse(
            1, 0, List.of(new DealerImportResponse.ImportError(0L, "placeholder")));
    when(dealerImportService.importDealers(file)).thenReturn(payload);

    ResponseEntity<ApiResponse<DealerImportResponse>> response = controller.importDealers(file);

    assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().data()).isEqualTo(payload);
    verify(dealerImportService).importDealers(file);
  }
}
