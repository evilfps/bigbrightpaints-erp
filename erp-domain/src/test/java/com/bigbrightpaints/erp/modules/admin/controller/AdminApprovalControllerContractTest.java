package com.bigbrightpaints.erp.modules.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import com.bigbrightpaints.erp.core.security.PortalRoleActionMatrix;
import com.bigbrightpaints.erp.modules.admin.dto.AdminApprovalDecisionRequest;
import com.bigbrightpaints.erp.modules.admin.dto.AdminApprovalInboxResponse;
import com.bigbrightpaints.erp.modules.admin.dto.AdminApprovalItemDto;
import com.bigbrightpaints.erp.modules.admin.service.AdminApprovalService;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;

class AdminApprovalControllerContractTest {

  @Test
  void classLevelMapping_and_authority_are_tenant_admin_only() {
    RequestMapping mapping = AdminApprovalController.class.getAnnotation(RequestMapping.class);
    PreAuthorize preAuthorize = AdminApprovalController.class.getAnnotation(PreAuthorize.class);

    assertThat(mapping).isNotNull();
    assertThat(mapping.value()).containsExactly("/api/v1/admin/approvals");
    assertThat(preAuthorize).isNotNull();
    assertThat(preAuthorize.value()).isEqualTo(PortalRoleActionMatrix.TENANT_ADMIN_ONLY);
  }

  @Test
  void decideEndpoint_uses_generic_decision_path() throws Exception {
    Method method =
        AdminApprovalController.class.getMethod(
            "decide", String.class, Long.class, AdminApprovalDecisionRequest.class);
    PostMapping mapping = method.getAnnotation(PostMapping.class);

    assertThat(mapping).isNotNull();
    assertThat(mapping.value()).containsExactly("/{originType}/{id}/decisions");
  }

  @Test
  void inbox_delegates_to_service_contract() {
    AdminApprovalService service = mock(AdminApprovalService.class);
    AdminApprovalController controller = new AdminApprovalController(service);
    AdminApprovalInboxResponse inbox =
        new AdminApprovalInboxResponse(
            List.of(
                new AdminApprovalItemDto(
                    AdminApprovalItemDto.OriginType.EXPORT_REQUEST,
                    AdminApprovalItemDto.OwnerType.REPORTS,
                    10L,
                    null,
                    "EXP-10",
                    "PENDING",
                    "Review export request EXP-10",
                    "SALES",
                    "periodId=1",
                    5L,
                    "accounting@bbp.com",
                    "APPROVAL_DECISION",
                    "Review approval",
                    "/api/v1/admin/approvals/EXPORT_REQUEST/10/decisions",
                    "/api/v1/admin/approvals/EXPORT_REQUEST/10/decisions",
                    Instant.parse("2026-04-15T08:30:00Z"))),
            1);
    when(service.getInbox()).thenReturn(inbox);

    ResponseEntity<ApiResponse<AdminApprovalInboxResponse>> response = controller.inbox();

    assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().success()).isTrue();
    assertThat(response.getBody().data()).isEqualTo(inbox);
    verify(service).getInbox();
  }
}
