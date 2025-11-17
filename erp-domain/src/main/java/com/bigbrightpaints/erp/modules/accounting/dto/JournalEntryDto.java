package com.bigbrightpaints.erp.modules.accounting.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record JournalEntryDto(Long id,
                              UUID publicId,
                              String referenceNumber,
                              LocalDate entryDate,
                              String memo,
                              String status,
                              Long dealerId,
                              String dealerName,
                              Long supplierId,
                              String supplierName,
                              Long accountingPeriodId,
                              String accountingPeriodLabel,
                              String accountingPeriodStatus,
                              Long reversalOfEntryId,
                              Long reversalEntryId,
                              String correctionType,
                              String correctionReason,
                              String voidReason,
                              List<JournalLineDto> lines,
                              Instant createdAt,
                              Instant updatedAt,
                              Instant postedAt,
                              String createdBy,
                              String postedBy,
                              String lastModifiedBy) {}
