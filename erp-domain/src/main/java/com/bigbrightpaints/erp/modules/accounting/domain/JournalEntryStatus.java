package com.bigbrightpaints.erp.modules.accounting.domain;

import java.util.Locale;

public enum JournalEntryStatus {
  DRAFT,
  POSTED,
  PAID,
  SETTLED,
  CLOSED,
  REVERSED,
  VOID,
  VOIDED,
  CANCELLED,
  BLOCKED,
  FAILED;

  public static JournalEntryStatus from(String value) {
    if (value == null || value.isBlank()) {
      return DRAFT;
    }
    return JournalEntryStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
  }
}
