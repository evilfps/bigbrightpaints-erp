package com.bigbrightpaints.erp.modules.factory.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.modules.factory.dto.BulkPackResponse;
import com.bigbrightpaints.erp.modules.factory.dto.PackingLineRequest;
import com.bigbrightpaints.erp.modules.factory.dto.PackingRecordDto;
import com.bigbrightpaints.erp.modules.factory.dto.PackingRequest;
import com.bigbrightpaints.erp.modules.factory.dto.UnpackedBatchDto;
import com.bigbrightpaints.erp.modules.factory.service.BulkPackingService;
import com.bigbrightpaints.erp.modules.factory.service.PackingService;

@Tag("critical")
@ExtendWith(MockitoExtension.class)
class PackingControllerTest {

  @Mock private PackingService packingService;

  @Mock private BulkPackingService bulkPackingService;

  @Test
  void recordPacking_rejectsWhenRequestMissing() {
    PackingController controller = new PackingController(packingService, bulkPackingService);

    assertThatThrownBy(() -> controller.recordPacking("pack-001", null, null))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Packing request is required");

    verifyNoInteractions(packingService);
  }

  @Test
  void recordPacking_rejectsWhenIdempotencyHeaderMissing() {
    PackingController controller = new PackingController(packingService, bulkPackingService);

    assertThatThrownBy(() -> controller.recordPacking(null, null, request(null)))
        .isInstanceOfSatisfying(
            ApplicationException.class,
            ex -> {
              assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD);
              assertThat(ex.getMessage()).isEqualTo("Idempotency-Key header is required");
            });

