package com.bigbrightpaints.erp.modules.sales.service;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.sales.domain.CreditRequest;
import com.bigbrightpaints.erp.modules.sales.domain.CreditRequestRepository;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import com.bigbrightpaints.erp.modules.sales.dto.CreditLimitRequestCreateRequest;
import com.bigbrightpaints.erp.modules.sales.dto.CreditLimitRequestDto;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class CreditLimitRequestService {

    private static final Set<String> VALID_STATUSES = Set.of("PENDING", "APPROVED", "REJECTED");
    private static final String STATUS_PENDING = "PENDING";

    private final CompanyContextService companyContextService;
    private final CreditRequestRepository creditRequestRepository;
    private final DealerRepository dealerRepository;
    private final AuditService auditService;

    public CreditLimitRequestService(CompanyContextService companyContextService,
                                     CreditRequestRepository creditRequestRepository,
                                     DealerRepository dealerRepository,
                                     AuditService auditService) {
        this.companyContextService = companyContextService;
        this.creditRequestRepository = creditRequestRepository;
        this.dealerRepository = dealerRepository;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public java.util.List<CreditLimitRequestDto> listRequests() {
        Company company = companyContextService.requireCurrentCompany();
        return creditRequestRepository.findByCompanyWithDealerOrderByCreatedAtDesc(company).stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional
    public CreditLimitRequestDto createRequest(CreditLimitRequestCreateRequest request) {
        return createRequest(request, null, null);
    }

    @Transactional
    public CreditLimitRequestDto createRequest(CreditLimitRequestCreateRequest request,
                                               Long requesterUserId,
                                               String requesterEmail) {
        Company company = companyContextService.requireCurrentCompany();
        CreditRequest creditRequest = new CreditRequest();
        creditRequest.setCompany(company);
        creditRequest.setDealer(requireDealer(company, requireDealerId(request.dealerId())));
        creditRequest.setAmountRequested(request.amountRequested());
        creditRequest.setReason(request.reason());
        creditRequest.setRequesterUserId(requesterUserId);
        creditRequest.setRequesterEmail(normalizeRequesterEmail(requesterEmail));
        creditRequest.setStatus(STATUS_PENDING);
        return toDto(creditRequestRepository.save(creditRequest));
    }

    @Transactional
    public CreditLimitRequestDto approveRequest(Long id, String decisionReason) {
        CreditRequest creditRequest = requireRequest(id);
        requirePendingRequest(creditRequest, "approved");
        String normalizedDecisionReason = requireDecisionReason(decisionReason, "approve");
        Dealer dealer = requireDealerForApproval(creditRequest);
        BigDecimal increment = requirePositiveIncrement(creditRequest);
        BigDecimal oldLimit = requireCurrentDealerCreditLimit(dealer);
        BigDecimal newLimit = oldLimit.add(increment);
        dealer.setCreditLimit(newLimit);
        dealerRepository.save(dealer);
        creditRequest.setDealer(dealer);
        creditRequest.setStatus("APPROVED");
        creditRequestRepository.save(creditRequest);
        Map<String, String> metadataOverrides = new HashMap<>();
        metadataOverrides.put("oldLimit", oldLimit.toPlainString());
        metadataOverrides.put("newLimit", newLimit.toPlainString());
        metadataOverrides.put("increment", increment.toPlainString());
        auditDecision(AuditEvent.TRANSACTION_APPROVED, creditRequest, normalizedDecisionReason, metadataOverrides);
        return toDto(creditRequest);
    }

    @Transactional
    public CreditLimitRequestDto rejectRequest(Long id, String decisionReason) {
        CreditRequest creditRequest = requireRequest(id);
        requirePendingRequest(creditRequest, "rejected");
        String normalizedDecisionReason = requireDecisionReason(decisionReason, "reject");
        creditRequest.setStatus("REJECTED");
        creditRequestRepository.save(creditRequest);
        auditDecision(AuditEvent.TRANSACTION_REJECTED, creditRequest, normalizedDecisionReason, Map.of());
        return toDto(creditRequest);
    }

    private Dealer requireDealer(Company company, Long dealerId) {
        return dealerRepository.findByCompanyAndId(company, dealerId)
                .orElseThrow(() -> new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE,
                        "Dealer not found"));
    }

    private Long requireDealerId(Long dealerId) {
        if (dealerId == null) {
            throw new ApplicationException(ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD,
                    "Credit limit request requires dealerId")
                    .withDetail("field", "dealerId")
                    .withDetail("resourceType", "credit_limit_request");
        }
        return dealerId;
    }

    private CreditRequest requireRequest(Long id) {
        Company company = companyContextService.requireCurrentCompany();
        return creditRequestRepository.findByCompanyAndId(company, id)
                .orElseThrow(() -> new ApplicationException(ErrorCode.BUSINESS_ENTITY_NOT_FOUND,
                        "Credit limit request not found"));
    }

    private void requirePendingRequest(CreditRequest creditRequest, String action) {
        String currentStatus = normalizeStatus(creditRequest.getStatus());
        if (!STATUS_PENDING.equals(currentStatus)) {
            throw new ApplicationException(ErrorCode.BUSINESS_INVALID_STATE,
                    "Only pending credit limit requests can be " + action)
                    .withDetail("currentStatus", currentStatus)
                    .withDetail("requiredStatus", STATUS_PENDING);
        }
    }

    private String requireDecisionReason(String reason, String operation) {
        if (!StringUtils.hasText(reason)) {
            throw new ApplicationException(ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD,
                    "Credit limit request " + operation + " decision requires reason")
                    .withDetail("field", "reason")
                    .withDetail("operation", operation)
                    .withDetail("resourceType", "credit_limit_request");
        }
        return reason.trim();
    }

    private String normalizeRequesterEmail(String requesterEmail) {
        if (!StringUtils.hasText(requesterEmail)) {
            return null;
        }
        return requesterEmail.trim();
    }

    private Dealer requireDealerForApproval(CreditRequest creditRequest) {
        Dealer dealer = creditRequest.getDealer();
        if (dealer == null || dealer.getId() == null) {
            throw new ApplicationException(ErrorCode.BUSINESS_INVALID_STATE,
                    "Credit limit request approval requires an assigned dealer")
                    .withDetail("requiredField", "dealerId")
                    .withDetail("resourceType", "credit_limit_request");
        }
        Company company = companyContextService.requireCurrentCompany();
        return dealerRepository.lockByCompanyAndId(company, dealer.getId())
                .orElseThrow(() -> new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE,
                        "Dealer linked to credit limit request was not found"));
    }

    private BigDecimal requirePositiveIncrement(CreditRequest creditRequest) {
        BigDecimal amountRequested = creditRequest.getAmountRequested();
        if (amountRequested == null || amountRequested.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "Credit limit request amount must be greater than zero to approve")
                    .withDetail("resourceType", "credit_limit_request")
                    .withDetail("field", "amountRequested");
        }
        return amountRequested;
    }

    private BigDecimal requireCurrentDealerCreditLimit(Dealer dealer) {
        if (dealer.getCreditLimit() == null || dealer.getCreditLimit().compareTo(BigDecimal.ZERO) < 0) {
            throw new ApplicationException(ErrorCode.BUSINESS_INVALID_STATE,
                    "Dealer credit limit is missing or invalid")
                    .withDetail("resourceType", "dealer")
                    .withDetail("dealerId", dealer.getId());
        }
        return dealer.getCreditLimit();
    }

    private void auditDecision(AuditEvent event,
                               CreditRequest creditRequest,
                               String decisionReason,
                               Map<String, String> metadataOverrides) {
        Map<String, String> metadata = new HashMap<>();
        metadata.put("resourceType", "credit_limit_request");
        metadata.put("decisionStatus", normalizeStatus(creditRequest.getStatus()));
        metadata.put("reason", decisionReason);
        metadata.put("decisionReason", decisionReason);
        if (creditRequest.getId() != null) {
            metadata.put("requestId", creditRequest.getId().toString());
        }
        if (creditRequest.getPublicId() != null) {
            metadata.put("requestPublicId", creditRequest.getPublicId().toString());
        }
        if (creditRequest.getDealer() != null && creditRequest.getDealer().getId() != null) {
            metadata.put("dealerId", creditRequest.getDealer().getId().toString());
        }
        if (creditRequest.getAmountRequested() != null) {
            metadata.put("amountRequested", creditRequest.getAmountRequested().toPlainString());
        }
        if (StringUtils.hasText(creditRequest.getReason())) {
            metadata.put("requestReason", creditRequest.getReason().trim());
        }
        if (metadataOverrides != null && !metadataOverrides.isEmpty()) {
            metadata.putAll(metadataOverrides);
        }
        auditService.logSuccess(event, metadata);
    }

    private String normalizeStatus(String status) {
        if (!StringUtils.hasText(status)) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT, "Status is required")
                    .withDetail("entity", "credit_limit_request");
        }
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        if (!VALID_STATUSES.contains(normalized)) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT, "Unsupported credit limit request status")
                    .withDetail("status", normalized)
                    .withDetail("allowedStatuses", VALID_STATUSES);
        }
        return normalized;
    }

    private CreditLimitRequestDto toDto(CreditRequest request) {
        return new CreditLimitRequestDto(
                request.getId(),
                request.getPublicId(),
                request.getDealer() != null ? request.getDealer().getName() : null,
                request.getAmountRequested(),
                normalizeStatus(request.getStatus()),
                request.getReason(),
                request.getCreatedAt());
    }
}
