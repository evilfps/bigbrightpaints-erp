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
                new AccountSeed("CASH", "Cash", AccountType.ASSET),
                new AccountSeed("AR", "Accounts Receivable", AccountType.ASSET),
                new AccountSeed("AP", "Accounts Payable", AccountType.LIABILITY),
                new AccountSeed("INV", "Inventory", AccountType.ASSET),
                new AccountSeed("COGS", "Cost of Goods Sold", AccountType.COGS),
                new AccountSeed("REV", "Revenue", AccountType.REVENUE),
                new AccountSeed("GST-IN", "GST Input Tax", AccountType.ASSET),
                new AccountSeed("GST-OUT", "GST Output Tax", AccountType.LIABILITY),
                new AccountSeed("GST-PAY", "GST Payable", AccountType.LIABILITY),
                new AccountSeed("DISC", "Discounts", AccountType.EXPENSE),
                new AccountSeed("WIP", "Work in Progress", AccountType.ASSET),
                new AccountSeed("OPEX", "Operating Expenses", AccountType.EXPENSE)
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
            accountRepository.findByCompanyAndCodeIgnoreCase(company, "INV")
                    .ifPresent(a -> company.setDefaultInventoryAccountId(a.getId()));
        }
        if (company.getDefaultCogsAccountId() == null) {
            accountRepository.findByCompanyAndCodeIgnoreCase(company, "COGS")
                    .ifPresent(a -> company.setDefaultCogsAccountId(a.getId()));
        }
        if (company.getDefaultRevenueAccountId() == null) {
            accountRepository.findByCompanyAndCodeIgnoreCase(company, "REV")
                    .ifPresent(a -> company.setDefaultRevenueAccountId(a.getId()));
        }
        if (company.getDefaultDiscountAccountId() == null) {
            accountRepository.findByCompanyAndCodeIgnoreCase(company, "DISC")
                    .ifPresent(a -> company.setDefaultDiscountAccountId(a.getId()));
        }
        if (company.getDefaultTaxAccountId() == null) {
            accountRepository.findByCompanyAndCodeIgnoreCase(company, "GST-OUT")
                    .ifPresent(a -> company.setDefaultTaxAccountId(a.getId()));
        }
        if (company.getGstInputTaxAccountId() == null) {
            accountRepository.findByCompanyAndCodeIgnoreCase(company, "GST-IN")
                    .ifPresent(a -> company.setGstInputTaxAccountId(a.getId()));
        }
        if (company.getGstOutputTaxAccountId() == null) {
            accountRepository.findByCompanyAndCodeIgnoreCase(company, "GST-OUT")
                    .ifPresent(a -> company.setGstOutputTaxAccountId(a.getId()));
        }
        if (company.getGstPayableAccountId() == null) {
            accountRepository.findByCompanyAndCodeIgnoreCase(company, "GST-PAY")
                    .ifPresent(a -> company.setGstPayableAccountId(a.getId()));
        }
        companyRepository.save(company);
    }

    private record AccountSeed(String code, String name, AccountType type) {}
}
