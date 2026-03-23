package com.bigbrightpaints.erp.modules.accounting.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record OverdueInvoiceDto(String invoiceNumber,
                                LocalDate issueDate,
                                LocalDate dueDate,
                                long daysOverdue,
                                BigDecimal outstandingAmount) {}
