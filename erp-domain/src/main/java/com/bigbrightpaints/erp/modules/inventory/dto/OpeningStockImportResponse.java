package com.bigbrightpaints.erp.modules.inventory.dto;

import com.bigbrightpaints.erp.modules.production.dto.SkuReadinessDto;
import java.util.List;

public record OpeningStockImportResponse(
        int rowsProcessed,
        int rawMaterialsCreated,
        int rawMaterialBatchesCreated,
        int finishedGoodsCreated,
        int finishedGoodBatchesCreated,
        List<ImportRowResult> results,
        List<ImportError> errors
) {

    public record ImportRowResult(
            long rowNumber,
            String sku,
            String stockType,
            SkuReadinessDto readiness
    ) {
    }

    public record ImportError(
            long rowNumber,
            String message,
            String sku,
            String stockType,
            SkuReadinessDto readiness
    ) {
    }
}
