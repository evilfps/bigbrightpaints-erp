package com.bigbrightpaints.erp.modules.admin.dto;

import java.time.Instant;
import java.util.List;

public record AdminDashboardDto(
    List<ActivityItem> recentActivity,
    ApprovalSummary approvalSummary,
    UserSummary userSummary,
    SupportSummary supportSummary,
    TenantRuntimeMetricsDto tenantRuntime,
    SecuritySummary securitySummary) {

  public record ActivityItem(Instant occurredAt, String eventType, String actor, String details) {}

  public record ApprovalSummary(
      long totalPending,
      long creditPending,
      long creditOverridePending,
      long payrollPending,
      long periodClosePending,
      long exportPending) {}

  public record UserSummary(long totalUsers, long enabledUsers, long disabledUsers, long mfaEnabledUsers) {}

  public record SupportSummary(long open, long inProgress, long resolved, long closed) {}

  public record SecuritySummary(long distinctSessions, long apiActivityCount, long apiFailureCount) {}
}
