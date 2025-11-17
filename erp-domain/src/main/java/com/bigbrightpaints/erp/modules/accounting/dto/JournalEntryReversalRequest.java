package com.bigbrightpaints.erp.modules.accounting.dto;

import java.time.LocalDate;

public record JournalEntryReversalRequest(LocalDate reversalDate,
                                          boolean voidOnly,
                                          String reason,
                                          String memo,
                                          Boolean adminOverride) {}
