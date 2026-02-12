package com.bigbrightpaints.erp.modules.sales.service;

import com.bigbrightpaints.erp.core.util.CompanyTime;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
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
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class CreditLimitOverrideService {

    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_APPROVED = "APPROVED";
    private static final String STATUS_REJECTED = "REJECTED";
    private static final String STATUS_EXPIRED = "EXPIRED";

    private final CompanyContextService companyContextService;
    private final CreditLimitOverrideRequestRepository creditLimitOverrideRequestRepository;
    private final DealerRepository dealerRepository;
    private final SalesOrderRepository salesOrderRepository;
    private final PackagingSlipRepository packagingSlipRepository;
    private final DealerLedgerService dealerLedgerService;

    public CreditLimitOverrideService(CompanyContextService companyContextService,
                                      CreditLimitOverrideRequestRepository creditLimitOverrideRequestRepository,
                                      DealerRepository dealerRepository,
                                      SalesOrderRepository salesOrderRepository,
                                      PackagingSlipRepository packagingSlipRepository,
                                      DealerLedgerService dealerLedgerService) {
        this.companyContextService = companyContextService;
        this.creditLimitOverrideRequestRepository = creditLimitOverrideRequestRepository;
        this.dealerRepository = dealerRepository;
        this.salesOrderRepository = salesOrderRepository;
        this.packagingSlipRepository = packagingSlipRepository;
        this.dealerLedgerService = dealerLedgerService;
    }

    public List<CreditLimitOverrideRequestDto> listRequests(String status) {
        Company company = companyContextService.requireCurrentCompany();
        String normalizedStatus = normalizeStatus(status);
        if (!StringUtils.hasText(normalizedStatus)) {
            return creditLimitOverrideRequestRepository.findByCompanyOrderByCreatedAtDesc(company)
                    .stream()
                    .map(this::toDto)
                    .toList();
        }
        return creditLimitOverrideRequestRepository.findByCompanyAndStatusOrderByCreatedAtDesc(
                        company, normalizedStatus)
                .stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional
    public CreditLimitOverrideRequestDto createRequest(CreditLimitOverrideRequestCreateRequest request, String requestedBy) {
        Company company = companyContextService.requireCurrentCompany();
        PackagingSlip slip = resolveSlip(company, request.packagingSlipId(), request.salesOrderId());
        SalesOrder order = resolveOrder(company, slip, request.salesOrderId());
        if (slip == null && order == null) {
            throw new ApplicationException(ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD,
                    "packagingSlipId or salesOrderId is required");
        }
        Dealer dealer = resolveDealer(company, request.dealerId(), order);

        if (request.dispatchAmount() == null || request.dispatchAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "dispatchAmount must be greater than zero");
        }

        if (slip != null) {
            Optional<CreditLimitOverrideRequest> existing = creditLimitOverrideRequestRepository
                    .findByCompanyAndPackagingSlipAndStatus(company, slip, STATUS_PENDING);
            if (existing.isPresent()) {
                return toDto(existing.get());
            }
        }

        BigDecimal exposure = dealerLedgerService.currentBalance(dealer.getId());
        BigDecimal limit = dealer.getCreditLimit() == null ? BigDecimal.ZERO : dealer.getCreditLimit();
        BigDecimal requiredHeadroom = exposure.add(request.dispatchAmount()).subtract(limit);
        if (requiredHeadroom.compareTo(BigDecimal.ZERO) < 0) {
            requiredHeadroom = BigDecimal.ZERO;
        }

        CreditLimitOverrideRequest overrideRequest = new CreditLimitOverrideRequest();
        overrideRequest.setCompany(company);
        overrideRequest.setDealer(dealer);
        overrideRequest.setPackagingSlip(slip);
        overrideRequest.setSalesOrder(order);
        overrideRequest.setDispatchAmount(request.dispatchAmount());
        overrideRequest.setCurrentExposure(exposure);
        overrideRequest.setCreditLimit(limit);
        overrideRequest.setRequiredHeadroom(requiredHeadroom);
        overrideRequest.setReason(request.reason());
        overrideRequest.setRequestedBy(requestedBy);
        overrideRequest.setStatus(STATUS_PENDING);
        overrideRequest.setExpiresAt(request.expiresAt());

        return toDto(creditLimitOverrideRequestRepository.save(overrideRequest));
    }

    @Transactional
    public CreditLimitOverrideRequestDto approveRequest(Long id, CreditLimitOverrideDecisionRequest request, String reviewedBy) {
        CreditLimitOverrideRequest overrideRequest = requireRequest(id);
        if (!STATUS_PENDING.equals(normalizeStatus(overrideRequest.getStatus()))) {
            throw new ApplicationException(ErrorCode.BUSINESS_INVALID_STATE,
                    "Only pending override requests can be approved");
        }
        overrideRequest.setStatus(STATUS_APPROVED);
        overrideRequest.setReviewedBy(reviewedBy);
        overrideRequest.setReviewedAt(CompanyTime.now(overrideRequest.getCompany()));
        if (request != null && request.reason() != null && !request.reason().isBlank()) {
            overrideRequest.setReason(request.reason());
        }
        if (request != null && request.expiresAt() != null) {
            overrideRequest.setExpiresAt(request.expiresAt());
        } else if (overrideRequest.getExpiresAt() == null) {
            overrideRequest.setExpiresAt(CompanyTime.now(overrideRequest.getCompany()).plus(1, ChronoUnit.DAYS));
        }
        return toDto(overrideRequest);
    }

    @Transactional
    public CreditLimitOverrideRequestDto rejectRequest(Long id, CreditLimitOverrideDecisionRequest request, String reviewedBy) {
        CreditLimitOverrideRequest overrideRequest = requireRequest(id);
        if (!STATUS_PENDING.equals(normalizeStatus(overrideRequest.getStatus()))) {
            throw new ApplicationException(ErrorCode.BUSINESS_INVALID_STATE,
                    "Only pending override requests can be rejected");
        }
        overrideRequest.setStatus(STATUS_REJECTED);
        overrideRequest.setReviewedBy(reviewedBy);
        overrideRequest.setReviewedAt(CompanyTime.now(overrideRequest.getCompany()));
        if (request != null && request.reason() != null && !request.reason().isBlank()) {
            overrideRequest.setReason(request.reason());
        }
        return toDto(overrideRequest);
    }

    @Transactional
    public boolean isOverrideApproved(Long overrideRequestId,
                                      Company company,
                                      Dealer dealer,
                                      PackagingSlip slip,
                                      SalesOrder order,
                                      BigDecimal dispatchAmount) {
        if (overrideRequestId == null) {
            return false;
        }
        CreditLimitOverrideRequest overrideRequest = creditLimitOverrideRequestRepository
                .findByCompanyAndId(company, overrideRequestId)
                .orElse(null);
        if (overrideRequest == null) {
            return false;
        }
        if (!STATUS_APPROVED.equalsIgnoreCase(overrideRequest.getStatus())) {
            return false;
        }
        if (overrideRequest.getExpiresAt() != null && overrideRequest.getExpiresAt().isBefore(CompanyTime.now(company))) {
            overrideRequest.setStatus(STATUS_EXPIRED);
            creditLimitOverrideRequestRepository.save(overrideRequest);
            return false;
        }
        if (dealer != null && overrideRequest.getDealer() != null
                && !dealer.getId().equals(overrideRequest.getDealer().getId())) {
            return false;
        }
        if (slip != null && overrideRequest.getPackagingSlip() != null
                && !slip.getId().equals(overrideRequest.getPackagingSlip().getId())) {
            return false;
        }
        if (order != null && overrideRequest.getSalesOrder() != null
                && !order.getId().equals(overrideRequest.getSalesOrder().getId())) {
            return false;
        }
        if (dispatchAmount != null && overrideRequest.getDispatchAmount() != null
                && dispatchAmount.compareTo(overrideRequest.getDispatchAmount()) > 0) {
            return false;
        }
        return true;
    }

    private PackagingSlip resolveSlip(Company company, Long slipId, Long orderId) {
        if (slipId != null) {
            return packagingSlipRepository.findByIdAndCompany(slipId, company)
                    .orElseThrow(() -> new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE,
                            "Packaging slip not found"));
        }
        if (orderId != null) {
            List<PackagingSlip> slips = packagingSlipRepository.findAllByCompanyAndSalesOrderId(company, orderId);
            if (slips.size() > 1) {
                throw new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE,
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
            return dealerRepository.findByCompanyAndId(company, dealerId)
                    .orElseThrow(() -> new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE,
                            "Dealer not found"));
        }
        Dealer dealer = order != null ? order.getDealer() : null;
        if (dealer == null) {
            throw new ApplicationException(ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD,
                    "Dealer is required for credit override request");
        }
        return dealer;
    }

    private CreditLimitOverrideRequest requireRequest(Long id) {
        Company company = companyContextService.requireCurrentCompany();
        return creditLimitOverrideRequestRepository.findByCompanyAndId(company, id)
                .orElseThrow(() -> new ApplicationException(ErrorCode.BUSINESS_ENTITY_NOT_FOUND,
                        "Credit override request not found"));
    }

    private String normalizeStatus(String status) {
        if (!StringUtils.hasText(status)) {
            return "";
        }
        return status.trim().toUpperCase(Locale.ROOT);
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
                request.getCurrentExposure(),
                request.getCreditLimit(),
                request.getRequiredHeadroom(),
                request.getStatus(),
                request.getReason(),
                request.getRequestedBy(),
                request.getReviewedBy(),
                request.getReviewedAt(),
                request.getExpiresAt(),
                request.getCreatedAt()
        );
    }
}
