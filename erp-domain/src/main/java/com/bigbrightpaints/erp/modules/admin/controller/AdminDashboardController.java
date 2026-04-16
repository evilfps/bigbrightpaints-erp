package com.bigbrightpaints.erp.modules.admin.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bigbrightpaints.erp.core.security.PortalRoleActionMatrix;
import com.bigbrightpaints.erp.modules.admin.dto.AdminDashboardDto;
import com.bigbrightpaints.erp.modules.admin.service.AdminDashboardService;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;

@RestController
@RequestMapping("/api/v1/admin/dashboard")
@PreAuthorize(PortalRoleActionMatrix.TENANT_ADMIN_ONLY)
public class AdminDashboardController {

  private final AdminDashboardService adminDashboardService;

  public AdminDashboardController(AdminDashboardService adminDashboardService) {
    this.adminDashboardService = adminDashboardService;
  }

  @GetMapping
  public ResponseEntity<ApiResponse<AdminDashboardDto>> dashboard() {
    return ResponseEntity.ok(
        ApiResponse.success("Admin dashboard fetched", adminDashboardService.dashboard()));
  }
}
