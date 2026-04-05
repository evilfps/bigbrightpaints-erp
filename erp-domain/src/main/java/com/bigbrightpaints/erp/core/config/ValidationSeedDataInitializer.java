package com.bigbrightpaints.erp.core.config;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.bigbrightpaints.erp.core.security.AuthScopeService;
import com.bigbrightpaints.erp.core.security.CryptoService;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.admin.domain.ExportRequest;
import com.bigbrightpaints.erp.modules.admin.domain.ExportRequestRepository;
import com.bigbrightpaints.erp.modules.admin.domain.SupportTicket;
import com.bigbrightpaints.erp.modules.admin.domain.SupportTicketCategory;
import com.bigbrightpaints.erp.modules.admin.domain.SupportTicketRepository;
import com.bigbrightpaints.erp.modules.admin.domain.SupportTicketStatus;
import com.bigbrightpaints.erp.modules.admin.dto.ExportApprovalStatus;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import com.bigbrightpaints.erp.modules.auth.service.PasswordPolicy;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.invoice.domain.Invoice;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceLine;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceRepository;
import com.bigbrightpaints.erp.modules.rbac.domain.Role;
import com.bigbrightpaints.erp.modules.rbac.domain.RoleRepository;
import com.bigbrightpaints.erp.modules.sales.domain.CreditRequest;
import com.bigbrightpaints.erp.modules.sales.domain.CreditRequestRepository;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;

@Configuration
@Profile("validation-seed")
public class ValidationSeedDataInitializer {

  private static final Logger log = LoggerFactory.getLogger(ValidationSeedDataInitializer.class);
  private static final LocalDate VALIDATION_INVOICE_ISSUE_DATE = LocalDate.of(2026, 1, 15);
  private static final LocalDate VALIDATION_INVOICE_DUE_DATE = LocalDate.of(2026, 1, 30);
  private static final BigDecimal VALIDATION_INVOICE_SUBTOTAL = new BigDecimal("1000.00");
  private static final BigDecimal VALIDATION_INVOICE_TAX = new BigDecimal("180.00");
  private static final BigDecimal VALIDATION_INVOICE_TOTAL = new BigDecimal("1180.00");
  private static final BigDecimal VALIDATION_INVOICE_TAX_RATE = new BigDecimal("18.00");
  private static final BigDecimal VALIDATION_HALF_TAX = new BigDecimal("90.00");
  private static final String VALIDATION_INVOICE_NOTES = "Validation seed invoice fixture";
  private static final String VALIDATION_INVOICE_PRODUCT_CODE = "VAL-SEED-ITEM";
  private static final String VALIDATION_INVOICE_LINE_DESCRIPTION = "Validation seed invoice line";
  private static final String HOLD_COMPANY_CODE = "HOLD";
  private static final String BLOCKED_COMPANY_CODE = "BLOCK";
  private static final String QUOTA_COMPANY_CODE = "QUOTA";
  private static final String VALIDATION_MFA_SECRET = "JBSWY3DPEHPK3PXP";
  private static final List<String> VALIDATION_MFA_RECOVERY_CODES =
      List.of("VALMFA0001", "VALMFA0002", "VALMFA0003");
  private static final String SEED_EXPORT_REPORT_TYPE = "SALES_LEDGER";
  private static final String SEED_EXPORT_PARAMETERS =
      "{\"range\":\"LAST_30_DAYS\",\"company\":\"MOCK\",\"seed\":\"validation-export-pending\"}";
  private static final String SEED_SUPPORT_SUBJECT = "Validation dealer support escalation";
  private static final String SEED_CREDIT_REASON = "Validation pending credit request";
  private static final Instant LOCKED_UNTIL_PLACEHOLDER = Instant.parse("2099-01-01T00:00:00Z");

