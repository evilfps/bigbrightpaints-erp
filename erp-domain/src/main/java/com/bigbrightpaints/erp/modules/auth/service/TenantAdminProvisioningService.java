package com.bigbrightpaints.erp.modules.auth.service;

import java.util.Locale;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.rbac.domain.Role;
import com.bigbrightpaints.erp.modules.rbac.domain.RoleRepository;
import com.bigbrightpaints.erp.modules.rbac.service.RoleService;

@Service
public class TenantAdminProvisioningService {

  private final UserAccountRepository userAccountRepository;
  private final RoleService roleService;
  private final RoleRepository roleRepository;
  private final ScopedAccountBootstrapService scopedAccountBootstrapService;
  private final PasswordResetService passwordResetService;

  public TenantAdminProvisioningService(
      UserAccountRepository userAccountRepository,
      RoleService roleService,
      RoleRepository roleRepository,
      ScopedAccountBootstrapService scopedAccountBootstrapService,
      PasswordResetService passwordResetService) {
    this.userAccountRepository = userAccountRepository;
    this.roleService = roleService;
    this.roleRepository = roleRepository;
    this.scopedAccountBootstrapService = scopedAccountBootstrapService;
    this.passwordResetService = passwordResetService;
  }

  public boolean isCredentialProvisioningReady() {
    return scopedAccountBootstrapService.isCredentialProvisioningReady();
  }

  @Transactional
  public UserAccount provisionInitialAdmin(
      Company company, String firstAdminEmail, String firstAdminDisplayName) {
    if (company == null || company.getId() == null) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          "Company must be persisted before admin provisioning");
    }
    String normalizedEmail = normalizeEmail(firstAdminEmail, "firstAdminEmail");
    if (userAccountRepository.existsByEmailIgnoreCaseAndAuthScopeCodeIgnoreCase(
        normalizedEmail, company.getCode())) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          "First admin email already exists: " + normalizedEmail);
    }
    Role adminRole = requireAdminRole();
    UserAccount firstAdmin =
        scopedAccountBootstrapService.provisionTenantAccount(
            company,
            normalizedEmail,
            resolveFirstAdminDisplayName(firstAdminDisplayName, company),
            java.util.List.of(adminRole));
    company.setMainAdminUserId(firstAdmin.getId());
    company.setOnboardingAdminEmail(firstAdmin.getEmail());
    company.setOnboardingAdminUserId(firstAdmin.getId());
    return firstAdmin;
  }

  @Transactional
  public String resetTenantAdminPassword(Company company, String adminEmail) {
    if (company == null || company.getId() == null) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          "Company must exist before resetting admin credentials");
    }
    String normalizedEmail = normalizeEmail(adminEmail, "adminEmail");
    UserAccount user =
        userAccountRepository
            .findByEmailIgnoreCaseAndAuthScopeCodeIgnoreCase(normalizedEmail, company.getCode())
            .orElseThrow(
                () ->
                    com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
                        "Admin user not found: " + normalizedEmail));
    if (user.getCompany() == null
        || user.getCompany().getId() == null
        || !user.getCompany().getId().equals(company.getId())) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          "Admin user is not assigned to company: " + company.getCode());
    }
    if (!hasAnyAuthority(user, "ROLE_ADMIN", "ROLE_SUPER_ADMIN")) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          "Target user is not an admin for company: " + company.getCode());
    }
    passwordResetService.requestResetByAdmin(user);
    return user.getEmail();
  }

  private String resolveFirstAdminDisplayName(String requestedDisplayName, Company company) {
    if (StringUtils.hasText(requestedDisplayName)) {
      return requestedDisplayName.trim();
    }
    String companyName = company != null ? company.getName() : null;
    if (StringUtils.hasText(companyName)) {
      return companyName.trim() + " Admin";
    }
    return "Company Admin";
  }

  private String normalizeEmail(String email, String fieldName) {
    if (!StringUtils.hasText(email)) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          fieldName + " is required");
    }
    return email.trim().toLowerCase(Locale.ROOT);
  }

  private boolean hasAnyAuthority(UserAccount user, String... authorities) {
    if (user == null || user.getRoles() == null || authorities == null || authorities.length == 0) {
      return false;
    }
    for (Role role : user.getRoles()) {
      if (role == null || !StringUtils.hasText(role.getName())) {
        continue;
      }
      for (String authority : authorities) {
        if (StringUtils.hasText(authority) && authority.equalsIgnoreCase(role.getName())) {
          return true;
        }
      }
    }
    return false;
  }

  private Role requireAdminRole() {
    roleService.ensureRoleExists("ROLE_ADMIN");
    return roleRepository
        .findByName("ROLE_ADMIN")
        .orElseThrow(
            () ->
                com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidState(
                    "ROLE_ADMIN must exist before tenant admin provisioning"));
  }
}
