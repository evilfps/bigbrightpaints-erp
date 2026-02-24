package com.bigbrightpaints.erp.modules.production.dto;

import java.util.List;

public record BulkVariantResponse(
        List<VariantItem> generated,
        List<VariantItem> conflicts,
        List<VariantItem> wouldCreate,
        List<VariantItem> created
) {
    public record VariantItem(
            String sku,
            String reason,
            String productName,
            String color,
            String size
    ) {}
}
