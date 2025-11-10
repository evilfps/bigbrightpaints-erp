package com.bigbrightpaints.erp.modules.invoice.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record InvoiceDto(Long id,
                         UUID publicId,
                         String invoiceNumber,
                         String status,
                         BigDecimal subtotal,
                         BigDecimal taxTotal,
                         BigDecimal totalAmount,
                         String currency,
                         LocalDate issueDate,
                         LocalDate dueDate,
                         Long dealerId,
                         String dealerName,
                         Long salesOrderId,
                         Instant createdAt,
                         List<InvoiceLineDto> lines) {}