    verifyNoInteractions(packingService);
  }

  @Test
  void recordPacking_appliesHeaderIdempotencyKeyWhenBodyMissing() {
    PackingController controller = new PackingController(packingService, bulkPackingService);
    when(packingService.recordPacking(any())).thenReturn(null);

    controller.recordPacking("header-key", null, request(null));

    ArgumentCaptor<PackingRequest> captor = ArgumentCaptor.forClass(PackingRequest.class);
    verify(packingService).recordPacking(captor.capture());
    assertThat(captor.getValue().idempotencyKey()).isEqualTo("header-key");
  }

  @Test
  void recordPacking_requiresCanonicalHeaderEvenWhenRequestBodyCarriesLegacyIdempotencyKey() {
    PackingController controller = new PackingController(packingService, bulkPackingService);

    assertThatThrownBy(() -> controller.recordPacking(null, null, request("body-only-key")))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Idempotency-Key header is required");

    verifyNoInteractions(packingService);
  }

  @Test
  void recordPacking_ignoresRequestBodyIdempotencyWhenCanonicalHeaderPresent() {
    PackingController controller = new PackingController(packingService, bulkPackingService);
    when(packingService.recordPacking(any())).thenReturn(null);

    controller.recordPacking("header-key", null, request("body-key"));

    ArgumentCaptor<PackingRequest> captor = ArgumentCaptor.forClass(PackingRequest.class);
    verify(packingService).recordPacking(captor.capture());
    assertThat(captor.getValue().idempotencyKey()).isEqualTo("header-key");
  }

  @Test
  void recordPacking_preservesCloseResidualWastageWhenApplyingHeaderIdempotencyKey() {
    PackingController controller = new PackingController(packingService, bulkPackingService);
    when(packingService.recordPacking(any())).thenReturn(null);

    controller.recordPacking("header-key", null, request("body-key", Boolean.TRUE));

    ArgumentCaptor<PackingRequest> captor = ArgumentCaptor.forClass(PackingRequest.class);
    verify(packingService).recordPacking(captor.capture());
    assertThat(captor.getValue().idempotencyKey()).isEqualTo("header-key");
    assertThat(captor.getValue().closeResidualWastageRequested()).isTrue();
  }

  @Test
  void recordPacking_rejectsRequestIdHeaderWhenPrimaryMissing() {
    PackingController controller = new PackingController(packingService, bulkPackingService);

    assertThatThrownBy(() -> controller.recordPacking(null, "req-123", request(null)))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("X-Request-Id is not supported");

    verifyNoInteractions(packingService);
  }

  @Test
  void recordPacking_rejectsRequestIdHeaderWhenPrimaryAlsoPresent() {
    PackingController controller = new PackingController(packingService, bulkPackingService);

    assertThatThrownBy(() -> controller.recordPacking("header-key", "req-123", request(null)))
        .isInstanceOfSatisfying(
            ApplicationException.class,
            ex -> {
              assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_INVALID_INPUT);
              assertThat(ex.getMessage())
                  .isEqualTo(
                      "X-Request-Id is not supported for packing records; use Idempotency-Key");
              assertThat(ex.getDetails())
                  .containsEntry("legacyHeader", "X-Request-Id")
                  .containsEntry("canonicalHeader", "Idempotency-Key")
                  .containsEntry("canonicalPath", "/api/v1/factory/packing-records");
            });

    verifyNoInteractions(packingService);
  }

  @Test
  void listUnpackedBatches_returnsPackingServiceResults() {
    PackingController controller = new PackingController(packingService, bulkPackingService);
    List<UnpackedBatchDto> batches =
        List.of(
            new UnpackedBatchDto(
                7L,
                "PROD-007",
                "Primer",
                "White",
                new BigDecimal("10"),
                new BigDecimal("4"),
                new BigDecimal("6"),
                "PARTIAL_PACKED",
                null));
    when(packingService.listUnpackedBatches()).thenReturn(batches);

    var response = controller.listUnpackedBatches();

    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().message()).isNull();
    assertThat(response.getBody().data()).isEqualTo(batches);
    verify(packingService).listUnpackedBatches();
  }

  @Test
  void packingHistory_returnsPackingServiceResults() {
    PackingController controller = new PackingController(packingService, bulkPackingService);
    List<PackingRecordDto> history =
        List.of(
            new PackingRecordDto(
                11L,
                7L,
                "PROD-007",
                41L,
                "1L",
                88L,
                "FG-088",
                4,
                "1L",
                new BigDecimal("4"),
                4,
                4,
                1,
                LocalDate.of(2026, 3, 1),
                "packer"));
    when(packingService.packingHistory(7L)).thenReturn(history);

    var response = controller.packingHistory(7L);

    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().data()).isEqualTo(history);
    verify(packingService).packingHistory(7L);
  }

  @Test
  void listBulkBatches_returnsBulkPackingServiceResults() {
    PackingController controller = new PackingController(packingService, bulkPackingService);
    List<BulkPackResponse.ChildBatchDto> batches = List.of();
    when(bulkPackingService.listBulkBatches(55L)).thenReturn(batches);

    var response = controller.listBulkBatches(55L);

    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().data()).isEqualTo(batches);
    verify(bulkPackingService).listBulkBatches(55L);
  }

  @Test
  void listChildBatches_returnsBulkPackingServiceResults() {
    PackingController controller = new PackingController(packingService, bulkPackingService);
    List<BulkPackResponse.ChildBatchDto> batches = List.of();
    when(bulkPackingService.listChildBatches(91L)).thenReturn(batches);

    var response = controller.listChildBatches(91L);

    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().data()).isEqualTo(batches);
    verify(bulkPackingService).listChildBatches(91L);
  }

  private PackingRequest request(String idempotencyKey) {
    return request(idempotencyKey, Boolean.FALSE);
  }

  private PackingRequest request(String idempotencyKey, Boolean closeResidualWastage) {
    return new PackingRequest(
        1L,
        LocalDate.of(2026, 2, 6),
        "packer",
        idempotencyKey,
        List.of(new PackingLineRequest("10L", new BigDecimal("1"), 1, 1, 1)),
        closeResidualWastage);
  }
}
