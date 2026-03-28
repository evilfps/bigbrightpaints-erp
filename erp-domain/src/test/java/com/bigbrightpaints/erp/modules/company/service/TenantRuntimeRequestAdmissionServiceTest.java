package com.bigbrightpaints.erp.modules.company.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TenantRuntimeRequestAdmissionServiceTest {

  @Mock private TenantRuntimeEnforcementService tenantRuntimeEnforcementService;

  private TenantRuntimeRequestAdmissionService service;

  @BeforeEach
  void setUp() {
    service = new TenantRuntimeRequestAdmissionService(tenantRuntimeEnforcementService);
  }

  @Test
  void beginRequest_delegatesDefaultAdmissionFlow() {
    TenantRuntimeEnforcementService.TenantRequestAdmission admission =
        TenantRuntimeEnforcementService.TenantRequestAdmission.notTracked();
    when(tenantRuntimeEnforcementService.admitRequest("ACME", "/api/v1/orders", "GET", "actor"))
        .thenReturn(admission);

    assertThat(service.beginRequest("ACME", "/api/v1/orders", "GET", "actor")).isSameAs(admission);
  }

  @Test
  void beginRequest_delegatesPrivilegedPolicyControlFlow() {
    TenantRuntimeEnforcementService.TenantRequestAdmission admission =
        TenantRuntimeEnforcementService.TenantRequestAdmission.notTracked();
    when(
            tenantRuntimeEnforcementService.admitRequest(
                "ACME", "/api/v1/superadmin/tenants/1/limits", "PUT", "actor", true))
        .thenReturn(admission);

    assertThat(
            service.beginRequest(
                "ACME", "/api/v1/superadmin/tenants/1/limits", "PUT", "actor", true))
        .isSameAs(admission);
  }

  @Test
  void completeRequest_delegatesResponseFinalization() {
    TenantRuntimeEnforcementService.TenantRequestAdmission admission =
        TenantRuntimeEnforcementService.TenantRequestAdmission.notTracked();

    service.completeRequest(admission, 204);

    verify(tenantRuntimeEnforcementService).completeRequestAdmission(admission, 204);
  }

  @Test
  void enforceAuthOperationAllowed_delegatesToRuntimePolicyService() {
    service.enforceAuthOperationAllowed("ACME", "actor", "LOGIN");

    verify(tenantRuntimeEnforcementService).enforceAuthOperation("ACME", "actor", "LOGIN");
  }

  @Test
  void snapshot_delegatesToCanonicalRuntimeSnapshotOwner() {
    TenantRuntimeEnforcementService.TenantRuntimeSnapshot snapshot =
        new TenantRuntimeEnforcementService.TenantRuntimeSnapshot(
            "ACME",
            TenantRuntimeEnforcementService.TenantRuntimeState.ACTIVE,
            "POLICY_ACTIVE",
            "chain-1",
            Instant.parse("2026-03-28T12:00:00Z"),
            10,
            20,
            30,
            new TenantRuntimeEnforcementService.TenantRuntimeMetrics(1L, 2L, 3L, 4, 5, 6, 7L));
    when(tenantRuntimeEnforcementService.snapshot("ACME")).thenReturn(snapshot);

    assertThat(service.snapshot("ACME")).isSameAs(snapshot);
  }
}
