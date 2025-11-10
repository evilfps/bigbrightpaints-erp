package com.bigbrightpaints.erp.modules.accounting.dto;

import java.math.BigDecimal;

public record JournalLineDto(Long accountId,
                              String accountCode,
                              String description,
                              BigDecimal debit,
                              BigDecimal credit) {}