  @Bean
  CommandLineRunner seedValidationActors(
      CompanyRepository companyRepository,
      RoleRepository roleRepository,
      UserAccountRepository userAccountRepository,
      DealerRepository dealerRepository,
      AccountRepository accountRepository,
      InvoiceRepository invoiceRepository,
      ExportRequestRepository exportRequestRepository,
      SupportTicketRepository supportTicketRepository,
      CreditRequestRepository creditRequestRepository,
      SystemSettingsRepository systemSettingsRepository,
      PasswordEncoder passwordEncoder,
      CryptoService cryptoService,
      PasswordPolicy passwordPolicy,
      AuthScopeService authScopeService,
      Environment environment,
      @Value("${erp.validation-seed.enabled:false}") boolean validationSeedEnabled,
      @Value("${erp.validation-seed.password:}") String defaultPassword) {
    return args -> {
      if (!validationSeedEnabled) {
        log.info(
            "Validation seed disabled; set erp.validation-seed.enabled=true to seed local"
                + " validation actors.");
        return;
      }

      ensureMockProfileEnabled(environment);
      String validatedPassword = requireStrongPassword(passwordPolicy, defaultPassword);

      Company mockCompany = ensureCompany(companyRepository, "MOCK", "Mock Training Co");
      Company rivalCompany = ensureCompany(companyRepository, "RIVAL", "Rival Validation Co");
      Company holdCompany =
          ensureCompany(companyRepository, HOLD_COMPANY_CODE, "Hold Validation Co");
      Company blockedCompany =
          ensureCompany(companyRepository, BLOCKED_COMPANY_CODE, "Blocked Validation Co");
      Company quotaCompany =
          ensureCompany(companyRepository, QUOTA_COMPANY_CODE, "Quota Validation Co");
      String platformScopeCode = authScopeService.getPlatformScopeCode();

      ensureRuntimePolicy(systemSettingsRepository, mockCompany, null, null, null, null, null);
      ensureRuntimePolicy(
          systemSettingsRepository, holdCompany, "HOLD", "COMPLIANCE_REVIEW", null, null, null);
      ensureRuntimePolicy(
          systemSettingsRepository, blockedCompany, "BLOCKED", "ABUSE_INCIDENT", null, null, null);
      ensureRuntimePolicy(
          systemSettingsRepository, quotaCompany, "ACTIVE", "ACTIVE_USER_LIMIT", 1, null, null);

      Role admin = ensureRole(roleRepository, "ROLE_ADMIN", "Administrator");
      Role accounting = ensureRole(roleRepository, "ROLE_ACCOUNTING", "Accounting");
      Role sales = ensureRole(roleRepository, "ROLE_SALES", "Sales");
      Role factory = ensureRole(roleRepository, "ROLE_FACTORY", "Factory");
      Role dealerRole = ensureRole(roleRepository, "ROLE_DEALER", "Dealer portal");
      Role superAdmin =
          ensureRole(roleRepository, "ROLE_SUPER_ADMIN", "Platform super administrator");

      Account mockReceivable =
          ensureAccount(
              accountRepository, mockCompany, "AR", "Accounts Receivable", AccountType.ASSET);
      Account rivalReceivable =
          ensureAccount(
              accountRepository, rivalCompany, "AR", "Accounts Receivable", AccountType.ASSET);

      UserAccount mockAdmin =
          ensureUser(
              userAccountRepository,
              passwordEncoder,
              cryptoService,
              "validation.admin@example.com",
              "Validation Admin",
              validatedPassword,
              mockCompany.getCode(),
              List.of(mockCompany),
              List.of(admin, accounting, sales));
      ensureUser(
          userAccountRepository,
          passwordEncoder,
          cryptoService,
          "validation.mustchange.admin@example.com",
          "Validation Must Change Admin",
          validatedPassword,
          mockCompany.getCode(),
          List.of(mockCompany),
          List.of(admin),
          true,
          null,
          false,
          null,
          List.of());
      ensureUser(
          userAccountRepository,
          passwordEncoder,
          cryptoService,
          "validation.locked.admin@example.com",
          "Validation Locked Admin",
          validatedPassword,
          mockCompany.getCode(),
          List.of(mockCompany),
          List.of(admin),
          false,
          LOCKED_UNTIL_PLACEHOLDER,
          false,
          null,
          List.of());
      ensureUser(
          userAccountRepository,
          passwordEncoder,
          cryptoService,
          "validation.accounting@example.com",
          "Validation Accounting",
          validatedPassword,
          mockCompany.getCode(),
          List.of(mockCompany),
          List.of(accounting));
      ensureUser(
          userAccountRepository,
          passwordEncoder,
          cryptoService,
          "validation.sales@example.com",
          "Validation Sales",
          validatedPassword,
          mockCompany.getCode(),
          List.of(mockCompany),
          List.of(sales));
      ensureUser(
          userAccountRepository,
          passwordEncoder,
          cryptoService,
          "validation.factory@example.com",
          "Validation Factory",
          validatedPassword,
          mockCompany.getCode(),
          List.of(mockCompany),
          List.of(factory));
      ensureUser(
          userAccountRepository,
          passwordEncoder,
          cryptoService,
          "validation.mfa.admin@example.com",
          "Validation MFA Admin",
          validatedPassword,
          mockCompany.getCode(),
          List.of(mockCompany),
          List.of(admin),
          false,
          null,
          true,
          VALIDATION_MFA_SECRET,
          VALIDATION_MFA_RECOVERY_CODES);

      UserAccount dealerUser =
          ensureUser(
              userAccountRepository,
              passwordEncoder,
              cryptoService,
              "validation.dealer@example.com",
              "Validation Dealer",
              validatedPassword,
              mockCompany.getCode(),
              List.of(mockCompany),
              List.of(dealerRole));
      Dealer mockDealer =
          ensureDealer(
              dealerRepository,
              mockCompany,
              mockReceivable,
              dealerUser,
              "VALID-DEALER",
              "Validation Dealer");

      UserAccount rivalDealerUser =
          ensureUser(
              userAccountRepository,
              passwordEncoder,
              cryptoService,
              "validation.rival.dealer@example.com",
              "Rival Validation Dealer",
              validatedPassword,
              rivalCompany.getCode(),
              List.of(rivalCompany),
              List.of(dealerRole));
      Dealer rivalDealer =
          ensureDealer(
              dealerRepository,
              rivalCompany,
              rivalReceivable,
              rivalDealerUser,
              "RIVAL-DEALER",
              "Rival Validation Dealer");
      ensureValidationInvoiceFixture(invoiceRepository, mockDealer, "VAL-MOCK-INV-001");
      ensureValidationInvoiceFixture(invoiceRepository, rivalDealer, "VAL-RIVAL-INV-001");
      ensurePendingExportRequest(exportRequestRepository, mockCompany, mockAdmin);
      ensurePendingSupportTicket(supportTicketRepository, mockCompany, dealerUser);
      ensurePendingCreditRequest(creditRequestRepository, mockCompany, mockDealer, dealerUser);

      UserAccount rivalAdmin =
          ensureUser(
              userAccountRepository,
              passwordEncoder,
              cryptoService,
              "validation.rival.admin@example.com",
              "Rival Validation Admin",
              validatedPassword,
              rivalCompany.getCode(),
              List.of(rivalCompany),
              List.of(admin));
      UserAccount holdAdmin =
          ensureUser(
              userAccountRepository,
              passwordEncoder,
              cryptoService,
              "validation.hold.admin@example.com",
              "Validation Hold Admin",
              validatedPassword,
              holdCompany.getCode(),
              List.of(holdCompany),
              List.of(admin));
      UserAccount blockedAdmin =
          ensureUser(
              userAccountRepository,
              passwordEncoder,
              cryptoService,
              "validation.blocked.admin@example.com",
              "Validation Blocked Admin",
              validatedPassword,
              blockedCompany.getCode(),
              List.of(blockedCompany),
              List.of(admin));
      UserAccount quotaAlpha =
          ensureUser(
              userAccountRepository,
              passwordEncoder,
              cryptoService,
              "validation.quota.alpha@example.com",
              "Validation Quota Alpha",
              validatedPassword,
              quotaCompany.getCode(),
              List.of(quotaCompany),
              List.of(admin));
      ensureUser(
          userAccountRepository,
          passwordEncoder,
          cryptoService,
          "validation.quota.beta@example.com",
          "Validation Quota Beta",
          validatedPassword,
          quotaCompany.getCode(),
          List.of(quotaCompany),
          List.of(admin));
      UserAccount superAdminUser =
          ensureUser(
              userAccountRepository,
              passwordEncoder,
              cryptoService,
              "validation.superadmin@example.com",
              "Validation Super Admin",
              validatedPassword,
              platformScopeCode,
              List.of(),
              List.of(admin, superAdmin));

      attachMainAdmin(companyRepository, mockCompany, mockAdmin);
      attachMainAdmin(companyRepository, rivalCompany, rivalAdmin);
      attachMainAdmin(companyRepository, holdCompany, holdAdmin);
      attachMainAdmin(companyRepository, blockedCompany, blockedAdmin);
      attachMainAdmin(companyRepository, quotaCompany, quotaAlpha);

      log.info(
          "Validation seed logins ready: admin={}, mustChange={}, locked={}, accounting={},"
              + " sales={}, factory={}, mfaAdmin={}, dealer={}, superadmin={} (password from"
              + " erp.validation-seed.password / ERP_VALIDATION_SEED_PASSWORD)",
          mockAdmin.getEmail(),
          "validation.mustchange.admin@example.com",
          "validation.locked.admin@example.com",
          "validation.accounting@example.com",
          "validation.sales@example.com",
          "validation.factory@example.com",
          "validation.mfa.admin@example.com",
          "validation.dealer@example.com",
          superAdminUser.getEmail());
      log.info(
          "Validation state companies ready: hold={} blocked={} quota={} with admin actors"
              + " [{}, {}, {}].",
          holdCompany.getCode(),
          blockedCompany.getCode(),
          quotaCompany.getCode(),
          holdAdmin.getEmail(),
          blockedAdmin.getEmail(),
          quotaAlpha.getEmail());
      log.info(
          "Validation admin inbox fixtures ready: exportReportType={} supportSubject={}"
              + " creditReason={}.",
          SEED_EXPORT_REPORT_TYPE,
          SEED_SUPPORT_SUBJECT,
          SEED_CREDIT_REASON);
      log.info(
          "Validation seed ready for companies [MOCK, RIVAL, HOLD, BLOCK, QUOTA] plus platform"
              + " scope {}.",
          platformScopeCode);
    };
  }

