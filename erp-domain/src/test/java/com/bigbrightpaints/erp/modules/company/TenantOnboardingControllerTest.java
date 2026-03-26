package com.bigbrightpaints.erp.modules.company;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import com.bigbrightpaints.erp.core.config.SystemSettingsRepository;
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
      assertThat(data).doesNotContainKey("adminTemporaryPassword");
      assertThat(data.get("credentialsEmailSent")).isEqualTo(true);
      assertThat(data.get("credentialsEmailedAt")).isNotNull();
      assertThat(data.get("mainAdminUserId")).isNotNull();

      Company company = companyRepository.findByCodeIgnoreCase(companyCode).orElseThrow();
      List<Account> accounts = accountRepository.findByCompanyOrderByCodeAsc(company);
      int declaredCount = Integer.parseInt(data.get("accountsCreated").toString());
      assertThat(accounts).hasSize(declaredCount);
      assertThat(accounts.size()).isBetween(50, 100);
      assertThat(accounts)
          .allSatisfy(
              account -> assertThat(account.getBalance()).isEqualByComparingTo(BigDecimal.ZERO));

      List<Account> roots = accountRepository.findByCompanyAndParentIsNullOrderByCodeAsc(company);
      assertThat(roots).isNotEmpty();
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

      assertThat(
              accountingPeriodRepository.findFirstByCompanyAndStatusOrderByStartDateDesc(
                  company, AccountingPeriodStatus.OPEN))
          .isPresent();
      assertThat(data.get("accountingPeriodId")).isNotNull();

      UserAccount admin = userAccountRepository.findByEmailIgnoreCase(adminEmail).orElseThrow();
      assertThat(admin.getCompanies()).extracting(Company::getCode).contains(companyCode);
      assertThat(admin.getRoles()).extracting(Role::getName).contains("ROLE_ADMIN");
      assertThat(admin.isMustChangePassword()).isTrue();
    }

    assertThat(systemSettingsRepository.findById("auto-approval.enabled")).isPresent();
    assertThat(systemSettingsRepository.findById("period-lock.enforced")).isPresent();
    assertThat(systemSettingsRepository.findById("cors.allowed-origins")).isNotPresent();
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
    Map<String, Object> request =
        Map.of(
            "email", email,
            "password", PASSWORD,
            "companyCode", companyCode);
    ResponseEntity<Map> response = rest.postForEntity("/api/v1/auth/login", request, Map.class);
    return (String) response.getBody().get("accessToken");
  }
}
