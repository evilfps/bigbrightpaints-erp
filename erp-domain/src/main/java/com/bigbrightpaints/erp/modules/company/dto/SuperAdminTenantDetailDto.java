package com.bigbrightpaints.erp.modules.company.dto;

import java.time.Instant;
import java.util.List;
import java.util.Set;

public record SuperAdminTenantDetailDto(
    Long companyId,
    String companyCode,
    String companyName,
    String timezone,
    String stateCode,
    String lifecycleState,
    String lifecycleReason,
    Set<String> enabledModules,
    Onboarding onboarding,
    MainAdminSummaryDto mainAdmin,
    Limits limits,
    Usage usage,
    SupportContext supportContext,
    List<SupportTimelineEvent> supportTimeline,
    AvailableActions availableActions) {

  public record Onboarding(
      String templateCode,
      String adminEmail,
      Long adminUserId,
      boolean tenantAdminProvisioned,
      boolean credentialsEmailSent,
      Instant credentialsEmailedAt,
      Instant completedAt) {}

  public record Limits(
      long quotaMaxActiveUsers,
      long quotaMaxApiRequests,
      long quotaMaxStorageBytes,
      long quotaMaxConcurrentRequests,
      boolean quotaSoftLimitEnabled,
      boolean quotaHardLimitEnabled) {}

  public record Usage(
      long activeUserCount,
      long apiActivityCount,
      long apiErrorCount,
      long apiErrorRateInBasisPoints,
      long auditStorageBytes,
      long currentConcurrentRequests,
      Instant lastActivityAt) {}

  public record SupportContext(String supportNotes, Set<String> supportTags) {}

  public record SupportTimelineEvent(
      String category, String title, String message, String actor, Instant occurredAt) {}

  public record AvailableActions(
      boolean canUpdateLifecycle,
      boolean canUpdateLimits,
      boolean canUpdateModules,
      boolean canIssueWarnings,
      boolean canResetAdminPassword,
      boolean canForceLogout,
      boolean canReplaceMainAdmin,
      boolean canRequestAdminEmailChange) {}
}
