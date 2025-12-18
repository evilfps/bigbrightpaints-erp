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
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;

@Configuration
public class DataInitializer {

    @Bean
    @Profile({"dev", "seed"})
    CommandLineRunner seedDefaultUser(UserAccountRepository userRepository,
                                      CompanyRepository companyRepository,
                                      RoleRepository roleRepository,
                                      AccountRepository accountRepository,
                                      PasswordEncoder passwordEncoder) {
        return args -> {
            Company company = companyRepository.findByCodeIgnoreCase("BBP")
                    .orElseGet(() -> {
                        Company c = new Company();
                        c.setName("Big Bright Paints");
                        c.setCode("BBP");
                        c.setTimezone("UTC");
                        return companyRepository.save(c);
                    });
            Role adminRole = roleRepository.findByName("ROLE_ADMIN").orElseGet(() -> {
                Role role = new Role();
                role.setName("ROLE_ADMIN");
                role.setDescription("Platform administrator");
                return roleRepository.save(role);
            });

            userRepository.findByEmailIgnoreCase("admin@bbp.dev").orElseGet(() -> {
                UserAccount user = new UserAccount(
                        "admin@bbp.dev",
                        passwordEncoder.encode("ChangeMe123!"),
                        "Dev Admin");
                user.addCompany(company);
                user.addRole(adminRole);
                return userRepository.save(user);
            });

            seedDefaultAccounts(company, accountRepository);
            setCompanyDefaultAccounts(company, companyRepository, accountRepository);
        };
    }

    private void seedDefaultAccounts(Company company, AccountRepository accountRepository) {
        if (company == null) {
            return;
        }
        List<AccountSeed> seeds = List.of(
                new AccountSeed("1000", "Cash", AccountType.ASSET),
                new AccountSeed("1100", "Accounts Receivable", AccountType.ASSET),
                new AccountSeed("1200", "Inventory", AccountType.ASSET),
                new AccountSeed("2000", "Accounts Payable", AccountType.LIABILITY),
                new AccountSeed("4000", "Revenue", AccountType.REVENUE),
                new AccountSeed("5000", "Cost of Goods Sold", AccountType.COGS),
                new AccountSeed("6000", "Operating Expenses", AccountType.EXPENSE)
        );
        for (AccountSeed seed : seeds) {
            accountRepository.findByCompanyAndCodeIgnoreCase(company, seed.code())
                    .orElseGet(() -> {
                        Account account = new Account();
                        account.setCompany(company);
                        account.setCode(seed.code());
                        account.setName(seed.name());
                        account.setType(seed.type());
                        return accountRepository.save(account);
                    });
        }
    }

    private void setCompanyDefaultAccounts(Company company,
                                          CompanyRepository companyRepository,
                                          AccountRepository accountRepository) {
        // Only set if missing to avoid overriding user-configured values
        if (company.getDefaultInventoryAccountId() == null) {
            accountRepository.findByCompanyAndCodeIgnoreCase(company, "1200")
                    .ifPresent(a -> company.setDefaultInventoryAccountId(a.getId()));
        }
        if (company.getDefaultCogsAccountId() == null) {
            accountRepository.findByCompanyAndCodeIgnoreCase(company, "5000")
                    .ifPresent(a -> company.setDefaultCogsAccountId(a.getId()));
        }
        if (company.getDefaultRevenueAccountId() == null) {
            accountRepository.findByCompanyAndCodeIgnoreCase(company, "4000")
                    .ifPresent(a -> company.setDefaultRevenueAccountId(a.getId()));
        }
        if (company.getDefaultTaxAccountId() == null) {
            accountRepository.findByCompanyAndCodeIgnoreCase(company, "2000")
                    .ifPresent(a -> company.setDefaultTaxAccountId(a.getId()));
        }
        companyRepository.save(company);
    }

    private record AccountSeed(String code, String name, AccountType type) {}
}
