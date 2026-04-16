package com.bigbrightpaints.erp.modules.admin.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.bigbrightpaints.erp.core.audit.AuditLogRepository;
import com.bigbrightpaints.erp.modules.admin.domain.SupportTicketRepository;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;

@ExtendWith(MockitoExtension.class)
class AdminDashboardServiceTest {

  @Mock private CompanyContextService companyContextService;
  @Mock private AdminApprovalService adminApprovalService;
  @Mock private TenantRuntimePolicyService tenantRuntimePolicyService;
  @Mock private UserAccountRepository userAccountRepository;
  @Mock private SupportTicketRepository supportTicketRepository;
  @Mock private AuditLogRepository auditLogRepository;

  private AdminDashboardService service;

  @BeforeEach
  void setUp() {
    service =
        new AdminDashboardService(
            companyContextService,
            adminApprovalService,
            tenantRuntimePolicyService,
            userAccountRepository,
            supportTicketRepository,
            auditLogRepository);
  }

  @Test
  void normalizeActorKey_returnsNullForNullOrBlankActor() {
    assertThat(normalizeActorKey(null)).isNull();
    assertThat(normalizeActorKey("   ")).isNull();
  }

  @Test
  void normalizeActorKey_trimsAndLowercasesActorEmail() {
    assertThat(normalizeActorKey("  Tenant.Admin@BigBrightPaints.COM  "))
        .isEqualTo("tenant.admin@bigbrightpaints.com");
  }

  private String normalizeActorKey(String actor) {
    return ReflectionTestUtils.invokeMethod(service, "normalizeActorKey", actor);
  }
}
