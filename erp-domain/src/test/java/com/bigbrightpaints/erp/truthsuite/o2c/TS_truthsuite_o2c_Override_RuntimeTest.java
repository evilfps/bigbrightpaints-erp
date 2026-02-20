package com.bigbrightpaints.erp.truthsuite.o2c;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditService;
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
import com.bigbrightpaints.erp.modules.sales.service.CreditLimitOverrideService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@Tag("critical")
@ExtendWith(MockitoExtension.class)
class TS_truthsuite_o2c_Override_RuntimeTest {

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
    void legacyApprovedRecordsRemainValidWhenMakerCheckerMetadataIsComplete() {
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
        ReflectionTestUtils.setField(dealer, "id", 42L);

        when(creditLimitOverrideRequestRepository.findByCompanyAndId(company, 1001L))
                .thenReturn(Optional.of(request));
        when(dealerLedgerService.currentBalance(42L)).thenReturn(new BigDecimal("100.00"));

        boolean approved = service.isOverrideApproved(
                1001L,
                company,
                dealer,
                null,
                null,
                new BigDecimal("120.00"));

        assertThat(approved).isTrue();
    }

    @Test
    void approvedRecordsFailClosedWhenMakerCheckerMetadataIsInvalid() {
        CreditLimitOverrideRequest request = new CreditLimitOverrideRequest();
        request.setCompany(company);
        request.setStatus("APPROVED");
        request.setDispatchAmount(new BigDecimal("100.00"));
        request.setRequiredHeadroom(new BigDecimal("40.00"));
        request.setRequestedBy("maker@bbp.com");
        request.setReviewedBy("maker@bbp.com");
        request.setReviewedAt(Instant.parse("2026-02-20T00:00:00Z"));
        request.setReason("[CREDIT_LIMIT_EXCEPTION_APPROVED] reviewed");

        Dealer dealer = new Dealer();
        dealer.setCreditLimit(new BigDecimal("200.00"));
        ReflectionTestUtils.setField(dealer, "id", 42L);

        when(creditLimitOverrideRequestRepository.findByCompanyAndId(company, 1002L))
                .thenReturn(Optional.of(request));

        boolean approved = service.isOverrideApproved(
                1002L,
                company,
                dealer,
                null,
                null,
                new BigDecimal("100.00"));

        assertThat(approved).isFalse();
    }

    @Test
    void approveUsesLegacyDecisionFallbackWhenReasonMissing() {
        CreditLimitOverrideRequest request = new CreditLimitOverrideRequest();
        request.setCompany(company);
        request.setStatus("PENDING");
        request.setRequestedBy("maker@bbp.com");
        request.setReason("[CREDIT_LIMIT_EXCEPTION_REQUESTED] Need urgent dispatch headroom");

        when(creditLimitOverrideRequestRepository.findByCompanyAndId(company, 1003L))
                .thenReturn(Optional.of(request));

        CreditLimitOverrideRequestDto response = service.approveRequest(
                1003L,
                new CreditLimitOverrideDecisionRequest(null, null),
                "checker@bbp.com");

        assertThat(response.status()).isEqualTo("APPROVED");
        assertThat(request.getReason()).contains("[CREDIT_LIMIT_EXCEPTION_APPROVED]");
        assertThat(request.getReason()).contains("Need urgent dispatch headroom");
    }

    @Test
    void approveUsesDefaultDecisionFallbackWhenNoLegacyReasonExists() {
        CreditLimitOverrideRequest request = new CreditLimitOverrideRequest();
        request.setCompany(company);
        request.setStatus("PENDING");
        request.setRequestedBy("maker@bbp.com");
        request.setReason("   ");

        when(creditLimitOverrideRequestRepository.findByCompanyAndId(company, 1010L))
                .thenReturn(Optional.of(request));

        CreditLimitOverrideRequestDto response = service.approveRequest(
                1010L,
                new CreditLimitOverrideDecisionRequest(null, null),
                "checker@bbp.com");

        assertThat(response.status()).isEqualTo("APPROVED");
        assertThat(request.getReason()).contains("[CREDIT_LIMIT_EXCEPTION_APPROVED]");
        assertThat(request.getReason()).contains("Approved via legacy decision payload");
    }

