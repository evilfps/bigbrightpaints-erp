package com.bigbrightpaints.erp.modules.sales.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.CompanyTime;
import com.bigbrightpaints.erp.modules.accounting.service.DealerLedgerService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlip;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlipRepository;
import com.bigbrightpaints.erp.modules.sales.domain.CreditLimitOverrideRequest;
import com.bigbrightpaints.erp.modules.sales.domain.CreditLimitOverrideRequestRepository;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrder;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrderRepository;
import com.bigbrightpaints.erp.modules.sales.dto.CreditLimitOverrideDecisionRequest;
import com.bigbrightpaints.erp.modules.sales.dto.CreditLimitOverrideRequestCreateRequest;
import com.bigbrightpaints.erp.modules.sales.dto.CreditLimitOverrideRequestDto;

@Service
public class CreditLimitOverrideService {

  private static final String STATUS_PENDING = "PENDING";
  private static final String STATUS_APPROVED = "APPROVED";
  private static final String STATUS_REJECTED = "REJECTED";
  private static final String STATUS_EXPIRED = "EXPIRED";
  private static final BigDecimal DISPATCH_AMOUNT_TOLERANCE = new BigDecimal("0.01");
  private static final BigDecimal HEADROOM_TOLERANCE = new BigDecimal("0.01");
  private static final String REASON_CODE_REQUEST = "CREDIT_LIMIT_EXCEPTION_REQUESTED";
  private static final String REASON_CODE_APPROVED = "CREDIT_LIMIT_EXCEPTION_APPROVED";
  private static final String REASON_CODE_REJECTED = "CREDIT_LIMIT_EXCEPTION_REJECTED";

  private final CompanyContextService companyContextService;
  private final CreditLimitOverrideRequestRepository creditLimitOverrideRequestRepository;
  private final DealerRepository dealerRepository;
  private final SalesOrderRepository salesOrderRepository;
  private final PackagingSlipRepository packagingSlipRepository;
  private final DealerLedgerService dealerLedgerService;
  private final AuditService auditService;

  public CreditLimitOverrideService(
      CompanyContextService companyContextService,
      CreditLimitOverrideRequestRepository creditLimitOverrideRequestRepository,
      DealerRepository dealerRepository,
      SalesOrderRepository salesOrderRepository,
      PackagingSlipRepository packagingSlipRepository,
      DealerLedgerService dealerLedgerService,
      AuditService auditService) {
    this.companyContextService = companyContextService;
    this.creditLimitOverrideRequestRepository = creditLimitOverrideRequestRepository;
    this.dealerRepository = dealerRepository;
    this.salesOrderRepository = salesOrderRepository;
    this.packagingSlipRepository = packagingSlipRepository;
    this.dealerLedgerService = dealerLedgerService;
    this.auditService = auditService;
  }

  public List<CreditLimitOverrideRequestDto> listRequests(String status) {
    Company company = companyContextService.requireCurrentCompany();
    String normalizedStatus = normalizeStatus(status);
    if (!StringUtils.hasText(normalizedStatus)) {
      return creditLimitOverrideRequestRepository
          .findByCompanyOrderByCreatedAtDesc(company)
          .stream()
          .map(this::toDto)
          .toList();
    }
    return creditLimitOverrideRequestRepository
        .findByCompanyAndStatusOrderByCreatedAtDesc(company, normalizedStatus)
        .stream()
        .map(this::toDto)
        .toList();
  }

