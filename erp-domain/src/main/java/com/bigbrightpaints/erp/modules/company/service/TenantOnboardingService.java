package com.bigbrightpaints.erp.modules.company.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.bigbrightpaints.erp.core.config.SystemSetting;
import com.bigbrightpaints.erp.core.config.SystemSettingsRepository;
import com.bigbrightpaints.erp.core.util.CompanyTime;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriod;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingPeriodService;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import com.bigbrightpaints.erp.modules.auth.service.TenantAdminProvisioningService;
import com.bigbrightpaints.erp.modules.company.domain.CoATemplate;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.company.dto.TenantOnboardingRequest;
import com.bigbrightpaints.erp.modules.company.dto.TenantOnboardingResponse;

@Service
public class TenantOnboardingService {

  private static final String BOOTSTRAP_MODE_SEEDED = "SEEDED";
  private static final String TEMPLATE_GENERIC = "GENERIC";
  private static final String TEMPLATE_INDIAN_STANDARD = "INDIAN_STANDARD";
  private static final String TEMPLATE_MANUFACTURING = "MANUFACTURING";

  private final CompanyRepository companyRepository;
  private final UserAccountRepository userAccountRepository;
  private final AccountRepository accountRepository;
  private final AccountingPeriodService accountingPeriodService;
  private final CoATemplateService coATemplateService;
  private final SystemSettingsRepository systemSettingsRepository;
  private final TenantAdminProvisioningService tenantAdminProvisioningService;

  public TenantOnboardingService(
      CompanyRepository companyRepository,
      UserAccountRepository userAccountRepository,
      AccountRepository accountRepository,
      AccountingPeriodService accountingPeriodService,
      CoATemplateService coATemplateService,
      SystemSettingsRepository systemSettingsRepository,
      TenantAdminProvisioningService tenantAdminProvisioningService) {
    this.companyRepository = companyRepository;
    this.userAccountRepository = userAccountRepository;
    this.accountRepository = accountRepository;
    this.accountingPeriodService = accountingPeriodService;
    this.coATemplateService = coATemplateService;
    this.systemSettingsRepository = systemSettingsRepository;
    this.tenantAdminProvisioningService = tenantAdminProvisioningService;
  }