  private void ensureMockProfileEnabled(Environment environment) {
    boolean mockProfileEnabled = Arrays.asList(environment.getActiveProfiles()).contains("mock");
    if (!mockProfileEnabled) {
      throw new IllegalStateException(
          "Validation seed may only run when the mock profile is active for local validation.");
    }
  }

  private String requireStrongPassword(PasswordPolicy passwordPolicy, String password) {
    String candidate = password == null ? "" : password.trim();
    List<String> violations = passwordPolicy.validate(candidate);
    if (!violations.isEmpty()) {
      throw new IllegalStateException(
          "Validation seed password must satisfy the application password policy: "
              + String.join(", ", violations));
    }
    return candidate;
  }

  private Company ensureCompany(CompanyRepository companyRepository, String code, String name) {
    Company company = companyRepository.findByCodeIgnoreCase(code).orElseGet(Company::new);
    company.setCode(code);
    company.setName(name);
    company.setTimezone("UTC");
    company.setBaseCurrency("INR");
    if (company.getStateCode() == null || company.getStateCode().isBlank()) {
      company.setStateCode("MH");
    }
    return companyRepository.save(company);
  }

  private Role ensureRole(RoleRepository roleRepository, String name, String description) {
    return roleRepository
        .findByName(name)
        .orElseGet(
            () -> {
              Role role = new Role();
              role.setName(name);
              role.setDescription(description);
              return roleRepository.save(role);
            });
  }