  @Transactional
  public CreditLimitOverrideRequestDto createRequest(
      CreditLimitOverrideRequestCreateRequest request, String requestedBy) {
    Company company = companyContextService.requireCurrentCompany();
    String normalizedRequester = normalizeActor(requestedBy, "requestedBy");
    String normalizedReason = requireReason(request.reason(), "create");
    BigDecimal requestedAmount = resolveRequestedAmount(request);
    PackagingSlip slip = resolveSlip(company, request.packagingSlipId(), request.salesOrderId());
    SalesOrder order = resolveOrder(company, slip, request.salesOrderId());
    Dealer dealer = resolveDealer(company, request.dealerId(), order);

    if (slip != null) {
      Optional<CreditLimitOverrideRequest> existing =
          creditLimitOverrideRequestRepository.findByCompanyAndPackagingSlipAndStatus(
              company, slip, STATUS_PENDING);
      if (existing.isPresent()) {
        return toDto(existing.get());
      }
    }

    Long excludeOrderId = order != null ? order.getId() : null;
    BigDecimal exposure = resolveCurrentExposure(company, dealer, excludeOrderId);
    BigDecimal limit = dealer.getCreditLimit() == null ? BigDecimal.ZERO : dealer.getCreditLimit();
    BigDecimal requiredHeadroom = exposure.add(requestedAmount).subtract(limit);
    if (requiredHeadroom.compareTo(BigDecimal.ZERO) < 0) {
      requiredHeadroom = BigDecimal.ZERO;
    }

    CreditLimitOverrideRequest overrideRequest = new CreditLimitOverrideRequest();
    overrideRequest.setCompany(company);
    overrideRequest.setDealer(dealer);
    overrideRequest.setPackagingSlip(slip);
    overrideRequest.setSalesOrder(order);
    overrideRequest.setDispatchAmount(requestedAmount);
    overrideRequest.setCurrentExposure(exposure);
    overrideRequest.setCreditLimit(limit);
    overrideRequest.setRequiredHeadroom(requiredHeadroom);
    overrideRequest.setReason(reasonWithCode(REASON_CODE_REQUEST, normalizedReason));
    overrideRequest.setRequestedBy(normalizedRequester);
    overrideRequest.setStatus(STATUS_PENDING);
    overrideRequest.setExpiresAt(request.expiresAt());

    CreditLimitOverrideRequest saved = creditLimitOverrideRequestRepository.save(overrideRequest);
    auditOverrideLifecycle(
        AuditEvent.TRANSACTION_CREATED, saved, normalizedRequester, REASON_CODE_REQUEST);
    return toDto(saved);
  }

  @Transactional
  public CreditLimitOverrideRequestDto approveRequest(
      Long id, CreditLimitOverrideDecisionRequest request, String reviewedBy) {
    CreditLimitOverrideRequest overrideRequest = requireRequest(id);
    String normalizedReviewer = normalizeActor(reviewedBy, "reviewedBy");
    assertMakerCheckerBoundary(overrideRequest, normalizedReviewer);
    String normalizedReason = resolveDecisionReason(overrideRequest, request, "approve");
    if (!STATUS_PENDING.equals(normalizeStatus(overrideRequest.getStatus()))) {
      throw new ApplicationException(
          ErrorCode.BUSINESS_INVALID_STATE, "Only pending override requests can be approved");
    }
    if (overrideRequest.getReviewedAt() != null
        || StringUtils.hasText(overrideRequest.getReviewedBy())) {
      throw new ApplicationException(
          ErrorCode.BUSINESS_INVALID_STATE,
          "Override request review metadata is immutable once set");
    }
    overrideRequest.setStatus(STATUS_APPROVED);
    overrideRequest.setReviewedBy(normalizedReviewer);
    overrideRequest.setReviewedAt(CompanyTime.now(overrideRequest.getCompany()));
    overrideRequest.setReason(reasonWithCode(REASON_CODE_APPROVED, normalizedReason));
    if (request != null && request.expiresAt() != null) {
      overrideRequest.setExpiresAt(request.expiresAt());
    } else if (overrideRequest.getExpiresAt() == null) {
      overrideRequest.setExpiresAt(
          CompanyTime.now(overrideRequest.getCompany()).plus(1, ChronoUnit.DAYS));
    }
    auditOverrideLifecycle(
        AuditEvent.TRANSACTION_APPROVED, overrideRequest, normalizedReviewer, REASON_CODE_APPROVED);
    return toDto(overrideRequest);
  }

  @Transactional
  public CreditLimitOverrideRequestDto rejectRequest(
      Long id, CreditLimitOverrideDecisionRequest request, String reviewedBy) {
    CreditLimitOverrideRequest overrideRequest = requireRequest(id);
    String normalizedReviewer = normalizeActor(reviewedBy, "reviewedBy");
    assertMakerCheckerBoundary(overrideRequest, normalizedReviewer);
    String normalizedReason = resolveDecisionReason(overrideRequest, request, "reject");
    if (!STATUS_PENDING.equals(normalizeStatus(overrideRequest.getStatus()))) {
      throw new ApplicationException(
          ErrorCode.BUSINESS_INVALID_STATE, "Only pending override requests can be rejected");
    }
    if (overrideRequest.getReviewedAt() != null
        || StringUtils.hasText(overrideRequest.getReviewedBy())) {
      throw new ApplicationException(
          ErrorCode.BUSINESS_INVALID_STATE,
          "Override request review metadata is immutable once set");
    }
    overrideRequest.setStatus(STATUS_REJECTED);
    overrideRequest.setReviewedBy(normalizedReviewer);
    overrideRequest.setReviewedAt(CompanyTime.now(overrideRequest.getCompany()));
    overrideRequest.setReason(reasonWithCode(REASON_CODE_REJECTED, normalizedReason));
    auditOverrideLifecycle(
        AuditEvent.TRANSACTION_REJECTED, overrideRequest, normalizedReviewer, REASON_CODE_REJECTED);
    return toDto(overrideRequest);
  }

