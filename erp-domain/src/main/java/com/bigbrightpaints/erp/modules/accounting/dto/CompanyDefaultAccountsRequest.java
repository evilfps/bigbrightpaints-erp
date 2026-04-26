package com.bigbrightpaints.erp.modules.accounting.dto;

import java.util.List;

public record CompanyDefaultAccountsRequest(
    Long inventoryAccountId,
    Long cogsAccountId,
    Long revenueAccountId,
    Long discountAccountId,
    Long fgDiscountAccountId,
    Long taxAccountId,
    List<String> clearAccountFields) {

  public CompanyDefaultAccountsRequest {
    clearAccountFields = clearAccountFields == null ? List.of() : List.copyOf(clearAccountFields);
  }

  public CompanyDefaultAccountsRequest(
      Long inventoryAccountId,
      Long cogsAccountId,
      Long revenueAccountId,
      Long discountAccountId,
      Long fgDiscountAccountId,
      Long taxAccountId) {
    this(
        inventoryAccountId,
        cogsAccountId,
        revenueAccountId,
        discountAccountId,
        fgDiscountAccountId,
        taxAccountId,
        List.of());
  }
}
