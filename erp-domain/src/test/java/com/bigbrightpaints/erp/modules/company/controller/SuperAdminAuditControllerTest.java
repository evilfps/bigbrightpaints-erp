package com.bigbrightpaints.erp.modules.company.controller;

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

class SuperAdminAuditControllerTest {

  @Test
  void listPlatformEvents_delegatesToPlatformFeed() {
    AuditAccessService auditAccessService = mock(AuditAccessService.class);
    SuperAdminAuditController controller = new SuperAdminAuditController(auditAccessService);
    AuditFeedFilter expectedFilter =
        new AuditFeedFilter(
            LocalDate.of(2026, 3, 1),
            LocalDate.of(2026, 3, 31),
            null,
            "CONFIGURATION_CHANGED",
            "SUCCESS",
            "root-superadmin@bbp.com",
            null,
            null,
            0,
            50);
    PageResponse<AuditFeedItemDto> expected =
        PageResponse.of(
            List.of(
                new AuditFeedItemDto(
                    1L,
                    "AUDIT_LOG",
                    "ADMIN",
                    Instant.parse("2026-03-01T10:00:00Z"),
                    7L,
                    "TENANT-A",
                    "SUPERADMIN",
                    "CONFIGURATION_CHANGED",
                    "SUCCESS",
                    null,
                    "root-superadmin@bbp.com",
                    null,
                    null,
                    null,
                    null,
                    null,
                    "PUT",
                    "/api/v1/superadmin/tenants/7/lifecycle",
                    "trace-1",
                    Map.of("targetCompanyCode", "TENANT-A"))),
            1,
            0,
            50);
    when(auditAccessService.queryPlatformFeed(expectedFilter)).thenReturn(expected);

    ApiResponse<PageResponse<AuditFeedItemDto>> body =
        controller
            .listPlatformEvents(
                "2026-03-01",
                "2026-03-31",
                "CONFIGURATION_CHANGED",
                "SUCCESS",
                "root-superadmin@bbp.com",
                null,
                null,
                0,
                50)
            .getBody();

    assertThat(body).isNotNull();
    assertThat(body.data()).isEqualTo(expected);
    verify(auditAccessService).queryPlatformFeed(expectedFilter);
  }
}
