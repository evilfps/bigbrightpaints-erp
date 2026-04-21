package com.bigbrightpaints.erp.modules.admin.service;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.bigbrightpaints.erp.core.audit.AuditLog;
import com.bigbrightpaints.erp.core.audit.AuditLogRepository;
import com.bigbrightpaints.erp.modules.admin.domain.SupportTicketRepository;
import com.bigbrightpaints.erp.modules.admin.domain.SupportTicketStatus;
import com.bigbrightpaints.erp.modules.admin.dto.AdminDashboardDto;
import com.bigbrightpaints.erp.modules.admin.dto.TenantRuntimeMetricsDto;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.rbac.domain.Role;
import com.bigbrightpaints.erp.modules.rbac.domain.SystemRole;

@Service
public class AdminDashboardService {

  private static final Set<String> TENANT_ADMIN_HIDDEN_ROLES =
      Set.of(SystemRole.ADMIN.getRoleName(), SystemRole.SUPER_ADMIN.getRoleName());
  private static final int RECENT_ACTIVITY_LIMIT = 12;
  private static final int RECENT_ACTIVITY_PAGE_SIZE = 50;
  private static final int MAX_RECENT_ACTIVITY_SCAN_PAGES = 8;

  private final CompanyContextService companyContextService;
  private final AdminApprovalService adminApprovalService;
  private final TenantRuntimePolicyService tenantRuntimePolicyService;
  private final UserAccountRepository userAccountRepository;
  private final SupportTicketRepository supportTicketRepository;
  private final AuditLogRepository auditLogRepository;

  public AdminDashboardService(
      CompanyContextService companyContextService,
      AdminApprovalService adminApprovalService,
      TenantRuntimePolicyService tenantRuntimePolicyService,
      UserAccountRepository userAccountRepository,
      SupportTicketRepository supportTicketRepository,
      AuditLogRepository auditLogRepository) {
    this.companyContextService = companyContextService;
    this.adminApprovalService = adminApprovalService;
    this.tenantRuntimePolicyService = tenantRuntimePolicyService;
    this.userAccountRepository = userAccountRepository;
    this.supportTicketRepository = supportTicketRepository;
    this.auditLogRepository = auditLogRepository;
  }

  @Transactional(readOnly = true)
  public AdminDashboardDto dashboard() {
    Company company = companyContextService.requireCurrentCompany();
    Long companyId = company.getId();

    AdminApprovalService.PendingCounts pendingCounts = adminApprovalService.getPendingCounts();

    AdminDashboardDto.ApprovalSummary approvalSummary =
        new AdminDashboardDto.ApprovalSummary(
            pendingCounts.totalPending(),
            pendingCounts.creditPending(),
            pendingCounts.creditOverridePending(),
            pendingCounts.payrollPending(),
            pendingCounts.periodClosePending(),
            pendingCounts.exportPending());

    List<UserAccount> companyUsers = userAccountRepository.findByCompany_Id(companyId);
    List<UserAccount> visibleUsers =
        companyUsers.stream().filter(this::isTenantAdminVisibleUser).toList();
    Map<String, Boolean> tenantActorProtection = buildTenantActorProtectionByEmail(companyUsers);

    long totalUsers = visibleUsers.size();
    long enabledUsers = visibleUsers.stream().filter(UserAccount::isEnabled).count();
    long mfaEnabledUsers = visibleUsers.stream().filter(UserAccount::isMfaEnabled).count();

    AdminDashboardDto.UserSummary userSummary =
        new AdminDashboardDto.UserSummary(
            totalUsers, enabledUsers, Math.max(totalUsers - enabledUsers, 0), mfaEnabledUsers);

    AdminDashboardDto.SupportSummary supportSummary =
        new AdminDashboardDto.SupportSummary(
            supportTicketRepository.countByCompanyAndStatus(company, SupportTicketStatus.OPEN),
            supportTicketRepository.countByCompanyAndStatus(
                company, SupportTicketStatus.IN_PROGRESS),
            supportTicketRepository.countByCompanyAndStatus(company, SupportTicketStatus.RESOLVED),
            supportTicketRepository.countByCompanyAndStatus(company, SupportTicketStatus.CLOSED));

    long distinctSessions = auditLogRepository.countDistinctSessionActivityByCompanyId(companyId);
    long apiActivity = auditLogRepository.countApiActivityByCompanyId(companyId);
    long apiFailures = auditLogRepository.countApiFailureActivityByCompanyId(companyId);

    AdminDashboardDto.SecuritySummary securitySummary =
        new AdminDashboardDto.SecuritySummary(distinctSessions, apiActivity, apiFailures);

    List<AdminDashboardDto.ActivityItem> recentActivity =
        loadRecentActivity(companyId, tenantActorProtection);

    TenantRuntimeMetricsDto runtime = tenantRuntimePolicyService.metrics();

    return new AdminDashboardDto(
        recentActivity, approvalSummary, userSummary, supportSummary, runtime, securitySummary);
  }

