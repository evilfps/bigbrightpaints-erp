package com.bigbrightpaints.erp.modules.admin.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bigbrightpaints.erp.core.security.PortalRoleActionMatrix;
import com.bigbrightpaints.erp.modules.admin.dto.AdminApprovalDecisionRequest;
import com.bigbrightpaints.erp.modules.admin.dto.AdminApprovalInboxResponse;
import com.bigbrightpaints.erp.modules.admin.dto.AdminApprovalItemDto;
import com.bigbrightpaints.erp.modules.admin.service.AdminApprovalService;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/admin/approvals")
@PreAuthorize(PortalRoleActionMatrix.TENANT_ADMIN_ONLY)
public class AdminApprovalController {

  private final AdminApprovalService adminApprovalService;

  public AdminApprovalController(AdminApprovalService adminApprovalService) {
    this.adminApprovalService = adminApprovalService;
  }

  @GetMapping
  public ResponseEntity<ApiResponse<AdminApprovalInboxResponse>> inbox() {
    return ResponseEntity.ok(
        ApiResponse.success("Approval inbox fetched", adminApprovalService.getInbox()));
  }

  @PostMapping("/{originType}/{id}/decisions")
  @Operation(
      summary = "Apply tenant-admin approval decision",
      description =
          "Origin-specific rules: CREDIT_REQUEST, CREDIT_LIMIT_OVERRIDE_REQUEST, and "
              + "PERIOD_CLOSE_REQUEST require a nonblank reason. PAYROLL_RUN supports only "
              + "APPROVE.")
  public ResponseEntity<ApiResponse<AdminApprovalItemDto>> decide(
      @Parameter(
              description =
                  "Approval origin type: EXPORT_REQUEST, CREDIT_REQUEST, CREDIT_LIMIT_OVERRIDE_REQUEST, PAYROLL_RUN, PERIOD_CLOSE_REQUEST")
          @PathVariable
          String originType,
      @PathVariable Long id,
      @Valid @RequestBody AdminApprovalDecisionRequest request) {
    return ResponseEntity.ok(
        ApiResponse.success(
            "Approval decision applied", adminApprovalService.decide(originType, id, request)));
  }
}
