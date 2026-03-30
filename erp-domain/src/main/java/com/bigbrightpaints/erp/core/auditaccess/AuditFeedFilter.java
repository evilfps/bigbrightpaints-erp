package com.bigbrightpaints.erp.core.auditaccess;

import java.time.LocalDate;

import org.springframework.util.StringUtils;

public record AuditFeedFilter(
    LocalDate from,
    LocalDate to,
    String module,
    String action,
    String status,
    String actor,
    String entityType,
    String reference,
    int page,
    int size) {

  private static final int MAX_PAGE_SIZE = 200;
  private static final int MAX_MERGE_WINDOW = 5000;

  public int safePage() {
    return Math.max(page, 0);
  }

  public int safeSize() {
    return Math.max(1, Math.min(size, MAX_PAGE_SIZE));
  }

  public int fetchLimit() {
    return Math.max(safeSize(), Math.min(requestedWindow(), MAX_MERGE_WINDOW));
  }

  public boolean exceedsMergeWindow() {
    return requestedWindow() > MAX_MERGE_WINDOW;
  }

  public int maxMergeWindow() {
    return MAX_MERGE_WINDOW;
  }

  public String normalizedModule() {
    return normalize(module);
  }

  public String normalizedAction() {
    return normalize(action);
  }

  public String normalizedStatus() {
    return normalize(status);
  }

  public String normalizedActor() {
    return normalize(actor);
  }

  public String normalizedEntityType() {
    return normalize(entityType);
  }

  public String normalizedReference() {
    return normalize(reference);
  }

  private static String normalize(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }

  private int requestedWindow() {
    long requested = ((long) safePage() + 1L) * safeSize();
    return (int) Math.min(requested, Integer.MAX_VALUE);
  }
}
