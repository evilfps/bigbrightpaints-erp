package com.bigbrightpaints.erp.modules.purchasing.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record RawMaterialPurchaseResponse(Long id,
                                          UUID publicId,
                                          String invoiceNumber,
                                          LocalDate invoiceDate,
                                          BigDecimal totalAmount,
                                          String status,
                                          String memo,
                                          Long supplierId,
                                          String supplierCode,
                                          String supplierName,
                                          Long journalEntryId,
                                          Instant createdAt,
                                          List<RawMaterialPurchaseLineResponse> lines) {}
