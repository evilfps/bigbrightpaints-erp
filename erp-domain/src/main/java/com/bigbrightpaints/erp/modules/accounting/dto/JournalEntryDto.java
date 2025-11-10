package com.bigbrightpaints.erp.modules.accounting.dto;

import java.math.BigDecimal;
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
                              List<JournalLineDto> lines) {}
