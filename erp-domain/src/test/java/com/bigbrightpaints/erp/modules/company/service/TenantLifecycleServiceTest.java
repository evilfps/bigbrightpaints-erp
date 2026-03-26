package com.bigbrightpaints.erp.modules.company.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyLifecycleState;
import com.bigbrightpaints.erp.modules.company.dto.CompanyLifecycleStateDto;

@ExtendWith(MockitoExtension.class)
class TenantLifecycleServiceTest {

  @Mock private AuditService auditService;

  @Test
  void transition_allowsActiveToSuspended_andWritesAuditEntry() {
    TenantLifecycleService service = new TenantLifecycleService(auditService);
    Company company = company(1L, "ACME", CompanyLifecycleState.ACTIVE);

    CompanyLifecycleStateDto response =
        service.transition(
            company,
            CompanyLifecycleState.SUSPENDED,
            "compliance-review",
            new UsernamePasswordAuthenticationToken("ops@bbp.com", "n/a"));

    assertThat(response.previousLifecycleState()).isEqualTo("ACTIVE");
    assertThat(response.lifecycleState()).isEqualTo("SUSPENDED");
    assertThat(company.getLifecycleState()).isEqualTo(CompanyLifecycleState.SUSPENDED);
    assertThat(company.getLifecycleReason()).isEqualTo("compliance-review");
    verify(auditService)
        .logAuthSuccess(
            eq(AuditEvent.CONFIGURATION_CHANGED), eq("ops@bbp.com"), eq("ACME"), anyMap());
  }

  @Test
  void transition_allowsReactivationFromDeactivatedState() {
    TenantLifecycleService service = new TenantLifecycleService(auditService);
    Company company = company(2L, "BETA", CompanyLifecycleState.DEACTIVATED);

    CompanyLifecycleStateDto response =
        service.transition(company, CompanyLifecycleState.ACTIVE, "manual-reactivation", null);

    assertThat(response.previousLifecycleState()).isEqualTo("DEACTIVATED");
    assertThat(response.lifecycleState()).isEqualTo("ACTIVE");
    assertThat(company.getLifecycleState()).isEqualTo(CompanyLifecycleState.ACTIVE);
    assertThat(company.getLifecycleReason()).isEqualTo("manual-reactivation");
  }

  @Test
  void transition_allowsSuspendedToDeactivated() {
    TenantLifecycleService service = new TenantLifecycleService(auditService);
    Company company = company(3L, "GAMMA", CompanyLifecycleState.SUSPENDED);

    CompanyLifecycleStateDto response =
        service.transition(company, CompanyLifecycleState.DEACTIVATED, "contract-terminated", null);

    assertThat(response.previousLifecycleState()).isEqualTo("SUSPENDED");
    assertThat(response.lifecycleState()).isEqualTo("DEACTIVATED");
  }

  private Company company(Long id, String code, CompanyLifecycleState lifecycleState) {
    Company company = new Company();
    ReflectionTestUtils.setField(company, "id", id);
    company.setCode(code);
    company.setName(code + " Ltd");
    company.setTimezone("UTC");
    company.setLifecycleState(lifecycleState);
    return company;
  }
}
