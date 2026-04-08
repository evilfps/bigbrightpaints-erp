package com.bigbrightpaints.erp.modules.company;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import com.bigbrightpaints.erp.core.config.SystemSettingsRepository;
import com.bigbrightpaints.erp.core.notification.EmailService;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodStatus;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.rbac.domain.Role;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;

class TenantOnboardingControllerTest extends AbstractIntegrationTest {

  private static final String ROOT_COMPANY_CODE = "ROOT";
  private static final String SUPER_ADMIN_EMAIL = "super-admin@bbp.com";
  private static final String PASSWORD = "admin123";

  @Autowired private TestRestTemplate rest;

  @Autowired private CompanyRepository companyRepository;

  @Autowired private AccountRepository accountRepository;

  @Autowired private AccountingPeriodRepository accountingPeriodRepository;

  @Autowired private UserAccountRepository userAccountRepository;

  @Autowired private SystemSettingsRepository systemSettingsRepository;

  @SpyBean private EmailService emailService;

  @BeforeEach
  void seedSuperAdmin() {
    dataSeeder.ensureUser(
        SUPER_ADMIN_EMAIL,
        PASSWORD,
        "Super Admin",
        ROOT_COMPANY_CODE,
        List.of("ROLE_SUPER_ADMIN", "ROLE_ADMIN"));
  }

  @Test
  void onboardTenant_returns_explicit_seeded_bootstrap_contract_for_each_template() {
    String superAdminToken = loginToken(SUPER_ADMIN_EMAIL, ROOT_COMPANY_CODE);
    List<String> templateCodes = List.of("GENERIC", "INDIAN_STANDARD", "MANUFACTURING");

    for (String templateCode : templateCodes) {
      String companyCode = uniqueCode("TEN");
      String adminEmail =
          templateCode.toLowerCase(Locale.ROOT) + "." + UUID.randomUUID() + "@example.com";
      Map<String, Object> request =
          Map.of(
              "name",
              "Tenant " + templateCode,
              "code",
              companyCode,
              "timezone",
              "Asia/Kolkata",
              "firstAdminEmail",
              adminEmail,
              "firstAdminDisplayName",
              "Admin " + templateCode,
              "coaTemplateCode",
              templateCode);

      ResponseEntity<Map> response =
          rest.exchange(
              "/api/v1/superadmin/tenants/onboard",
              HttpMethod.POST,
              new HttpEntity<>(request, headers(superAdminToken, ROOT_COMPANY_CODE)),
              Map.class);

      assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
      assertThat(response.getBody()).isNotNull();
      assertThat(response.getBody().get("message"))
          .isEqualTo(
              "Tenant onboarded with seeded chart of accounts, tenant admin, and default accounting"
                  + " period");
      @SuppressWarnings("unchecked")
      Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
      assertThat(data).isNotNull();
      assertThat(data.get("templateCode").toString()).isEqualTo(templateCode);
      assertThat(data.get("bootstrapMode")).isEqualTo("SEEDED");
      assertThat(data.get("seededChartOfAccounts")).isEqualTo(true);
      assertThat(data.get("companyCode").toString()).isEqualTo(companyCode);
      assertThat(data.get("defaultAccountingPeriodCreated")).isEqualTo(true);
      assertThat(data.get("adminEmail").toString()).isEqualTo(adminEmail);
      assertThat(data.get("tenantAdminProvisioned")).isEqualTo(true);
      assertThat(data).doesNotContainKeys("temporaryPassword", "adminTemporaryPassword");

      Company company = companyRepository.findByCodeIgnoreCase(companyCode).orElseThrow();
      List<Account> accounts = accountRepository.findByCompanyOrderByCodeAsc(company);
      int declaredCount = Integer.parseInt(data.get("accountsCreated").toString());
      assertThat(accounts).hasSize(declaredCount);
      assertThat(accounts.size()).isBetween(50, 100);
      assertThat(accounts)
          .allSatisfy(
              account -> assertThat(account.getBalance()).isEqualByComparingTo(BigDecimal.ZERO));

      List<Account> roots = accountRepository.findByCompanyAndParentIsNullOrderByCodeAsc(company);
      assertThat(roots).hasSizeGreaterThanOrEqualTo(6);
      assertThat(roots)
          .extracting(Account::getType)
          .containsExactlyInAnyOrder(
              com.bigbrightpaints.erp.modules.accounting.domain.AccountType.ASSET,
              com.bigbrightpaints.erp.modules.accounting.domain.AccountType.LIABILITY,
              com.bigbrightpaints.erp.modules.accounting.domain.AccountType.EQUITY,
              com.bigbrightpaints.erp.modules.accounting.domain.AccountType.REVENUE,
              com.bigbrightpaints.erp.modules.accounting.domain.AccountType.COGS,
              com.bigbrightpaints.erp.modules.accounting.domain.AccountType.EXPENSE);
      long childrenCount =
          roots.stream()
              .mapToLong(
                  root ->
                      accountRepository.findByCompanyAndParentOrderByCodeAsc(company, root).size())
              .sum();
      assertThat(childrenCount).isGreaterThan(0L);
      assertThat(accounts)
          .filteredOn(account -> account.getHierarchyLevel() != null)
          .allSatisfy(account -> assertThat(account.getHierarchyLevel()).isGreaterThanOrEqualTo(1));
      assertThat(accounts)
          .filteredOn(account -> account.getParent() == null)
          .allSatisfy(account -> assertThat(account.getHierarchyLevel()).isEqualTo(1));
      assertThat(accounts)
          .filteredOn(account -> account.getParent() != null)
          .allSatisfy(account -> assertThat(account.getHierarchyLevel()).isGreaterThan(1));

      assertThat(company.getDefaultInventoryAccountId()).isNotNull();
      assertThat(company.getDefaultCogsAccountId()).isNotNull();
      assertThat(company.getDefaultRevenueAccountId()).isNotNull();
      assertThat(company.getDefaultDiscountAccountId()).isNotNull();
      assertThat(company.getDefaultTaxAccountId()).isNotNull();

      assertThat(
              accountingPeriodRepository.findFirstByCompanyAndStatusOrderByStartDateDesc(
                  company, AccountingPeriodStatus.OPEN))
          .isPresent();
      assertThat(data.get("accountingPeriodId")).isNotNull();

      UserAccount admin =
          userAccountRepository
              .findByEmailIgnoreCaseAndAuthScopeCodeIgnoreCase(adminEmail, companyCode)
              .orElseThrow();
      assertThat(admin.getCompany()).extracting(Company::getCode).isEqualTo(companyCode);
      assertThat(admin.getRoles()).extracting(Role::getName).contains("ROLE_ADMIN");
      assertThat(admin.isMustChangePassword()).isTrue();
    }

    assertThat(systemSettingsRepository.findById("auto-approval.enabled")).isPresent();
    assertThat(systemSettingsRepository.findById("period-lock.enforced")).isPresent();
    assertThat(systemSettingsRepository.findById("cors.allowed-origins")).isNotPresent();
  }

