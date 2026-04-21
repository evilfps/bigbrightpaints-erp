package com.bigbrightpaints.erp.modules.accounting.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.bigbrightpaints.erp.core.health.ConfigurationHealthService.ConfigurationHealthReport;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingService;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;

@ExtendWith(MockitoExtension.class)
class AccountingConfigurationControllerTest {

  @Mock private AccountingService accountingService;

  @Test
  void health_returnsCompanyScopedHealthReport() {
    ConfigurationHealthReport report = new ConfigurationHealthReport(true, List.of());
    when(accountingService.getConfigurationHealthReport()).thenReturn(report);
    AccountingConfigurationController controller =
        new AccountingConfigurationController(accountingService);

    ResponseEntity<ApiResponse<ConfigurationHealthReport>> response = controller.health();

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().success()).isTrue();
    assertThat(response.getBody().message()).isEqualTo("Configuration health report");
    assertThat(response.getBody().data()).isSameAs(report);
    verify(accountingService).getConfigurationHealthReport();
  }
}
