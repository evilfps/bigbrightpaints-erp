package com.bigbrightpaints.erp.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.orchestrator.repository.AuditRecord;
import com.bigbrightpaints.erp.orchestrator.repository.AuditRepository;
import com.bigbrightpaints.erp.orchestrator.service.TraceService;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;

@DisplayName("Orchestrator: trace company scoping")
public class TraceServiceIT extends AbstractIntegrationTest {

  @Autowired private TraceService traceService;

  @Autowired private AuditRepository auditRepository;

  @AfterEach
  void clearContext() {
    CompanyContextHolder.clear();
  }

  @Test
  @Transactional
  void trace_is_scoped_to_company() {
    Company companyA = dataSeeder.ensureCompany("TRACE-A", "Trace A Co");
    Company companyB = dataSeeder.ensureCompany("TRACE-B", "Trace B Co");
    String traceId = UUID.randomUUID().toString();

    traceService.record(traceId, "TEST_EVENT", companyA.getCode(), Map.of("k", "v"));

    CompanyContextHolder.setCompanyCode(companyA.getCode());
    List<AuditRecord> scoped = traceService.getTrace(traceId);
    assertThat(scoped).hasSize(1);
    assertThat(scoped.get(0).getCompanyId()).isEqualTo(companyA.getId());

    CompanyContextHolder.setCompanyCode(companyB.getCode());
    assertThat(traceService.getTrace(traceId)).isEmpty();

    assertThat(
            auditRepository.findByTraceIdAndCompanyIdOrderByTimestampAsc(traceId, companyA.getId()))
        .hasSize(1);
  }
}
