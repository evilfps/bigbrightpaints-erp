package com.bigbrightpaints.erp.modules.auth.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;

@ExtendWith(MockitoExtension.class)
class UserProfileServiceTest {

  @Mock private UserAccountRepository userAccountRepository;

  private UserProfileService userProfileService;

  @BeforeEach
  void setUp() {
    userProfileService = new UserProfileService(userAccountRepository);
  }

  @Test
  void view_usesAuthScopeCodeWhenPresent() {
    UserAccount user = new UserAccount("user@example.com", "PLATFORM", "hash", "User");

    assertThat(userProfileService.view(user).companyCode()).isEqualTo("PLATFORM");
  }

  @Test
  void view_fallsBackToBoundCompanyCodeWhenAuthScopeMissing() {
    UserAccount user = new UserAccount("user@example.com", "hash", "User");
    user.setAuthScopeCode(null);
    Company company = new Company();
    company.setCode("ACME");
    user.setCompany(company);

    assertThat(userProfileService.view(user).companyCode()).isEqualTo("ACME");
  }

  @Test
  void resolveCompanyCode_handlesNullUserAndMissingCompany() {
    assertThat(
            (String)
                ReflectionTestUtils.invokeMethod(
                    userProfileService, "resolveCompanyCode", (Object) null))
        .isNull();

    UserAccount user = new UserAccount("user@example.com", "hash", "User");
    user.setAuthScopeCode(" ");
    user.clearCompany();

    assertThat(userProfileService.view(user).companyCode()).isNull();
  }

  @Test
  void view_returnsNullWhenCompanyExistsButCodeIsBlank() {
    UserAccount user = new UserAccount("user@example.com", "hash", "User");
    user.setAuthScopeCode(null);
    Company company = new Company();
    company.setCode(" ");
    user.setCompany(company);

    assertThat(userProfileService.view(user).companyCode()).isNull();
  }
}
