package com.bigbrightpaints.erp.modules.reports.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record AccountStatementEntryDto(String dealerName,
                                       LocalDate date,
                                       String reference,
                                       BigDecimal debit,
                                       BigDecimal credit,
                                       BigDecimal balance) {}
