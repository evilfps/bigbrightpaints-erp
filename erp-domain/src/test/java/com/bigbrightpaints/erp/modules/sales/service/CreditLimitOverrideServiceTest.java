package com.bigbrightpaints.erp.modules.sales.service;

import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.modules.accounting.service.DealerLedgerService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlipRepository;
import com.bigbrightpaints.erp.modules.sales.domain.CreditLimitOverrideRequest;
import com.bigbrightpaints.erp.modules.sales.domain.CreditLimitOverrideRequestRepository;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrderRepository;
import com.bigbrightpaints.erp.modules.sales.dto.CreditLimitOverrideDecisionRequest;
import com.bigbrightpaints.erp.modules.sales.dto.CreditLimitOverrideRequestDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CreditLimitOverrideServiceTest {

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
                auditService
        );
        company = new Company();
        company.setTimezone("UTC");
        lenient().when(companyContextService.requireCurrentCompany()).thenReturn(company);
    }

    @Test
    void listRequests_trimsAndNormalizesStatusFilter() {
        CreditLimitOverrideRequest request = new CreditLimitOverrideRequest();
        request.setCompany(company);
        request.setStatus("PENDING");

        when(creditLimitOverrideRequestRepository.findByCompanyAndStatusOrderByCreatedAtDesc(company, "PENDING"))
                .thenReturn(List.of(request));

        List<CreditLimitOverrideRequestDto> result = service.listRequests(" pending ");

        assertThat(result).hasSize(1);
        verify(creditLimitOverrideRequestRepository)
                .findByCompanyAndStatusOrderByCreatedAtDesc(company, "PENDING");
    }

    @Test
    void approveRequest_acceptsTrimmedPendingStatus() {
        CreditLimitOverrideRequest request = new CreditLimitOverrideRequest();
        request.setCompany(company);
        request.setStatus(" pending ");
        request.setRequestedBy("maker@bbp.com");

        when(creditLimitOverrideRequestRepository.findByCompanyAndId(company, 11L))
                .thenReturn(Optional.of(request));

        CreditLimitOverrideRequestDto response = service.approveRequest(
                11L,
                new CreditLimitOverrideDecisionRequest("Approved after review", null),
                "admin@bbp.com");

        assertThat(response.status()).isEqualTo("APPROVED");
        assertThat(request.getStatus()).isEqualTo("APPROVED");
    }

    @Test
    void approveRequest_allowsMissingDecisionReasonWithLegacyFallback() {
        CreditLimitOverrideRequest request = new CreditLimitOverrideRequest();
        request.setCompany(company);
        request.setStatus("PENDING");
        request.setRequestedBy("maker@bbp.com");
        request.setReason("Need urgent dispatch headroom");

        when(creditLimitOverrideRequestRepository.findByCompanyAndId(company, 12L))
                .thenReturn(Optional.of(request));

        CreditLimitOverrideRequestDto response = service.approveRequest(
                12L,
                new CreditLimitOverrideDecisionRequest(null, null),
                "checker@bbp.com");

        assertThat(response.status()).isEqualTo("APPROVED");
        assertThat(request.getReason()).contains("[CREDIT_LIMIT_EXCEPTION_APPROVED]");
        assertThat(request.getReason()).contains("Need urgent dispatch headroom");
    }

    @Test
    void rejectRequest_allowsMissingDecisionReasonWithDefaultFallback() {
        CreditLimitOverrideRequest request = new CreditLimitOverrideRequest();
        request.setCompany(company);
        request.setStatus("PENDING");
        request.setRequestedBy("maker@bbp.com");

        when(creditLimitOverrideRequestRepository.findByCompanyAndId(company, 13L))
                .thenReturn(Optional.of(request));

        CreditLimitOverrideRequestDto response = service.rejectRequest(
                13L,
                new CreditLimitOverrideDecisionRequest(null, null),
                "checker@bbp.com");

        assertThat(response.status()).isEqualTo("REJECTED");
        assertThat(request.getReason()).contains("[CREDIT_LIMIT_EXCEPTION_REJECTED]");
        assertThat(request.getReason()).contains("Rejected via legacy decision payload");
    }

    @Test
    void isOverrideApproved_allowsMinorDispatchDriftWithinToleranceWhenHeadroomIsValid() {
        CreditLimitOverrideRequest request = new CreditLimitOverrideRequest();
        request.setCompany(company);
        request.setStatus("APPROVED");
        request.setDispatchAmount(new BigDecimal("120.00"));
        request.setRequiredHeadroom(new BigDecimal("50.00"));
        request.setRequestedBy("maker@bbp.com");
        request.setReviewedBy("checker@bbp.com");
        request.setReviewedAt(Instant.parse("2026-02-20T00:00:00Z"));
        request.setReason("[CREDIT_LIMIT_EXCEPTION_APPROVED] Approved for urgent dispatch");

        Dealer dealer = new Dealer();
        dealer.setCreditLimit(new BigDecimal("200.00"));
        org.springframework.test.util.ReflectionTestUtils.setField(dealer, "id", 42L);

        when(creditLimitOverrideRequestRepository.findByCompanyAndId(company, 21L))
                .thenReturn(Optional.of(request));
        when(dealerLedgerService.currentBalance(42L)).thenReturn(new BigDecimal("120.00"));

        boolean approved = service.isOverrideApproved(
                21L,
                company,
                dealer,
                null,
                null,
                new BigDecimal("120.01"));

        assertThat(approved).isTrue();
    }

    @Test
    void isOverrideApproved_rejectsDispatchWhenApprovedHeadroomIsExceeded() {
        CreditLimitOverrideRequest request = new CreditLimitOverrideRequest();
        request.setCompany(company);
        request.setStatus("APPROVED");
        request.setDispatchAmount(new BigDecimal("120.00"));
        request.setRequiredHeadroom(new BigDecimal("20.00"));
        request.setRequestedBy("maker@bbp.com");
        request.setReviewedBy("checker@bbp.com");
        request.setReviewedAt(Instant.parse("2026-02-20T00:00:00Z"));
        request.setReason("[CREDIT_LIMIT_EXCEPTION_APPROVED] Approved for urgent dispatch");

        Dealer dealer = new Dealer();
        dealer.setCreditLimit(new BigDecimal("200.00"));
        org.springframework.test.util.ReflectionTestUtils.setField(dealer, "id", 42L);

        when(creditLimitOverrideRequestRepository.findByCompanyAndId(company, 22L))
                .thenReturn(Optional.of(request));
        when(dealerLedgerService.currentBalance(42L)).thenReturn(new BigDecimal("120.00"));

        boolean approved = service.isOverrideApproved(
                22L,
                company,
                dealer,
                null,
                null,
                new BigDecimal("120.01"));

        assertThat(approved).isFalse();
    }

    @Test
    void isOverrideApproved_rejectsMateriallyHigherDispatchEvenWhenHeadroomWouldAllow() {
        CreditLimitOverrideRequest request = new CreditLimitOverrideRequest();
        request.setCompany(company);
        request.setStatus("APPROVED");
        request.setDispatchAmount(new BigDecimal("120.00"));
        request.setRequiredHeadroom(new BigDecimal("80.00"));
        request.setRequestedBy("maker@bbp.com");
        request.setReviewedBy("checker@bbp.com");
        request.setReviewedAt(Instant.parse("2026-02-20T00:00:00Z"));
        request.setReason("[CREDIT_LIMIT_EXCEPTION_APPROVED] Approved for urgent dispatch");

        Dealer dealer = new Dealer();
        dealer.setCreditLimit(new BigDecimal("200.00"));
        org.springframework.test.util.ReflectionTestUtils.setField(dealer, "id", 42L);

        when(creditLimitOverrideRequestRepository.findByCompanyAndId(company, 23L))
                .thenReturn(Optional.of(request));

        boolean approved = service.isOverrideApproved(
                23L,
                company,
                dealer,
                null,
                null,
                new BigDecimal("150.00"));

        assertThat(approved).isFalse();
    }

    @Test
    void isOverrideApproved_allowsLegacyApprovedRecordWithoutReasonCodePrefix() {
        CreditLimitOverrideRequest request = new CreditLimitOverrideRequest();
        request.setCompany(company);
        request.setStatus("APPROVED");
        request.setDispatchAmount(new BigDecimal("120.00"));
        request.setRequiredHeadroom(new BigDecimal("80.00"));
        request.setRequestedBy("maker@bbp.com");
        request.setReviewedBy("checker@bbp.com");
        request.setReviewedAt(Instant.parse("2026-02-20T00:00:00Z"));
        request.setReason("Approved by finance lead");

        Dealer dealer = new Dealer();
        dealer.setCreditLimit(new BigDecimal("200.00"));
        org.springframework.test.util.ReflectionTestUtils.setField(dealer, "id", 42L);

        when(creditLimitOverrideRequestRepository.findByCompanyAndId(company, 25L))
                .thenReturn(Optional.of(request));
        when(dealerLedgerService.currentBalance(42L)).thenReturn(new BigDecimal("100.00"));

        boolean approved = service.isOverrideApproved(
                25L,
                company,
                dealer,
                null,
                null,
                new BigDecimal("120.00"));

        assertThat(approved).isTrue();
    }

    @Test
    void isOverrideApproved_failsClosedWhenMakerCheckerMetadataIsMissing() {
        CreditLimitOverrideRequest request = new CreditLimitOverrideRequest();
        request.setCompany(company);
        request.setStatus("APPROVED");
        request.setDispatchAmount(new BigDecimal("120.00"));
        request.setRequiredHeadroom(new BigDecimal("80.00"));
        request.setRequestedBy("maker@bbp.com");
        request.setReviewedBy("maker@bbp.com");
        request.setReason("[CREDIT_LIMIT_EXCEPTION_APPROVED] Approved for urgent dispatch");

        Dealer dealer = new Dealer();
        dealer.setCreditLimit(new BigDecimal("200.00"));
        org.springframework.test.util.ReflectionTestUtils.setField(dealer, "id", 42L);

        when(creditLimitOverrideRequestRepository.findByCompanyAndId(company, 24L))
                .thenReturn(Optional.of(request));

        boolean approved = service.isOverrideApproved(
                24L,
                company,
                dealer,
                null,
                null,
                new BigDecimal("100.00"));

        assertThat(approved).isFalse();
    }
}
