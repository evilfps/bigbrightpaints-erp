package com.bigbrightpaints.erp.orchestrator.service;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.orchestrator.repository.AuditRecord;
import com.bigbrightpaints.erp.orchestrator.repository.AuditRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class TraceService {

    private final AuditRepository auditRepository;
    private final CompanyRepository companyRepository;
    private final CompanyContextService companyContextService;

    public TraceService(AuditRepository auditRepository,
                        CompanyRepository companyRepository,
                        CompanyContextService companyContextService) {
        this.auditRepository = auditRepository;
        this.companyRepository = companyRepository;
        this.companyContextService = companyContextService;
    }

    public void record(String traceId, String eventType, String companyCode, Map<String, Object> details) {
        Long companyId = resolveCompanyId(companyCode);
        AuditRecord record = new AuditRecord(traceId, eventType, Instant.now(), details.toString(), companyId);
        auditRepository.save(record);
    }

    public List<AuditRecord> getTrace(String traceId) {
        Company company = companyContextService.requireCurrentCompany();
        return auditRepository.findByTraceIdAndCompanyIdOrderByTimestampAsc(traceId, company.getId());
    }

    private Long resolveCompanyId(String companyCode) {
        if (!StringUtils.hasText(companyCode)) {
            return null;
        }
        return companyRepository.findByCodeIgnoreCase(companyCode)
                .map(Company::getId)
                .orElse(null);
    }
}