  private Map<String, Boolean> buildTenantActorProtectionByEmail(List<UserAccount> companyUsers) {
    if (companyUsers == null || companyUsers.isEmpty()) {
      return Map.of();
    }
    Map<String, Boolean> protectionByActor = new HashMap<>();
    for (UserAccount user : companyUsers) {
      String actorKey = normalizeActorKey(user != null ? user.getEmail() : null);
      if (!StringUtils.hasText(actorKey)) {
        continue;
      }
      protectionByActor.put(actorKey, isTenantAdminProtectedUser(user));
    }
    return protectionByActor;
  }

  private List<AdminDashboardDto.ActivityItem> loadRecentActivity(
      Long companyId, Map<String, Boolean> tenantActorProtectionByEmail) {
    List<AdminDashboardDto.ActivityItem> recentActivity = new ArrayList<>(RECENT_ACTIVITY_LIMIT);
    Map<UUID, ActorProtectionState> actorProtectionByPublicIdCache = new HashMap<>();

    for (int pageIndex = 0;
        pageIndex < MAX_RECENT_ACTIVITY_SCAN_PAGES && recentActivity.size() < RECENT_ACTIVITY_LIMIT;
        pageIndex++) {
      Page<AuditLog> page =
          auditLogRepository.findByCompanyIdOrderByTimestampDesc(
              companyId, PageRequest.of(pageIndex, RECENT_ACTIVITY_PAGE_SIZE));
      if (page.isEmpty()) {
        break;
      }

      primeProtectedActorCache(page.getContent(), actorProtectionByPublicIdCache);

      for (AuditLog auditLog : page.getContent()) {
        if (!isProtectedActorActivity(
            auditLog, tenantActorProtectionByEmail, actorProtectionByPublicIdCache)) {
          recentActivity.add(toActivityItem(auditLog));
          if (recentActivity.size() >= RECENT_ACTIVITY_LIMIT) {
            break;
          }
        }
      }

      if (!page.hasNext()) {
        break;
      }
    }

    return recentActivity;
  }

  private void primeProtectedActorCache(
      List<AuditLog> auditLogs, Map<UUID, ActorProtectionState> actorProtectionByPublicIdCache) {
    if (auditLogs == null || auditLogs.isEmpty()) {
      return;
    }
    Set<UUID> missingPublicIds =
        auditLogs.stream()
            .map(AuditLog::getUserId)
            .map(this::parsePublicId)
            .filter(Objects::nonNull)
            .filter(publicId -> !actorProtectionByPublicIdCache.containsKey(publicId))
            .collect(Collectors.toCollection(HashSet::new));
    if (missingPublicIds.isEmpty()) {
      return;
    }

    List<UserAccount> actors = userAccountRepository.findByPublicIdIn(missingPublicIds);
    Set<UUID> resolvedIds = new HashSet<>();
    for (UserAccount actor : actors) {
      if (actor == null || actor.getPublicId() == null) {
        continue;
      }
      UUID publicId = actor.getPublicId();
      actorProtectionByPublicIdCache.put(
          publicId,
          isTenantAdminProtectedUser(actor)
              ? ActorProtectionState.PROTECTED
              : ActorProtectionState.NOT_PROTECTED);
      resolvedIds.add(publicId);
    }
    for (UUID unresolvedPublicId : missingPublicIds) {
      if (!resolvedIds.contains(unresolvedPublicId)) {
        actorProtectionByPublicIdCache.put(unresolvedPublicId, ActorProtectionState.UNKNOWN);
      }
    }
  }

