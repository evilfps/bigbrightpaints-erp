package com.bigbrightpaints.erp.modules.admin.service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditLogRepository;
import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.notification.EmailService;
import com.bigbrightpaints.erp.core.security.AccessDeniedAuditMarker;
import com.bigbrightpaints.erp.core.security.SecurityActorResolver;
import com.bigbrightpaints.erp.core.security.TokenBlacklistService;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.admin.dto.CreateUserRequest;
import com.bigbrightpaints.erp.modules.admin.dto.UpdateUserRequest;
import com.bigbrightpaints.erp.modules.admin.dto.UserDto;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import com.bigbrightpaints.erp.modules.auth.service.PasswordResetService;
import com.bigbrightpaints.erp.modules.auth.service.RefreshTokenService;
import com.bigbrightpaints.erp.modules.auth.service.ScopedAccountBootstrapService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.rbac.domain.Role;
import com.bigbrightpaints.erp.modules.rbac.domain.SystemRole;
import com.bigbrightpaints.erp.modules.rbac.service.RoleService;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import com.bigbrightpaints.erp.modules.sales.util.DealerProvisioningSupport;

@Service
public class AdminUserService {
  private static final String SUPER_ADMIN_ROLE = SystemRole.SUPER_ADMIN.getRoleName();
  private static final String DEALER_ROLE = SystemRole.DEALER.getRoleName();
  private static final List<String> TENANT_ASSIGNABLE_ROLE_ORDER =
      List.of(
          SystemRole.ACCOUNTING.getRoleName(),
          SystemRole.FACTORY.getRoleName(),
          SystemRole.SALES.getRoleName(),
          SystemRole.DEALER.getRoleName());
  private static final Set<String> TENANT_ASSIGNABLE_ROLES =
      Set.copyOf(TENANT_ASSIGNABLE_ROLE_ORDER);
  private static final String TENANT_ASSIGNABLE_ROLES_SUMMARY =
      String.join(", ", TENANT_ASSIGNABLE_ROLE_ORDER);
  private static final String OUT_OF_SCOPE_MESSAGE =
      "Target user is out of scope for this operation";
  private static final String USER_NOT_FOUND_MESSAGE = "User not found";

  private final UserAccountRepository userRepository;
  private final CompanyContextService companyContextService;
  private final RoleService roleService;
  private final EmailService emailService;
  private final TokenBlacklistService tokenBlacklistService;
  private final RefreshTokenService refreshTokenService;
  private final PasswordResetService passwordResetService;
  private final ScopedAccountBootstrapService scopedAccountBootstrapService;
  private final AuditService auditService;
  private final AuditLogRepository auditLogRepository;
  private final DealerRepository dealerRepository;
  private final AccountRepository accountRepository;
  private final TenantRuntimePolicyService tenantRuntimePolicyService;

  public AdminUserService(
      UserAccountRepository userRepository,
      CompanyContextService companyContextService,
      RoleService roleService,
      EmailService emailService,
      TokenBlacklistService tokenBlacklistService,
      RefreshTokenService refreshTokenService,
      PasswordResetService passwordResetService,
      ScopedAccountBootstrapService scopedAccountBootstrapService,
      AuditService auditService,
      AuditLogRepository auditLogRepository,
      DealerRepository dealerRepository,
      AccountRepository accountRepository,
      TenantRuntimePolicyService tenantRuntimePolicyService) {
    this.userRepository = userRepository;
    this.companyContextService = companyContextService;
    this.roleService = roleService;
    this.emailService = emailService;
    this.tokenBlacklistService = tokenBlacklistService;
    this.refreshTokenService = refreshTokenService;
    this.passwordResetService = passwordResetService;
    this.scopedAccountBootstrapService = scopedAccountBootstrapService;
    this.auditService = auditService;
    this.auditLogRepository = auditLogRepository;
    this.dealerRepository = dealerRepository;
    this.accountRepository = accountRepository;
    this.tenantRuntimePolicyService = tenantRuntimePolicyService;
  }

