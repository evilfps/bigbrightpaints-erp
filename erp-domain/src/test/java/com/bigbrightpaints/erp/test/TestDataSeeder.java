package com.bigbrightpaints.erp.test;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.bigbrightpaints.erp.core.security.AuthScopeService;
import com.bigbrightpaints.erp.core.service.CriticalFixtureService;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.rbac.domain.Permission;
import com.bigbrightpaints.erp.modules.rbac.domain.PermissionRepository;
import com.bigbrightpaints.erp.modules.rbac.domain.Role;
import com.bigbrightpaints.erp.modules.rbac.domain.RoleRepository;
import com.bigbrightpaints.erp.modules.rbac.domain.SystemRole;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrder;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrderRepository;

@Component
@Transactional(propagation = Propagation.REQUIRES_NEW)
public class TestDataSeeder {

  private final CompanyRepository companyRepository;
  private final RoleRepository roleRepository;
  private final PermissionRepository permissionRepository;
  private final UserAccountRepository userRepository;
  private final SalesOrderRepository salesOrderRepository;
  private final PasswordEncoder passwordEncoder;
  private final AuthScopeService authScopeService;
  private final ObjectProvider<CriticalFixtureService> criticalFixtureService;

  public TestDataSeeder(
      CompanyRepository companyRepository,
      RoleRepository roleRepository,
      PermissionRepository permissionRepository,
      UserAccountRepository userRepository,
      SalesOrderRepository salesOrderRepository,
      PasswordEncoder passwordEncoder,
      AuthScopeService authScopeService,
      ObjectProvider<CriticalFixtureService> criticalFixtureService) {
    this.companyRepository = companyRepository;
    this.roleRepository = roleRepository;
    this.permissionRepository = permissionRepository;
    this.userRepository = userRepository;
    this.salesOrderRepository = salesOrderRepository;
    this.passwordEncoder = passwordEncoder;
    this.authScopeService = authScopeService;
    this.criticalFixtureService = criticalFixtureService;
  }

  public Company ensureCompany(String code, String name) {
    return companyRepository
        .findByCodeIgnoreCase(code)
        .map(
            existing -> {
              if (existing.getName() == null) {
                existing.setName(name);
              }
              criticalFixtureService.ifAvailable(service -> service.seedCompanyFixtures(existing));
              return existing;
            })
        .orElseGet(
            () -> {
              Company company = new Company();
              company.setCode(code);
              company.setName(name);
              company.setTimezone(ZoneId.systemDefault().getId());
              Company saved = companyRepository.save(company);
              criticalFixtureService.ifAvailable(service -> service.seedCompanyFixtures(saved));
              return saved;
            });
  }

  public UserAccount ensureUser(
      String email,
      String rawPassword,
      String displayName,
      String companyCode,
      List<String> roleNames) {
    String scopeCode = authScopeService.requireScopeCode(companyCode);
    Company company =
        authScopeService.isPlatformScope(scopeCode)
            ? null
            : ensureCompany(scopeCode, scopeCode + " Ltd");
    UserAccount user =
        userRepository
            .findByEmailIgnoreCaseAndAuthScopeCodeIgnoreCase(email, scopeCode)
            .orElseGet(
                () -> {
                  UserAccount account =
                      new UserAccount(
                          email, scopeCode, passwordEncoder.encode(rawPassword), displayName);
                  if (company != null) {
                    account.setCompany(company);
                  }
                  return account;
                });
    user.setAuthScopeCode(scopeCode);
    user.setDisplayName(displayName);
    user.setPasswordHash(passwordEncoder.encode(rawPassword));
    user.setCompany(company);
    roleNames.forEach(roleName -> user.addRole(ensureRole(roleName)));
    return userRepository.save(user);
  }

  private Role ensureRole(String name) {
    Role role =
        roleRepository
            .findByName(name)
            .orElseGet(
                () -> {
                  Role created = new Role();
                  created.setName(name);
                  created.setDescription(name + " role");
                  return created;
                });

    SystemRole.fromName(role.getName())
        .ifPresent(
            systemRole -> {
              Set<String> existingCodes =
                  new HashSet<>(role.getPermissions().stream().map(Permission::getCode).toList());
              systemRole
                  .getDefaultPermissions()
                  .forEach(
                      code -> {
                        if (!existingCodes.contains(code)) {
                          role.getPermissions().add(ensurePermission(code));
                        }
                      });
            });

    return roleRepository.save(role);
  }

  private Permission ensurePermission(String code) {
    return permissionRepository
        .findByCode(code)
        .orElseGet(
            () -> {
              Permission permission = new Permission();
              permission.setCode(code);
              permission.setDescription(code);
              return permissionRepository.save(permission);
            });
  }

  public SalesOrder ensureSalesOrder(
      String companyCode, String orderNumber, BigDecimal totalAmount) {
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
