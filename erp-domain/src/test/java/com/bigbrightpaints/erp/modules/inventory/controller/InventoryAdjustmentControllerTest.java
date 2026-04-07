package com.bigbrightpaints.erp.modules.inventory.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryAdjustmentType;
import com.bigbrightpaints.erp.modules.inventory.dto.InventoryAdjustmentDto;
import com.bigbrightpaints.erp.modules.inventory.dto.InventoryAdjustmentLineDto;
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

    assertThatThrownBy(() -> controller.createAdjustment(null, request))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Idempotency-Key header is required");
  }

  @Test
  void createAdjustment_rejectsHeaderBodyMismatch() {
    InventoryAdjustmentController controller = controller();
    InventoryAdjustmentRequest request = validRequest("body-key");

    assertThatThrownBy(() -> controller.createAdjustment("header-key", request))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Idempotency key mismatch");
  }

  @Test
  void createAdjustment_appliesHeaderIdempotencyKeyWhenBodyMissing() {
    InventoryAdjustmentController controller = controller();
    when(inventoryAdjustmentService.createAdjustment(any())).thenReturn(null);

    InventoryAdjustmentRequest request = validRequest(null);
    controller.createAdjustment("header-key", request);

    ArgumentCaptor<InventoryAdjustmentRequest> captor =
        ArgumentCaptor.forClass(InventoryAdjustmentRequest.class);
    verify(inventoryAdjustmentService).createAdjustment(captor.capture());
    assertThat(captor.getValue().idempotencyKey()).isEqualTo("header-key");
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

    assertThatThrownBy(() -> controller.createAdjustment("header-key", invalid))
        .isInstanceOf(ConstraintViolationException.class);
  }

  @Test
  void createAdjustment_returnsCreatedStatus() {
    InventoryAdjustmentController controller = controller();
    when(inventoryAdjustmentService.createAdjustment(any()))
        .thenReturn(
            new InventoryAdjustmentDto(
                17L,
                UUID.randomUUID(),
                "INV-ADJ-17",
                LocalDate.of(2026, 2, 9),
                InventoryAdjustmentType.DAMAGED.name(),
                "POSTED",
                "reason",
                new BigDecimal("10.00"),
                88L,
                List.of(
                    new InventoryAdjustmentLineDto(
                        1001L,
                        "FG-1",
                        new BigDecimal("1.00"),
                        new BigDecimal("10.00"),
                        new BigDecimal("10.00"),
                        "note"))));

    assertThat(controller.createAdjustment("header-key", validRequest(null)).getStatusCode())
        .isEqualTo(HttpStatus.CREATED);
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
