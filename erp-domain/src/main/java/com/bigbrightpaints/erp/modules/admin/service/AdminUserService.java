package com.bigbrightpaints.erp.modules.admin.service;

import com.bigbrightpaints.erp.core.notification.EmailService;
import com.bigbrightpaints.erp.core.security.TokenBlacklistService;
import com.bigbrightpaints.erp.core.util.PasswordUtils;
import com.bigbrightpaints.erp.modules.admin.dto.CreateUserRequest;
import com.bigbrightpaints.erp.modules.admin.dto.UpdateUserRequest;
import com.bigbrightpaints.erp.modules.admin.dto.UserDto;
import com.bigbrightpaints.erp.modules.auth.service.RefreshTokenService;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.rbac.domain.Role;
import com.bigbrightpaints.erp.modules.rbac.domain.RoleRepository;
import com.bigbrightpaints.erp.modules.rbac.service.RoleService;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import jakarta.transaction.Transactional;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
public class AdminUserService {

    private final UserAccountRepository userRepository;
    private final CompanyContextService companyContextService;
    private final RoleRepository roleRepository;
    private final RoleService roleService;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;
    private final TokenBlacklistService tokenBlacklistService;
    private final RefreshTokenService refreshTokenService;
    private final DealerRepository dealerRepository;
    private final AccountRepository accountRepository;
    private final TenantRuntimePolicyService tenantRuntimePolicyService;

    public AdminUserService(UserAccountRepository userRepository,
                            CompanyContextService companyContextService,
                            RoleRepository roleRepository,
                            RoleService roleService,
                            PasswordEncoder passwordEncoder,
                            EmailService emailService,
                            TokenBlacklistService tokenBlacklistService,
                            RefreshTokenService refreshTokenService,
                            DealerRepository dealerRepository,
                            AccountRepository accountRepository,
                            TenantRuntimePolicyService tenantRuntimePolicyService) {
        this.userRepository = userRepository;
        this.companyContextService = companyContextService;
        this.roleRepository = roleRepository;
        this.roleService = roleService;
        this.passwordEncoder = passwordEncoder;
        this.emailService = emailService;
        this.tokenBlacklistService = tokenBlacklistService;
        this.refreshTokenService = refreshTokenService;
        this.dealerRepository = dealerRepository;
        this.accountRepository = accountRepository;
        this.tenantRuntimePolicyService = tenantRuntimePolicyService;
    }

