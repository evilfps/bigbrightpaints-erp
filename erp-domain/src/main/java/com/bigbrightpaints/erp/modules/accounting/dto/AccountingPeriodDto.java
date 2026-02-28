package com.bigbrightpaints.erp.modules.accounting.dto;

import java.time.Instant;
import java.time.LocalDate;

public record AccountingPeriodDto(Long id,
                                  int year,
                                  int month,
                                  LocalDate startDate,
                                  LocalDate endDate,
                                  String label,
                                  String status,
                                  boolean bankReconciled,
                                  Instant bankReconciledAt,
                                  String bankReconciledBy,
                                  boolean inventoryCounted,
                                  Instant inventoryCountedAt,
                                  String inventoryCountedBy,
                                  Instant closedAt,
                                  String closedBy,
                                  String closedReason,
                                  Instant lockedAt,
                                  String lockedBy,
                                  String lockReason,
                                  Instant reopenedAt,
                                  String reopenedBy,
                                  String reopenReason,
                                  Long closingJournalEntryId,
                                  String checklistNotes,
                                  String costingMethod) {}
