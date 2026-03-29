package com.bigbrightpaints.erp.modules.inventory.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryAdjustmentType;
import com.bigbrightpaints.erp.modules.inventory.dto.InventoryAdjustmentRequest;
import com.bigbrightpaints.erp.modules.inventory.service.InventoryAdjustmentService;

import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;

@ExtendWith(MockitoExtension.class)
class InventoryAdjustmentControllerTest {

  @Mock private InventoryAdjustmentService inventoryAdjustmentService;

  @Test
  void createAdjustment_rejectsWhenIdempotencyMissing() {
    InventoryAdjustmentController controller = controller();
    InventoryAdjustmentRequest request = validRequest(null);

    assertThatThrownBy(() -> controller.createAdjustment(null, null, request))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Idempotency-Key header is required");
  }

  @Test
  void createAdjustment_rejectsHeaderBodyMismatch() {
    InventoryAdjustmentController controller = controller();
    InventoryAdjustmentRequest request = validRequest("body-key");

    assertThatThrownBy(() -> controller.createAdjustment("header-key", null, request))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Idempotency key mismatch");
  }

  @Test
  void createAdjustment_appliesHeaderIdempotencyKeyWhenBodyMissing() {
    InventoryAdjustmentController controller = controller();
    when(inventoryAdjustmentService.createAdjustment(any())).thenReturn(null);

    InventoryAdjustmentRequest request = validRequest(null);
    controller.createAdjustment("header-key", null, request);

    ArgumentCaptor<InventoryAdjustmentRequest> captor =
        ArgumentCaptor.forClass(InventoryAdjustmentRequest.class);
    verify(inventoryAdjustmentService).createAdjustment(captor.capture());
    assertThat(captor.getValue().idempotencyKey()).isEqualTo("header-key");
  }

  @Test
  void createAdjustment_rejectsLegacyHeaderWhenPrimaryMissing() {
    InventoryAdjustmentController controller = controller();
    assertThatThrownBy(() -> controller.createAdjustment(null, "legacy-key", validRequest(null)))
        .isInstanceOfSatisfying(
            ApplicationException.class,
            ex -> {
              assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_INVALID_INPUT);
              assertThat(ex.getMessage())
                  .contains("X-Idempotency-Key is not supported for inventory adjustments");
              assertThat(ex.getDetails())
                  .containsEntry("legacyHeader", "X-Idempotency-Key")
                  .containsEntry("legacyHeaderValue", "legacy-key")
                  .containsEntry("canonicalHeader", "Idempotency-Key")
                  .containsEntry("canonicalPath", "/api/v1/inventory/adjustments");
            });

    verifyNoInteractions(inventoryAdjustmentService);
  }

  @Test
  void createAdjustment_rejectsLegacyHeaderWhenPrimaryAlsoPresent() {
    InventoryAdjustmentController controller = controller();

    InventoryAdjustmentRequest request = validRequest(null);
    assertThatThrownBy(() -> controller.createAdjustment("header-key", "legacy-key", request))
        .isInstanceOfSatisfying(
            ApplicationException.class,
            ex -> {
              assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_INVALID_INPUT);
              assertThat(ex.getMessage())
                  .contains("X-Idempotency-Key is not supported for inventory adjustments");
              assertThat(ex.getDetails())
                  .containsEntry("legacyHeader", "X-Idempotency-Key")
                  .containsEntry("legacyHeaderValue", "legacy-key")
                  .containsEntry("canonicalHeader", "Idempotency-Key")
                  .containsEntry("canonicalPath", "/api/v1/inventory/adjustments");
            });

    verifyNoInteractions(inventoryAdjustmentService);
  }

  @Test
  void createAdjustment_validatesResolvedRequest() {
    InventoryAdjustmentController controller = controller();
    InventoryAdjustmentRequest invalid =
        new InventoryAdjustmentRequest(
            LocalDate.of(2026, 2, 9),
            null,
            null,
            "reason",
            Boolean.FALSE,
            null,
            List.of(new InventoryAdjustmentRequest.LineRequest(null, null, null, "note")));

    assertThatThrownBy(() -> controller.createAdjustment("header-key", null, invalid))
        .isInstanceOf(ConstraintViolationException.class);
  }

  private InventoryAdjustmentController controller() {
    Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
    return new InventoryAdjustmentController(inventoryAdjustmentService, validator);
  }

  private InventoryAdjustmentRequest validRequest(String idempotencyKey) {
    return new InventoryAdjustmentRequest(
        LocalDate.of(2026, 2, 9),
        InventoryAdjustmentType.DAMAGED,
        101L,
        "reason",
        Boolean.FALSE,
        idempotencyKey,
        List.of(
            new InventoryAdjustmentRequest.LineRequest(
                1001L, new BigDecimal("1.00"), new BigDecimal("10.00"), "Damaged")));
  }
}
