package com.bigbrightpaints.erp.orchestrator.service;

import com.bigbrightpaints.erp.core.util.CompanyTime;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.orchestrator.repository.AuditRecord;
import com.bigbrightpaints.erp.orchestrator.repository.AuditRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class TraceService {

    private final AuditRepository auditRepository;
    private final CompanyRepository companyRepository;
    private final CompanyContextService companyContextService;
    private final ObjectMapper objectMapper;

    public TraceService(AuditRepository auditRepository,
                        CompanyRepository companyRepository,
                        CompanyContextService companyContextService,
                        ObjectMapper objectMapper) {
        this.auditRepository = auditRepository;
        this.companyRepository = companyRepository;
        this.companyContextService = companyContextService;
        this.objectMapper = objectMapper;
    }

    public void record(String traceId, String eventType, String companyCode, Map<String, Object> details) {
        record(traceId, eventType, companyCode, details, null, null);
    }

    public void record(String traceId,
                       String eventType,
                       String companyCode,
                       Map<String, Object> details,
                       String requestId,
                       String idempotencyKey) {
        Company company = requireCompany(companyCode);
        String payload = serializeDetails(details);
        String sanitizedTraceId = CorrelationIdentifierSanitizer.sanitizeRequiredTraceId(traceId);
        String sanitizedRequestId = CorrelationIdentifierSanitizer.sanitizeOptionalRequestId(requestId);
        String sanitizedIdempotencyKey = CorrelationIdentifierSanitizer.sanitizeOptionalIdempotencyKey(idempotencyKey);
        AuditRecord record = new AuditRecord(
                sanitizedTraceId,
                eventType,
                CompanyTime.now(company),
                payload,
                company.getId(),
                sanitizedRequestId,
                sanitizedIdempotencyKey);
        auditRepository.save(record);
    }

    public List<AuditRecord> getTrace(String traceId) {
        String sanitizedTraceId = CorrelationIdentifierSanitizer.sanitizeRequiredTraceId(traceId);
        Company company = companyContextService.requireCurrentCompany();
        return auditRepository.findByTraceIdAndCompanyIdOrderByTimestampAsc(sanitizedTraceId, company.getId());
    }

    private Company requireCompany(String companyCode) {
        if (!StringUtils.hasText(companyCode)) {
            throw new IllegalStateException("Company context is required");
        }
        return companyRepository.findByCodeIgnoreCase(companyCode.trim())
                .orElseThrow(() -> new IllegalStateException("Company not found: code=" + companyCode));
    }

    private String serializeDetails(Map<String, Object> details) {
        Map<String, Object> safe = details != null ? details : Map.of();
        try {
            return objectMapper.writeValueAsString(safe);
        } catch (JsonProcessingException ex) {
            return safe.toString();
        }
    }
}
