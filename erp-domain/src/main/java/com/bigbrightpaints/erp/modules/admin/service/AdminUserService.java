package com.bigbrightpaints.erp.modules.admin.service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditLogRepository;
import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.notification.EmailService;
import com.bigbrightpaints.erp.core.security.SecurityActorResolver;
import com.bigbrightpaints.erp.core.security.TokenBlacklistService;
import com.bigbrightpaints.erp.core.util.PasswordUtils;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.admin.dto.CreateUserRequest;
import com.bigbrightpaints.erp.modules.admin.dto.UpdateUserRequest;
import com.bigbrightpaints.erp.modules.admin.dto.UserDto;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import com.bigbrightpaints.erp.modules.auth.service.PasswordResetService;
import com.bigbrightpaints.erp.modules.auth.service.RefreshTokenService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.rbac.domain.Role;
import com.bigbrightpaints.erp.modules.rbac.service.RoleService;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import com.bigbrightpaints.erp.modules.sales.util.DealerProvisioningSupport;

@Service
public class AdminUserService {
  private static final String SUPER_ADMIN_ROLE = "ROLE_SUPER_ADMIN";
  private static final String OUT_OF_SCOPE_MESSAGE =
      "Target user is out of scope for this operation";
  private static final String USER_NOT_FOUND_MESSAGE = "User not found";

  private final UserAccountRepository userRepository;
  private final CompanyContextService companyContextService;
  private final CompanyRepository companyRepository;
  private final RoleService roleService;
  private final PasswordEncoder passwordEncoder;
  private final EmailService emailService;
  private final TokenBlacklistService tokenBlacklistService;
  private final RefreshTokenService refreshTokenService;
  private final PasswordResetService passwordResetService;
  private final AuditService auditService;
  private final AuditLogRepository auditLogRepository;
  private final DealerRepository dealerRepository;
  private final AccountRepository accountRepository;
  private final TenantRuntimePolicyService tenantRuntimePolicyService;

  public AdminUserService(
      UserAccountRepository userRepository,
      CompanyContextService companyContextService,
      CompanyRepository companyRepository,
      RoleService roleService,
      PasswordEncoder passwordEncoder,
      EmailService emailService,
      TokenBlacklistService tokenBlacklistService,
      RefreshTokenService refreshTokenService,
      PasswordResetService passwordResetService,
      AuditService auditService,
      AuditLogRepository auditLogRepository,
      DealerRepository dealerRepository,
      AccountRepository accountRepository,
      TenantRuntimePolicyService tenantRuntimePolicyService) {
    this.userRepository = userRepository;
    this.companyContextService = companyContextService;
    this.companyRepository = companyRepository;
    this.roleService = roleService;
    this.passwordEncoder = passwordEncoder;
    this.emailService = emailService;
    this.tokenBlacklistService = tokenBlacklistService;
    this.refreshTokenService = refreshTokenService;
    this.passwordResetService = passwordResetService;
    this.auditService = auditService;
    this.auditLogRepository = auditLogRepository;
    this.dealerRepository = dealerRepository;
    this.accountRepository = accountRepository;
    this.tenantRuntimePolicyService = tenantRuntimePolicyService;
  }

  public List<UserDto> listUsers() {
    Company company = companyContextService.requireCurrentCompany();
    List<UserAccount> users = userRepository.findDistinctByCompanies_Id(company.getId());
    Map<String, Instant> lastLoginByEmail = resolveLastLoginByEmail(users);
    return users.stream()
        .map(user -> toDto(user, lastLoginByEmail.get(normalizeEmailKey(user.getEmail()))))
        .toList();
  }

