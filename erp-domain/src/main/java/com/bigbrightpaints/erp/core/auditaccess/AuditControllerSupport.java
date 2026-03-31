package com.bigbrightpaints.erp.core.auditaccess;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

import org.springframework.util.StringUtils;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;

public abstract class AuditControllerSupport {

  protected AuditFeedFilter buildFilter(
      String from,
      String to,
      String module,
      String action,
      String status,
      String actor,
      String entityType,
      String reference,
      int page,
      int size) {
    return new AuditFeedFilter(
        parseOptionalDate(from, "from"),
        parseOptionalDate(to, "to"),
        module,
        action,
        status,
        actor,
        entityType,
        reference,
        page,
        size);
  }

  protected LocalDate parseOptionalDate(String raw, String fieldName) {
    if (!StringUtils.hasText(raw)) {
      return null;
    }
    String trimmed = raw.trim();
    try {
      return LocalDate.parse(trimmed);
    } catch (DateTimeParseException ex) {
      throw new ApplicationException(
              ErrorCode.VALIDATION_INVALID_DATE,
              "Invalid " + fieldName + " date format; expected ISO date yyyy-MM-dd")
          .withDetail(fieldName, trimmed);
    }
  }
}
