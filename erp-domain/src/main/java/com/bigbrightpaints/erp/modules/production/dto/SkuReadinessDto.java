package com.bigbrightpaints.erp.modules.production.dto;

import java.util.List;

public record SkuReadinessDto(
        String sku,
        Stage catalog,
        Stage inventory,
        Stage production,
        Stage sales
) {

    public record Stage(
            boolean ready,
            List<String> blockers
    ) {
    }
}
