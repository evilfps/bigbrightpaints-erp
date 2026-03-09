package com.bigbrightpaints.erp.modules.invoice.dto;

import com.bigbrightpaints.erp.shared.dto.DocumentLifecycleDto;
import com.bigbrightpaints.erp.shared.dto.LinkedBusinessReferenceDto;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record InvoiceDto(Long id, UUID publicId, String invoiceNumber, String status, BigDecimal subtotal, BigDecimal taxTotal, BigDecimal totalAmount, BigDecimal outstandingAmount, String currency, LocalDate issueDate, LocalDate dueDate, Long dealerId, String dealerName, Long salesOrderId, Long journalEntryId, Instant createdAt, List<InvoiceLineDto> lines, DocumentLifecycleDto lifecycle, List<LinkedBusinessReferenceDto> linkedReferences) {}
