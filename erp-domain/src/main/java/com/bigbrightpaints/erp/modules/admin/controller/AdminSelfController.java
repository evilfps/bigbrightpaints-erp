package com.bigbrightpaints.erp.modules.admin.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bigbrightpaints.erp.core.security.PortalRoleActionMatrix;
import com.bigbrightpaints.erp.modules.admin.dto.AdminSelfSettingsDto;
import com.bigbrightpaints.erp.modules.admin.service.AdminSelfService;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;

@RestController
@RequestMapping("/api/v1/admin/self")
@PreAuthorize(PortalRoleActionMatrix.TENANT_ADMIN_ONLY)
public class AdminSelfController {

  private final AdminSelfService adminSelfService;

  public AdminSelfController(AdminSelfService adminSelfService) {
    this.adminSelfService = adminSelfService;
  }

  @GetMapping("/settings")
  public ResponseEntity<ApiResponse<AdminSelfSettingsDto>> settings() {
    return ResponseEntity.ok(
        ApiResponse.success("Admin self settings fetched", adminSelfService.settings()));
  }
}
