package com.bigbrightpaints.erp.modules.accounting.dto;

public record MonthEndChecklistItemDto(String key,
                                        String label,
                                        boolean completed,
                                        String detail) {}
