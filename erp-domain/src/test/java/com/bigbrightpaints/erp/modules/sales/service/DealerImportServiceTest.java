package com.bigbrightpaints.erp.modules.sales.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.modules.sales.dto.CreateDealerRequest;
import com.bigbrightpaints.erp.modules.sales.dto.DealerImportResponse;

import jakarta.validation.Validation;
import jakarta.validation.Validator;

@Tag("critical")
class DealerImportServiceTest {

  private final DealerService dealerService = mock(DealerService.class);
  private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
  private final DealerImportService service = new DealerImportService(dealerService, validator);

  @Test
  void importDealers_returnsRowLevelErrorForInvalidPaymentTerms() {
    MockMultipartFile file =
        csvFile(
            "name,email,creditLimit,region,paymentTerms\n"
                + "Dealer One,one@example.com,1000,NORTH,NET_30\n"
                + "Dealer Two,two@example.com,2500,SOUTH,NET_15\n");

    DealerImportResponse response = service.importDealers(file);

    assertThat(response.successCount()).isEqualTo(1);
    assertThat(response.failureCount()).isEqualTo(1);
    assertThat(response.errors()).hasSize(1);
    assertThat(response.errors().getFirst().rowNumber()).isEqualTo(2L);
    assertThat(response.errors().getFirst().message())
        .contains("paymentTerms must be one of NET_30, NET_60, NET_90");
    verify(dealerService, times(1)).createDealer(any(CreateDealerRequest.class));
  }

  @Test
  void importDealers_returnsRowLevelErrorForBeanValidationViolations() {
    MockMultipartFile file =
        csvFile(
            "name,email,creditLimit,region,paymentTerms,gstNumber,stateCode\n"
                + "Dealer One,one@example.com,1000,NORTH,NET_30,27ABCDE1234F1Z5,KA\n"
                + "Dealer Two,invalid-email,2500,SOUTH,NET_30,INVALID,123\n");

    DealerImportResponse response = service.importDealers(file);

    assertThat(response.successCount()).isEqualTo(1);
    assertThat(response.failureCount()).isEqualTo(1);
    assertThat(response.errors()).hasSize(1);
    assertThat(response.errors().getFirst().rowNumber()).isEqualTo(2L);
    assertThat(response.errors().getFirst().message())
        .contains("contactEmail: Provide a valid contact email")
        .contains("gstNumber: GST number must be a valid 15-character GSTIN")
        .contains("stateCode: State code must be exactly 2 characters");
    verify(dealerService, times(1)).createDealer(any(CreateDealerRequest.class));
  }

  @Test
  void importDealers_capturesServiceValidationErrorsPerRow() {
    AtomicInteger invocation = new AtomicInteger();
    doAnswer(
            ignored -> {
              if (invocation.incrementAndGet() == 2) {
                throw new ApplicationException(
                    ErrorCode.VALIDATION_INVALID_INPUT,
                    "Dealer already exists for this portal user");
              }
              return null;
            })
        .when(dealerService)
        .createDealer(any(CreateDealerRequest.class));

    MockMultipartFile file =
        csvFile(
            "name,email,creditLimit,region,paymentTerms\n"
                + "Dealer One,one@example.com,1000,NORTH,NET_30\n"
                + "Dealer One Duplicate,one@example.com,1000,NORTH,NET_30\n");

    DealerImportResponse response = service.importDealers(file);

    assertThat(response.successCount()).isEqualTo(1);
    assertThat(response.failureCount()).isEqualTo(1);
    assertThat(response.errors()).hasSize(1);
    assertThat(response.errors().getFirst().rowNumber()).isEqualTo(2L);
    assertThat(response.errors().getFirst().message())
        .isEqualTo("Dealer already exists for this portal user");
  }

  @Test
  void importDealers_rejectsCsvWithoutRequiredHeaders() {
    MockMultipartFile file =
        csvFile("name,email,creditLimit,paymentTerms\nDealer,one@example.com,1000,NET_30\n");

    assertThatThrownBy(() -> service.importDealers(file))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("CSV is missing required headers");
  }

  private MockMultipartFile csvFile(String payload) {
    return new MockMultipartFile(
        "file", "dealers.csv", "text/csv", payload.getBytes(StandardCharsets.UTF_8));
  }
}
