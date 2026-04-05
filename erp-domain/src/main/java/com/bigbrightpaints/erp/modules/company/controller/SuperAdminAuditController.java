package com.bigbrightpaints.erp.modules.company.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.bigbrightpaints.erp.core.auditaccess.AuditAccessService;
import com.bigbrightpaints.erp.core.auditaccess.AuditControllerSupport;
import com.bigbrightpaints.erp.core.auditaccess.dto.AuditFeedItemDto;
import com.bigbrightpaints.erp.core.security.PortalRoleActionMatrix;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;
import com.bigbrightpaints.erp.shared.dto.PageResponse;

@RestController
@RequestMapping("/api/v1/superadmin/audit")
@PreAuthorize(PortalRoleActionMatrix.SUPER_ADMIN_ONLY)
public class SuperAdminAuditController extends AuditControllerSupport {

  private final AuditAccessService auditAccessService;

  public SuperAdminAuditController(AuditAccessService auditAccessService) {
    this.auditAccessService = auditAccessService;
  }

  @GetMapping("/platform-events")
  public ResponseEntity<ApiResponse<PageResponse<AuditFeedItemDto>>> listPlatformEvents(
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(required = false) String action,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String actor,
      @RequestParam(required = false) String entityType,
      @RequestParam(required = false) String reference,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return ResponseEntity.ok(
        ApiResponse.success(
            auditAccessService.queryPlatformFeed(
                buildFilter(
                    from, to, null, action, status, actor, entityType, reference, page, size))));
  }
}