  private Account ensureAccount(
      AccountRepository accountRepository,
      Company company,
      String code,
      String name,
      AccountType type) {
    return accountRepository
        .findByCompanyAndCodeIgnoreCase(company, code)
        .orElseGet(
            () -> {
              Account account = new Account();
              account.setCompany(company);
              account.setCode(code);
              account.setName(name);
              account.setType(type);
              account.setBalance(BigDecimal.ZERO);
              return accountRepository.save(account);
            });
  }

  private void attachMainAdmin(
      CompanyRepository companyRepository, Company company, UserAccount adminUser) {
    SeedCompanyAdminSupport.attachMainAdmin(companyRepository, company, adminUser);
  }

  private UserAccount ensureUser(
      UserAccountRepository userAccountRepository,
      PasswordEncoder passwordEncoder,
      CryptoService cryptoService,
      String email,
      String displayName,
      String password,
      String authScopeCode,
      List<Company> companies,
      List<Role> roles) {
    return ensureUser(
        userAccountRepository,
        passwordEncoder,
        cryptoService,
        email,
        displayName,
        password,
        authScopeCode,
        companies,
        roles,
        false,
        null,
        false,
        null,
        List.of());
  }

  private UserAccount ensureUser(
      UserAccountRepository userAccountRepository,
      PasswordEncoder passwordEncoder,
      CryptoService cryptoService,
      String email,
      String displayName,
      String password,
      String authScopeCode,
      List<Company> companies,
      List<Role> roles,
      boolean mustChangePassword,
      Instant lockedUntil,
      boolean mfaEnabled,
      String mfaSecret,
      List<String> recoveryCodes) {
    String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
    String normalizedScopeCode = authScopeCode.trim().toUpperCase(Locale.ROOT);
    String encodedPassword = passwordEncoder.encode(password);
    UserAccount user =
        userAccountRepository
            .findByEmailIgnoreCaseAndAuthScopeCodeIgnoreCase(normalizedEmail, normalizedScopeCode)
            .orElseGet(
                () ->
                    new UserAccount(
                        normalizedEmail, normalizedScopeCode, encodedPassword, displayName));
    user.setEmail(normalizedEmail);
    user.setAuthScopeCode(normalizedScopeCode);
    user.setDisplayName(displayName);
    user.setPasswordHash(encodedPassword);
    user.setEnabled(true);
    user.setMustChangePassword(mustChangePassword);
    user.setFailedLoginAttempts(lockedUntil == null ? 0 : 5);
    user.setLockedUntil(lockedUntil);
    user.setMfaEnabled(mfaEnabled);
    if (mfaEnabled) {
      user.setMfaSecret(cryptoService.encrypt(mfaSecret));
      user.setMfaRecoveryCodeHashes(
          recoveryCodes == null
              ? List.of()
              : recoveryCodes.stream().map(passwordEncoder::encode).toList());
    } else {
      user.setMfaSecret(null);
      user.setMfaRecoveryCodeHashes(List.of());
    }
    normalizeCompanyMemberships(user, companies);
    normalizeRoleMemberships(user, roles);
    return userAccountRepository.save(user);
  }

