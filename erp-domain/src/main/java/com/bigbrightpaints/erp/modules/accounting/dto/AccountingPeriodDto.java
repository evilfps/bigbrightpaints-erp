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
                                  String checklistNotes) {}
