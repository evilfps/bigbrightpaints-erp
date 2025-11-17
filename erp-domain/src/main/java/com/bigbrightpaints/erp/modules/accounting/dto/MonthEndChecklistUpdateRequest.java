package com.bigbrightpaints.erp.modules.accounting.dto;

public record MonthEndChecklistUpdateRequest(Boolean bankReconciled,
                                              Boolean inventoryCounted,
                                              String note) {}