  private void ensureRuntimePolicy(
      SystemSettingsRepository systemSettingsRepository,
      Company company,
      String state,
      String reason,
      Integer maxActiveUsers,
      Integer maxRequestsPerMinute,
      Integer maxConcurrentRequests) {
    if (company == null || company.getId() == null) {
      return;
    }
    upsertRuntimeSetting(
        systemSettingsRepository, "tenant.runtime.hold-state." + company.getId(), state);
    upsertRuntimeSetting(
        systemSettingsRepository, "tenant.runtime.hold-reason." + company.getId(), reason);
    upsertRuntimeSetting(
        systemSettingsRepository,
        "tenant.runtime.max-active-users." + company.getId(),
        maxActiveUsers == null ? null : String.valueOf(maxActiveUsers));
    upsertRuntimeSetting(
        systemSettingsRepository,
        "tenant.runtime.max-requests-per-minute." + company.getId(),
        maxRequestsPerMinute == null ? null : String.valueOf(maxRequestsPerMinute));
    upsertRuntimeSetting(
        systemSettingsRepository,
        "tenant.runtime.max-concurrent-requests." + company.getId(),
        maxConcurrentRequests == null ? null : String.valueOf(maxConcurrentRequests));
    upsertRuntimeSetting(
        systemSettingsRepository,
        "tenant.runtime.policy-reference." + company.getId(),
        "validation-seed-" + company.getCode().toLowerCase(Locale.ROOT));
    upsertRuntimeSetting(
        systemSettingsRepository,
        "tenant.runtime.policy-updated-at." + company.getId(),
        Instant.now().toString());
  }

