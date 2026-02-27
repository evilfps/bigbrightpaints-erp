package com.bigbrightpaints.erp.modules.auth.service;

import com.bigbrightpaints.erp.core.notification.EmailService;
import com.bigbrightpaints.erp.core.security.TokenBlacklistService;
import com.bigbrightpaints.erp.core.util.PasswordUtils;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.rbac.domain.Role;
import com.bigbrightpaints.erp.modules.rbac.service.RoleService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.util.Locale;

@Service
public class TenantAdminProvisioningService {

    private final UserAccountRepository userAccountRepository;
    private final RoleService roleService;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;
    private final TokenBlacklistService tokenBlacklistService;
    private final RefreshTokenService refreshTokenService;

    public TenantAdminProvisioningService(UserAccountRepository userAccountRepository,
                                          RoleService roleService,
                                          PasswordEncoder passwordEncoder,
                                          EmailService emailService,
                                          TokenBlacklistService tokenBlacklistService,
                                          RefreshTokenService refreshTokenService) {
        this.userAccountRepository = userAccountRepository;
        this.roleService = roleService;
        this.passwordEncoder = passwordEncoder;
        this.emailService = emailService;
        this.tokenBlacklistService = tokenBlacklistService;
        this.refreshTokenService = refreshTokenService;
    }

    public boolean isCredentialEmailDeliveryEnabled() {
        return emailService.isCredentialEmailDeliveryEnabled();
    }

    @Transactional
    public String provisionInitialAdmin(Company company, String firstAdminEmail, String firstAdminDisplayName) {
        if (company == null || company.getId() == null) {
            throw new IllegalArgumentException("Company must be persisted before admin provisioning");
        }
        String normalizedEmail = normalizeEmail(firstAdminEmail, "firstAdminEmail");
        if (userAccountRepository.findByEmailIgnoreCase(normalizedEmail).isPresent()) {
            throw new IllegalArgumentException("First admin email already exists: " + normalizedEmail);
        }
        Role adminRole = roleService.ensureRoleExists("ROLE_ADMIN");
        String temporaryPassword = PasswordUtils.generateTemporaryPassword(14);
        UserAccount firstAdmin = new UserAccount(
                normalizedEmail,
                passwordEncoder.encode(temporaryPassword),
                resolveFirstAdminDisplayName(firstAdminDisplayName, company));
        firstAdmin.setMustChangePassword(true);
        firstAdmin.addCompany(company);
        firstAdmin.addRole(adminRole);
        userAccountRepository.save(firstAdmin);
        sendCredentialsEmailAfterCommit(
                firstAdmin.getEmail(),
                firstAdmin.getDisplayName(),
                temporaryPassword,
                company.getCode());
        return firstAdmin.getEmail();
    }

    @Transactional
    public String resetTenantAdminPassword(Company company, String adminEmail) {
        if (company == null || company.getId() == null) {
            throw new IllegalArgumentException("Company must exist before resetting admin credentials");
        }
        String normalizedEmail = normalizeEmail(adminEmail, "adminEmail");
        UserAccount user = userAccountRepository.findByEmailIgnoreCase(normalizedEmail)
                .orElseThrow(() -> new IllegalArgumentException("Admin user not found: " + normalizedEmail));
        boolean assigned = user.getCompanies().stream()
                .anyMatch(tenant -> tenant.getId() != null && tenant.getId().equals(company.getId()));
        if (!assigned) {
            throw new IllegalArgumentException("Admin user is not assigned to company: " + company.getCode());
        }
        if (!hasAnyAuthority(user, "ROLE_ADMIN", "ROLE_SUPER_ADMIN")) {
            throw new IllegalArgumentException("Target user is not an admin for company: " + company.getCode());
        }
        String temporaryPassword = PasswordUtils.generateTemporaryPassword(14);
        tokenBlacklistService.revokeAllUserTokens(user.getEmail());
        refreshTokenService.revokeAllForUser(user.getEmail());
        user.setPasswordHash(passwordEncoder.encode(temporaryPassword));
        user.setMustChangePassword(true);
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        userAccountRepository.save(user);
        sendCredentialsEmailAfterCommit(
                user.getEmail(),
                user.getDisplayName(),
                temporaryPassword,
                company.getCode());
        return user.getEmail();
    }

    private void sendCredentialsEmailAfterCommit(String email,
                                                 String displayName,
                                                 String temporaryPassword,
                                                 String companyCode) {
        Runnable sendEmail = () -> emailService.sendUserCredentialsEmailRequired(
                email,
                displayName,
                temporaryPassword,
                companyCode);
        if (TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    sendEmail.run();
                }
            });
            return;
        }
        sendEmail.run();
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
            throw new IllegalArgumentException(fieldName + " is required");
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
}
