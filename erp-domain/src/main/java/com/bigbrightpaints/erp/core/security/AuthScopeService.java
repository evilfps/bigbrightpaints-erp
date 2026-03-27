package com.bigbrightpaints.erp.core.security;

import java.util.Locale;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.bigbrightpaints.erp.core.config.SystemSetting;
import com.bigbrightpaints.erp.core.config.SystemSettingsRepository;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;

@Service
public class AuthScopeService {

  public static final String KEY_PLATFORM_AUTH_CODE = "auth.platform.code";
  public static final String DEFAULT_PLATFORM_AUTH_CODE = "PLATFORM";

  private final SystemSettingsRepository settingsRepository;
  private final CompanyRepository companyRepository;
  private final UserAccountRepository userAccountRepository;

  public AuthScopeService(
      SystemSettingsRepository settingsRepository,
      CompanyRepository companyRepository,
      UserAccountRepository userAccountRepository) {
    this.settingsRepository = settingsRepository;
    this.companyRepository = companyRepository;
    this.userAccountRepository = userAccountRepository;
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

  @Transactional
  public String updatePlatformScopeCode(String requestedCode) {
    String normalized = requireScopeCode(requestedCode);
    if (companyRepository.findByCodeIgnoreCase(normalized).isPresent()) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          "Platform auth code conflicts with tenant company code: " + normalized);
    }
    String current = getPlatformScopeCode();
    if (!current.equalsIgnoreCase(normalized)) {
      List<UserAccount> platformUsers =
          userAccountRepository.findAllByAuthScopeCodeIgnoreCase(current);
      for (UserAccount platformUser : platformUsers) {
        if (userAccountRepository.existsByEmailIgnoreCaseAndAuthScopeCodeIgnoreCaseAndIdNot(
            platformUser.getEmail(), normalized, platformUser.getId())) {
          throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
              "Platform auth code switch conflicts with existing scoped account: "
                  + platformUser.getEmail());
        }
      }
      for (UserAccount platformUser : platformUsers) {
        platformUser.setAuthScopeCode(normalized);
        platformUser.clearCompany();
      }
      userAccountRepository.saveAll(platformUsers);
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