  private void upsertRuntimeSetting(
      SystemSettingsRepository systemSettingsRepository, String key, String value) {
    if (value == null || value.isBlank()) {
      systemSettingsRepository.deleteById(key);
      return;
    }
    systemSettingsRepository.save(new SystemSetting(key, value));
  }

  private void normalizeCompanyMemberships(UserAccount user, List<Company> companies) {
    user.clearCompany();
    if (companies != null && !companies.isEmpty()) {
      user.setCompany(companies.getFirst());
    }
  }

  private void normalizeRoleMemberships(UserAccount user, List<Role> roles) {
    user.getRoles().clear();
    roles.forEach(user::addRole);
  }

  private Dealer ensureDealer(
      DealerRepository dealerRepository,
      Company company,
      Account receivableAccount,
      UserAccount portalUser,
      String code,
      String name) {
    Dealer dealer =
        dealerRepository.findByCompanyAndCodeIgnoreCase(company, code).orElseGet(Dealer::new);
    BigDecimal existingOutstandingBalance = dealer.getOutstandingBalance();
    dealer.setCompany(company);
    dealer.setCode(code);
    dealer.setName(name);
    dealer.setCompanyName(name + " Pvt Ltd");
    dealer.setEmail(portalUser.getEmail());
    dealer.setPortalUser(portalUser);
    dealer.setReceivableAccount(receivableAccount);
    dealer.setStateCode(company.getStateCode());
    dealer.setCreditLimit(new BigDecimal("500000"));
    dealer.setOutstandingBalance(
        existingOutstandingBalance == null ? BigDecimal.ZERO : existingOutstandingBalance);
    return dealerRepository.save(dealer);
  }

  private Invoice ensureValidationInvoiceFixture(
      InvoiceRepository invoiceRepository, Dealer dealer, String invoiceNumber) {
    Invoice invoice =
        invoiceRepository
            .findByCompanyAndDealerOrderByIssueDateDesc(dealer.getCompany(), dealer)
            .stream()
            .filter(existing -> invoiceNumber.equalsIgnoreCase(existing.getInvoiceNumber()))
            .findFirst()
            .orElseGet(Invoice::new);
    invoice.setCompany(dealer.getCompany());
    invoice.setDealer(dealer);
    invoice.setInvoiceNumber(invoiceNumber);
    invoice.setStatus("ISSUED");
    invoice.setIssueDate(VALIDATION_INVOICE_ISSUE_DATE);
    invoice.setDueDate(VALIDATION_INVOICE_DUE_DATE);
    invoice.setSubtotal(VALIDATION_INVOICE_SUBTOTAL);
    invoice.setTaxTotal(VALIDATION_INVOICE_TAX);
    invoice.setTotalAmount(VALIDATION_INVOICE_TOTAL);
    invoice.setOutstandingAmount(VALIDATION_INVOICE_TOTAL);
    invoice.setCurrency("INR");
    invoice.setNotes(VALIDATION_INVOICE_NOTES);
    invoice.getLines().clear();
    invoice.getLines().add(buildValidationInvoiceLine(invoice));
    return invoiceRepository.save(invoice);
  }

