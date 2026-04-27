package com.bigbrightpaints.erp.core.security;

import java.util.Locale;
import java.util.Set;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.bigbrightpaints.erp.core.config.SystemSettingsService;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.modules.admin.domain.ExportRequest;
import com.bigbrightpaints.erp.modules.admin.dto.ExportApprovalStatus;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;

/**
 * Canonical owner for sensitive disclosure policy decisions used by report exports and related
 * approval/RBAC boundaries.
 */
@Component
public class SensitiveDisclosurePolicyOwner {

  public static final String REPORT_OR_ACCOUNTING_ONLY =
      "hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')";
  public static final String TENANT_ADMIN_ONLY =
      "hasAuthority('ROLE_ADMIN') and !hasAuthority('ROLE_SUPER_ADMIN')";
  public static final String ADMIN_ONLY = "hasAuthority('ROLE_ADMIN')";

  public static final String ACCOUNTING_SUPPLIER_STATEMENT_EXPORT_TYPE =
      "ACCOUNTING_SUPPLIER_STATEMENT";
  public static final String ACCOUNTING_SUPPLIER_AGING_EXPORT_TYPE = "ACCOUNTING_SUPPLIER_AGING";

  private static final Set<String> APPROVAL_BYPASS_EXPORT_TYPES =
      Set.of(ACCOUNTING_SUPPLIER_STATEMENT_EXPORT_TYPE, ACCOUNTING_SUPPLIER_AGING_EXPORT_TYPE);

  private final SystemSettingsService systemSettingsService;

  public SensitiveDisclosurePolicyOwner(SystemSettingsService systemSettingsService) {
    this.systemSettingsService = systemSettingsService;
  }

  public boolean exportApprovalRequired() {
    return systemSettingsService.isExportApprovalRequired();
  }

  public void enforceRequesterOwnedDownload(ExportRequest request, UserAccount actor) {
    if (request == null || actor == null || request.getUserId() == null || actor.getId() == null) {
      throw new ApplicationException(
          ErrorCode.AUTH_INSUFFICIENT_PERMISSIONS,
          "Export request does not belong to the authenticated actor");
    }
    if (!request.getUserId().equals(actor.getId())) {
      throw new ApplicationException(
              ErrorCode.AUTH_INSUFFICIENT_PERMISSIONS,
              "Export request does not belong to the authenticated actor")
          .withDetail("requestId", request.getId())
          .withDetail("actor", actor.getEmail());
    }
  }

  public void enforceApprovalGate(ExportRequest request) {
    if (request == null || !exportApprovalRequired()) {
      return;
    }
    if (request.getStatus() != ExportApprovalStatus.APPROVED) {
      throw new ApplicationException(
              ErrorCode.AUTH_INSUFFICIENT_PERMISSIONS,
              "Export request is not approved for download")
          .withDetail("requestId", request.getId())
          .withDetail("status", request.getStatus() != null ? request.getStatus().name() : null);
    }
  }

  public static boolean isApprovalBypassExportType(String exportType) {
    if (!StringUtils.hasText(exportType)) {
      return false;
    }
    return APPROVAL_BYPASS_EXPORT_TYPES.contains(exportType.trim().toUpperCase(Locale.ROOT));
  }
}
