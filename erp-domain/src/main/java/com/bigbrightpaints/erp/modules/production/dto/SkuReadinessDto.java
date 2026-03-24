package com.bigbrightpaints.erp.modules.production.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import java.util.List;

public record SkuReadinessDto(
        String sku,
        @JsonAlias("catalog")
        Stage masterReady,
        @JsonAlias("inventory")
        Stage inventoryReady,
        @JsonAlias("production")
        Stage productionReady,
        @JsonAlias("packing")
        Stage packingReady,
        @JsonAlias("sales")
        Stage salesReady,
        @JsonAlias("accounting")
        Stage accountingReady
) {

    public Stage catalog() {
        return masterReady;
    }

    public Stage inventory() {
        return inventoryReady;
    }

    public Stage production() {
        return productionReady;
    }

    public Stage packing() {
        return packingReady;
    }

    public Stage sales() {
        return salesReady;
    }

    public Stage accounting() {
        return accountingReady;
    }

    public record Stage(
            boolean ready,
            List<String> blockers
    ) {
    }
}
