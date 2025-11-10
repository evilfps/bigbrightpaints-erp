package com.bigbrightpaints.erp.test;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.rbac.domain.Role;
import com.bigbrightpaints.erp.modules.rbac.domain.RoleRepository;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrder;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrderRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Component
@Transactional
public class TestDataSeeder {

    private final CompanyRepository companyRepository;
    private final RoleRepository roleRepository;
    private final UserAccountRepository userRepository;
    private final SalesOrderRepository salesOrderRepository;
    private final PasswordEncoder passwordEncoder;

    public TestDataSeeder(CompanyRepository companyRepository,
                          RoleRepository roleRepository,
                          UserAccountRepository userRepository,
                          SalesOrderRepository salesOrderRepository,
                          PasswordEncoder passwordEncoder) {
        this.companyRepository = companyRepository;
        this.roleRepository = roleRepository;
        this.userRepository = userRepository;
        this.salesOrderRepository = salesOrderRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public Company ensureCompany(String code, String name) {
        return companyRepository.findByCodeIgnoreCase(code)
                .map(existing -> {
                    if (existing.getName() == null) {
                        existing.setName(name);
                    }
                    return existing;
                })
                .orElseGet(() -> {
                    Company company = new Company();
                    company.setCode(code);
                    company.setName(name);
                    company.setTimezone("UTC");
                    return companyRepository.save(company);
                });
    }

    public UserAccount ensureUser(String email, String rawPassword, String displayName,
                                  String companyCode, List<String> roleNames) {
        Company company = ensureCompany(companyCode, companyCode + " Ltd");
        UserAccount user = userRepository.findByEmailIgnoreCase(email)
                .orElseGet(() -> {
                    UserAccount account = new UserAccount(email, passwordEncoder.encode(rawPassword), displayName);
                    account.getCompanies().add(company);
                    return account;
                });
        user.setDisplayName(displayName);
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.getCompanies().add(company);
        roleNames.forEach(roleName -> user.addRole(ensureRole(roleName)));
        return userRepository.save(user);
    }

    private Role ensureRole(String name) {
        return roleRepository.findByName(name)
                .orElseGet(() -> {
                    Role role = new Role();
                    role.setName(name);
                    role.setDescription(name + " role");
                    return roleRepository.save(role);
                });
    }

    public SalesOrder ensureSalesOrder(String companyCode, String orderNumber, BigDecimal totalAmount) {
        Company company = ensureCompany(companyCode, companyCode + " Ltd");
        SalesOrder order = new SalesOrder();
        order.setCompany(company);
        order.setOrderNumber(orderNumber);
        order.setStatus("DRAFT");
        order.setTotalAmount(totalAmount);
        order.setCurrency("INR");
        order.setNotes("Seed order");
        return salesOrderRepository.save(order);
    }
}