  public List<UserDto> listUsers() {
    Company company = companyContextService.requireCurrentCompany();
    List<UserAccount> users = userRepository.findByCompany_Id(company.getId());
    List<UserAccount> visibleUsers =
        hasSuperAdminAuthority()
            ? users
            : users.stream().filter(user -> !isTenantAdminProtectedTarget(user)).toList();
    Map<String, Instant> lastLoginByEmail = resolveLastLoginByEmail(company.getId(), visibleUsers);
    return visibleUsers.stream()
        .map(user -> toDto(user, lastLoginByEmail.get(normalizeEmailKey(user.getEmail()))))
        .toList();
  }

  public UserDto getUser(Long id) {
    Company company = companyContextService.requireCurrentCompany();
    UserAccount user =
        resolveScopedUserForAdminAction(
            id,
            company,
            "admin-read-user-out-of-scope",
            false,
            OutOfScopeResponseMode.ACCESS_DENIED);
    return toDto(user, resolveLastLoginAt(user));
  }

  @Transactional
  public UserDto createUser(CreateUserRequest request) {
    Company company = companyContextService.requireCurrentCompany();
    List<String> normalizedRoles = validateAndNormalizeAssignableRoles(request.roles(), company);
    boolean isDealerUser =
        normalizedRoles.stream().anyMatch(roleName -> DEALER_ROLE.equalsIgnoreCase(roleName));
    UserAccount saved =
        resolveScopedDealerConvergenceAccount(company, request, normalizedRoles, isDealerUser)
            .orElseGet(
                () -> {
                  tenantRuntimePolicyService.assertCanAddEnabledUser(company, "ADMIN_USER_CREATE");
                  UserAccount user = new UserAccount();
                  attachRoles(user, normalizedRoles);
                  return scopedAccountBootstrapService.provisionTenantAccount(
                      company, request.email(), request.displayName(), user.getRoles());
                });

    // Auto-create Dealer entity if user has ROLE_DEALER
    if (isDealerUser) {
      createDealerForUser(saved, company);
    }

    auditUserAccountAction(
        AuditEvent.USER_CREATED,
        saved,
        company,
        "admin_user_create",
        Map.of("provisioningMode", "CANONICAL_EMAIL_BOOTSTRAP"));
    Instant lastLoginAt = resolveLastLoginAt(saved);
    return toDto(saved, lastLoginAt);
  }

  private java.util.Optional<UserAccount> resolveScopedDealerConvergenceAccount(
      Company company,
      CreateUserRequest request,
      List<String> normalizedRoles,
      boolean dealerUserRequested) {
    if (!dealerUserRequested) {
      return java.util.Optional.empty();
    }
    String requestedEmail = request.email() == null ? null : request.email().trim();
    return userRepository
        .findByEmailIgnoreCaseAndAuthScopeCodeIgnoreCase(requestedEmail, company.getCode())
        .map(existingUser -> assertDealerConvergenceCandidate(existingUser, company, normalizedRoles));
  }

  private UserAccount assertDealerConvergenceCandidate(
      UserAccount existingUser, Company company, List<String> normalizedRoles) {
    if (!isUserWithinCompanyScope(existingUser, company)) {
      throw duplicateScopedUser(company);
    }
    Set<String> existingRoleSet = normalizePersistedRoleSet(existingUser);
    Set<String> requestedRoleSet = new LinkedHashSet<>(normalizedRoles);
    if (!existingRoleSet.contains(DEALER_ROLE) || !existingRoleSet.containsAll(requestedRoleSet)) {
      throw duplicateScopedUser(company);
    }
    return existingUser;
  }

