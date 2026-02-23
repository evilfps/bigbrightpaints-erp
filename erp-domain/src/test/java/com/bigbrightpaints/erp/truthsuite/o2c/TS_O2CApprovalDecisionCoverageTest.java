package com.bigbrightpaints.erp.truthsuite.o2c;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.bigbrightpaints.erp.core.audit.AuditService;
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
import com.bigbrightpaints.erp.modules.sales.service.CreditLimitOverrideService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@Tag("critical")
@ExtendWith(MockitoExtension.class)
class TS_O2CApprovalDecisionCoverageTest {

    @Mock
    private CompanyContextService companyContextService;
    @Mock
    private CreditLimitOverrideRequestRepository creditLimitOverrideRequestRepository;
    @Mock
    private DealerRepository dealerRepository;
    @Mock
    private SalesOrderRepository salesOrderRepository;
    @Mock
    private PackagingSlipRepository packagingSlipRepository;
    @Mock
    private DealerLedgerService dealerLedgerService;
    @Mock
    private AuditService auditService;

    private CreditLimitOverrideService service;
    private Company company;

    @BeforeEach
    void setUp() {
        service = new CreditLimitOverrideService(
                companyContextService,
                creditLimitOverrideRequestRepository,
                dealerRepository,
                salesOrderRepository,
                packagingSlipRepository,
                dealerLedgerService,
                auditService);

        company = new Company();
        company.setTimezone("UTC");
        lenient().when(companyContextService.requireCurrentCompany()).thenReturn(company);
    }

    @Test
    void isOverrideApprovedFailsClosedWhenMakerCheckerBoundaryDiffersOnlyByCaseAndWhitespace() {
        CreditLimitOverrideRequest request = approvedRequest(new BigDecimal("80.00"), new BigDecimal("15.00"));
        request.setRequestedBy(" maker@bbp.com ");
        request.setReviewedBy("MAKER@bbp.com");

        when(creditLimitOverrideRequestRepository.findByCompanyAndId(company, 3001L))
                .thenReturn(Optional.of(request));

        boolean approved = service.isOverrideApproved(3001L, company, null, null, null, null);

        assertThat(approved).isFalse();
    }

    @Test
    void isOverrideApprovedFailsClosedForDealerSlipAndOrderMismatches() {
        Dealer approvedDealer = dealer(42L, new BigDecimal("150.00"));
        PackagingSlip approvedSlip = slip(55L);
        SalesOrder approvedOrder = order(77L);

        CreditLimitOverrideRequest request = approvedRequest(null, new BigDecimal("10.00"));
        request.setDealer(approvedDealer);
        request.setPackagingSlip(approvedSlip);
        request.setSalesOrder(approvedOrder);

        when(creditLimitOverrideRequestRepository.findByCompanyAndId(company, 3002L))
                .thenReturn(Optional.of(request));

        assertThat(service.isOverrideApproved(3002L, company, dealer(99L, new BigDecimal("150.00")), approvedSlip, approvedOrder, null))
                .isFalse();
        assertThat(service.isOverrideApproved(3002L, company, approvedDealer, slip(99L), approvedOrder, null))
                .isFalse();
        assertThat(service.isOverrideApproved(3002L, company, approvedDealer, approvedSlip, order(99L), null))
                .isFalse();
    }

    @Test
    void isOverrideApprovedFailsClosedWhenDispatchHeadroomCannotBeEvaluated() {
        CreditLimitOverrideRequest request = approvedRequest(new BigDecimal("90.00"), new BigDecimal("12.00"));

        when(creditLimitOverrideRequestRepository.findByCompanyAndId(company, 3003L))
                .thenReturn(Optional.of(request));

        boolean approved = service.isOverrideApproved(
                3003L,
                company,
                null,
                null,
                null,
                new BigDecimal("90.00"));

        assertThat(approved).isFalse();
    }

    @Test
    void isOverrideApprovedAllowsDeterministicTolerancePathWhenApprovalContextMatches() {
        Dealer approvedDealer = dealer(42L, new BigDecimal("150.00"));
        PackagingSlip approvedSlip = slip(55L);
        SalesOrder approvedOrder = order(77L);

        CreditLimitOverrideRequest request = approvedRequest(new BigDecimal("80.00"), new BigDecimal("15.00"));
        request.setDealer(approvedDealer);
        request.setPackagingSlip(approvedSlip);
        request.setSalesOrder(approvedOrder);

        when(creditLimitOverrideRequestRepository.findByCompanyAndId(company, 3004L))
                .thenReturn(Optional.of(request));
        when(dealerLedgerService.currentBalance(42L)).thenReturn(new BigDecimal("85.00"));

        boolean approved = service.isOverrideApproved(
                3004L,
                company,
                approvedDealer,
                approvedSlip,
                approvedOrder,
                new BigDecimal("80.01"));

        assertThat(approved).isTrue();
    }

    private CreditLimitOverrideRequest approvedRequest(BigDecimal dispatchAmount, BigDecimal requiredHeadroom) {
        CreditLimitOverrideRequest request = new CreditLimitOverrideRequest();
        request.setCompany(company);
        request.setStatus("APPROVED");
        request.setRequestedBy("maker@bbp.com");
        request.setReviewedBy("checker@bbp.com");
        request.setReviewedAt(Instant.parse("2026-02-20T00:00:00Z"));
        request.setReason("[CREDIT_LIMIT_EXCEPTION_APPROVED] approved for dispatch");
        request.setDispatchAmount(dispatchAmount);
        request.setRequiredHeadroom(requiredHeadroom);
        return request;
    }

    private Dealer dealer(Long id, BigDecimal creditLimit) {
        Dealer dealer = new Dealer();
        dealer.setCreditLimit(creditLimit);
        ReflectionTestUtils.setField(dealer, "id", id);
        return dealer;
    }

    private PackagingSlip slip(Long id) {
        PackagingSlip slip = new PackagingSlip();
        ReflectionTestUtils.setField(slip, "id", id);
        return slip;
    }

    private SalesOrder order(Long id) {
        SalesOrder order = new SalesOrder();
        ReflectionTestUtils.setField(order, "id", id);
        return order;
    }
}