  @Transactional
  public UserDto createUser(CreateUserRequest request) {
    Company company = companyContextService.requireCurrentCompany();
    List<Company> targetCompanies = resolveTargetCompaniesForCreate(company, request.companyIds());
    assertActorCanAssignRoles(request.roles(), company);
    targetCompanies.forEach(
        targetCompany ->
            tenantRuntimePolicyService.assertCanAddEnabledUser(targetCompany, "ADMIN_USER_CREATE"));
    boolean isTemporaryPassword = !StringUtils.hasText(request.password());
    String tempPassword =
        isTemporaryPassword ? PasswordUtils.generateTemporaryPassword(12) : request.password();
    UserAccount user =
        new UserAccount(
            request.email(), passwordEncoder.encode(tempPassword), request.displayName());

    // Force password change if using temporary password
    if (isTemporaryPassword) {
      user.setMustChangePassword(true);
    }

    attachCompanies(user, targetCompanies);
    attachRoles(user, request.roles());
    UserAccount saved = userRepository.save(user);
    saved.getRoles().removeIf(java.util.Objects::isNull);
    if (saved.getRoles().isEmpty() && request.roles() != null && !request.roles().isEmpty()) {
      attachRoles(saved, request.roles());
      saved = userRepository.save(saved);
    }

    // Auto-create Dealer entity if user has ROLE_DEALER
    boolean isDealerUser =
        request.roles().stream()
            .anyMatch(r -> r.equalsIgnoreCase("ROLE_DEALER") || r.equalsIgnoreCase("DEALER"));
    if (isDealerUser && !targetCompanies.isEmpty()) {
      createDealerForUser(saved, targetCompanies.getFirst());
    }

    emailService.sendUserCredentialsEmail(saved.getEmail(), saved.getDisplayName(), tempPassword);
    auditUserAccountAction(
        AuditEvent.USER_CREATED,
        saved,
        company,
        "admin_user_create",
        Map.of("temporaryPasswordIssued", Boolean.toString(isTemporaryPassword)));
    Instant lastLoginAt = resolveLastLoginAt(saved.getEmail());
    UserDto dto = toDto(saved, lastLoginAt);
    if (!dto.roles().isEmpty()) {
      return dto;
    }
    List<String> fallbackRoles =
        request.roles().stream()
            .map(this::normalizeRequestedRoleName)
            .filter(StringUtils::hasText)
            .distinct()
            .toList();
    if (fallbackRoles.isEmpty()) {
      return dto;
    }
    List<String> fallbackCompanies =
        dto.companies().isEmpty()
            ? targetCompanies.stream().map(Company::getCode).filter(StringUtils::hasText).toList()
            : dto.companies();
    return new UserDto(
        dto.id(),
        dto.publicId(),
        dto.email(),
        dto.displayName(),
        dto.enabled(),
        dto.mfaEnabled(),
        fallbackRoles,
        fallbackCompanies,
        dto.lastLoginAt());
  }