  private RuntimeException duplicateScopedUser(Company company) {
    String scopeCode =
        company != null && StringUtils.hasText(company.getCode())
            ? company.getCode().trim().toUpperCase(Locale.ROOT)
            : "UNKNOWN";
    return com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
        "User already exists for scope: " + scopeCode);
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
    dealer.setStatus(DealerProvisioningSupport.resolveStatusForOnboarding(dealer.getStatus()));
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
    List<String> normalizedRoleUpdates =
        request.roles() == null || request.roles().isEmpty()
            ? List.of()
            : validateAndNormalizeAssignableRoles(request.roles(), company);
    boolean roleUpdateRequested = !normalizedRoleUpdates.isEmpty();
    Set<String> requestedRoleSet =
        roleUpdateRequested ? new LinkedHashSet<>(normalizedRoleUpdates) : Set.of();
    Set<String> existingRoleSet = roleUpdateRequested ? normalizePersistedRoleSet(user) : Set.of();
    boolean roleAssignmentChanged =
        roleUpdateRequested && !existingRoleSet.equals(requestedRoleSet);
    boolean displayNameChanged = !Objects.equals(user.getDisplayName(), request.displayName());
    user.setDisplayName(request.displayName());
    if (roleAssignmentChanged) {
      user.getRoles().clear();
      attachRoles(user, normalizedRoleUpdates);
    }
    // Revoke tokens if permissions changed to force re-authentication
    if (roleAssignmentChanged) {
      tokenBlacklistService.revokeAllUserTokens(user.getPublicId().toString());
      refreshTokenService.revokeAllForUser(user.getPublicId());
    }
    auditUserAccountAction(
        AuditEvent.USER_UPDATED,
        user,
        company,
        "admin_user_update",
        Map.of(
            "displayNameChanged", Boolean.toString(displayNameChanged),
            "rolesChanged", Boolean.toString(roleAssignmentChanged),
            "displayName", user.getDisplayName()));
    return toDto(user, resolveLastLoginAt(user));
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
    tokenBlacklistService.revokeAllUserTokens(user.getPublicId().toString());
    refreshTokenService.revokeAllForUser(user.getPublicId());
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
    tokenBlacklistService.revokeAllUserTokens(user.getPublicId().toString());
    refreshTokenService.revokeAllForUser(user.getPublicId());
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
      if (!superAdmin && outOfScopeResponseMode == OutOfScopeResponseMode.ACCESS_DENIED) {
        auditPrivilegedUserActionDenied(
            null,
            activeCompany,
            denialReason,
            Map.of(
                "targetUserId",
                String.valueOf(userId),
                "targetResolution",
                "MISSING_OR_OUT_OF_SCOPE"));
        throw new AccessDeniedException(OUT_OF_SCOPE_MESSAGE);
      }
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
      if (isTenantAdminProtectedTarget(user)) {
        return handleOutOfScopeAdminAction(
            user,
            activeCompany,
            denialReason,
            outOfScopeResponseMode,
            Map.of("targetResolution", "PROTECTED_ROLE_TARGET"));
      }
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
    return handleOutOfScopeAdminAction(
        user, activeCompany, denialReason, outOfScopeResponseMode, Map.of());
  }

  private UserAccount handleOutOfScopeAdminAction(
      UserAccount user,
      Company activeCompany,
      String denialReason,
      OutOfScopeResponseMode outOfScopeResponseMode,
      Map<String, String> extraMetadata) {
    auditPrivilegedUserActionDenied(user, activeCompany, denialReason, extraMetadata);
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
      Company targetCompany = resolveTargetCompany(user, actorCompany);
      if (targetCompany != null) {
        tenantRuntimePolicyService.assertCanAddEnabledUser(targetCompany, operation);
      }
    }
    user.setEnabled(enabled);
    userRepository.save(user);

    if (!enabled) {
      tokenBlacklistService.revokeAllUserTokens(user.getPublicId().toString());
      refreshTokenService.revokeAllForUser(user.getPublicId());
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
    return toDto(user, resolveLastLoginAt(user));
  }

  private boolean hasSuperAdminAuthority() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null || authentication.getAuthorities() == null) {
      return false;
    }
    return authentication.getAuthorities().stream()
        .anyMatch(authority -> SUPER_ADMIN_ROLE.equalsIgnoreCase(authority.getAuthority()));
  }

  private List<String> validateAndNormalizeAssignableRoles(
      List<String> roles, Company actorCompany) {
    if (roles == null || roles.isEmpty()) {
      return List.of();
    }
    boolean actorIsSuperAdmin = hasSuperAdminAuthority();
    Set<String> normalizedRoles = new LinkedHashSet<>(roles.size());
    for (String roleName : roles) {
      String normalizedRoleName = normalizeRequestedRoleName(roleName);
      if (requiresSuperAdminRoleAssignment(normalizedRoleName)) {
        if (actorIsSuperAdmin) {
          throw unsupportedRoleForTenantAdmin(normalizedRoleName);
        }
        auditPrivilegedUserActionDenied(
            null,
            actorCompany,
            "tenant-admin-role-management-requires-super-admin",
            Map.of("targetRole", normalizedRoleName));
        throw new AccessDeniedException(
            "SUPER_ADMIN authority required for role: " + normalizedRoleName);
      }
      validateTenantAssignableRole(normalizedRoleName);
      normalizedRoles.add(normalizedRoleName);
    }
    return List.copyOf(normalizedRoles);
  }

  private String normalizeRequestedRoleName(String roleName) {
    if (!StringUtils.hasText(roleName)) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          "Role entries cannot be blank");
    }
    String normalized = roleName.trim().toUpperCase(Locale.ROOT);
    if (normalized.startsWith("ROLE_")) {
      return normalized;
    }
    return "ROLE_" + normalized;
  }

  private boolean requiresSuperAdminRoleAssignment(String normalizedRoleName) {
    return isTenantAdminProtectedRole(normalizedRoleName);
  }

  private void validateTenantAssignableRole(String normalizedRoleName) {
    if (!TENANT_ASSIGNABLE_ROLES.contains(normalizedRoleName)) {
      throw unsupportedRoleForTenantAdmin(normalizedRoleName);
    }
  }

  private com.bigbrightpaints.erp.core.exception.ApplicationException unsupportedRoleForTenantAdmin(
      String normalizedRoleName) {
    return com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
        "Unsupported role for tenant-admin user management: "
            + normalizedRoleName
            + ". Allowed roles: "
            + TENANT_ASSIGNABLE_ROLES_SUMMARY);
  }

  private void attachRoles(UserAccount user, List<String> normalizedRoleNames) {
    normalizedRoleNames.forEach(
        normalizedRoleName -> {
          Role role = roleService.ensureRoleExists(normalizedRoleName);
          user.addRole(role);
        });
  }

  private Set<String> normalizePersistedRoleSet(UserAccount user) {
    if (user == null || user.getRoles() == null || user.getRoles().isEmpty()) {
      return Set.of();
    }
    return user.getRoles().stream()
        .map(Role::getName)
        .filter(StringUtils::hasText)
        .map(this::normalizeRequestedRoleName)
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  private Map<String, Instant> resolveLastLoginByEmail(Long companyId, List<UserAccount> users) {
    if (companyId == null) {
      return Map.of();
    }
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
        .findLatestTimestampByEventTypeAndCompanyIdAndUsernameIn(
            AuditEvent.LOGIN_SUCCESS, companyId, normalizedEmails)
        .stream()
        .filter(row -> StringUtils.hasText(row.getUsernameKey()) && row.getLastLoginAt() != null)
        .collect(
            Collectors.toMap(
                row -> row.getUsernameKey().trim().toLowerCase(Locale.ROOT),
                row -> row.getLastLoginAt().atZone(ZoneOffset.UTC).toInstant(),
                (existing, replacement) -> existing,
                LinkedHashMap::new));
  }

  private Instant resolveLastLoginAt(UserAccount user) {
    if (user == null || user.getCompany() == null || user.getCompany().getId() == null) {
      return null;
    }
    String userEmail = user.getEmail();
    String normalizedEmail = normalizeEmailKey(userEmail);
    if (!StringUtils.hasText(normalizedEmail)) {
      return null;
    }
    return auditLogRepository
        .findFirstByEventTypeAndCompanyIdAndUsernameIgnoreCaseOrderByTimestampDesc(
            AuditEvent.LOGIN_SUCCESS, user.getCompany().getId(), normalizedEmail)
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
        || user.getCompany() == null
        || user.getCompany().getId() == null) {
      return false;
    }
    return activeCompany.getId().equals(user.getCompany().getId());
  }

  private boolean isTenantAdminProtectedTarget(UserAccount user) {
    if (user == null || user.getRoles() == null || user.getRoles().isEmpty()) {
      return false;
    }
    return user.getRoles().stream()
        .filter(Objects::nonNull)
        .map(Role::getName)
        .anyMatch(this::isTenantAdminProtectedRole);
  }

  private boolean isTenantAdminProtectedRole(String roleName) {
    String normalized = normalizeRoleNameForComparison(roleName);
    if (!StringUtils.hasText(normalized)) {
      return false;
    }
    return SystemRole.ADMIN.getRoleName().equals(normalized) || SUPER_ADMIN_ROLE.equals(normalized);
  }

  private String normalizeRoleNameForComparison(String roleName) {
    if (!StringUtils.hasText(roleName)) {
      return null;
    }
    String normalized = roleName.trim().toUpperCase(Locale.ROOT);
    if (normalized.startsWith("ROLE_")) {
      return normalized;
    }
    return "ROLE_" + normalized;
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
    auditPrivilegedUserActionDenied(targetUser, actorCompany, denialReason, Map.of());
  }

  private void auditPrivilegedUserActionDenied(
      UserAccount targetUser,
      Company actorCompany,
      String denialReason,
      Map<String, String> extraMetadata) {
    Map<String, String> metadata = new LinkedHashMap<>();
    String actor = resolveAuditActor();
    metadata.put("actor", actor);
    metadata.put("reason", denialReason);
    metadata.put("tenantScope", actorCompany != null ? actorCompany.getCode() : "GLOBAL");
    if (extraMetadata != null && !extraMetadata.isEmpty()) {
      metadata.putAll(extraMetadata);
    }
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
    AccessDeniedAuditMarker.markCurrentRequestAudited();
  }

  private String resolveAuditActor() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication != null && StringUtils.hasText(authentication.getName())) {
      return authentication.getName().trim();
    }
    return SecurityActorResolver.resolveActorWithSystemProcessFallback();
  }

  private Company resolveTargetCompany(UserAccount user, Company actorCompany) {
    if (user != null && user.getCompany() != null) {
      return user.getCompany();
    }
    return actorCompany;
  }

  private List<Company> resolveActorScopedTargetCompanies(UserAccount user, Company actorCompany) {
    if (hasSuperAdminAuthority()) {
      Company targetCompany = resolveTargetCompany(user, actorCompany);
      return targetCompany == null ? List.of() : List.of(targetCompany);
    }
    if (actorCompany == null || actorCompany.getId() == null) {
      return List.of();
    }
    if (user == null || user.getCompany() == null || user.getCompany().getId() == null) {
      return List.of(actorCompany);
    }
    return actorCompany.getId().equals(user.getCompany().getId())
        ? List.of(user.getCompany())
        : List.of();
  }

  private String resolveTargetCompanyCodes(UserAccount user) {
    if (user == null
        || user.getCompany() == null
        || !StringUtils.hasText(user.getCompany().getCode())) {
      return null;
    }
    return user.getCompany().getCode().trim();
  }

  private UserDto toDto(UserAccount user, Instant lastLoginAt) {
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
        user.getCompany() == null || !StringUtils.hasText(user.getCompany().getCode())
            ? null
            : user.getCompany().getCode(),
        lastLoginAt);
  }
}