  private InvoiceLine buildValidationInvoiceLine(Invoice invoice) {
    InvoiceLine line = new InvoiceLine();
    line.setInvoice(invoice);
    line.setProductCode(VALIDATION_INVOICE_PRODUCT_CODE);
    line.setDescription(VALIDATION_INVOICE_LINE_DESCRIPTION);
    line.setQuantity(BigDecimal.ONE.setScale(2));
    line.setUnitPrice(VALIDATION_INVOICE_SUBTOTAL);
    line.setTaxRate(VALIDATION_INVOICE_TAX_RATE);
    line.setTaxableAmount(VALIDATION_INVOICE_SUBTOTAL);
    line.setTaxAmount(VALIDATION_INVOICE_TAX);
    line.setCgstAmount(VALIDATION_HALF_TAX);
    line.setSgstAmount(VALIDATION_HALF_TAX);
    line.setIgstAmount(BigDecimal.ZERO.setScale(2));
    line.setDiscountAmount(BigDecimal.ZERO.setScale(2));
    line.setLineTotal(VALIDATION_INVOICE_SUBTOTAL);
    return line;
  }

  private void ensurePendingExportRequest(
      ExportRequestRepository exportRequestRepository, Company company, UserAccount requester) {
    if (company == null || requester == null) {
      return;
    }
    Long requesterId = requesterId(requester);
    boolean exists =
        exportRequestRepository
            .findByCompanyAndStatusOrderByCreatedAtAsc(company, ExportApprovalStatus.PENDING)
            .stream()
            .anyMatch(
                request ->
                    requesterId.equals(request.getUserId())
                        && SEED_EXPORT_REPORT_TYPE.equalsIgnoreCase(request.getReportType())
                        && SEED_EXPORT_PARAMETERS.equals(request.getParameters()));
    if (exists) {
      return;
    }
    ExportRequest request = new ExportRequest();
    request.setCompany(company);
    request.setUserId(requesterId);
    request.setReportType(SEED_EXPORT_REPORT_TYPE);
    request.setParameters(SEED_EXPORT_PARAMETERS);
    request.setStatus(ExportApprovalStatus.PENDING);
    exportRequestRepository.save(request);
  }

  private void ensurePendingSupportTicket(
      SupportTicketRepository supportTicketRepository, Company company, UserAccount requester) {
    if (company == null || requester == null) {
      return;
    }
    Long requesterId = requesterId(requester);
    boolean exists =
        supportTicketRepository
            .findByCompanyAndUserIdOrderByCreatedAtDesc(company, requesterId)
            .stream()
            .anyMatch(
                ticket ->
                    ticket.getCategory() == SupportTicketCategory.SUPPORT
                        && SEED_SUPPORT_SUBJECT.equalsIgnoreCase(ticket.getSubject()));
    if (exists) {
      return;
    }
    SupportTicket ticket = new SupportTicket();
    ticket.setCompany(company);
    ticket.setUserId(requesterId);
    ticket.setCategory(SupportTicketCategory.SUPPORT);
    ticket.setSubject(SEED_SUPPORT_SUBJECT);
    ticket.setDescription(
        "Seeded validation support ticket for dealer and tenant admin replay on current main.");
    ticket.setStatus(SupportTicketStatus.OPEN);
    supportTicketRepository.save(ticket);
  }

  private void ensurePendingCreditRequest(
      CreditRequestRepository creditRequestRepository,
      Company company,
      Dealer dealer,
      UserAccount requester) {
    if (company == null || dealer == null || requester == null) {
      return;
    }
    boolean exists =
        creditRequestRepository.findPendingByCompanyOrderByCreatedAtDesc(company).stream()
            .anyMatch(
                request ->
                    request.getDealer() != null
                        && dealer.getId() != null
                        && dealer.getId().equals(request.getDealer().getId())
                        && SEED_CREDIT_REASON.equalsIgnoreCase(request.getReason()));
    if (exists) {
      return;
    }
    CreditRequest request = new CreditRequest();
    request.setCompany(company);
    request.setDealer(dealer);
    request.setAmountRequested(new BigDecimal("75000.00"));
    request.setStatus("PENDING");
    request.setReason(SEED_CREDIT_REASON);
    request.setRequesterUserId(requesterId(requester));
    request.setRequesterEmail(requester.getEmail());
    creditRequestRepository.save(request);
  }

  private Long requesterId(UserAccount requester) {
    return requester.getId() != null ? requester.getId() : 0L;
  }
}