  @Transactional
  public boolean isOverrideApproved(
      Long overrideRequestId,
      Company company,
      Dealer dealer,
      PackagingSlip slip,
      SalesOrder order,
      BigDecimal dispatchAmount) {
    if (overrideRequestId == null) {
      return false;
    }
    CreditLimitOverrideRequest overrideRequest =
        creditLimitOverrideRequestRepository
            .findByCompanyAndId(company, overrideRequestId)
            .orElse(null);
    if (overrideRequest == null) {
      return false;
    }
    if (!STATUS_APPROVED.equalsIgnoreCase(overrideRequest.getStatus())) {
      return false;
    }
    if (!hasImmutableApprovalMetadata(overrideRequest)) {
      return false;
    }
    if (overrideRequest.getExpiresAt() != null
        && overrideRequest.getExpiresAt().isBefore(CompanyTime.now(company))) {
      overrideRequest.setStatus(STATUS_EXPIRED);
      creditLimitOverrideRequestRepository.save(overrideRequest);
      return false;
    }
    if (dealer != null
        && overrideRequest.getDealer() != null
        && !dealer.getId().equals(overrideRequest.getDealer().getId())) {
      return false;
    }
    if (slip != null
        && overrideRequest.getPackagingSlip() != null
        && !slip.getId().equals(overrideRequest.getPackagingSlip().getId())) {
      return false;
    }
    if (order != null
        && overrideRequest.getSalesOrder() != null
        && !order.getId().equals(overrideRequest.getSalesOrder().getId())) {
      return false;
    }
    if (dispatchAmount != null) {
      BigDecimal approvedDispatchAmount = overrideRequest.getDispatchAmount();
      if (approvedDispatchAmount != null
          && dispatchAmount.compareTo(approvedDispatchAmount.add(DISPATCH_AMOUNT_TOLERANCE)) > 0) {
        return false;
      }
      if (!isWithinApprovedHeadroom(company, overrideRequest, dealer, order, dispatchAmount)) {
        return false;
      }
    }
    return true;
  }

  @Transactional
  public BigDecimal approvedHeadroomForDealer(Company company, Dealer dealer) {
    if (company == null || dealer == null || dealer.getId() == null) {
      return BigDecimal.ZERO;
    }
    Instant now = CompanyTime.now(company);
    BigDecimal approvedHeadroom = BigDecimal.ZERO;
    List<CreditLimitOverrideRequest> approvedRequests =
        creditLimitOverrideRequestRepository.findApprovedByCompanyAndDealerOrderByCreatedAtDesc(
            company, dealer);
    for (CreditLimitOverrideRequest approvedRequest : approvedRequests) {
      if (!hasImmutableApprovalMetadata(approvedRequest)) {
        continue;
      }
      Instant expiresAt = approvedRequest.getExpiresAt();
      if (expiresAt != null && expiresAt.isBefore(now)) {
        approvedRequest.setStatus(STATUS_EXPIRED);
        creditLimitOverrideRequestRepository.save(approvedRequest);
        continue;
      }
      BigDecimal headroom = approvedRequest.getRequiredHeadroom();
      if (headroom != null && headroom.compareTo(BigDecimal.ZERO) > 0) {
        approvedHeadroom = approvedHeadroom.add(headroom);
      }
    }
    return approvedHeadroom;
  }

