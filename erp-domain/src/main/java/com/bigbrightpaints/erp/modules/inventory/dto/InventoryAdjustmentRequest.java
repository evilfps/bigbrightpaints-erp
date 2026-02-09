package com.bigbrightpaints.erp.modules.inventory.dto;

import com.bigbrightpaints.erp.modules.inventory.domain.InventoryAdjustmentType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record InventoryAdjustmentRequest(LocalDate adjustmentDate,
                                         @NotNull InventoryAdjustmentType type,
                                         @NotNull Long adjustmentAccountId,
                                         String reason,
                                         Boolean adminOverride,
                                         @NotBlank String idempotencyKey,
                                         @NotEmpty List<@Valid LineRequest> lines) {

    public record LineRequest(@NotNull Long finishedGoodId,
                              @NotNull BigDecimal quantity,
                              @NotNull BigDecimal unitCost,
                              String note) {}
}