  @Test
  void onboardTenant_allows_first_admin_login_and_immediate_auth_me_company_binding() {
    String superAdminToken = loginToken(SUPER_ADMIN_EMAIL, ROOT_COMPANY_CODE);
    String companyCode = uniqueCode("E2E");
    String adminEmail = "first-admin-" + UUID.randomUUID() + "@example.com";
    String adminDisplayName = "First Admin";
    AtomicReference<String> temporaryPassword = new AtomicReference<>();

    doAnswer(
            invocation -> {
              temporaryPassword.set(invocation.getArgument(2, String.class));
              return null;
            })
        .when(emailService)
        .sendUserCredentialsEmailRequired(
            eq(adminEmail), eq(adminDisplayName), anyString(), eq(companyCode));

    ResponseEntity<Map> onboardResponse =
        rest.exchange(
            "/api/v1/superadmin/tenants/onboard",
            HttpMethod.POST,
            new HttpEntity<>(
                Map.of(
                    "name",
                    "E2E Tenant",
                    "code",
                    companyCode,
                    "timezone",
                    "Asia/Kolkata",
                    "firstAdminEmail",
                    adminEmail,
                    "firstAdminDisplayName",
                    adminDisplayName,
                    "coaTemplateCode",
                    "GENERIC"),
                headers(superAdminToken, ROOT_COMPANY_CODE)),
            Map.class);

    assertThat(onboardResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(temporaryPassword.get()).isNotBlank();

    ResponseEntity<Map> loginResponse =
        rest.postForEntity(
            "/api/v1/auth/login",
            Map.of(
                "email", adminEmail,
                "password", temporaryPassword.get(),
                "companyCode", companyCode),
            Map.class);

    assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(loginResponse.getBody()).isNotNull();
    assertThat(loginResponse.getBody()).containsEntry("companyCode", companyCode);
    assertThat(loginResponse.getBody()).containsEntry("displayName", adminDisplayName);
    assertThat(loginResponse.getBody()).containsEntry("mustChangePassword", true);

    String accessToken = loginResponse.getBody().get("accessToken").toString();
    ResponseEntity<Map> meResponse =
        rest.exchange(
            "/api/v1/auth/me",
            HttpMethod.GET,
            new HttpEntity<>(headers(accessToken, companyCode)),
            Map.class);

    assertThat(meResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(meResponse.getBody()).isNotNull();
    @SuppressWarnings("unchecked")
    Map<String, Object> meData = (Map<String, Object>) meResponse.getBody().get("data");
    assertThat(meData).isNotNull();
    assertThat(meData).containsEntry("email", adminEmail);
    assertThat(meData).containsEntry("displayName", adminDisplayName);
    assertThat(meData).containsEntry("companyCode", companyCode);
    assertThat(meData).containsEntry("mustChangePassword", true);
    assertThat(meData).doesNotContainKey("companyId");
    @SuppressWarnings("unchecked")
    List<String> roles = (List<String>) meData.get("roles");
    assertThat(roles).contains("ROLE_ADMIN");

    UserAccount admin =
        userAccountRepository
            .findByEmailIgnoreCaseAndAuthScopeCodeIgnoreCase(adminEmail, companyCode)
            .orElseThrow();
    assertThat(admin.getCompany()).extracting(Company::getCode).isEqualTo(companyCode);
  }

  private String uniqueCode(String prefix) {
    return prefix
        + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase(Locale.ROOT);
  }

  private HttpHeaders headers(String token, String companyCode) {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(token);
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("X-Company-Code", companyCode);
    return headers;
  }

  private String loginToken(String email, String companyCode) {
    return loginToken(email, companyCode, PASSWORD);
  }

  private String loginToken(String email, String companyCode, String password) {
    Map<String, Object> request =
        Map.of(
            "email", email,
            "password", password,
            "companyCode", companyCode);
    ResponseEntity<Map> response = rest.postForEntity("/api/v1/auth/login", request, Map.class);
    return (String) response.getBody().get("accessToken");
  }
}
