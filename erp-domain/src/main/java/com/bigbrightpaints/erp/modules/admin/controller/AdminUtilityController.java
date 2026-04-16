package com.bigbrightpaints.erp.modules.admin.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bigbrightpaints.erp.core.notification.EmailService;
import com.bigbrightpaints.erp.modules.admin.dto.AdminNotifyRequest;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/superadmin")
@PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN')")
public class AdminUtilityController {

  private final EmailService emailService;

  public AdminUtilityController(EmailService emailService) {
    this.emailService = emailService;
  }

  @PostMapping("/notify")
  public ApiResponse<String> notifyUser(@Valid @RequestBody AdminNotifyRequest request) {
    emailService.sendSimpleEmail(request.to(), request.subject(), request.body());
    return ApiResponse.success("Notification sent", "Email dispatched");
  }
}