  private void createDealerForUser(UserAccount user, Company company) {
    Dealer dealer =
        dealerRepository
            .findByCompanyAndPortalUserEmail(company, user.getEmail())
            .or(() -> dealerRepository.findByCompanyAndEmailIgnoreCase(company, user.getEmail()))
            .orElseGet(
                () -> {
                  Dealer fresh = new Dealer();
                  fresh.setCompany(company);
                  fresh.setName(user.getDisplayName());
                  fresh.setCode(
                      DealerProvisioningSupport.generateDealerCode(
                          user.getDisplayName(), company, dealerRepository));
                  return fresh;
                });

    if (!StringUtils.hasText(dealer.getCode())) {
      dealer.setCode(
          DealerProvisioningSupport.generateDealerCode(
              user.getDisplayName(), company, dealerRepository));
    }
    if (!StringUtils.hasText(dealer.getName())) {
      dealer.setName(user.getDisplayName());
    }
    dealer.setEmail(user.getEmail());
    dealer.setPortalUser(user);
    dealer.setStatus(DealerProvisioningSupport.ACTIVE_STATUS);
    dealer = dealerRepository.save(dealer);

    if (dealer.getReceivableAccount() == null) {
      dealer.setReceivableAccount(
          DealerProvisioningSupport.createReceivableAccount(company, dealer, accountRepository));
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
    UserAccount user =
        resolveScopedUserForAdminAction(
            id,
            company,
            "admin-update-user-out-of-scope",
            false,
            OutOfScopeResponseMode.ACCESS_DENIED);
    user.setDisplayName(request.displayName());
    boolean requiresReauth = false;
    if (request.enabled() != null) {
      boolean enabledChanged = user.isEnabled() != request.enabled();
      updateUserStatusInternal(user, request.enabled(), company, "ADMIN_USER_UPDATE");
      requiresReauth = enabledChanged;
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
    auditUserAccountAction(
        AuditEvent.USER_UPDATED,
        user,
        company,
        "admin_user_update",
        Map.of("displayName", user.getDisplayName()));
    return toDto(user, resolveLastLoginAt(user.getEmail()));
  }

  @Transactional
  public void forceResetPassword(Long userId) {
    Company company = companyContextService.requireCurrentCompany();
    UserAccount targetUser =
        resolveScopedUserForAdminAction(
            userId,
            company,
            "admin-force-reset-password-out-of-scope",
            false,
            OutOfScopeResponseMode.MASK_AS_MISSING);
    passwordResetService.requestResetByAdmin(targetUser);
    auditUserAccountAction(
        AuditEvent.PASSWORD_RESET_REQUESTED,
        targetUser,
        company,
        "admin_force_reset_password",
        Map.of("targetUserId", String.valueOf(targetUser.getId())));
  }

  @Transactional
  public UserDto updateUserStatus(Long userId, boolean enabled) {
    Company company = companyContextService.requireCurrentCompany();
    UserAccount user =
        resolveScopedUserForAdminAction(
            userId,
            company,
            "admin-status-update-out-of-scope",
            false,
            OutOfScopeResponseMode.MASK_AS_MISSING);
    return updateUserStatusInternal(user, enabled, company, "ADMIN_USER_STATUS");
  }

  @Transactional
  public void suspend(Long id) {
    Company company = companyContextService.requireCurrentCompany();
    UserAccount user =
        resolveScopedUserForAdminAction(
            id,
            company,
            "admin-suspend-user-out-of-scope",
            true,
            OutOfScopeResponseMode.MASK_AS_MISSING);
    updateUserStatusInternal(user, false, company, "ADMIN_USER_SUSPEND");
  }

  @Transactional
  public void unsuspend(Long id) {
    Company company = companyContextService.requireCurrentCompany();
    UserAccount user =
        resolveScopedUserForAdminAction(
            id,
            company,
            "admin-unsuspend-user-out-of-scope",
            true,
            OutOfScopeResponseMode.MASK_AS_MISSING);
    updateUserStatusInternal(user, true, company, "ADMIN_USER_UNSUSPEND");
  }

  @Transactional
  public void deleteUser(Long id) {
    Company company = companyContextService.requireCurrentCompany();
    UserAccount user =
        resolveScopedUserForAdminAction(
            id,
            company,
            "admin-delete-user-out-of-scope",
            true,
            OutOfScopeResponseMode.MASK_AS_MISSING);
    assertNotProtectedMainAdmin(user, company, "delete");
    tokenBlacklistService.revokeAllUserTokens(user.getEmail());
    refreshTokenService.revokeAllForUser(user.getEmail());
    userRepository.delete(user);
    emailService.sendUserDeletedEmail(user.getEmail(), user.getDisplayName());
    auditUserAccountAction(
        AuditEvent.USER_DELETED,
        user,
        company,
        "admin_user_delete",
        Map.of("targetUserId", String.valueOf(user.getId())));
  }

  @Transactional
  public void disableMfa(Long id) {
    Company company = companyContextService.requireCurrentCompany();
    UserAccount user =
        resolveScopedUserForAdminAction(
            id,
            company,
            "admin-disable-mfa-out-of-scope",
            true,
            OutOfScopeResponseMode.MASK_AS_MISSING);
    user.setMfaEnabled(false);
    user.setMfaSecret(null);
    user.setMfaRecoveryCodeHashes(List.of());
    userRepository.save(user);
    tokenBlacklistService.revokeAllUserTokens(user.getEmail());
    refreshTokenService.revokeAllForUser(user.getEmail());
    auditUserAccountAction(
        AuditEvent.MFA_DISABLED,
        user,
        company,
        "admin_disable_mfa",
        Map.of("targetUserId", String.valueOf(user.getId())));
  }

  private UserAccount resolveScopedUserForAdminAction(
      Long userId,
      Company activeCompany,
      String denialReason,
      boolean lockTarget,
      OutOfScopeResponseMode outOfScopeResponseMode) {
    boolean superAdmin = hasSuperAdminAuthority();
    java.util.Optional<UserAccount> candidate =
        lockTarget
            ? resolveLockedAdminActionTarget(userId, activeCompany, superAdmin)
            : userRepository.findById(userId);
    UserAccount user = candidate.orElse(null);
    if (user == null) {
      if (!superAdmin && lockTarget) {
        return userRepository
            .findById(userId)
            .map(
                outOfScopeUser ->
                    handleOutOfScopeAdminAction(
                        outOfScopeUser, activeCompany, denialReason, outOfScopeResponseMode))
            .orElseThrow(
                () ->
                    com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
                        USER_NOT_FOUND_MESSAGE));
      }
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          USER_NOT_FOUND_MESSAGE);
    }
    if (superAdmin) {
      return user;
    }
    if (isUserWithinCompanyScope(user, activeCompany)) {
      return user;
    }
    return handleOutOfScopeAdminAction(user, activeCompany, denialReason, outOfScopeResponseMode);
  }

