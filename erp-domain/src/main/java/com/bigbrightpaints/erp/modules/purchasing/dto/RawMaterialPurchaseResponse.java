package com.bigbrightpaints.erp.modules.purchasing.dto;

import com.bigbrightpaints.erp.shared.dto.DocumentLifecycleDto;
import com.bigbrightpaints.erp.shared.dto.LinkedBusinessReferenceDto;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record RawMaterialPurchaseResponse(Long id, UUID publicId, String invoiceNumber, LocalDate invoiceDate, BigDecimal totalAmount, BigDecimal taxAmount, BigDecimal outstandingAmount, String status, String memo, Long supplierId, String supplierCode, String supplierName, Long purchaseOrderId, String purchaseOrderNumber, Long goodsReceiptId, String goodsReceiptNumber, Long journalEntryId, Instant createdAt, List<RawMaterialPurchaseLineResponse> lines, DocumentLifecycleDto lifecycle, List<LinkedBusinessReferenceDto> linkedReferences) {}