    public List<UserDto> listUsers() {
        Company company = companyContextService.requireCurrentCompany();
        return userRepository.findDistinctByCompanies_Id(company.getId()).stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional
    public UserDto createUser(CreateUserRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        tenantRuntimePolicyService.assertCanAddEnabledUser(company, "ADMIN_USER_CREATE");
        boolean isTemporaryPassword = !StringUtils.hasText(request.password());
        String tempPassword = isTemporaryPassword ? PasswordUtils.generateTemporaryPassword(12) : request.password();
        UserAccount user = new UserAccount(request.email(), passwordEncoder.encode(tempPassword), request.displayName());
        
        // Force password change if using temporary password
        if (isTemporaryPassword) {
            user.setMustChangePassword(true);
        }
        
        attachCompanies(user, company, request.companyIds());
        attachRoles(user, request.roles());
        UserAccount saved = userRepository.save(user);
        
        // Auto-create Dealer entity if user has ROLE_DEALER
        boolean isDealerUser = request.roles().stream()
                .anyMatch(r -> r.equalsIgnoreCase("ROLE_DEALER") || r.equalsIgnoreCase("DEALER"));
        if (isDealerUser && !saved.getCompanies().isEmpty()) {
            createDealerForUser(saved, company);
        }
        
        emailService.sendUserCredentialsEmail(saved.getEmail(), saved.getDisplayName(), tempPassword);
        return toDto(saved);
    }
    
    private void createDealerForUser(UserAccount user, Company company) {
        // Check if dealer already exists for this user
        if (dealerRepository.findByCompanyAndPortalUserEmail(company, user.getEmail()).isPresent()) {
            return;
        }
        
        String dealerCode = generateDealerCode(user.getDisplayName(), company);
        
        Dealer dealer = new Dealer();
        dealer.setCompany(company);
        dealer.setName(user.getDisplayName());
        dealer.setCode(dealerCode);
        dealer.setEmail(user.getEmail());
        dealer.setPortalUser(user);
        dealer = dealerRepository.save(dealer);
        
        // Create receivable account for the dealer
        Account receivableAccount = createReceivableAccount(company, dealer);
        dealer.setReceivableAccount(receivableAccount);
        dealerRepository.save(dealer);
    }
    
    private Account createReceivableAccount(Company company, Dealer dealer) {
        String baseCode = "AR-" + dealer.getCode();
        String code = baseCode;
        int attempt = 1;
        while (accountRepository.findByCompanyAndCodeIgnoreCase(company, code).isPresent()) {
            code = baseCode + "-" + attempt++;
        }
        Account account = new Account();
        account.setCompany(company);
        account.setCode(code);
        account.setName(dealer.getName() + " Receivable");
        account.setType(AccountType.ASSET);
        resolveControlAccount(company, "AR", AccountType.ASSET).ifPresent(account::setParent);
        return accountRepository.save(account);
    }

    private Optional<Account> resolveControlAccount(Company company, String code, AccountType expectedType) {
        return accountRepository.findByCompanyAndCodeIgnoreCase(company, code)
                .filter(account -> account.getType() == expectedType);
    }
    
    private String generateDealerCode(String name, Company company) {
        String normalized = java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replaceAll("[^A-Za-z0-9]", "")
                .toUpperCase(Locale.ROOT);
        String base = normalized.isEmpty() ? "DEALER" : normalized;
        String code = base;
        int attempt = 1;
        while (dealerRepository.findByCompanyAndCodeIgnoreCase(company, code).isPresent()) {
            code = base + attempt++;
        }
        return code;
    }

    @Transactional
    public UserDto updateUser(Long id, UpdateUserRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        UserAccount user = userRepository.findByIdAndCompanies_Id(id, company.getId())
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        user.setDisplayName(request.displayName());
        boolean requiresReauth = false;
        if (request.enabled() != null) {
            if (request.enabled() && !user.isEnabled()) {
                tenantRuntimePolicyService.assertCanAddEnabledUser(company, "ADMIN_USER_ENABLE");
            }
            if (!request.enabled() && user.isEnabled()) {
                requiresReauth = true; // User being disabled
            }
            user.setEnabled(request.enabled());
        }
        if (request.companyIds() != null && !request.companyIds().isEmpty()) {
            user.getCompanies().clear();
            attachCompanies(user, company, request.companyIds());
            requiresReauth = true; // Company access changed
        }
        if (request.roles() != null && !request.roles().isEmpty()) {
            user.getRoles().clear();
            attachRoles(user, request.roles());
            requiresReauth = true; // Roles changed
        }
        // Revoke tokens if permissions changed to force re-authentication
        if (requiresReauth) {
            tokenBlacklistService.revokeAllUserTokens(user.getEmail());
            refreshTokenService.revokeAllForUser(user.getEmail());
        }
        return toDto(user);
    }

    @Transactional
    public void suspend(Long id) {
        Company company = companyContextService.requireCurrentCompany();
        // Use pessimistic lock to prevent race condition on concurrent status changes
        userRepository.lockByIdAndCompanyId(id, company.getId()).ifPresent(user -> {
            user.setEnabled(false);
            userRepository.save(user);
            // Revoke all active tokens to force re-authentication
            tokenBlacklistService.revokeAllUserTokens(user.getEmail());
            refreshTokenService.revokeAllForUser(user.getEmail());
            emailService.sendUserSuspendedEmail(user.getEmail(), user.getDisplayName());
        });
    }

    @Transactional
    public void unsuspend(Long id) {
        Company company = companyContextService.requireCurrentCompany();
        // Use pessimistic lock to prevent race condition on concurrent status changes
        userRepository.lockByIdAndCompanyId(id, company.getId()).ifPresent(user -> {
            if (!user.isEnabled()) {
                tenantRuntimePolicyService.assertCanAddEnabledUser(company, "ADMIN_USER_UNSUSPEND");
            }
            user.setEnabled(true);
            userRepository.save(user);
        });
    }

    @Transactional
    public void deleteUser(Long id) {
        Company company = companyContextService.requireCurrentCompany();
        userRepository.lockByIdAndCompanyId(id, company.getId()).ifPresent(user -> {
            tokenBlacklistService.revokeAllUserTokens(user.getEmail());
            refreshTokenService.revokeAllForUser(user.getEmail());
            userRepository.delete(user);
            emailService.sendUserDeletedEmail(user.getEmail(), user.getDisplayName());
        });
    }

    @Transactional
    public void disableMfa(Long id) {
        Company company = companyContextService.requireCurrentCompany();
        userRepository.lockByIdAndCompanyId(id, company.getId()).ifPresent(user -> {
            user.setMfaEnabled(false);
            user.setMfaSecret(null);
            user.setMfaRecoveryCodeHashes(List.of());
            userRepository.save(user);
            tokenBlacklistService.revokeAllUserTokens(user.getEmail());
            refreshTokenService.revokeAllForUser(user.getEmail());
        });
    }

    private void validateCompanyScope(Company company, List<Long> companyIds) {
        if (companyIds == null || companyIds.isEmpty()) {
            throw new IllegalArgumentException("User must belong to an active company");
        }
        boolean allMatch = companyIds.stream().allMatch(id -> id.equals(company.getId()));
        if (!allMatch) {
            throw new IllegalArgumentException("User must be assigned to the active company");
        }
    }

    private void attachCompanies(UserAccount user, Company company, List<Long> companyIds) {
        validateCompanyScope(company, companyIds);
        user.addCompany(company);
    }

    private void attachRoles(UserAccount user, List<String> roles) {
        roles.forEach(roleName -> {
            if (!StringUtils.hasText(roleName)) {
                return;
            }
            String trimmed = roleName.trim();
            String normalized = trimmed.toUpperCase(Locale.ROOT);
            // If caller omitted prefix but matches a system role, add prefix
            if (!normalized.startsWith("ROLE_")) {
                String withPrefix = "ROLE_" + normalized;
                if (roleService.isSystemRole(withPrefix)) {
                    normalized = withPrefix;
                }
            }
            // Allow both system roles and custom roles
            Role role = roleService.ensureRoleExists(normalized);
            user.addRole(role);
        });
    }

    private UserDto toDto(UserAccount user) {
        List<String> companies = user.getCompanies().stream().map(Company::getCode).toList();
        List<String> roles = user.getRoles().stream().map(Role::getName).toList();
        return new UserDto(user.getId(), user.getPublicId(), user.getEmail(), user.getDisplayName(),
                user.isEnabled(), user.isMfaEnabled(), roles, companies);
    }
}
