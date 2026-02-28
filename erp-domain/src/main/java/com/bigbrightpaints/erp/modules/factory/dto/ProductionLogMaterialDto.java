package com.bigbrightpaints.erp.modules.factory.dto;

import java.math.BigDecimal;

public record ProductionLogMaterialDto(
        Long rawMaterialId,
        Long rawMaterialBatchId,
        String rawMaterialBatchCode,
        Long rawMaterialMovementId,
        String materialName,
        BigDecimal quantity,
        String unitOfMeasure,
        BigDecimal costPerUnit,
        BigDecimal totalCost
) {}
