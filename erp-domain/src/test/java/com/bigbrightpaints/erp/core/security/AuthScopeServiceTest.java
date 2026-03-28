package com.bigbrightpaints.erp.core.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.bigbrightpaints.erp.core.config.SystemSetting;
import com.bigbrightpaints.erp.core.config.SystemSettingsRepository;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;

@ExtendWith(MockitoExtension.class)
class AuthScopeServiceTest {

  @Mock private SystemSettingsRepository settingsRepository;
  @Mock private CompanyRepository companyRepository;
  @Mock private UserAccountRepository userAccountRepository;

  private AuthScopeService authScopeService;

  @BeforeEach
  void setUp() {
    authScopeService =
        new AuthScopeService(settingsRepository, companyRepository, userAccountRepository);
  }

  @Test
  void isPlatformScope_returnsFalseWhenScopeBlank() {
    assertThat(authScopeService.isPlatformScope("   ")).isFalse();
    verify(settingsRepository, never()).findById(AuthScopeService.KEY_PLATFORM_AUTH_CODE);
  }

  @Test
  void requireScopeCode_rejectsBlankInput() {
    assertThatThrownBy(() -> authScopeService.requireScopeCode("   "))
        .hasMessageContaining("companyCode is required");
  }

  @Test
  void updatePlatformScopeCode_skipsUserRewriteWhenCodeIsUnchanged() {
    when(settingsRepository.findById(AuthScopeService.KEY_PLATFORM_AUTH_CODE))
        .thenReturn(
            Optional.of(
                new SystemSetting(
                    AuthScopeService.KEY_PLATFORM_AUTH_CODE,
                    AuthScopeService.DEFAULT_PLATFORM_AUTH_CODE)));

    String updated = authScopeService.updatePlatformScopeCode(" platform ");

    assertThat(updated).isEqualTo(AuthScopeService.DEFAULT_PLATFORM_AUTH_CODE);
    verify(userAccountRepository, never()).findAllByAuthScopeCodeIgnoreCase(anyString());
    verify(settingsRepository)
        .save(
            org.mockito.ArgumentMatchers.argThat(
                setting ->
                    AuthScopeService.KEY_PLATFORM_AUTH_CODE.equals(setting.getKey())
                        && AuthScopeService.DEFAULT_PLATFORM_AUTH_CODE.equals(setting.getValue())));
  }

  @Test
  void updatePlatformScopeCode_rejectsScopedEmailConflict() {
    UserAccount platformUser =
        new UserAccount(
            "platform-root@bbp.com",
            AuthScopeService.DEFAULT_PLATFORM_AUTH_CODE,
            "hash",
            "Platform Root");
    ReflectionTestUtils.setField(platformUser, "id", 7L);
    when(settingsRepository.findById(AuthScopeService.KEY_PLATFORM_AUTH_CODE))
        .thenReturn(
            Optional.of(
                new SystemSetting(
                    AuthScopeService.KEY_PLATFORM_AUTH_CODE,
                    AuthScopeService.DEFAULT_PLATFORM_AUTH_CODE)));
    when(companyRepository.findByCodeIgnoreCase("ROOTCTRL")).thenReturn(Optional.empty());
    when(userAccountRepository.findAllByAuthScopeCodeIgnoreCase(
            AuthScopeService.DEFAULT_PLATFORM_AUTH_CODE))
        .thenReturn(List.of(platformUser));
    when(userAccountRepository.existsByEmailIgnoreCaseAndAuthScopeCodeIgnoreCaseAndIdNot(
            eq("platform-root@bbp.com"), eq("ROOTCTRL"), eq(7L)))
        .thenReturn(true);

    assertThatThrownBy(() -> authScopeService.updatePlatformScopeCode("rootctrl"))
        .hasMessageContaining("platform-root@bbp.com");
  }
}
