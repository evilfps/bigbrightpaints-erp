package com.bigbrightpaints.erp.core.config;

import java.math.BigDecimal;
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
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
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

  @Bean
  CommandLineRunner seedValidationActors(
      CompanyRepository companyRepository,
      RoleRepository roleRepository,
      UserAccountRepository userAccountRepository,
      DealerRepository dealerRepository,
      AccountRepository accountRepository,
      InvoiceRepository invoiceRepository,
      PasswordEncoder passwordEncoder,
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
      String platformScopeCode = authScopeService.getPlatformScopeCode();

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

      ensureUser(
          userAccountRepository,
          passwordEncoder,
          "validation.admin@example.com",
          "Validation Admin",
          validatedPassword,
          mockCompany.getCode(),
          List.of(mockCompany),
          List.of(admin, accounting, sales));
      ensureUser(
          userAccountRepository,
          passwordEncoder,
          "validation.accounting@example.com",
          "Validation Accounting",
          validatedPassword,
          mockCompany.getCode(),
          List.of(mockCompany),
          List.of(accounting));
      ensureUser(
          userAccountRepository,
          passwordEncoder,
          "validation.sales@example.com",
          "Validation Sales",
          validatedPassword,
          mockCompany.getCode(),
          List.of(mockCompany),
          List.of(sales));
      ensureUser(
          userAccountRepository,
          passwordEncoder,
          "validation.factory@example.com",
          "Validation Factory",
          validatedPassword,
          mockCompany.getCode(),
          List.of(mockCompany),
          List.of(factory));

      UserAccount dealerUser =
          ensureUser(
              userAccountRepository,
              passwordEncoder,
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

      ensureUser(
          userAccountRepository,
          passwordEncoder,
          "validation.rival.admin@example.com",
          "Rival Validation Admin",
          validatedPassword,
          rivalCompany.getCode(),
          List.of(rivalCompany),
          List.of(admin));
      ensureUser(
          userAccountRepository,
          passwordEncoder,
          "validation.superadmin@example.com",
          "Validation Super Admin",
          validatedPassword,
          platformScopeCode,
          List.of(),
          List.of(admin, superAdmin));

      log.info(
          "Validation seed ready for companies [MOCK, RIVAL] plus platform scope {}. Actor"
              + " password comes from"
              + " erp.validation-seed.password / ERP_VALIDATION_SEED_PASSWORD.",
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

  private UserAccount ensureUser(
      UserAccountRepository userAccountRepository,
      PasswordEncoder passwordEncoder,
      String email,
      String displayName,
      String password,
      String authScopeCode,
      List<Company> companies,
      List<Role> roles) {
    String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
    String normalizedScopeCode = authScopeCode.trim().toUpperCase(Locale.ROOT);
    UserAccount user =
        userAccountRepository
            .findByEmailIgnoreCaseAndAuthScopeCodeIgnoreCase(normalizedEmail, normalizedScopeCode)
            .orElseGet(
                () ->
                    new UserAccount(
                        normalizedEmail,
                        normalizedScopeCode,
                        passwordEncoder.encode(password),
                        displayName));
    user.setEmail(normalizedEmail);
    user.setAuthScopeCode(normalizedScopeCode);
    user.setDisplayName(displayName);
    user.setPasswordHash(passwordEncoder.encode(password));
    user.setEnabled(true);
    user.setMustChangePassword(false);
    user.setFailedLoginAttempts(0);
    user.setLockedUntil(null);
    user.setMfaEnabled(false);
    user.setMfaSecret(null);
    user.setMfaRecoveryCodeHashes(List.of());
    normalizeCompanyMemberships(user, companies);
    normalizeRoleMemberships(user, roles);
    return userAccountRepository.save(user);
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
}