  private AdminDashboardDto.ActivityItem toActivityItem(AuditLog auditLog) {
    String details =
        StringUtils.hasText(auditLog.getRequestPath())
            ? auditLog.getRequestMethod() + " " + auditLog.getRequestPath()
            : StringUtils.hasText(auditLog.getDetails()) ? auditLog.getDetails() : null;
    return new AdminDashboardDto.ActivityItem(
        auditLog.getTimestamp() != null
            ? auditLog.getTimestamp().atZone(ZoneOffset.UTC).toInstant()
            : null,
        auditLog.getEventType() != null ? auditLog.getEventType().name() : "UNKNOWN",
        auditLog.getUsername(),
        details);
  }

  private boolean isTenantAdminVisibleUser(UserAccount user) {
    return !isTenantAdminProtectedUser(user);
  }

  private boolean isTenantAdminProtectedUser(UserAccount user) {
    if (user == null || user.getRoles() == null || user.getRoles().isEmpty()) {
      return false;
    }
    return user.getRoles().stream()
        .filter(Objects::nonNull)
        .map(Role::getName)
        .map(this::normalizeRoleNameForComparison)
        .anyMatch(TENANT_ADMIN_HIDDEN_ROLES::contains);
  }

  private boolean isProtectedActorActivity(
      AuditLog auditLog,
      Map<String, Boolean> tenantActorProtectionByEmail,
      Map<UUID, ActorProtectionState> actorProtectionByPublicIdCache) {
    if (auditLog == null) {
      return false;
    }
    ActorProtectionResolution actorProtection =
        resolveActorProtectionState(
            auditLog, tenantActorProtectionByEmail, actorProtectionByPublicIdCache);
    if (!isSuperAdminControlPlanePath(auditLog.getRequestPath())) {
      // Fail closed unless immutable actor identity proves this row is non-protected.
      return !(actorProtection.state() == ActorProtectionState.NOT_PROTECTED
          && actorProtection.evidence() == ActorProtectionEvidence.PUBLIC_ID);
    }

    if (actorProtection.state() == ActorProtectionState.PROTECTED) {
      return true;
    }
    if (actorProtection.state() == ActorProtectionState.NOT_PROTECTED
        && actorProtection.evidence() == ActorProtectionEvidence.PUBLIC_ID) {
      return false;
    }
    return true;
  }

  private ActorProtectionResolution resolveActorProtectionState(
      AuditLog auditLog,
      Map<String, Boolean> tenantActorProtectionByEmail,
      Map<UUID, ActorProtectionState> actorProtectionByPublicIdCache) {
    UUID actorPublicId = parsePublicId(auditLog.getUserId());
    if (actorPublicId != null && actorProtectionByPublicIdCache.containsKey(actorPublicId)) {
      return new ActorProtectionResolution(
          actorProtectionByPublicIdCache.get(actorPublicId), ActorProtectionEvidence.PUBLIC_ID);
    }
    String actorKey = normalizeActorKey(auditLog.getUsername());
    if (StringUtils.hasText(actorKey)
        && tenantActorProtectionByEmail != null
        && tenantActorProtectionByEmail.containsKey(actorKey)) {
      return new ActorProtectionResolution(
          Boolean.TRUE.equals(tenantActorProtectionByEmail.get(actorKey))
              ? ActorProtectionState.PROTECTED
              : ActorProtectionState.NOT_PROTECTED,
          ActorProtectionEvidence.EMAIL);
    }
    return new ActorProtectionResolution(
        ActorProtectionState.UNKNOWN, ActorProtectionEvidence.NONE);
  }

  private boolean isSuperAdminControlPlanePath(String requestPath) {
    if (!StringUtils.hasText(requestPath)) {
      return false;
    }
    String normalizedPath = requestPath.trim().toLowerCase(Locale.ROOT);
    return normalizedPath.contains("/api/v1/superadmin");
  }

  private UUID parsePublicId(String actorUserId) {
    if (!StringUtils.hasText(actorUserId)) {
      return null;
    }
    try {
      return UUID.fromString(actorUserId.trim());
    } catch (IllegalArgumentException ex) {
      return null;
    }
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

  private String normalizeActorKey(String actor) {
    if (actor == null) {
      return null;
    }
    String trimmedActor = actor.trim();
    if (trimmedActor.isEmpty()) {
      return null;
    }
    return trimmedActor.toLowerCase(Locale.ROOT);
  }

  private enum ActorProtectionState {
    PROTECTED,
    NOT_PROTECTED,
    UNKNOWN
  }

  private enum ActorProtectionEvidence {
    PUBLIC_ID,
    EMAIL,
    NONE
  }

  private record ActorProtectionResolution(
      ActorProtectionState state, ActorProtectionEvidence evidence) {}
}