    @Test
    void rejectUsesDefaultDecisionFallbackWhenLegacyPayloadOmitsReason() {
        CreditLimitOverrideRequest request = new CreditLimitOverrideRequest();
        request.setCompany(company);
        request.setStatus("PENDING");
        request.setRequestedBy("maker@bbp.com");

        when(creditLimitOverrideRequestRepository.findByCompanyAndId(company, 1004L))
                .thenReturn(Optional.of(request));

        CreditLimitOverrideRequestDto response = service.rejectRequest(
                1004L,
                new CreditLimitOverrideDecisionRequest(null, null),
                "checker@bbp.com");

        assertThat(response.status()).isEqualTo("REJECTED");
        assertThat(request.getReason()).contains("[CREDIT_LIMIT_EXCEPTION_REJECTED]");
        assertThat(request.getReason()).contains("Rejected via legacy decision payload");
    }

    @Test
    void createRequestNormalizesInputsAndCapturesAuditMetadata() {
        Dealer dealer = new Dealer();
        dealer.setCreditLimit(new BigDecimal("200.00"));
        ReflectionTestUtils.setField(dealer, "id", 42L);

        SalesOrder order = new SalesOrder();
        order.setCompany(company);
        order.setDealer(dealer);
        ReflectionTestUtils.setField(order, "id", 71L);

        PackagingSlip slip = new PackagingSlip();
        slip.setCompany(company);
        slip.setSalesOrder(order);
        ReflectionTestUtils.setField(slip, "id", 55L);

        when(packagingSlipRepository.findByIdAndCompany(55L, company)).thenReturn(Optional.of(slip));
        when(creditLimitOverrideRequestRepository.findByCompanyAndPackagingSlipAndStatus(
                company, slip, "PENDING")).thenReturn(Optional.empty());
        when(dealerLedgerService.currentBalance(42L)).thenReturn(new BigDecimal("120.00"));
        when(creditLimitOverrideRequestRepository.save(org.mockito.ArgumentMatchers.any(CreditLimitOverrideRequest.class)))
                .thenAnswer(invocation -> {
                    CreditLimitOverrideRequest entity = invocation.getArgument(0);
                    ReflectionTestUtils.setField(entity, "id", 700L);
                    ReflectionTestUtils.setField(entity, "publicId", UUID.fromString("00000000-0000-0000-0000-000000000700"));
                    ReflectionTestUtils.setField(entity, "createdAt", Instant.parse("2026-02-20T01:00:00Z"));
                    return entity;
                });

        CreditLimitOverrideRequestDto response = service.createRequest(
                new CreditLimitOverrideRequestCreateRequest(
                        null,
                        55L,
                        null,
                        new BigDecimal("50.00"),
                        "  Need urgent dispatch headroom  ",
                        Instant.parse("2026-02-22T01:00:00Z")
                ),
                " ops-admin@bbp.com ");

        assertThat(response.status()).isEqualTo("PENDING");
        assertThat(response.requestedBy()).isEqualTo("ops-admin@bbp.com");
        assertThat(response.reason()).contains("[CREDIT_LIMIT_EXCEPTION_REQUESTED]");
        assertThat(response.reason()).contains("Need urgent dispatch headroom");

        ArgumentCaptor<Map<String, String>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditService).logSuccess(eq(AuditEvent.TRANSACTION_CREATED), metadataCaptor.capture());
        Map<String, String> metadata = metadataCaptor.getValue();
        assertEquals("credit_limit_override_request", metadata.get("resourceType"));
        assertEquals("ops-admin@bbp.com", metadata.get("actor"));
        assertEquals("CREDIT_LIMIT_EXCEPTION_REQUESTED", metadata.get("reasonCode"));
        assertEquals("700", metadata.get("overrideRequestId"));
        assertEquals("42", metadata.get("dealerId"));
        assertEquals("55", metadata.get("packagingSlipId"));
        assertEquals("71", metadata.get("salesOrderId"));
        assertEquals("50.00", metadata.get("dispatchAmount"));
        assertEquals("120.00", metadata.get("currentExposure"));
        assertEquals("200.00", metadata.get("creditLimit"));
        assertEquals("0", metadata.get("requiredHeadroom"));
        assertEquals("ops-admin@bbp.com", metadata.get("requestedBy"));
        assertEquals("2026-02-20T01:00:00Z", metadata.get("createdAt"));
        assertEquals("2026-02-22T01:00:00Z", metadata.get("expiresAt"));
    }

    @Test
    void createRequestFailsWhenRequestedByIsBlank() {
        ApplicationException ex = assertThrows(
                ApplicationException.class,
                () -> service.createRequest(
                        new CreditLimitOverrideRequestCreateRequest(
                                42L,
                                null,
                                null,
                                new BigDecimal("10.00"),
                                "Need headroom",
                                null
                        ),
                        "   "
                )
        );

        assertEquals(ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD, ex.getErrorCode());
        assertThat(ex.getMessage()).contains("requestedBy is required");
    }

    @Test
    void createRequestFailsWhenReasonIsBlank() {
        ApplicationException ex = assertThrows(
                ApplicationException.class,
                () -> service.createRequest(
                        new CreditLimitOverrideRequestCreateRequest(
                                42L,
                                null,
                                null,
                                new BigDecimal("10.00"),
                                "   ",
                                null
                        ),
                        "ops-admin@bbp.com"
                )
        );

        assertEquals(ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD, ex.getErrorCode());
        assertThat(ex.getMessage()).contains("requires reason");
    }

    @Test
    void approveFailsForMakerCheckerViolation() {
        CreditLimitOverrideRequest request = new CreditLimitOverrideRequest();
        request.setCompany(company);
        request.setStatus("PENDING");
        request.setRequestedBy("maker@bbp.com");
        when(creditLimitOverrideRequestRepository.findByCompanyAndId(company, 1005L))
                .thenReturn(Optional.of(request));

        ApplicationException ex = assertThrows(
                ApplicationException.class,
                () -> service.approveRequest(
                        1005L,
                        new CreditLimitOverrideDecisionRequest("Approved", null),
                        " Maker@bbp.com ")
        );

        assertEquals(ErrorCode.VALIDATION_INVALID_INPUT, ex.getErrorCode());
        assertThat(ex.getMessage()).contains("Maker-checker violation");
    }

    @Test
    void approveFailsWhenReviewMetadataWasAlreadySet() {
        CreditLimitOverrideRequest request = new CreditLimitOverrideRequest();
        request.setCompany(company);
        request.setStatus("PENDING");
        request.setRequestedBy("maker@bbp.com");
        request.setReviewedBy("legacy-reviewer@bbp.com");
        when(creditLimitOverrideRequestRepository.findByCompanyAndId(company, 1006L))
                .thenReturn(Optional.of(request));

        ApplicationException ex = assertThrows(
                ApplicationException.class,
                () -> service.approveRequest(
                        1006L,
                        new CreditLimitOverrideDecisionRequest("Approved", null),
                        "checker@bbp.com")
        );

        assertEquals(ErrorCode.BUSINESS_INVALID_STATE, ex.getErrorCode());
        assertThat(ex.getMessage()).contains("immutable");
    }

    @Test
    void approveFailsWhenReviewedAtAlreadyExistsEvenIfReviewerIsBlank() {
        CreditLimitOverrideRequest request = new CreditLimitOverrideRequest();
        request.setCompany(company);
        request.setStatus("PENDING");
        request.setRequestedBy("maker@bbp.com");
        request.setReviewedBy("   ");
        request.setReviewedAt(Instant.parse("2026-02-19T05:00:00Z"));
        when(creditLimitOverrideRequestRepository.findByCompanyAndId(company, 1011L))
                .thenReturn(Optional.of(request));

        ApplicationException ex = assertThrows(
                ApplicationException.class,
                () -> service.approveRequest(
                        1011L,
                        new CreditLimitOverrideDecisionRequest("Approved", null),
                        "checker@bbp.com")
        );

        assertEquals(ErrorCode.BUSINESS_INVALID_STATE, ex.getErrorCode());
        assertThat(ex.getMessage()).contains("immutable");
    }

    @Test
    void rejectFailsWhenReviewMetadataWasAlreadySet() {
        CreditLimitOverrideRequest request = new CreditLimitOverrideRequest();
        request.setCompany(company);
        request.setStatus("PENDING");
        request.setRequestedBy("maker@bbp.com");
        request.setReviewedAt(Instant.parse("2026-02-19T03:00:00Z"));
        when(creditLimitOverrideRequestRepository.findByCompanyAndId(company, 1007L))
                .thenReturn(Optional.of(request));

        ApplicationException ex = assertThrows(
                ApplicationException.class,
                () -> service.rejectRequest(
                        1007L,
                        new CreditLimitOverrideDecisionRequest("Rejected", null),
                        "checker@bbp.com")
        );

        assertEquals(ErrorCode.BUSINESS_INVALID_STATE, ex.getErrorCode());
        assertThat(ex.getMessage()).contains("immutable");
    }

    @Test
    void rejectFailsWhenReviewerAlreadyExistsEvenIfReviewedAtIsNull() {
        CreditLimitOverrideRequest request = new CreditLimitOverrideRequest();
        request.setCompany(company);
        request.setStatus("PENDING");
        request.setRequestedBy("maker@bbp.com");
        request.setReviewedBy("legacy-reviewer@bbp.com");
        request.setReviewedAt(null);
        when(creditLimitOverrideRequestRepository.findByCompanyAndId(company, 1012L))
                .thenReturn(Optional.of(request));

        ApplicationException ex = assertThrows(
                ApplicationException.class,
                () -> service.rejectRequest(
                        1012L,
                        new CreditLimitOverrideDecisionRequest("Rejected", null),
                        "checker@bbp.com")
        );

        assertEquals(ErrorCode.BUSINESS_INVALID_STATE, ex.getErrorCode());
        assertThat(ex.getMessage()).contains("immutable");
    }

    @Test
    void isOverrideApprovedRejectsApprovedRecordsWithNonApprovedReasonCode() {
        CreditLimitOverrideRequest request = new CreditLimitOverrideRequest();
        request.setCompany(company);
        request.setStatus("APPROVED");
        request.setDispatchAmount(new BigDecimal("120.00"));
        request.setRequiredHeadroom(new BigDecimal("80.00"));
        request.setRequestedBy("maker@bbp.com");
        request.setReviewedBy("checker@bbp.com");
        request.setReviewedAt(Instant.parse("2026-02-20T00:00:00Z"));
        request.setReason("[CREDIT_LIMIT_EXCEPTION_REJECTED] invalid approval marker");

        Dealer dealer = new Dealer();
        dealer.setCreditLimit(new BigDecimal("200.00"));
        ReflectionTestUtils.setField(dealer, "id", 42L);

        when(creditLimitOverrideRequestRepository.findByCompanyAndId(company, 1008L))
                .thenReturn(Optional.of(request));

        boolean approved = service.isOverrideApproved(
                1008L,
                company,
                dealer,
                null,
                null,
                new BigDecimal("120.00"));

        assertThat(approved).isFalse();
    }

    @Test
    void isOverrideApprovedExpiresStaleRecordsAndPersistsExpiredStatus() {
        CreditLimitOverrideRequest request = new CreditLimitOverrideRequest();
        request.setCompany(company);
        request.setStatus("APPROVED");
        request.setRequestedBy("maker@bbp.com");
        request.setReviewedBy("checker@bbp.com");
        request.setReviewedAt(Instant.parse("2026-02-20T00:00:00Z"));
        request.setReason("[CREDIT_LIMIT_EXCEPTION_APPROVED] reviewed");
        request.setExpiresAt(Instant.parse("2026-02-19T00:00:00Z"));

        when(creditLimitOverrideRequestRepository.findByCompanyAndId(company, 1009L))
                .thenReturn(Optional.of(request));

        boolean approved = service.isOverrideApproved(
                1009L,
                company,
                null,
                null,
                null,
                null);

        assertThat(approved).isFalse();
        assertThat(request.getStatus()).isEqualTo("EXPIRED");
        verify(creditLimitOverrideRequestRepository).save(request);
    }

    @Test
    void helperMethodsNormalizeLegacyReasonMarkersForBackwardCompatibility() {
        String normalizedReason = ReflectionTestUtils.invokeMethod(
                service,
                "reasonWithCode",
                "CREDIT_LIMIT_EXCEPTION_APPROVED",
                "[LEGACY_APPROVAL] reviewed by finance");
        assertThat(normalizedReason).isEqualTo("[CREDIT_LIMIT_EXCEPTION_APPROVED] reviewed by finance");

        String malformedCode = ReflectionTestUtils.invokeMethod(
                service,
                "extractReasonCode",
                "[] malformed");
        assertThat(malformedCode).isEmpty();

        String emptyCode = ReflectionTestUtils.invokeMethod(
                service,
                "extractReasonCode",
                new Object[]{null});
        assertThat(emptyCode).isEmpty();

        String strippedReason = ReflectionTestUtils.invokeMethod(
                service,
                "stripReasonCodePrefix",
                "[CREDIT_LIMIT_EXCEPTION_APPROVED]");
        assertThat(strippedReason).isEmpty();

        String legacyReason = ReflectionTestUtils.invokeMethod(
                service,
                "stripReasonCodePrefix",
                "legacy reason only");
        assertThat(legacyReason).isEqualTo("legacy reason only");

        CreditLimitOverrideRequest incompleteMetadata = new CreditLimitOverrideRequest();
        incompleteMetadata.setRequestedBy("maker@bbp.com");
        boolean hasImmutableMetadata = ReflectionTestUtils.invokeMethod(
                service,
                "hasImmutableApprovalMetadata",
                incompleteMetadata);
        assertThat(hasImmutableMetadata).isFalse();

        String normalizedWithoutReason = ReflectionTestUtils.invokeMethod(
                service,
                "reasonWithCode",
                "CREDIT_LIMIT_EXCEPTION_APPROVED",
                null);
        assertThat(normalizedWithoutReason).isEqualTo("[CREDIT_LIMIT_EXCEPTION_APPROVED] ");

        String normalizedWithoutClosingBracket = ReflectionTestUtils.invokeMethod(
                service,
                "reasonWithCode",
                "CREDIT_LIMIT_EXCEPTION_APPROVED",
                "[legacy reason");
        assertThat(normalizedWithoutClosingBracket).contains("[legacy reason");

        String unresolvedCode = ReflectionTestUtils.invokeMethod(
                service,
                "extractReasonCode",
                "[legacy_reason");
        assertThat(unresolvedCode).isEmpty();

        CreditLimitOverrideRequest withIncomingDecision = new CreditLimitOverrideRequest();
        withIncomingDecision.setReason("[CREDIT_LIMIT_EXCEPTION_REQUESTED] fallback");
        String incomingDecision = ReflectionTestUtils.invokeMethod(
                service,
                "resolveDecisionReason",
                withIncomingDecision,
                new CreditLimitOverrideDecisionRequest("explicit reviewer reason", null),
                "approve");
        assertThat(incomingDecision).isEqualTo("explicit reviewer reason");

        String nonBracketPrefix = ReflectionTestUtils.invokeMethod(
                service,
                "stripReasonCodePrefix",
                "[legacy_reason");
        assertThat(nonBracketPrefix).isEqualTo("[legacy_reason");

        CreditLimitOverrideRequest missingRequestedBy = new CreditLimitOverrideRequest();
        missingRequestedBy.setRequestedBy("  ");
        missingRequestedBy.setReviewedBy("checker@bbp.com");
        missingRequestedBy.setReviewedAt(Instant.parse("2026-02-20T00:00:00Z"));
        boolean hasMetadataWithoutRequester = ReflectionTestUtils.invokeMethod(
                service,
                "hasImmutableApprovalMetadata",
                missingRequestedBy);
        assertThat(hasMetadataWithoutRequester).isFalse();

        CreditLimitOverrideRequest missingReviewedAt = new CreditLimitOverrideRequest();
        missingReviewedAt.setRequestedBy("maker@bbp.com");
        missingReviewedAt.setReviewedBy("checker@bbp.com");
        missingReviewedAt.setReviewedAt(null);
        boolean hasMetadataWithoutReviewedAt = ReflectionTestUtils.invokeMethod(
                service,
                "hasImmutableApprovalMetadata",
                missingReviewedAt);
        assertThat(hasMetadataWithoutReviewedAt).isFalse();
    }

    @Test
    void auditLifecycleIncludesOptionalFieldsWhenPresent() {
        Dealer dealer = new Dealer();
        ReflectionTestUtils.setField(dealer, "id", 42L);

        SalesOrder order = new SalesOrder();
        ReflectionTestUtils.setField(order, "id", 71L);

        PackagingSlip slip = new PackagingSlip();
        ReflectionTestUtils.setField(slip, "id", 55L);

        CreditLimitOverrideRequest request = new CreditLimitOverrideRequest();
        request.setCompany(company);
        request.setDealer(dealer);
        request.setSalesOrder(order);
        request.setPackagingSlip(slip);
        request.setStatus(" approved ");
        request.setReason("[CREDIT_LIMIT_EXCEPTION_APPROVED] Reason retained");
        request.setDispatchAmount(new BigDecimal("50.00"));
        request.setCurrentExposure(new BigDecimal("120.00"));
        request.setCreditLimit(new BigDecimal("200.00"));
        request.setRequiredHeadroom(new BigDecimal("0.00"));
        request.setRequestedBy("maker@bbp.com");
        request.setReviewedBy("checker@bbp.com");
        request.setReviewedAt(Instant.parse("2026-02-20T02:00:00Z"));
        request.setExpiresAt(Instant.parse("2026-02-22T02:00:00Z"));
        ReflectionTestUtils.setField(request, "id", 701L);
        ReflectionTestUtils.setField(request, "publicId", UUID.fromString("00000000-0000-0000-0000-000000000701"));
        ReflectionTestUtils.setField(request, "createdAt", Instant.parse("2026-02-20T01:30:00Z"));

        ReflectionTestUtils.invokeMethod(
                service,
                "auditOverrideLifecycle",
                AuditEvent.TRANSACTION_APPROVED,
                request,
                "checker@bbp.com",
                "CREDIT_LIMIT_EXCEPTION_APPROVED");

        ArgumentCaptor<Map<String, String>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditService).logSuccess(eq(AuditEvent.TRANSACTION_APPROVED), metadataCaptor.capture());
        Map<String, String> metadata = metadataCaptor.getValue();
        assertEquals("701", metadata.get("overrideRequestId"));
        assertEquals("00000000-0000-0000-0000-000000000701", metadata.get("overrideRequestPublicId"));
        assertEquals("42", metadata.get("dealerId"));
        assertEquals("55", metadata.get("packagingSlipId"));
        assertEquals("71", metadata.get("salesOrderId"));
        assertEquals("2026-02-20T01:30:00Z", metadata.get("createdAt"));
        assertEquals("2026-02-20T02:00:00Z", metadata.get("reviewedAt"));
        assertEquals("2026-02-22T02:00:00Z", metadata.get("expiresAt"));
    }

    @Test
    void auditLifecycleSkipsOptionalMetadataWhenNestedEntitiesHaveNoIds() {
        CreditLimitOverrideRequest request = new CreditLimitOverrideRequest();
        request.setCompany(company);
        request.setDealer(new Dealer());
        request.setSalesOrder(new SalesOrder());
        request.setPackagingSlip(new PackagingSlip());
        request.setStatus("PENDING");
        request.setDispatchAmount(null);
        request.setCurrentExposure(null);
        request.setCreditLimit(null);
        request.setRequiredHeadroom(null);
        request.setRequestedBy(null);
        request.setReviewedBy(null);
        request.setReason(null);

        ReflectionTestUtils.invokeMethod(
                service,
                "auditOverrideLifecycle",
                AuditEvent.TRANSACTION_APPROVED,
                request,
                "checker@bbp.com",
                "CREDIT_LIMIT_EXCEPTION_APPROVED");

        ArgumentCaptor<Map<String, String>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditService).logSuccess(eq(AuditEvent.TRANSACTION_APPROVED), metadataCaptor.capture());
        Map<String, String> metadata = metadataCaptor.getValue();
        assertThat(metadata).doesNotContainKeys(
                "dealerId",
                "packagingSlipId",
                "salesOrderId",
                "dispatchAmount",
                "currentExposure",
                "creditLimit",
                "requiredHeadroom",
                "requestedBy",
                "reviewedBy",
                "reason");
    }
}
