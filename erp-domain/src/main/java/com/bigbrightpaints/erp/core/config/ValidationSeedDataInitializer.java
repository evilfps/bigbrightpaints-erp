package com.bigbrightpaints.erp.core.config;

import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
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
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
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
                                           @Value("${erp.validation-seed.password:Validation123!}") String defaultPassword) {
        return args -> {
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
                    defaultPassword, List.of(mockCompany), List.of(admin, accounting, sales));
            ensureUser(userAccountRepository, passwordEncoder, "validation.accounting@example.com", "Validation Accounting",
                    defaultPassword, List.of(mockCompany), List.of(accounting));
            ensureUser(userAccountRepository, passwordEncoder, "validation.sales@example.com", "Validation Sales",
                    defaultPassword, List.of(mockCompany), List.of(sales));
            ensureUser(userAccountRepository, passwordEncoder, "validation.factory@example.com", "Validation Factory",
                    defaultPassword, List.of(mockCompany), List.of(factory));

            UserAccount dealerUser = ensureUser(userAccountRepository, passwordEncoder,
                    "validation.dealer@example.com", "Validation Dealer",
                    defaultPassword, List.of(mockCompany), List.of(dealerRole));
            ensureDealer(dealerRepository, mockCompany, mockReceivable, dealerUser,
                    "VALID-DEALER", "Validation Dealer");

            UserAccount rivalDealerUser = ensureUser(userAccountRepository, passwordEncoder,
                    "validation.rival.dealer@example.com", "Rival Validation Dealer",
                    defaultPassword, List.of(rivalCompany), List.of(dealerRole));
            ensureDealer(dealerRepository, rivalCompany, rivalReceivable, rivalDealerUser,
                    "RIVAL-DEALER", "Rival Validation Dealer");

            ensureUser(userAccountRepository, passwordEncoder, "validation.rival.admin@example.com", "Rival Validation Admin",
                    defaultPassword, List.of(rivalCompany), List.of(admin));
            ensureUser(userAccountRepository, passwordEncoder, "validation.superadmin@example.com", "Validation Super Admin",
                    defaultPassword, List.of(superAdminCompany, mockCompany), List.of(admin, superAdmin));

            log.info("Validation seed ready for companies [MOCK, RIVAL, SKE] with default actor password '{}'.", defaultPassword);
        };
    }

    private Company ensureCompany(CompanyRepository companyRepository, String code, String name) {
        Company company = companyRepository.findByCodeIgnoreCase(code).orElseGet(Company::new);
        company.setCode(code);
        company.setName(name);
        company.setTimezone("UTC");
        company.setBaseCurrency("INR");
        company.setStateCode("MH");
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
        companies.forEach(user::addCompany);
        roles.forEach(user::addRole);
        return userAccountRepository.save(user);
    }

    private Dealer ensureDealer(DealerRepository dealerRepository,
                                Company company,
                                Account receivableAccount,
                                UserAccount portalUser,
                                String code,
                                String name) {
        Dealer dealer = dealerRepository.findByCompanyAndCodeIgnoreCase(company, code).orElseGet(Dealer::new);
        dealer.setCompany(company);
        dealer.setCode(code);
        dealer.setName(name);
        dealer.setCompanyName(name + " Pvt Ltd");
        dealer.setEmail(portalUser.getEmail());
        dealer.setPortalUser(portalUser);
        dealer.setReceivableAccount(receivableAccount);
        dealer.setStateCode(company.getStateCode());
        dealer.setCreditLimit(new BigDecimal("500000"));
        dealer.setOutstandingBalance(BigDecimal.ZERO);
        return dealerRepository.save(dealer);
    }
}
