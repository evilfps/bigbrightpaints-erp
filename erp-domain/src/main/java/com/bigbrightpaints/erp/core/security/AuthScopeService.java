package com.bigbrightpaints.erp.core.security;

import java.util.Locale;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.bigbrightpaints.erp.core.config.SystemSetting;
import com.bigbrightpaints.erp.core.config.SystemSettingsRepository;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;

@Service
public class AuthScopeService {

  public static final String KEY_PLATFORM_AUTH_CODE = "auth.platform.code";
  public static final String DEFAULT_PLATFORM_AUTH_CODE = "PLATFORM";

  private final SystemSettingsRepository settingsRepository;
  private final CompanyRepository companyRepository;

  public AuthScopeService(
      SystemSettingsRepository settingsRepository, CompanyRepository companyRepository) {
    this.settingsRepository = settingsRepository;
    this.companyRepository = companyRepository;
  }

  public String getPlatformScopeCode() {
    return settingsRepository
        .findById(KEY_PLATFORM_AUTH_CODE)
        .map(SystemSetting::getValue)
        .filter(StringUtils::hasText)
        .map(this::normalizeScopeCode)
        .orElse(DEFAULT_PLATFORM_AUTH_CODE);
  }

  public boolean isPlatformScope(String authScopeCode) {
    if (!StringUtils.hasText(authScopeCode)) {
      return false;
    }
    return getPlatformScopeCode().equalsIgnoreCase(authScopeCode.trim());
  }

  public String updatePlatformScopeCode(String requestedCode) {
    String normalized = requireScopeCode(requestedCode);
    if (companyRepository.findByCodeIgnoreCase(normalized).isPresent()) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          "Platform auth code conflicts with tenant company code: " + normalized);
    }
    settingsRepository.save(new SystemSetting(KEY_PLATFORM_AUTH_CODE, normalized));
    return normalized;
  }

  public String requireScopeCode(String authScopeCode) {
    if (!StringUtils.hasText(authScopeCode)) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          "companyCode is required");
    }
    return normalizeScopeCode(authScopeCode);
  }

  public String normalizeScopeCode(String authScopeCode) {
    return authScopeCode.trim().toUpperCase(Locale.ROOT);
  }
}
