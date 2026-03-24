package com.bigbrightpaints.erp.modules.company.service;

import com.bigbrightpaints.erp.core.config.SystemSetting;
import com.bigbrightpaints.erp.core.config.SystemSettingsRepository;
import com.bigbrightpaints.erp.core.notification.EmailService;
import com.bigbrightpaints.erp.core.util.CompanyTime;
import com.bigbrightpaints.erp.core.util.PasswordUtils;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriod;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingPeriodService;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import com.bigbrightpaints.erp.modules.company.domain.CoATemplate;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.company.dto.TenantOnboardingRequest;
import com.bigbrightpaints.erp.modules.company.dto.TenantOnboardingResponse;
import com.bigbrightpaints.erp.modules.rbac.domain.Role;
import com.bigbrightpaints.erp.modules.rbac.domain.RoleRepository;
import com.bigbrightpaints.erp.modules.rbac.service.RoleService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class TenantOnboardingService {

    private static final String BOOTSTRAP_MODE_SEEDED = "SEEDED";
    private static final String TEMPLATE_GENERIC = "GENERIC";
    private static final String TEMPLATE_INDIAN_STANDARD = "INDIAN_STANDARD";
    private static final String TEMPLATE_MANUFACTURING = "MANUFACTURING";

    private final CompanyRepository companyRepository;
    private final UserAccountRepository userAccountRepository;
    private final RoleService roleService;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final AccountRepository accountRepository;
    private final AccountingPeriodService accountingPeriodService;
    private final CoATemplateService coATemplateService;
    private final EmailService emailService;
    private final SystemSettingsRepository systemSettingsRepository;

    public TenantOnboardingService(CompanyRepository companyRepository,
                                   UserAccountRepository userAccountRepository,
                                   RoleService roleService,
                                   RoleRepository roleRepository,
                                   PasswordEncoder passwordEncoder,
                                   AccountRepository accountRepository,
                                   AccountingPeriodService accountingPeriodService,
                                   CoATemplateService coATemplateService,
                                   EmailService emailService,
                                   SystemSettingsRepository systemSettingsRepository) {
        this.companyRepository = companyRepository;
        this.userAccountRepository = userAccountRepository;
        this.roleService = roleService;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
        this.accountRepository = accountRepository;
        this.accountingPeriodService = accountingPeriodService;
        this.coATemplateService = coATemplateService;
        this.emailService = emailService;
        this.systemSettingsRepository = systemSettingsRepository;
    }

    @Transactional
    public TenantOnboardingResponse onboardTenant(TenantOnboardingRequest request) {
        if (request == null) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput("Tenant onboarding request is required");
        }

        String normalizedCompanyCode = normalizeCompanyCode(request.code());
        String normalizedAdminEmail = normalizeEmail(request.firstAdminEmail());
        ensureCompanyCodeAvailable(normalizedCompanyCode);
        ensureAdminEmailAvailable(normalizedAdminEmail);

        CoATemplate template = coATemplateService.requireActiveTemplate(request.coaTemplateCode());
        List<AccountBlueprint> templateAccounts = resolveTemplateBlueprints(template.getCode());
        validateTemplateSize(template.getCode(), templateAccounts.size());

        Company company = createCompany(request, normalizedCompanyCode);
        Company savedCompany = companyRepository.save(company);

        Map<String, Account> createdAccounts = createTemplateAccounts(savedCompany, templateAccounts);
        applyCompanyDefaultAccounts(savedCompany, createdAccounts);
        companyRepository.save(savedCompany);

        AdminProvisioningResult adminProvisioningResult = createTenantAdmin(
                savedCompany,
                normalizedAdminEmail,
                request.firstAdminDisplayName());

        AccountingPeriod defaultPeriod = accountingPeriodService.ensurePeriod(savedCompany, CompanyTime.today(savedCompany));
        boolean systemSettingsInitialized = initializeDefaultSystemSettings();

        return new TenantOnboardingResponse(
                savedCompany.getId(),
                savedCompany.getCode(),
                template.getCode(),
                BOOTSTRAP_MODE_SEEDED,
                true,
                createdAccounts.size(),
                defaultPeriod.getId(),
                true,
                normalizedAdminEmail,
                true,
                adminProvisioningResult.temporaryPassword(),
                adminProvisioningResult.credentialsEmailSent(),
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
        company.setQuotaMaxConcurrentSessions(defaultLong(request.maxConcurrentUsers()));
        company.setQuotaSoftLimitEnabled(defaultBoolean(request.softLimitEnabled(), false));
        company.setQuotaHardLimitEnabled(defaultBoolean(request.hardLimitEnabled(), true));
        return company;
    }

    private Map<String, Account> createTemplateAccounts(Company company, List<AccountBlueprint> blueprints) {
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
        Account inventory = firstPresent(accountsByCode,
                "FINISHED-GOODS-INVENTORY",
                "INV",
                "RAW-MATERIAL-INVENTORY");
        Account cogs = firstPresent(accountsByCode, "COGS", "FG-COGS", "RM-CONSUMPTION");
        Account revenue = firstPresent(accountsByCode, "REV", "SERVICE-REVENUE");
        Account discount = firstPresent(accountsByCode, "DISC", "SALES-RETURNS");
        Account taxOutput = firstPresent(accountsByCode, "GST-OUT", "TAX-PAYABLE");
        Account taxInput = firstPresent(accountsByCode, "GST-IN", "TDS-RECEIVABLE");
        Account taxPayable = firstPresent(accountsByCode, "GST-PAY", "TDS-PAYABLE", "TAX-PAYABLE");
        Account cash = firstPresent(accountsByCode, "CASH", "BANK-CURRENT");
        Account payrollExpense = firstPresent(accountsByCode, "SALARY-EXPENSE", "DIRECT-MATERIAL-CONSUMPTION", "OFFICE-EXPENSE");

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

    private AdminProvisioningResult createTenantAdmin(Company company, String adminEmail, String adminDisplayName) {
        Role adminRole = requireAdminRole();
        String temporaryPassword = PasswordUtils.generateTemporaryPassword(14);
        UserAccount admin = new UserAccount(
                adminEmail,
                passwordEncoder.encode(temporaryPassword),
                resolveAdminDisplayName(adminDisplayName, company));
        admin.setMustChangePassword(true);
        admin.addCompany(company);
        admin.addRole(adminRole);
        userAccountRepository.save(admin);

        emailService.sendUserCredentialsEmail(
                admin.getEmail(),
                admin.getDisplayName(),
                temporaryPassword,
                company.getCode());
        return new AdminProvisioningResult(temporaryPassword, emailService.isCredentialEmailDeliveryEnabled());
    }

    private Role requireAdminRole() {
        roleService.ensureRoleExists("ROLE_ADMIN");
        return roleRepository.findByName("ROLE_ADMIN")
                .orElseThrow(() -> com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidState(
                        "ROLE_ADMIN must exist before tenant onboarding"));
    }

    private String resolveAdminDisplayName(String requestedDisplayName, Company company) {
        if (StringUtils.hasText(requestedDisplayName)) {
            return requestedDisplayName.trim();
        }
        if (company != null && StringUtils.hasText(company.getName())) {
            return company.getName().trim() + " Admin";
        }
        return "Company Admin";
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
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput("Company code is required");
        }
        return companyCode.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeEmail(String email) {
        if (!StringUtils.hasText(email)) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput("firstAdminEmail is required");
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private void ensureCompanyCodeAvailable(String companyCode) {
        if (companyRepository.findByCodeIgnoreCase(companyCode).isPresent()) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
                    "Company code already exists: " + companyCode);
        }
    }

    private void ensureAdminEmailAvailable(String adminEmail) {
        if (userAccountRepository.findByEmailIgnoreCase(adminEmail).isPresent()) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
                    "First admin email already exists: " + adminEmail);
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
            case TEMPLATE_INDIAN_STANDARD -> mergeBlueprints(genericTemplateBlueprints(), indianTemplateExtensions());
            case TEMPLATE_MANUFACTURING -> mergeBlueprints(
                    mergeBlueprints(genericTemplateBlueprints(), indianTemplateExtensions()),
                    manufacturingTemplateExtensions());
            default -> throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
                    "Unsupported CoA template: " + templateCode);
        };
    }

    private List<AccountBlueprint> mergeBlueprints(List<AccountBlueprint> base, List<AccountBlueprint> extensions) {
        LinkedHashMap<String, AccountBlueprint> merged = new LinkedHashMap<>();
        for (AccountBlueprint blueprint : base) {
            merged.putIfAbsent(blueprint.code(), blueprint);
        }
        for (AccountBlueprint blueprint : extensions) {
            merged.putIfAbsent(blueprint.code(), blueprint);
        }
        return List.copyOf(merged.values());
    }

    private List<AccountBlueprint> genericTemplateBlueprints() {
        List<AccountBlueprint> list = new ArrayList<>();

        // Assets
        list.add(spec("1000", "Assets", AccountType.ASSET, null));
        list.add(spec("1100", "Current Assets", AccountType.ASSET, "1000"));
        list.add(spec("CASH", "Cash", AccountType.ASSET, "1100"));
        list.add(spec("BANK-CURRENT", "Bank - Current Account", AccountType.ASSET, "1100"));
        list.add(spec("BANK-SAVINGS", "Bank - Savings Account", AccountType.ASSET, "1100"));
        list.add(spec("PETTY-CASH", "Petty Cash", AccountType.ASSET, "1100"));
        list.add(spec("AR", "Accounts Receivable", AccountType.ASSET, "1100"));
        list.add(spec("INV", "Inventory", AccountType.ASSET, "1100"));
        list.add(spec("PREPAID-EXPENSES", "Prepaid Expenses", AccountType.ASSET, "1100"));
        list.add(spec("ADVANCE-SUPPLIERS", "Advance to Suppliers", AccountType.ASSET, "1100"));
        list.add(spec("GST-IN", "GST Input Tax", AccountType.ASSET, "1100"));
        list.add(spec("OTHER-CURRENT-ASSETS", "Other Current Assets", AccountType.ASSET, "1100"));
        list.add(spec("1200", "Non-Current Assets", AccountType.ASSET, "1000"));
        list.add(spec("FIXED-ASSETS", "Fixed Assets", AccountType.ASSET, "1200"));
        list.add(spec("ACCUM-DEPRECIATION", "Accumulated Depreciation", AccountType.ASSET, "1200"));
        list.add(spec("INTANGIBLE-ASSETS", "Intangible Assets", AccountType.ASSET, "1200"));
        list.add(spec("SECURITY-DEPOSITS", "Security Deposits", AccountType.ASSET, "1200"));
        list.add(spec("LONG-TERM-INVESTMENTS", "Long-Term Investments", AccountType.ASSET, "1200"));

        // Liabilities
        list.add(spec("2000", "Liabilities", AccountType.LIABILITY, null));
        list.add(spec("2100", "Current Liabilities", AccountType.LIABILITY, "2000"));
        list.add(spec("AP", "Accounts Payable", AccountType.LIABILITY, "2100"));
        list.add(spec("ACCRUED-EXPENSES", "Accrued Expenses", AccountType.LIABILITY, "2100"));
        list.add(spec("TAX-PAYABLE", "Tax Payable", AccountType.LIABILITY, "2100"));
        list.add(spec("GST-OUT", "GST Output Tax", AccountType.LIABILITY, "2100"));
        list.add(spec("GST-PAY", "GST Payable", AccountType.LIABILITY, "2100"));
        list.add(spec("CUSTOMER-ADVANCES", "Customer Advances", AccountType.LIABILITY, "2100"));
        list.add(spec("SALARY-PAYABLE", "Salary Payable", AccountType.LIABILITY, "2100"));
        list.add(spec("SHORT-TERM-LOANS", "Short-Term Loans", AccountType.LIABILITY, "2100"));
        list.add(spec("2200", "Non-Current Liabilities", AccountType.LIABILITY, "2000"));
        list.add(spec("LONG-TERM-BORROWINGS", "Long-Term Borrowings", AccountType.LIABILITY, "2200"));
        list.add(spec("PROVISIONS", "Long-Term Provisions", AccountType.LIABILITY, "2200"));

        // Equity
        list.add(spec("3000", "Equity", AccountType.EQUITY, null));
        list.add(spec("SHARE-CAPITAL", "Share Capital", AccountType.EQUITY, "3000"));
        list.add(spec("RETAINED-EARNINGS", "Retained Earnings", AccountType.EQUITY, "3000"));
        list.add(spec("OWNER-CAPITAL", "Owner Capital", AccountType.EQUITY, "3000"));
        list.add(spec("OWNER-DRAWINGS", "Owner Drawings", AccountType.EQUITY, "3000"));
        list.add(spec("OPEN-BAL", "Opening Balance", AccountType.EQUITY, "3000"));

        // Revenue
        list.add(spec("4000", "Revenue", AccountType.REVENUE, null));
        list.add(spec("REV", "Sales Revenue", AccountType.REVENUE, "4000"));
        list.add(spec("SERVICE-REVENUE", "Service Revenue", AccountType.REVENUE, "4000"));
        list.add(spec("OTHER-OPERATING-REVENUE", "Other Operating Revenue", AccountType.REVENUE, "4000"));
        list.add(spec("SALES-RETURNS", "Sales Returns", AccountType.REVENUE, "4000"));
        list.add(spec("4100", "Other Income", AccountType.OTHER_INCOME, null));
        list.add(spec("INTEREST-INCOME", "Interest Income", AccountType.OTHER_INCOME, "4100"));
        list.add(spec("FX-GAIN", "Foreign Exchange Gain", AccountType.OTHER_INCOME, "4100"));

        // COGS
        list.add(spec("5000", "Cost of Goods Sold", AccountType.COGS, null));
        list.add(spec("COGS", "Primary Cost of Goods Sold", AccountType.COGS, "5000"));
        list.add(spec("FREIGHT-IN", "Freight Inward", AccountType.COGS, "5000"));
        list.add(spec("PURCHASE-ADJUSTMENTS", "Purchase Adjustments", AccountType.COGS, "5000"));
        list.add(spec("DIRECT-MATERIAL-CONSUMPTION", "Direct Material Consumption", AccountType.COGS, "5000"));

        // Expenses
        list.add(spec("6000", "Operating Expenses", AccountType.EXPENSE, null));
        list.add(spec("SALARY-EXPENSE", "Salary Expense", AccountType.EXPENSE, "6000"));
        list.add(spec("RENT-EXPENSE", "Rent Expense", AccountType.EXPENSE, "6000"));
        list.add(spec("UTILITIES-EXPENSE", "Utilities Expense", AccountType.EXPENSE, "6000"));
        list.add(spec("OFFICE-EXPENSE", "Office Expense", AccountType.EXPENSE, "6000"));
        list.add(spec("DISC", "Discounts Allowed", AccountType.EXPENSE, "6000"));
        list.add(spec("MARKETING-EXPENSE", "Marketing Expense", AccountType.EXPENSE, "6000"));
        list.add(spec("TRAVEL-EXPENSE", "Travel Expense", AccountType.EXPENSE, "6000"));
        list.add(spec("DEPRECIATION-EXPENSE", "Depreciation Expense", AccountType.EXPENSE, "6000"));
        list.add(spec("LEGAL-PROFESSIONAL-EXPENSE", "Legal and Professional Expense", AccountType.EXPENSE, "6000"));

        // Other expenses
        list.add(spec("7000", "Other Expenses", AccountType.OTHER_EXPENSE, null));
        list.add(spec("INTEREST-EXPENSE", "Interest Expense", AccountType.OTHER_EXPENSE, "7000"));
        list.add(spec("FX-LOSS", "Foreign Exchange Loss", AccountType.OTHER_EXPENSE, "7000"));
        list.add(spec("BAD-DEBT-EXPENSE", "Bad Debt Expense", AccountType.OTHER_EXPENSE, "7000"));

        return List.copyOf(list);
    }

    private List<AccountBlueprint> indianTemplateExtensions() {
        return List.of(
                spec("CGST-IN", "CGST Input Credit", AccountType.ASSET, "GST-IN"),
                spec("SGST-IN", "SGST Input Credit", AccountType.ASSET, "GST-IN"),
                spec("IGST-IN", "IGST Input Credit", AccountType.ASSET, "GST-IN"),
                spec("TDS-RECEIVABLE", "TDS Receivable", AccountType.ASSET, "1100"),
                spec("CGST-OUT", "CGST Output Tax", AccountType.LIABILITY, "GST-OUT"),
                spec("SGST-OUT", "SGST Output Tax", AccountType.LIABILITY, "GST-OUT"),
                spec("IGST-OUT", "IGST Output Tax", AccountType.LIABILITY, "GST-OUT"),
                spec("TDS-PAYABLE", "TDS Payable", AccountType.LIABILITY, "TAX-PAYABLE"),
                spec("TCS-PAYABLE", "TCS Payable", AccountType.LIABILITY, "TAX-PAYABLE"),
                spec("PROFESSIONAL-TAX-PAYABLE", "Professional Tax Payable", AccountType.LIABILITY, "TAX-PAYABLE"),
                spec("COMPLIANCE-EXPENSE", "Compliance and Filing Charges", AccountType.EXPENSE, "6000"),
                spec("EXPORT-INCENTIVE-INCOME", "Export Incentive Income", AccountType.OTHER_INCOME, "4100")
        );
    }

    private List<AccountBlueprint> manufacturingTemplateExtensions() {
        return List.of(
                spec("RAW-MATERIAL-INVENTORY", "Raw Material Inventory", AccountType.ASSET, "1100"),
                spec("PACKAGING-INVENTORY", "Packaging Material Inventory", AccountType.ASSET, "1100"),
                spec("WIP", "Work in Progress", AccountType.ASSET, "1100"),
                spec("SEMI-FINISHED-INVENTORY", "Semi-Finished Goods Inventory", AccountType.ASSET, "1100"),
                spec("FINISHED-GOODS-INVENTORY", "Finished Goods Inventory", AccountType.ASSET, "1100"),
                spec("STORES-SPARES", "Stores and Spares", AccountType.ASSET, "1100"),
                spec("PLANT-MACHINERY", "Plant and Machinery", AccountType.ASSET, "1200"),
                spec("TOOLING-MOULDS", "Tooling and Moulds", AccountType.ASSET, "1200"),
                spec("RM-CONSUMPTION", "Raw Material Consumption", AccountType.COGS, "5000"),
                spec("WIP-CONSUMPTION", "WIP Consumption", AccountType.COGS, "5000"),
                spec("FG-COGS", "Finished Goods COGS", AccountType.COGS, "5000"),
                spec("PRODUCTION-VARIANCE", "Production Variance", AccountType.COGS, "5000"),
                spec("FACTORY-OVERHEAD", "Factory Overhead", AccountType.EXPENSE, "6000"),
                spec("POWER-FUEL-EXPENSE", "Power and Fuel Expense", AccountType.EXPENSE, "6000"),
                spec("PLANT-MAINTENANCE-EXPENSE", "Plant Maintenance Expense", AccountType.EXPENSE, "6000"),
                spec("QUALITY-CONTROL-EXPENSE", "Quality Control Expense", AccountType.EXPENSE, "6000")
        );
    }

    private AccountBlueprint spec(String code, String name, AccountType type, String parentCode) {
        return new AccountBlueprint(code, name, type, parentCode);
    }

    private record AccountBlueprint(String code, String name, AccountType type, String parentCode) {
    }

    private record AdminProvisioningResult(String temporaryPassword, boolean credentialsEmailSent) {
    }
}