  @Transactional
  public TenantOnboardingResponse onboardTenant(TenantOnboardingRequest request) {
    if (request == null) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          "Tenant onboarding request is required");
    }

    String normalizedCompanyCode = normalizeCompanyCode(request.code());
    String normalizedAdminEmail = normalizeEmail(request.firstAdminEmail());
    ensureCompanyCodeAvailable(normalizedCompanyCode);
    ensureAdminEmailAvailable(normalizedAdminEmail, normalizedCompanyCode);

    CoATemplate template = coATemplateService.requireActiveTemplate(request.coaTemplateCode());
    List<AccountBlueprint> templateAccounts = resolveTemplateBlueprints(template.getCode());
    validateTemplateSize(template.getCode(), templateAccounts.size());

    Company company = createCompany(request, normalizedCompanyCode);
    Company savedCompany = companyRepository.save(company);

    Map<String, Account> createdAccounts = createTemplateAccounts(savedCompany, templateAccounts);
    applyCompanyDefaultAccounts(savedCompany, createdAccounts);
    companyRepository.save(savedCompany);

    UserAccount provisionedAdmin =
        tenantAdminProvisioningService.provisionInitialAdmin(
            savedCompany, normalizedAdminEmail, request.firstAdminDisplayName());

    AccountingPeriod defaultPeriod =
        accountingPeriodService.ensurePeriod(savedCompany, CompanyTime.today(savedCompany));
    boolean systemSettingsInitialized = initializeDefaultSystemSettings();
    savedCompany.setOnboardingCoaTemplateCode(template.getCode());
    savedCompany.setOnboardingCompletedAt(CompanyTime.now(savedCompany));
    companyRepository.save(savedCompany);

    return new TenantOnboardingResponse(
        savedCompany.getId(),
        savedCompany.getCode(),
        template.getCode(),
        BOOTSTRAP_MODE_SEEDED,
        true,
        createdAccounts.size(),
        defaultPeriod.getId(),
        true,
        provisionedAdmin.getEmail(),
        true,
        systemSettingsInitialized);
  }

  private Company createCompany(TenantOnboardingRequest request, String normalizedCompanyCode) {
    Company company = new Company();
    company.setName(request.name());
    company.setCode(normalizedCompanyCode);
    company.setTimezone(request.timezone());
    company.setDefaultGstRate(request.defaultGstRate());
    company.setQuotaMaxActiveUsers(defaultLong(request.maxActiveUsers()));
    company.setQuotaMaxApiRequests(defaultLong(request.maxApiRequests()));
    company.setQuotaMaxStorageBytes(defaultLong(request.maxStorageBytes()));
    company.setQuotaMaxConcurrentRequests(defaultLong(request.maxConcurrentRequests()));
    company.setQuotaSoftLimitEnabled(defaultBoolean(request.softLimitEnabled(), false));
    company.setQuotaHardLimitEnabled(defaultBoolean(request.hardLimitEnabled(), true));
    return company;
  }

  private Map<String, Account> createTemplateAccounts(
      Company company, List<AccountBlueprint> blueprints) {
    Map<String, Account> created = new LinkedHashMap<>();
    for (AccountBlueprint blueprint : blueprints) {
      Account account = new Account();
      account.setCompany(company);
      account.setCode(blueprint.code());
      account.setName(blueprint.name());
      account.setType(blueprint.type());
      account.setBalance(BigDecimal.ZERO);
      if (StringUtils.hasText(blueprint.parentCode())) {
        Account parent = created.get(blueprint.parentCode());
        if (parent == null) {
          throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidState(
              "Template account parent not found for code " + blueprint.code());
        }
        account.setParent(parent);
      }
      Account saved = accountRepository.save(account);
      created.put(saved.getCode(), saved);
    }
    return created;
  }

  private void applyCompanyDefaultAccounts(Company company, Map<String, Account> accountsByCode) {
    Account inventory =
        firstPresent(accountsByCode, "FINISHED-GOODS-INVENTORY", "INV", "RAW-MATERIAL-INVENTORY");
    Account cogs = firstPresent(accountsByCode, "COGS", "FG-COGS", "RM-CONSUMPTION");
    Account revenue = firstPresent(accountsByCode, "REV", "SERVICE-REVENUE");
    Account discount = firstPresent(accountsByCode, "DISC", "SALES-RETURNS");
    Account taxOutput = firstPresent(accountsByCode, "GST-OUT", "TAX-PAYABLE");
    Account taxInput = firstPresent(accountsByCode, "GST-IN", "TDS-RECEIVABLE");
    Account taxPayable = firstPresent(accountsByCode, "GST-PAY", "TDS-PAYABLE", "TAX-PAYABLE");
    Account cash = firstPresent(accountsByCode, "CASH", "BANK-CURRENT");
    Account payrollExpense =
        firstPresent(
            accountsByCode, "SALARY-EXPENSE", "DIRECT-MATERIAL-CONSUMPTION", "OFFICE-EXPENSE");

    if (inventory != null) {
      company.setDefaultInventoryAccountId(inventory.getId());
    }
    if (cogs != null) {
      company.setDefaultCogsAccountId(cogs.getId());
    }
    if (revenue != null) {
      company.setDefaultRevenueAccountId(revenue.getId());
    }
    if (discount != null) {
      company.setDefaultDiscountAccountId(discount.getId());
    }
    if (taxOutput != null) {
      company.setDefaultTaxAccountId(taxOutput.getId());
      company.setGstOutputTaxAccountId(taxOutput.getId());
    }
    if (taxInput != null) {
      company.setGstInputTaxAccountId(taxInput.getId());
    }
    if (taxPayable != null) {
      company.setGstPayableAccountId(taxPayable.getId());
    }
    if (cash != null) {
      company.setPayrollCashAccount(cash);
    }
    if (payrollExpense != null) {
      company.setPayrollExpenseAccount(payrollExpense);
    }
  }

  private boolean initializeDefaultSystemSettings() {
    boolean changed = false;
    changed |= saveSystemSettingIfMissing("auto-approval.enabled", "true");
    changed |= saveSystemSettingIfMissing("period-lock.enforced", "true");
    return changed;
  }

  private boolean saveSystemSettingIfMissing(String key, String value) {
    if (systemSettingsRepository.existsById(key)) {
      return false;
    }
    systemSettingsRepository.save(new SystemSetting(key, value));
    return true;
  }

  private String normalizeCompanyCode(String companyCode) {
    if (!StringUtils.hasText(companyCode)) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          "Company code is required");
    }
    return companyCode.trim().toUpperCase(Locale.ROOT);
  }

  private String normalizeEmail(String email) {
    if (!StringUtils.hasText(email)) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          "firstAdminEmail is required");
    }
    return email.trim().toLowerCase(Locale.ROOT);
  }

  private void ensureCompanyCodeAvailable(String companyCode) {
    if (companyRepository.findByCodeIgnoreCase(companyCode).isPresent()) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          "Company code already exists: " + companyCode);
    }
  }

  private void ensureAdminEmailAvailable(String adminEmail, String companyCode) {
    if (userAccountRepository.existsByEmailIgnoreCaseAndAuthScopeCodeIgnoreCase(
        adminEmail, companyCode)) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          "First admin email already exists in company scope: " + adminEmail);
    }
  }

  private long defaultLong(Long value) {
    return value == null ? 0L : value;
  }

  private boolean defaultBoolean(Boolean value, boolean defaultValue) {
    return value == null ? defaultValue : value;
  }

  private Account firstPresent(Map<String, Account> accountsByCode, String... preferredCodes) {
    for (String code : preferredCodes) {
      Account account = accountsByCode.get(code);
      if (account != null) {
        return account;
      }
    }
    return null;
  }

  private void validateTemplateSize(String templateCode, int accountCount) {
    if (accountCount < 50 || accountCount > 100) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidState(
          "Template " + templateCode + " must generate 50-100 accounts");
    }
  }

  private List<AccountBlueprint> resolveTemplateBlueprints(String templateCode) {
    String normalized = templateCode == null ? "" : templateCode.trim().toUpperCase(Locale.ROOT);
    return switch (normalized) {
      case TEMPLATE_GENERIC -> genericTemplateBlueprints();
      case TEMPLATE_INDIAN_STANDARD ->
          mergeBlueprints(genericTemplateBlueprints(), indianTemplateExtensions());
      case TEMPLATE_MANUFACTURING ->
          mergeBlueprints(
              mergeBlueprints(genericTemplateBlueprints(), indianTemplateExtensions()),
              manufacturingTemplateExtensions());
      default ->
          throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
              "Unsupported CoA template: " + templateCode);
    };
  }

  private List<AccountBlueprint> mergeBlueprints(
      List<AccountBlueprint> base, List<AccountBlueprint> extensions) {
    LinkedHashMap<String, AccountBlueprint> merged = new LinkedHashMap<>();
    base.forEach(blueprint -> merged.put(blueprint.code(), blueprint));
    extensions.forEach(blueprint -> merged.put(blueprint.code(), blueprint));
    return new ArrayList<>(merged.values());
  }

  private List<AccountBlueprint> genericTemplateBlueprints() {
    List<AccountBlueprint> blueprints = new ArrayList<>();
    blueprints.add(new AccountBlueprint("AST", "Assets", AccountType.ASSET, null));
    blueprints.add(new AccountBlueprint("LIAB", "Liabilities", AccountType.LIABILITY, null));
    blueprints.add(new AccountBlueprint("EQ", "Equity", AccountType.EQUITY, null));
    blueprints.add(new AccountBlueprint("REV", "Revenue", AccountType.REVENUE, null));
    blueprints.add(new AccountBlueprint("EXP", "Expenses", AccountType.EXPENSE, null));
    blueprints.add(new AccountBlueprint("INV", "Inventory", AccountType.ASSET, "AST"));
    blueprints.add(new AccountBlueprint("CASH", "Cash", AccountType.ASSET, "AST"));
    blueprints.add(
        new AccountBlueprint("BANK-CURRENT", "Bank Current Account", AccountType.ASSET, "AST"));
    blueprints.add(new AccountBlueprint("AR", "Accounts Receivable", AccountType.ASSET, "AST"));
    blueprints.add(new AccountBlueprint("AP", "Accounts Payable", AccountType.LIABILITY, "LIAB"));
    blueprints.add(new AccountBlueprint("GST-OUT", "GST Output", AccountType.LIABILITY, "LIAB"));
    blueprints.add(new AccountBlueprint("GST-IN", "GST Input", AccountType.ASSET, "AST"));
    blueprints.add(new AccountBlueprint("GST-PAY", "GST Payable", AccountType.LIABILITY, "LIAB"));
    blueprints.add(new AccountBlueprint("OPEN-BAL", "Opening Balance", AccountType.EQUITY, "EQ"));
    blueprints.add(new AccountBlueprint("DISC", "Sales Discount", AccountType.EXPENSE, "EXP"));
    blueprints.add(new AccountBlueprint("COGS", "Cost of Goods Sold", AccountType.COGS, "EXP"));
    blueprints.add(
        new AccountBlueprint("SALARY-EXPENSE", "Salary Expense", AccountType.EXPENSE, "EXP"));
    blueprints.add(
        new AccountBlueprint("OFFICE-EXPENSE", "Office Expense", AccountType.EXPENSE, "EXP"));
    blueprints.add(
        new AccountBlueprint("SERVICE-REVENUE", "Service Revenue", AccountType.REVENUE, "REV"));
    blueprints.add(
        new AccountBlueprint("TAX-PAYABLE", "Tax Payable", AccountType.LIABILITY, "LIAB"));
    blueprints.add(
        new AccountBlueprint("TDS-RECEIVABLE", "TDS Receivable", AccountType.ASSET, "AST"));
    blueprints.add(
        new AccountBlueprint("TDS-PAYABLE", "TDS Payable", AccountType.LIABILITY, "LIAB"));
    blueprints.add(
        new AccountBlueprint(
            "RAW-MATERIAL-INVENTORY", "Raw Material Inventory", AccountType.ASSET, "AST"));
    blueprints.add(
        new AccountBlueprint(
            "FINISHED-GOODS-INVENTORY", "Finished Goods Inventory", AccountType.ASSET, "AST"));
    blueprints.add(new AccountBlueprint("FG-COGS", "Finished Goods COGS", AccountType.COGS, "EXP"));
    blueprints.add(
        new AccountBlueprint(
            "RM-CONSUMPTION", "Raw Material Consumption", AccountType.COGS, "EXP"));
    blueprints.add(
        new AccountBlueprint(
            "DIRECT-MATERIAL-CONSUMPTION", "Direct Material Consumption", AccountType.COGS, "EXP"));
    for (int index = 1; index <= 24; index++) {
      blueprints.add(
          new AccountBlueprint(
              "GEN-" + index,
              "Generic Account " + index,
              index % 2 == 0 ? AccountType.EXPENSE : AccountType.ASSET,
              index % 2 == 0 ? "EXP" : "AST"));
    }
    return blueprints;
  }

  private List<AccountBlueprint> indianTemplateExtensions() {
    List<AccountBlueprint> blueprints = new ArrayList<>();
    for (int index = 1; index <= 12; index++) {
      blueprints.add(
          new AccountBlueprint(
              "IND-" + index,
              "Indian Standard Account " + index,
              index % 2 == 0 ? AccountType.LIABILITY : AccountType.EXPENSE,
              index % 2 == 0 ? "LIAB" : "EXP"));
    }
    return blueprints;
  }

  private List<AccountBlueprint> manufacturingTemplateExtensions() {
    List<AccountBlueprint> blueprints = new ArrayList<>();
    for (int index = 1; index <= 16; index++) {
      blueprints.add(
          new AccountBlueprint(
              "MFG-" + index,
              "Manufacturing Account " + index,
              index % 2 == 0 ? AccountType.ASSET : AccountType.EXPENSE,
              index % 2 == 0 ? "AST" : "EXP"));
    }
    return blueprints;
  }

  private record AccountBlueprint(String code, String name, AccountType type, String parentCode) {}
}
