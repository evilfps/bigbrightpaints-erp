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
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.rbac.domain.Role;
import com.bigbrightpaints.erp.modules.rbac.domain.RoleRepository;
import com.bigbrightpaints.erp.modules.rbac.service.RoleService;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.sales.util.DealerProvisioningSupport;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class AdminUserService {
    private static final String SUPER_ADMIN_ROLE = "ROLE_SUPER_ADMIN";

    private final UserAccountRepository userRepository;
    private final CompanyContextService companyContextService;
    private final CompanyRepository companyRepository;
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
                            CompanyRepository companyRepository,
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
        this.companyRepository = companyRepository;
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
        List<Company> targetCompanies = resolveTargetCompaniesForCreate(company, request.companyIds());
        targetCompanies.forEach(targetCompany ->
                tenantRuntimePolicyService.assertCanAddEnabledUser(targetCompany, "ADMIN_USER_CREATE"));
        boolean isTemporaryPassword = !StringUtils.hasText(request.password());
        String tempPassword = isTemporaryPassword ? PasswordUtils.generateTemporaryPassword(12) : request.password();
        UserAccount user = new UserAccount(request.email(), passwordEncoder.encode(tempPassword), request.displayName());
        
        // Force password change if using temporary password
        if (isTemporaryPassword) {
            user.setMustChangePassword(true);
        }
        
        attachCompanies(user, targetCompanies);
        attachRoles(user, request.roles());
        UserAccount saved = userRepository.save(user);
        
        // Auto-create Dealer entity if user has ROLE_DEALER
        boolean isDealerUser = request.roles().stream()
                .anyMatch(r -> r.equalsIgnoreCase("ROLE_DEALER") || r.equalsIgnoreCase("DEALER"));
        if (isDealerUser && !targetCompanies.isEmpty()) {
            createDealerForUser(saved, targetCompanies.getFirst());
        }
        
        emailService.sendUserCredentialsEmail(saved.getEmail(), saved.getDisplayName(), tempPassword);
        return toDto(saved);
    }
    
    private void createDealerForUser(UserAccount user, Company company) {
        Dealer dealer = dealerRepository.findByCompanyAndPortalUserEmail(company, user.getEmail())
                .or(() -> dealerRepository.findByCompanyAndEmailIgnoreCase(company, user.getEmail()))
                .orElseGet(() -> {
                    Dealer fresh = new Dealer();
                    fresh.setCompany(company);
                    fresh.setName(user.getDisplayName());
                    fresh.setCode(DealerProvisioningSupport.generateDealerCode(
                            user.getDisplayName(),
                            company,
                            dealerRepository));
                    return fresh;
                });

        if (!StringUtils.hasText(dealer.getCode())) {
            dealer.setCode(DealerProvisioningSupport.generateDealerCode(
                    user.getDisplayName(),
                    company,
                    dealerRepository));
        }
        if (!StringUtils.hasText(dealer.getName())) {
            dealer.setName(user.getDisplayName());
        }
        dealer.setEmail(user.getEmail());
        dealer.setPortalUser(user);
        dealer.setStatus(DealerProvisioningSupport.ACTIVE_STATUS);
        dealer = dealerRepository.save(dealer);

        if (dealer.getReceivableAccount() == null) {
            dealer.setReceivableAccount(DealerProvisioningSupport.createReceivableAccount(company, dealer, accountRepository));
            dealerRepository.save(dealer);
            return;
        }

        if (!dealer.getReceivableAccount().isActive()) {
            dealer.getReceivableAccount().setActive(true);
            accountRepository.save(dealer.getReceivableAccount());
        }
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

    private void attachCompanies(UserAccount user, List<Company> companies) {
        companies.forEach(user::addCompany);
    }

    private List<Company> resolveTargetCompaniesForCreate(Company activeCompany, List<Long> companyIds) {
        if (!hasSuperAdminAuthority()) {
            validateCompanyScope(activeCompany, companyIds);
            return List.of(activeCompany);
        }
        if (companyIds == null || companyIds.isEmpty()) {
            throw new IllegalArgumentException("User must belong to an active company");
        }
        Set<Long> requestedCompanyIds = new LinkedHashSet<>(companyIds);
        Map<Long, Company> companiesById = companyRepository.findAllById(requestedCompanyIds).stream()
                .collect(Collectors.toMap(Company::getId, company -> company));
        if (companiesById.size() != requestedCompanyIds.size()) {
            Long missingCompanyId = requestedCompanyIds.stream()
                    .filter(id -> !companiesById.containsKey(id))
                    .findFirst()
                    .orElse(null);
            throw new IllegalArgumentException("Company not found: " + missingCompanyId);
        }
        return companyIds.stream()
                .distinct()
                .map(companiesById::get)
                .toList();
    }

    private boolean hasSuperAdminAuthority() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getAuthorities() == null) {
            return false;
        }
        return authentication.getAuthorities().stream()
                .anyMatch(authority -> SUPER_ADMIN_ROLE.equalsIgnoreCase(authority.getAuthority()));
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
            if (SUPER_ADMIN_ROLE.equalsIgnoreCase(normalized) && !hasSuperAdminAuthority()) {
                throw new org.springframework.security.access.AccessDeniedException(
                        "SUPER_ADMIN authority required to assign role: " + normalized);
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
