package com.bigbrightpaints.erp.orchestrator.service;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;

@Service
class IntegrationCoordinatorSupportService {

  private final CompanyRepository companyRepository;

  IntegrationCoordinatorSupportService(CompanyRepository companyRepository) {
    this.companyRepository = companyRepository;
  }

  void runWithCompanyContext(String companyId, Runnable action) {
    withCompanyContext(
        companyId,
        () -> {
          action.run();
          return null;
        });
  }

  <T> T withCompanyContext(String companyId, Supplier<T> callback) {
    String normalizedCompanyId = normalizeCompanyId(companyId);
    String previousCompany = CompanyContextHolder.getCompanyCode();
    boolean changed =
        normalizedCompanyId != null && !Objects.equals(previousCompany, normalizedCompanyId);
    if (changed) {
      CompanyContextHolder.setCompanyCode(normalizedCompanyId);
    }
    try {
      return callback.get();
    } finally {
      if (changed) {
        if (previousCompany != null) {
          CompanyContextHolder.setCompanyCode(previousCompany);
        } else {
          CompanyContextHolder.clear();
        }
      }
    }
  }

  Company requireCompany(String companyId, String operation) {
    String normalizedCompanyId = normalizeCompanyId(companyId);
    if (normalizedCompanyId == null) {
      throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT, "Missing companyId")
          .withDetail("field", "companyId")
          .withDetail("operation", operation);
    }
    return companyRepository
        .findByCodeIgnoreCase(normalizedCompanyId)
        .or(() -> parseLong(normalizedCompanyId).flatMap(companyRepository::findById))
        .orElseThrow(
            () ->
                new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT, "Unknown companyId")
                    .withDetail("field", "companyId")
                    .withDetail("operation", operation)
                    .withDetail(
                        "safeIdentifier",
                        CorrelationIdentifierSanitizer.safeIdentifierForLog(normalizedCompanyId)));
  }

  String normalizeCompanyId(String companyId) {
    if (companyId == null) {
      return null;
    }
    String trimmed = companyId.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  Optional<Long> parseLong(String value) {
    if (!StringUtils.hasText(value)) {
      return Optional.empty();
    }
    try {
      return Optional.of(Long.parseLong(value));
    } catch (NumberFormatException ex) {
      return Optional.empty();
    }
  }

  String correlationMemo(String baseMemo, String traceId, String idempotencyKey) {
    StringBuilder builder = new StringBuilder(baseMemo != null ? baseMemo : "");
    String sanitizedTraceId = CorrelationIdentifierSanitizer.sanitizeOptionalTraceId(traceId);
    String sanitizedIdempotencyKey =
        CorrelationIdentifierSanitizer.sanitizeOptionalIdempotencyKey(idempotencyKey);
    if (StringUtils.hasText(sanitizedTraceId)) {
      builder.append(" [trace=").append(sanitizedTraceId).append("]");
    }
    if (StringUtils.hasText(sanitizedIdempotencyKey)) {
      builder.append(" [idem=").append(sanitizedIdempotencyKey).append("]");
    }
    return builder.toString();
  }

  String correlationSuffix(String traceId, String idempotencyKey) {
    StringBuilder builder = new StringBuilder();
    String safeTraceId = CorrelationIdentifierSanitizer.safeTraceForLog(traceId);
    String safeIdempotencyKey =
        CorrelationIdentifierSanitizer.safeIdempotencyForLog(idempotencyKey);
    if (StringUtils.hasText(safeTraceId)) {
      builder.append(" [trace=").append(safeTraceId).append("]");
    }
    if (StringUtils.hasText(safeIdempotencyKey)) {
      builder.append(" [idem=").append(safeIdempotencyKey).append("]");
    }
    return builder.toString();
  }
}
