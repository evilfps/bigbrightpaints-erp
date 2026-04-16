package com.bigbrightpaints.erp.modules.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;

import com.bigbrightpaints.erp.core.security.PortalRoleActionMatrix;
import com.bigbrightpaints.erp.modules.admin.domain.SupportTicketCategory;
import com.bigbrightpaints.erp.modules.admin.domain.SupportTicketStatus;
import com.bigbrightpaints.erp.modules.admin.dto.SupportTicketListResponse;
import com.bigbrightpaints.erp.modules.admin.dto.SupportTicketResponse;
import com.bigbrightpaints.erp.modules.admin.service.AdminSupportService;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;

class AdminSupportControllerContractTest {

  @Test
  void classLevelMapping_and_authority_are_tenant_admin_only() {
    RequestMapping mapping = AdminSupportController.class.getAnnotation(RequestMapping.class);
    PreAuthorize preAuthorize = AdminSupportController.class.getAnnotation(PreAuthorize.class);

    assertThat(mapping).isNotNull();
    assertThat(mapping.value()).containsExactly("/api/v1/admin/support/tickets");
    assertThat(preAuthorize).isNotNull();
    assertThat(preAuthorize.value()).isEqualTo(PortalRoleActionMatrix.TENANT_ADMIN_ONLY);
  }

  @Test
  void list_delegates_to_service_contract() {
    AdminSupportService service = mock(AdminSupportService.class);
    AdminSupportController controller = new AdminSupportController(service);
    SupportTicketResponse ticket =
        new SupportTicketResponse(
            100L,
            null,
            "TENANT-A",
            7L,
            "admin@tenant-a.test",
            SupportTicketCategory.SUPPORT,
            "Export blocked",
            "Approval completed but file not downloadable",
            SupportTicketStatus.OPEN,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null);
    when(service.listAllTenantTickets()).thenReturn(List.of(ticket));

    ResponseEntity<ApiResponse<SupportTicketListResponse>> response = controller.list();

    assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().data()).isNotNull();
    assertThat(response.getBody().data().tickets()).containsExactly(ticket);
    verify(service).listAllTenantTickets();
  }
}
