package com.bigbrightpaints.erp.modules.inventory.dto;

public record FinishedGoodRequest(
    String productCode,
    String name,
    String unit,
    String costingMethod,
    Long valuationAccountId,
    Long cogsAccountId,
    Long revenueAccountId,
    Long discountAccountId,
    Long taxAccountId) {}
