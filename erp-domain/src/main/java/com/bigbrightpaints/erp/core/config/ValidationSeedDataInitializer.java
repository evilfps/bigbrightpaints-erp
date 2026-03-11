package com.bigbrightpaints.erp.core.config;

import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import com.bigbrightpaints.erp.modules.auth.service.PasswordPolicy;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.rbac.domain.Role;
import com.bigbrightpaints.erp.modules.rbac.domain.RoleRepository;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

@Configuration
@Profile("validation-seed")
public class ValidationSeedDataInitializer {

    private static final Logger log = LoggerFactory.getLogger(ValidationSeedDataInitializer.class);

    @Bean
    CommandLineRunner seedValidationActors(CompanyRepository companyRepository,
                                           RoleRepository roleRepository,
                                           UserAccountRepository userAccountRepository,
                                           DealerRepository dealerRepository,
                                           AccountRepository accountRepository,
                                           PasswordEncoder passwordEncoder,
                                           PasswordPolicy passwordPolicy,
                                           Environment environment,
                                           @Value("${erp.validation-seed.enabled:false}") boolean validationSeedEnabled,
                                           @Value("${erp.validation-seed.password:}") String defaultPassword) {
        return args -> {
            if (!validationSeedEnabled) {
                log.info("Validation seed disabled; set erp.validation-seed.enabled=true to seed local validation actors.");
                return;
            }

            ensureMockProfileEnabled(environment);
            String validatedPassword = requireStrongPassword(passwordPolicy, defaultPassword);

            Company mockCompany = ensureCompany(companyRepository, "MOCK", "Mock Training Co");
            Company rivalCompany = ensureCompany(companyRepository, "RIVAL", "Rival Validation Co");
            Company superAdminCompany = ensureCompany(companyRepository, "SKE", "Platform Super Admin");

            Role admin = ensureRole(roleRepository, "ROLE_ADMIN", "Administrator");
            Role accounting = ensureRole(roleRepository, "ROLE_ACCOUNTING", "Accounting");
            Role sales = ensureRole(roleRepository, "ROLE_SALES", "Sales");
            Role factory = ensureRole(roleRepository, "ROLE_FACTORY", "Factory");
            Role dealerRole = ensureRole(roleRepository, "ROLE_DEALER", "Dealer portal");
            Role superAdmin = ensureRole(roleRepository, "ROLE_SUPER_ADMIN", "Platform super administrator");

            Account mockReceivable = ensureAccount(accountRepository, mockCompany, "AR", "Accounts Receivable", AccountType.ASSET);
            Account rivalReceivable = ensureAccount(accountRepository, rivalCompany, "AR", "Accounts Receivable", AccountType.ASSET);

            ensureUser(userAccountRepository, passwordEncoder, "validation.admin@example.com", "Validation Admin",
                    validatedPassword, List.of(mockCompany), List.of(admin, accounting, sales));
            ensureUser(userAccountRepository, passwordEncoder, "validation.accounting@example.com", "Validation Accounting",
                    validatedPassword, List.of(mockCompany), List.of(accounting));
            ensureUser(userAccountRepository, passwordEncoder, "validation.sales@example.com", "Validation Sales",
                    validatedPassword, List.of(mockCompany), List.of(sales));
            ensureUser(userAccountRepository, passwordEncoder, "validation.factory@example.com", "Validation Factory",
                    validatedPassword, List.of(mockCompany), List.of(factory));

            UserAccount dealerUser = ensureUser(userAccountRepository, passwordEncoder,
                    "validation.dealer@example.com", "Validation Dealer",
                    validatedPassword, List.of(mockCompany), List.of(dealerRole));
            ensureDealer(dealerRepository, mockCompany, mockReceivable, dealerUser,
                    "VALID-DEALER", "Validation Dealer");

            UserAccount rivalDealerUser = ensureUser(userAccountRepository, passwordEncoder,
                    "validation.rival.dealer@example.com", "Rival Validation Dealer",
                    validatedPassword, List.of(rivalCompany), List.of(dealerRole));
            ensureDealer(dealerRepository, rivalCompany, rivalReceivable, rivalDealerUser,
                    "RIVAL-DEALER", "Rival Validation Dealer");

            ensureUser(userAccountRepository, passwordEncoder, "validation.rival.admin@example.com", "Rival Validation Admin",
                    validatedPassword, List.of(rivalCompany), List.of(admin));
            ensureUser(userAccountRepository, passwordEncoder, "validation.superadmin@example.com", "Validation Super Admin",
                    validatedPassword, List.of(superAdminCompany, mockCompany), List.of(admin, superAdmin));

            log.info("Validation seed ready for companies [MOCK, RIVAL, SKE]. Actor password comes from erp.validation-seed.password / ERP_VALIDATION_SEED_PASSWORD.");
        };
    }

    private void ensureMockProfileEnabled(Environment environment) {
        boolean mockProfileEnabled = Arrays.asList(environment.getActiveProfiles()).contains("mock");
        if (!mockProfileEnabled) {
            throw new IllegalStateException("Validation seed may only run when the mock profile is active for local validation.");
        }
    }

    private String requireStrongPassword(PasswordPolicy passwordPolicy, String password) {
        String candidate = password == null ? "" : password.trim();
        List<String> violations = passwordPolicy.validate(candidate);
        if (!violations.isEmpty()) {
            throw new IllegalStateException("Validation seed password must satisfy the application password policy: "
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
        return roleRepository.findByName(name).orElseGet(() -> {
            Role role = new Role();
            role.setName(name);
            role.setDescription(description);
            return roleRepository.save(role);
        });
    }

    private Account ensureAccount(AccountRepository accountRepository,
                                  Company company,
                                  String code,
                                  String name,
                                  AccountType type) {
        return accountRepository.findByCompanyAndCodeIgnoreCase(company, code).orElseGet(() -> {
            Account account = new Account();
            account.setCompany(company);
            account.setCode(code);
            account.setName(name);
            account.setType(type);
            account.setBalance(BigDecimal.ZERO);
            return accountRepository.save(account);
        });
    }

    private UserAccount ensureUser(UserAccountRepository userAccountRepository,
                                   PasswordEncoder passwordEncoder,
                                   String email,
                                   String displayName,
                                   String password,
                                   List<Company> companies,
                                   List<Role> roles) {
        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
        UserAccount user = userAccountRepository.findByEmailIgnoreCase(normalizedEmail)
                .orElseGet(() -> new UserAccount(normalizedEmail, passwordEncoder.encode(password), displayName));
        user.setEmail(normalizedEmail);
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
        user.getCompanies().clear();
        companies.forEach(user::addCompany);
    }

    private void normalizeRoleMemberships(UserAccount user, List<Role> roles) {
        user.getRoles().clear();
        roles.forEach(user::addRole);
    }

    private Dealer ensureDealer(DealerRepository dealerRepository,
                                Company company,
                                Account receivableAccount,
                                UserAccount portalUser,
                                String code,
                                String name) {
        Dealer dealer = dealerRepository.findByCompanyAndCodeIgnoreCase(company, code).orElseGet(Dealer::new);
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
        dealer.setOutstandingBalance(existingOutstandingBalance == null ? BigDecimal.ZERO : existingOutstandingBalance);
        return dealerRepository.save(dealer);
    }
}
