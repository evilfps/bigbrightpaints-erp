package com.bigbrightpaints.erp.modules.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.bigbrightpaints.erp.core.auditaccess.AuditAccessService;
import com.bigbrightpaints.erp.core.auditaccess.AuditFeedFilter;
import com.bigbrightpaints.erp.core.auditaccess.dto.AuditFeedItemDto;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;
import com.bigbrightpaints.erp.shared.dto.PageResponse;

class AdminAuditControllerTest {

  @Test
  void listEvents_delegatesToCanonicalTenantAdminFeed() {
    AuditAccessService auditAccessService = mock(AuditAccessService.class);
    AdminAuditController controller = new AdminAuditController(auditAccessService);
    AuditFeedFilter expectedFilter =
        new AuditFeedFilter(
            LocalDate.of(2026, 3, 1),
            LocalDate.of(2026, 3, 31),
            null,
            "ACCESS_DENIED",
            "FAILURE",
            "ops@example.com",
            null,
            "trace-1",
            0,
            50);
    PageResponse<AuditFeedItemDto> expected =
        PageResponse.of(
            List.of(
                new AuditFeedItemDto(
                    1L,
                    "AUDIT_LOG",
                    "SECURITY",
                    Instant.parse("2026-03-01T10:00:00Z"),
                    7L,
                    "TENANT-A",
                    "ADMIN",
                    "ACCESS_DENIED",
                    "FAILURE",
                    null,
                    "ops@example.com",
                    null,
                    null,
                    null,
                    null,
                    null,
                    "GET",
                    "/api/v1/admin/users",
                    "trace-1",
                    Map.of())),
            1,
            0,
            50);
    when(auditAccessService.queryTenantAdminFeed(expectedFilter)).thenReturn(expected);

    ApiResponse<PageResponse<AuditFeedItemDto>> body =
        controller
            .listEvents(
                "2026-03-01",
                "2026-03-31",
                null,
                "ACCESS_DENIED",
                "FAILURE",
                "ops@example.com",
                null,
                "trace-1",
                0,
                50)
            .getBody();

    assertThat(body).isNotNull();
    assertThat(body.data()).isEqualTo(expected);
    verify(auditAccessService).queryTenantAdminFeed(expectedFilter);
  }
}
