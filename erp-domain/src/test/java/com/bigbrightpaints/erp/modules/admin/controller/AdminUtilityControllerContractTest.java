package com.bigbrightpaints.erp.modules.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;

import com.bigbrightpaints.erp.core.notification.EmailService;
import com.bigbrightpaints.erp.modules.admin.dto.AdminNotifyRequest;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;

class AdminUtilityControllerContractTest {

  @Test
  void classLevelMapping_and_authority_are_superadmin_only() {
    RequestMapping mapping = AdminUtilityController.class.getAnnotation(RequestMapping.class);
    PreAuthorize preAuthorize = AdminUtilityController.class.getAnnotation(PreAuthorize.class);

    assertThat(mapping).isNotNull();
    assertThat(mapping.value()).containsExactly("/api/v1/superadmin");
    assertThat(preAuthorize).isNotNull();
    assertThat(preAuthorize.value()).isEqualTo("hasAuthority('ROLE_SUPER_ADMIN')");
  }

  @Test
  void notifyUser_dispatches_email_and_returns_success_contract() {
    EmailService emailService = mock(EmailService.class);
    AdminUtilityController controller = new AdminUtilityController(emailService);
    AdminNotifyRequest request =
        new AdminNotifyRequest(
            "admin.user@bigbrightpaints.com",
            "Tenant runtime maintenance",
            "Maintenance window starts at 23:00 UTC");

    ApiResponse<String> response = controller.notifyUser(request);

    assertThat(response.success()).isTrue();
    assertThat(response.message()).isEqualTo("Notification sent");
    assertThat(response.data()).isEqualTo("Email dispatched");
    verify(emailService)
        .sendSimpleEmail(
            "admin.user@bigbrightpaints.com",
            "Tenant runtime maintenance",
            "Maintenance window starts at 23:00 UTC");
  }
}
