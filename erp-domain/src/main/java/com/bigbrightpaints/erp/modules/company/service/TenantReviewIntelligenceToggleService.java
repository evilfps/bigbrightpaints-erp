package com.bigbrightpaints.erp.modules.company.service;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.bigbrightpaints.erp.core.config.SystemSetting;
import com.bigbrightpaints.erp.core.config.SystemSettingsRepository;
import com.bigbrightpaints.erp.core.util.CompanyTime;

@Service
public class TenantReviewIntelligenceToggleService {

  private static final Logger log =
      LoggerFactory.getLogger(TenantReviewIntelligenceToggleService.class);
  private static final String REVIEW_INTELLIGENCE_ENABLED_PREFIX =
      "tenant.review-intelligence.enabled.";
  private static final String REVIEW_INTELLIGENCE_UPDATED_AT_PREFIX =
      "tenant.review-intelligence.updated-at.";

  private final SystemSettingsRepository systemSettingsRepository;

  public TenantReviewIntelligenceToggleService(SystemSettingsRepository systemSettingsRepository) {
    this.systemSettingsRepository = systemSettingsRepository;
  }

  public ToggleSnapshot snapshot(Long companyId) {
    if (companyId == null) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          "companyId is required");
    }
    String enabledValue = readSetting(enabledKey(companyId));
    String updatedAtValue = readSetting(updatedAtKey(companyId));
    return new ToggleSnapshot(companyId, parseEnabled(enabledValue), parseInstant(updatedAtValue));
  }

  public boolean isEnabledForCompany(Long companyId) {
    if (companyId == null) {
      return false;
    }
    try {
      return parseEnabled(readSetting(enabledKey(companyId)));
    } catch (RuntimeException ex) {
      log.warn(
          "Failed to resolve review-intelligence toggle state for companyId={}; defaulting to"
              + " disabled",
          companyId,
          ex);
      return false;
    }
  }

  @Transactional
  public ToggleSnapshot update(Long companyId, boolean enabled) {
    if (companyId == null) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          "companyId is required");
    }
    Instant updatedAt = CompanyTime.now();
    persistSetting(enabledKey(companyId), Boolean.toString(enabled));
    persistSetting(updatedAtKey(companyId), updatedAt.toString());
    return new ToggleSnapshot(companyId, enabled, updatedAt);
  }

  private String readSetting(String key) {
    return systemSettingsRepository.findById(key).map(SystemSetting::getValue).orElse(null);
  }

  private void persistSetting(String key, String value) {
    systemSettingsRepository.save(new SystemSetting(key, value));
  }

  private boolean parseEnabled(String raw) {
    if (!StringUtils.hasText(raw)) {
      return false;
    }
    String normalized = raw.trim().toLowerCase(Locale.ROOT);
    return normalized.equals("true")
        || normalized.equals("1")
        || normalized.equals("yes")
        || normalized.equals("on");
  }

  private Instant parseInstant(String raw) {
    if (!StringUtils.hasText(raw)) {
      return null;
    }
    try {
      return Instant.parse(raw.trim());
    } catch (DateTimeParseException ex) {
      return null;
    }
  }

  private String enabledKey(Long companyId) {
    return REVIEW_INTELLIGENCE_ENABLED_PREFIX + companyId;
  }

  private String updatedAtKey(Long companyId) {
    return REVIEW_INTELLIGENCE_UPDATED_AT_PREFIX + companyId;
  }

  public record ToggleSnapshot(Long companyId, boolean enabled, Instant updatedAt) {}
}