  private boolean isWithinApprovedHeadroom(
      Company company,
      CreditLimitOverrideRequest overrideRequest,
      Dealer dealer,
      SalesOrder order,
      BigDecimal dispatchAmount) {
    if (company == null || dealer == null || dealer.getId() == null || dispatchAmount == null) {
      return false;
    }
    BigDecimal creditLimit = dealer.getCreditLimit();
    if (creditLimit == null || creditLimit.compareTo(BigDecimal.ZERO) <= 0) {
      return true;
    }
    BigDecimal approvedHeadroom = overrideRequest.getRequiredHeadroom();
    if (approvedHeadroom == null) {
      return false;
    }
    Long excludeOrderId = order != null ? order.getId() : null;
    BigDecimal exposure = resolveCurrentExposure(company, dealer, excludeOrderId);
    BigDecimal requiredHeadroom = exposure.add(dispatchAmount).subtract(creditLimit);
    if (requiredHeadroom.compareTo(BigDecimal.ZERO) < 0) {
      requiredHeadroom = BigDecimal.ZERO;
    }
    BigDecimal normalizedApprovedHeadroom =
        approvedHeadroom.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO : approvedHeadroom;
    return requiredHeadroom.compareTo(normalizedApprovedHeadroom.add(HEADROOM_TOLERANCE)) <= 0;
  }

  private PackagingSlip resolveSlip(Company company, Long slipId, Long orderId) {
    if (slipId != null) {
      return packagingSlipRepository
          .findByIdAndCompany(slipId, company)
          .orElseThrow(
              () ->
                  new ApplicationException(
                      ErrorCode.VALIDATION_INVALID_REFERENCE, "Packaging slip not found"));
    }
    if (orderId != null) {
      List<PackagingSlip> slips =
          packagingSlipRepository.findAllByCompanyAndSalesOrderId(company, orderId);
      if (slips.size() > 1) {
        throw new ApplicationException(
            ErrorCode.VALIDATION_INVALID_REFERENCE,
            "Multiple packaging slips found for order; provide packagingSlipId");
      }
      return slips.isEmpty() ? null : slips.get(0);
    }
    return null;
  }

  private SalesOrder resolveOrder(Company company, PackagingSlip slip, Long orderId) {
    if (orderId != null) {
      return salesOrderRepository.findByCompanyAndId(company, orderId).orElse(null);
    }
    return slip != null ? slip.getSalesOrder() : null;
  }