  private java.util.Optional<UserAccount> resolveLockedAdminActionTarget(
      Long userId, Company activeCompany, boolean superAdmin) {
    if (superAdmin || activeCompany == null || activeCompany.getId() == null) {
      return userRepository.lockById(userId);
    }
    return userRepository.lockByIdAndCompanyId(userId, activeCompany.getId());
  }

  private UserAccount handleOutOfScopeAdminAction(
      UserAccount user,
      Company activeCompany,
      String denialReason,
      OutOfScopeResponseMode outOfScopeResponseMode) {
    auditPrivilegedUserActionDenied(user, activeCompany, denialReason);
    if (outOfScopeResponseMode == OutOfScopeResponseMode.MASK_AS_MISSING) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          USER_NOT_FOUND_MESSAGE);
    }
    throw new AccessDeniedException(OUT_OF_SCOPE_MESSAGE);
  }

  private enum OutOfScopeResponseMode {
    ACCESS_DENIED,
    MASK_AS_MISSING
  }

  private UserDto updateUserStatusInternal(
      UserAccount user, boolean enabled, Company actorCompany, String operation) {
    boolean previousEnabled = user.isEnabled();
    if (!enabled) {
      assertNotProtectedMainAdmin(user, actorCompany, "disable");
    }
    if (enabled && !previousEnabled) {
      resolveTargetCompanies(user, actorCompany)
          .forEach(
              company -> tenantRuntimePolicyService.assertCanAddEnabledUser(company, operation));
    }
    user.setEnabled(enabled);
    userRepository.save(user);

    if (!enabled) {
      tokenBlacklistService.revokeAllUserTokens(user.getEmail());
      refreshTokenService.revokeAllForUser(user.getEmail());
      emailService.sendUserSuspendedEmail(user.getEmail(), user.getDisplayName());
    }

    auditUserAccountAction(
        enabled ? AuditEvent.USER_ACTIVATED : AuditEvent.USER_DEACTIVATED,
        user,
        actorCompany,
        enabled ? "admin_enable_user" : "admin_disable_user",
        Map.of(
            "targetUserId", String.valueOf(user.getId()),
            "previousEnabled", Boolean.toString(previousEnabled),
            "enabled", Boolean.toString(enabled)));
    return toDto(user, resolveLastLoginAt(user.getEmail()));
  }

  private void validateCompanyScope(Company company, List<Long> companyIds) {
    if (companyIds == null || companyIds.isEmpty()) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          "User must belong to an active company");
    }
    boolean allMatch = companyIds.stream().allMatch(id -> id.equals(company.getId()));
    if (!allMatch) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          "User must be assigned to the active company");
    }
  }

  private void attachCompanies(UserAccount user, Company company, List<Long> companyIds) {
    validateCompanyScope(company, companyIds);
    user.addCompany(company);
  }

  private void attachCompanies(UserAccount user, List<Company> companies) {
    companies.forEach(user::addCompany);
  }

  private List<Company> resolveTargetCompaniesForCreate(
      Company activeCompany, List<Long> companyIds) {
    if (!hasSuperAdminAuthority()) {
      validateCompanyScope(activeCompany, companyIds);
      return List.of(activeCompany);
    }
    if (companyIds == null || companyIds.isEmpty()) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          "User must belong to an active company");
    }
    Set<Long> requestedCompanyIds = new LinkedHashSet<>(companyIds);
    Map<Long, Company> companiesById =
        companyRepository.findAllById(requestedCompanyIds).stream()
            .collect(Collectors.toMap(Company::getId, company -> company));
    if (companiesById.size() != requestedCompanyIds.size()) {
      Long missingCompanyId =
          requestedCompanyIds.stream()
              .filter(id -> !companiesById.containsKey(id))
              .findFirst()
              .orElse(null);
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          "Company not found: " + missingCompanyId);
    }
    return companyIds.stream().distinct().map(companiesById::get).toList();
  }

  private boolean hasSuperAdminAuthority() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null || authentication.getAuthorities() == null) {
      return false;
    }
    return authentication.getAuthorities().stream()
        .anyMatch(authority -> SUPER_ADMIN_ROLE.equalsIgnoreCase(authority.getAuthority()));
  }

  private void assertActorCanAssignRoles(List<String> roles, Company actorCompany) {
    if (roles == null || roles.isEmpty() || hasSuperAdminAuthority()) {
      return;
    }
    String actor = resolveAuditActor();
    String tenantScope =
        actorCompany != null && StringUtils.hasText(actorCompany.getCode())
            ? actorCompany.getCode().trim()
            : null;
    for (String roleName : roles) {
      String normalizedRoleName = normalizeRequestedRoleName(roleName);
      if (!requiresSuperAdminRoleAssignment(normalizedRoleName)) {
        continue;
      }
      Map<String, String> metadata = new LinkedHashMap<>();
      metadata.put("actor", actor);
      metadata.put("reason", "tenant-admin-role-management-requires-super-admin");
      metadata.put("tenantScope", StringUtils.hasText(tenantScope) ? tenantScope : "none");
      metadata.put("targetRole", normalizedRoleName);
      auditService.logAuthFailure(AuditEvent.ACCESS_DENIED, actor, tenantScope, metadata);
      throw new AccessDeniedException(
          "SUPER_ADMIN authority required for role: " + normalizedRoleName);
    }
  }

  private String normalizeRequestedRoleName(String roleName) {
    if (!StringUtils.hasText(roleName)) {
      return null;
    }
    String normalized = roleName.trim().toUpperCase(Locale.ROOT);
    if (!normalized.startsWith("ROLE_")) {
      String withPrefix = "ROLE_" + normalized;
      if (roleService.isSystemRole(withPrefix)) {
        normalized = withPrefix;
      }
    }
    return normalized;
  }

  private boolean requiresSuperAdminRoleAssignment(String normalizedRoleName) {
    return "ROLE_ADMIN".equalsIgnoreCase(normalizedRoleName)
        || SUPER_ADMIN_ROLE.equalsIgnoreCase(normalizedRoleName);
  }

  private void attachRoles(UserAccount user, List<String> roles) {
    roles.forEach(
        roleName -> {
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

  private Map<String, Instant> resolveLastLoginByEmail(List<UserAccount> users) {
    Set<String> normalizedEmails =
        users.stream()
            .map(UserAccount::getEmail)
            .map(this::normalizeEmailKey)
            .filter(StringUtils::hasText)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    if (normalizedEmails.isEmpty()) {
      return Map.of();
    }

    return auditLogRepository
        .findLatestTimestampByEventTypeAndUsernameIn(AuditEvent.LOGIN_SUCCESS, normalizedEmails)
        .stream()
        .filter(row -> StringUtils.hasText(row.getUsernameKey()) && row.getLastLoginAt() != null)
        .collect(
            Collectors.toMap(
                row -> row.getUsernameKey().trim().toLowerCase(Locale.ROOT),
                row -> row.getLastLoginAt().atZone(ZoneOffset.UTC).toInstant(),
                (existing, replacement) -> existing,
                LinkedHashMap::new));
  }

  private Instant resolveLastLoginAt(String userEmail) {
    String normalizedEmail = normalizeEmailKey(userEmail);
    if (!StringUtils.hasText(normalizedEmail)) {
      return null;
    }
    return auditLogRepository
        .findFirstByEventTypeAndUsernameIgnoreCaseOrderByTimestampDesc(
            AuditEvent.LOGIN_SUCCESS, normalizedEmail)
        .map(auditLog -> auditLog.getTimestamp().atZone(ZoneOffset.UTC).toInstant())
        .orElse(null);
  }

  private String normalizeEmailKey(String email) {
    if (!StringUtils.hasText(email)) {
      return null;
    }
    return email.trim().toLowerCase(Locale.ROOT);
  }

  private void auditUserAccountAction(
      AuditEvent event,
      UserAccount targetUser,
      Company actorCompany,
      String action,
      Map<String, String> metadata) {
    Map<String, String> auditMetadata = new LinkedHashMap<>();
    if (metadata != null && !metadata.isEmpty()) {
      auditMetadata.putAll(metadata);
    }
    String actor = resolveAuditActor();
    String targetCompanyCodes = resolveTargetCompanyCodes(targetUser);
    auditMetadata.put("actor", actor);
    auditMetadata.put("targetUserEmail", targetUser != null ? targetUser.getEmail() : "UNKNOWN");
    auditMetadata.put("action", action);
    auditMetadata.put("tenantScope", actorCompany != null ? actorCompany.getCode() : "GLOBAL");
    if (StringUtils.hasText(targetCompanyCodes)) {
      auditMetadata.put("targetCompanyCode", targetCompanyCodes);
    }
    auditService.logAuthSuccess(
        event, actor, actorCompany != null ? actorCompany.getCode() : null, auditMetadata);
  }

  private boolean isUserWithinCompanyScope(UserAccount user, Company activeCompany) {
    if (user == null
        || activeCompany == null
        || activeCompany.getId() == null
        || user.getCompanies() == null) {
      return false;
    }
    return user.getCompanies().stream().map(Company::getId).anyMatch(activeCompany.getId()::equals);
  }

  private void assertNotProtectedMainAdmin(UserAccount user, Company actorCompany, String action) {
    if (user == null || user.getId() == null) {
      return;
    }
    boolean protectedMainAdmin =
        resolveActorScopedTargetCompanies(user, actorCompany).stream()
            .filter(company -> company != null && company.getMainAdminUserId() != null)
            .anyMatch(company -> user.getId().equals(company.getMainAdminUserId()));
    if (protectedMainAdmin) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidState(
          "Replace the tenant main admin before attempting to " + action + " this user");
    }
  }

  private void auditPrivilegedUserActionDenied(
      UserAccount targetUser, Company actorCompany, String denialReason) {
    Map<String, String> metadata = new LinkedHashMap<>();
    String actor = resolveAuditActor();
    metadata.put("actor", actor);
    metadata.put("reason", denialReason);
    metadata.put("tenantScope", actorCompany != null ? actorCompany.getCode() : "GLOBAL");
    if (targetUser != null) {
      if (targetUser.getId() != null) {
        metadata.put("targetUserId", String.valueOf(targetUser.getId()));
      }
      if (StringUtils.hasText(targetUser.getEmail())) {
        metadata.put("targetUserEmail", targetUser.getEmail());
      }
      String targetCompanyCodes = resolveTargetCompanyCodes(targetUser);
      if (StringUtils.hasText(targetCompanyCodes)) {
        metadata.put("targetCompanyCode", targetCompanyCodes);
      }
    }
    auditService.logAuthFailure(
        AuditEvent.ACCESS_DENIED,
        actor,
        actorCompany != null ? actorCompany.getCode() : null,
        metadata);
  }

  private String resolveAuditActor() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication != null && StringUtils.hasText(authentication.getName())) {
      return authentication.getName().trim();
    }
    return SecurityActorResolver.resolveActorWithSystemProcessFallback();
  }

  private List<Company> resolveTargetCompanies(UserAccount user, Company actorCompany) {
    if (user != null && user.getCompanies() != null && !user.getCompanies().isEmpty()) {
      return user.getCompanies().stream().distinct().toList();
    }
    return actorCompany == null ? List.of() : List.of(actorCompany);
  }

  private List<Company> resolveActorScopedTargetCompanies(UserAccount user, Company actorCompany) {
    if (hasSuperAdminAuthority()) {
      return resolveTargetCompanies(user, actorCompany);
    }
    if (actorCompany == null || actorCompany.getId() == null) {
      return List.of();
    }
    if (user == null || user.getCompanies() == null || user.getCompanies().isEmpty()) {
      return List.of(actorCompany);
    }
    return user.getCompanies().stream()
        .filter(company -> company != null && actorCompany.getId().equals(company.getId()))
        .distinct()
        .toList();
  }

  private String resolveTargetCompanyCodes(UserAccount user) {
    if (user == null || user.getCompanies() == null || user.getCompanies().isEmpty()) {
      return null;
    }
    return user.getCompanies().stream()
        .map(Company::getCode)
        .filter(StringUtils::hasText)
        .map(String::trim)
        .distinct()
        .sorted(String.CASE_INSENSITIVE_ORDER)
        .collect(Collectors.joining(","));
  }

  private UserDto toDto(UserAccount user, Instant lastLoginAt) {
    List<String> companies =
        user.getCompanies().stream()
            .filter(java.util.Objects::nonNull)
            .map(Company::getCode)
            .filter(StringUtils::hasText)
            .toList();
    List<String> roles =
        user.getRoles().stream()
            .filter(java.util.Objects::nonNull)
            .map(Role::getName)
            .filter(StringUtils::hasText)
            .toList();
    return new UserDto(
        user.getId(),
        user.getPublicId(),
        user.getEmail(),
        user.getDisplayName(),
        user.isEnabled(),
        user.isMfaEnabled(),
        roles,
        companies,
        lastLoginAt);
  }
}
