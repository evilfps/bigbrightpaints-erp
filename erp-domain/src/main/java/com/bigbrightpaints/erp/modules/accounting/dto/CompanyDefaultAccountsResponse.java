package com.bigbrightpaints.erp.modules.accounting.dto;

public record CompanyDefaultAccountsResponse(
    Long inventoryAccountId,
    Long cogsAccountId,
    Long revenueAccountId,
    Long discountAccountId,
    Long fgDiscountAccountId,
    Long taxAccountId) {}