  private Dealer resolveDealer(Company company, Long dealerId, SalesOrder order) {
    if (dealerId != null) {
      return dealerRepository
          .findByCompanyAndId(company, dealerId)
          .orElseThrow(
              () ->
                  new ApplicationException(
                      ErrorCode.VALIDATION_INVALID_REFERENCE, "Dealer not found"));
    }
    Dealer dealer = order != null ? order.getDealer() : null;
    if (dealer == null) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD,
          "Dealer is required for credit override request");
    }
    return dealer;
  }

  private CreditLimitOverrideRequest requireRequest(Long id) {
    Company company = companyContextService.requireCurrentCompany();
    return creditLimitOverrideRequestRepository
        .findByCompanyAndId(company, id)
        .orElseThrow(
            () ->
                new ApplicationException(
                    ErrorCode.BUSINESS_ENTITY_NOT_FOUND, "Credit override request not found"));
  }

  private String normalizeStatus(String status) {
    if (!StringUtils.hasText(status)) {
      return "";
    }
    return status.trim().toUpperCase(Locale.ROOT);
  }

  private String normalizeActor(String actor, String field) {
    if (!StringUtils.hasText(actor)) {
      throw new ApplicationException(
              ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD, field + " is required")
          .withDetail("field", field)
          .withDetail("resourceType", "credit_limit_override_request");
    }
    return actor.trim();
  }

  private BigDecimal resolveRequestedAmount(CreditLimitOverrideRequestCreateRequest request) {
    BigDecimal requestedAmount = request.requestedAmount();
    BigDecimal legacyDispatchAmount = request.dispatchAmount();
    if (requestedAmount != null && requestedAmount.compareTo(BigDecimal.ZERO) <= 0) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_INVALID_INPUT, "requestedAmount must be greater than zero");
    }
    if (legacyDispatchAmount != null && legacyDispatchAmount.compareTo(BigDecimal.ZERO) <= 0) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_INVALID_INPUT, "dispatchAmount must be greater than zero");
    }
    if (requestedAmount == null && legacyDispatchAmount == null) {
      throw new ApplicationException(
              ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD,
              "requestedAmount is required (dispatchAmount is a legacy alias)")
          .withDetail("field", "requestedAmount");
    }
    if (requestedAmount != null
        && legacyDispatchAmount != null
        && requestedAmount.compareTo(legacyDispatchAmount) != 0) {
      throw new ApplicationException(
              ErrorCode.VALIDATION_INVALID_INPUT,
              "requestedAmount and dispatchAmount must match when both are provided")
          .withDetail("requestedAmount", requestedAmount)
          .withDetail("dispatchAmount", legacyDispatchAmount);
    }
    return requestedAmount != null ? requestedAmount : legacyDispatchAmount;
  }

  private String requireReason(String reason, String operation) {
    if (!StringUtils.hasText(reason)) {
      throw new ApplicationException(
              ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD,
              "Credit override request " + operation + " requires reason")
          .withDetail("field", "reason")
          .withDetail("operation", operation)
          .withDetail("resourceType", "credit_limit_override_request");
    }
    return reason.trim();
  }

  private void assertMakerCheckerBoundary(
      CreditLimitOverrideRequest overrideRequest, String reviewer) {
    String requester = normalizeActor(overrideRequest.getRequestedBy(), "requestedBy");
    if (requester.equalsIgnoreCase(reviewer)) {
      throw new ApplicationException(
              ErrorCode.VALIDATION_INVALID_INPUT,
              "Maker-checker violation: requester and reviewer cannot be the same actor")
          .withDetail("resourceType", "credit_limit_override_request")
          .withDetail("requestedBy", requester)
          .withDetail("reviewedBy", reviewer);
    }
  }

  private boolean hasImmutableApprovalMetadata(CreditLimitOverrideRequest overrideRequest) {
    if (!StringUtils.hasText(overrideRequest.getRequestedBy())
        || !StringUtils.hasText(overrideRequest.getReviewedBy())
        || overrideRequest.getReviewedAt() == null) {
      return false;
    }
    if (overrideRequest
        .getRequestedBy()
        .trim()
        .equalsIgnoreCase(overrideRequest.getReviewedBy().trim())) {
      return false;
    }
    String reasonCode = extractReasonCode(overrideRequest.getReason());
    if (!StringUtils.hasText(reasonCode)) {
      return true;
    }
    return REASON_CODE_APPROVED.equals(reasonCode);
  }

  private String reasonWithCode(String reasonCode, String reason) {
    String normalizedReason = StringUtils.hasText(reason) ? reason.trim() : "";
    if (normalizedReason.startsWith("[") && normalizedReason.contains("]")) {
      int markerEnd = normalizedReason.indexOf(']');
      if (markerEnd > 0 && markerEnd < normalizedReason.length() - 1) {
        normalizedReason = normalizedReason.substring(markerEnd + 1).trim();
      }
    }
    return "[" + reasonCode + "] " + normalizedReason;
  }

  private String extractReasonCode(String reason) {
    if (!StringUtils.hasText(reason)) {
      return "";
    }
    String value = reason.trim();
    if (!value.startsWith("[") || !value.contains("]")) {
      return "";
    }
    int markerEnd = value.indexOf(']');
    if (markerEnd <= 1) {
      return "";
    }
    return value.substring(1, markerEnd).trim().toUpperCase(Locale.ROOT);
  }

  private String resolveDecisionReason(
      CreditLimitOverrideRequest overrideRequest,
      CreditLimitOverrideDecisionRequest request,
      String operation) {
    String incomingReason =
        request == null || request.reason() == null ? null : request.reason().trim();
    if (StringUtils.hasText(incomingReason)) {
      return incomingReason;
    }
    String existingReason = stripReasonCodePrefix(overrideRequest.getReason());
    if (StringUtils.hasText(existingReason)) {
      return existingReason;
    }
    if ("reject".equalsIgnoreCase(operation)) {
      return "Rejected via legacy decision payload";
    }
    return "Approved via legacy decision payload";
  }

  private String stripReasonCodePrefix(String reason) {
    if (!StringUtils.hasText(reason)) {
      return "";
    }
    String value = reason.trim();
    if (!value.startsWith("[") || !value.contains("]")) {
      return value;
    }
    int markerEnd = value.indexOf(']');
    if (markerEnd < 0 || markerEnd >= value.length() - 1) {
      return "";
    }
    return value.substring(markerEnd + 1).trim();
  }

  private void auditOverrideLifecycle(
      AuditEvent event,
      CreditLimitOverrideRequest overrideRequest,
      String actor,
      String reasonCode) {
    Map<String, String> metadata = new HashMap<>();
    metadata.put("resourceType", "credit_limit_override_request");
    metadata.put("actor", actor);
    metadata.put("reasonCode", reasonCode);
    metadata.put("status", normalizeStatus(overrideRequest.getStatus()));
    if (StringUtils.hasText(overrideRequest.getReason())) {
      metadata.put("reason", overrideRequest.getReason().trim());
    }
    if (overrideRequest.getId() != null) {
      metadata.put("overrideRequestId", overrideRequest.getId().toString());
    }
    if (overrideRequest.getPublicId() != null) {
      metadata.put("overrideRequestPublicId", overrideRequest.getPublicId().toString());
    }
    if (overrideRequest.getDealer() != null && overrideRequest.getDealer().getId() != null) {
      metadata.put("dealerId", overrideRequest.getDealer().getId().toString());
    }
    if (overrideRequest.getPackagingSlip() != null
        && overrideRequest.getPackagingSlip().getId() != null) {
      metadata.put("packagingSlipId", overrideRequest.getPackagingSlip().getId().toString());
    }
    if (overrideRequest.getSalesOrder() != null
        && overrideRequest.getSalesOrder().getId() != null) {
      metadata.put("salesOrderId", overrideRequest.getSalesOrder().getId().toString());
    }
    if (overrideRequest.getDispatchAmount() != null) {
      metadata.put("dispatchAmount", overrideRequest.getDispatchAmount().toPlainString());
    }
    if (overrideRequest.getCurrentExposure() != null) {
      metadata.put("currentExposure", overrideRequest.getCurrentExposure().toPlainString());
    }
    if (overrideRequest.getCreditLimit() != null) {
      metadata.put("creditLimit", overrideRequest.getCreditLimit().toPlainString());
    }
    if (overrideRequest.getRequiredHeadroom() != null) {
      metadata.put("requiredHeadroom", overrideRequest.getRequiredHeadroom().toPlainString());
    }
    if (StringUtils.hasText(overrideRequest.getRequestedBy())) {
      metadata.put("requestedBy", overrideRequest.getRequestedBy().trim());
    }
    if (StringUtils.hasText(overrideRequest.getReviewedBy())) {
      metadata.put("reviewedBy", overrideRequest.getReviewedBy().trim());
    }
    if (overrideRequest.getCreatedAt() != null) {
      metadata.put("createdAt", overrideRequest.getCreatedAt().toString());
    }
    if (overrideRequest.getReviewedAt() != null) {
      metadata.put("reviewedAt", overrideRequest.getReviewedAt().toString());
    }
    if (overrideRequest.getExpiresAt() != null) {
      metadata.put("expiresAt", overrideRequest.getExpiresAt().toString());
    }
    auditService.logSuccess(event, metadata);
  }

  private BigDecimal resolveCurrentExposure(Company company, Dealer dealer, Long excludeOrderId) {
    BigDecimal outstandingBalance = safe(dealerLedgerService.currentBalance(dealer.getId()));
    BigDecimal pendingOrderExposure =
        safe(
            salesOrderRepository.sumPendingCreditExposureByCompanyAndDealer(
                company,
                dealer,
                SalesOrderCreditExposurePolicy.pendingCreditExposureStatuses(),
                excludeOrderId));
    return outstandingBalance.add(pendingOrderExposure);
  }

  private BigDecimal safe(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  private CreditLimitOverrideRequestDto toDto(CreditLimitOverrideRequest request) {
    Dealer dealer = request.getDealer();
    return new CreditLimitOverrideRequestDto(
        request.getId(),
        request.getPublicId(),
        dealer != null ? dealer.getId() : null,
        dealer != null ? dealer.getName() : null,
        request.getPackagingSlip() != null ? request.getPackagingSlip().getId() : null,
        request.getSalesOrder() != null ? request.getSalesOrder().getId() : null,
        request.getDispatchAmount(),
        request.getDispatchAmount(),
        request.getCurrentExposure(),
        request.getCreditLimit(),
        request.getRequiredHeadroom(),
        request.getStatus(),
        request.getReason(),
        request.getRequestedBy(),
        request.getReviewedBy(),
        request.getReviewedAt(),
        request.getExpiresAt(),
        request.getCreatedAt());
  }
}
