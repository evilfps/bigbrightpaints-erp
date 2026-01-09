package com.bigbrightpaints.erp.modules.accounting.dto;

import com.bigbrightpaints.erp.modules.inventory.domain.MaterialType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record OnboardingOpeningStockRequest(
        @NotBlank String referenceNumber,
        LocalDate entryDate,
        @NotNull Long offsetAccountId,
        String memo,
        List<@Valid FinishedGoodLine> finishedGoods,
        List<@Valid RawMaterialLine> rawMaterials
) {
    public record FinishedGoodLine(
            @NotBlank String productCode,
            @NotNull BigDecimal quantity,
            @NotNull BigDecimal unitCost,
            String batchCode,
            LocalDate manufacturedDate
    ) {}

    public record RawMaterialLine(
            @NotBlank String sku,
            @NotNull BigDecimal quantity,
            @NotNull BigDecimal unitCost,
            String unit,
            String batchCode,
            MaterialType materialType
    ) {}
}
