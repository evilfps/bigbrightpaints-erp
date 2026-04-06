package com.bigbrightpaints.erp.modules.accounting.dto;

public record CompanyDefaultAccountsRequest(
    Long inventoryAccountId,
    Long cogsAccountId,
    Long revenueAccountId,
    Long discountAccountId,
    Long fgDiscountAccountId,
    Long taxAccountId) {}
