package com.bigbrightpaints.erp.modules.sales.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
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

@ExtendWith(MockitoExtension.class)
@Tag("critical")
class CreditLimitRequestServiceTest {

    @Mock
    private CompanyContextService companyContextService;
    @Mock
    private CreditRequestRepository creditRequestRepository;
    @Mock
    private DealerRepository dealerRepository;
    @Mock
    private AuditService auditService;

    private CreditLimitRequestService service;
    private Company company;

    @BeforeEach
    void setUp() {
        service = new CreditLimitRequestService(
                companyContextService,
                creditRequestRepository,
                dealerRepository,
                auditService
        );
        company = new Company();
        company.setCode("COMP");
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
    }

    @Test
    void createRequestPersistsPendingStatusWithoutCallerSuppliedStatus() {
        Dealer dealer = dealer(17L, "Dealer One", new BigDecimal("2500"));
        when(dealerRepository.findByCompanyAndId(company, 17L)).thenReturn(Optional.of(dealer));
        when(creditRequestRepository.save(any(CreditRequest.class))).thenAnswer(invocation -> {
            CreditRequest request = invocation.getArgument(0);
            setField(request, "id", 901L);
            setField(request, "publicId", UUID.fromString("123e4567-e89b-12d3-a456-426614174000"));
            setField(request, "createdAt", Instant.parse("2026-03-23T10:15:30Z"));
            return request;
        });

        CreditLimitRequestDto dto = service.createRequest(new CreditLimitRequestCreateRequest(
                17L,
                new BigDecimal("1500"),
                "Need durable increase"
        ));

        assertEquals("PENDING", dto.status());
        assertEquals("Dealer One", dto.dealerName());
        ArgumentCaptor<CreditRequest> requestCaptor = ArgumentCaptor.forClass(CreditRequest.class);
        verify(creditRequestRepository).save(requestCaptor.capture());
        assertEquals("PENDING", requestCaptor.getValue().getStatus());
        assertEquals(new BigDecimal("1500"), requestCaptor.getValue().getAmountRequested());
        assertEquals("Need durable increase", requestCaptor.getValue().getReason());
    }

    @Test
    void createRequestPersistsRequesterIdentityWhenProvided() {
        Dealer dealer = dealer(18L, "Dealer Two", new BigDecimal("4000"));
        when(dealerRepository.findByCompanyAndId(company, 18L)).thenReturn(Optional.of(dealer));
        when(creditRequestRepository.save(any(CreditRequest.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.createRequest(
                new CreditLimitRequestCreateRequest(
                        18L,
                        new BigDecimal("1200"),
                        "Dealer self-service request"
                ),
                4401L,
                "dealer.user@bbp.com"
        );

        ArgumentCaptor<CreditRequest> requestCaptor = ArgumentCaptor.forClass(CreditRequest.class);
        verify(creditRequestRepository).save(requestCaptor.capture());
        assertThat(requestCaptor.getValue().getRequesterUserId()).isEqualTo(4401L);
        assertThat(requestCaptor.getValue().getRequesterEmail()).isEqualTo("dealer.user@bbp.com");
    }

    @Test
    void createRequestRequiresDealerId() {
        ApplicationException ex = assertThrows(ApplicationException.class, () -> service.createRequest(
                new CreditLimitRequestCreateRequest(
                        null,
                        new BigDecimal("800"),
                        "Portal initiated request"
                )));

        assertEquals(ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD, ex.getErrorCode());
        assertThat(ex.getDetails()).containsEntry("field", "dealerId");
        verify(dealerRepository, never()).findByCompanyAndId(any(), any());
    }

    @Test
    void listRequestsUsesDealerFetchPathForStableDtoMapping() {
        Dealer dealer = dealer(21L, "Prime Dealer", new BigDecimal("3000"));
        CreditRequest request = new CreditRequest();
        request.setCompany(company);
        request.setDealer(dealer);
        request.setAmountRequested(new BigDecimal("900"));
        request.setStatus("PENDING");
        request.setReason("Expansion");
        setField(request, "id", 9011L);
        setField(request, "publicId", UUID.randomUUID());
        setField(request, "createdAt", Instant.parse("2026-03-23T11:00:00Z"));

        when(creditRequestRepository.findByCompanyWithDealerOrderByCreatedAtDesc(company))
                .thenReturn(List.of(request));

        List<CreditLimitRequestDto> response = service.listRequests();

        assertEquals(1, response.size());
        assertEquals("Prime Dealer", response.getFirst().dealerName());
        verify(creditRequestRepository).findByCompanyWithDealerOrderByCreatedAtDesc(company);
        verify(creditRequestRepository, never()).findByCompanyOrderByCreatedAtDesc(any());
    }

    @Test
    void listRequestsRejectsBlankStatus() {
        CreditRequest request = request(9012L, null, new BigDecimal("900"), "Expansion", "   ");
        when(creditRequestRepository.findByCompanyWithDealerOrderByCreatedAtDesc(company))
                .thenReturn(List.of(request));

        ApplicationException ex = assertThrows(ApplicationException.class, service::listRequests);

        assertEquals(ErrorCode.VALIDATION_INVALID_INPUT, ex.getErrorCode());
        assertThat(ex.getMessage()).contains("Status is required");
        assertThat(ex.getDetails()).containsEntry("entity", "credit_limit_request");
    }

    @Test
    void listRequestsRejectsUnsupportedStatus() {
        CreditRequest request = request(9013L, null, new BigDecimal("900"), "Expansion", "DRAFT");
        when(creditRequestRepository.findByCompanyWithDealerOrderByCreatedAtDesc(company))
                .thenReturn(List.of(request));

        ApplicationException ex = assertThrows(ApplicationException.class, service::listRequests);

        assertEquals(ErrorCode.VALIDATION_INVALID_INPUT, ex.getErrorCode());
        assertThat(ex.getMessage()).contains("Unsupported credit limit request status");
    }

    @Test
    void approveRequestIncrementsDealerCreditLimitAndAuditsMutationMetadata() {
        Dealer dealer = dealer(77L, "Dealer", new BigDecimal("2500"));
        CreditRequest existing = request(910L, dealer, new BigDecimal("600"), "Temporary headroom needed", "PENDING");
        when(creditRequestRepository.findByCompanyAndId(company, 910L)).thenReturn(Optional.of(existing));
        when(dealerRepository.lockByCompanyAndId(company, 77L)).thenReturn(Optional.of(dealer));

        CreditLimitRequestDto dto = service.approveRequest(910L, "  Exposure validated by accounting review  ");

        assertEquals("APPROVED", dto.status());
        assertEquals(new BigDecimal("3100"), dealer.getCreditLimit());
        verify(dealerRepository).lockByCompanyAndId(company, 77L);
        verify(dealerRepository).save(dealer);
        verify(creditRequestRepository).save(existing);

        ArgumentCaptor<Map<String, String>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditService).logSuccess(eq(AuditEvent.TRANSACTION_APPROVED), metadataCaptor.capture());
        assertThat(metadataCaptor.getValue())
                .containsEntry("resourceType", "credit_limit_request")
                .containsEntry("decisionStatus", "APPROVED")
                .containsEntry("reason", "Exposure validated by accounting review")
                .containsEntry("decisionReason", "Exposure validated by accounting review")
                .containsEntry("requestReason", "Temporary headroom needed")
                .containsEntry("oldLimit", "2500")
                .containsEntry("newLimit", "3100")
                .containsEntry("increment", "600");
    }

    @Test
    void approveRequestRejectsNonPendingStatus() {
        CreditRequest existing = request(912L, null, new BigDecimal("600"), null, "APPROVED");
        when(creditRequestRepository.findByCompanyAndId(company, 912L)).thenReturn(Optional.of(existing));

        ApplicationException ex = assertThrows(ApplicationException.class,
                () -> service.approveRequest(912L, "Already reviewed"));

        assertEquals(ErrorCode.BUSINESS_INVALID_STATE, ex.getErrorCode());
        assertEquals("APPROVED", existing.getStatus());
        verifyNoInteractions(auditService);
    }

    @Test
    void approveRequestRequiresDecisionReason() {
        CreditRequest existing = request(914L, null, new BigDecimal("600"), null, "PENDING");
        when(creditRequestRepository.findByCompanyAndId(company, 914L)).thenReturn(Optional.of(existing));

        ApplicationException ex = assertThrows(ApplicationException.class,
                () -> service.approveRequest(914L, "   "));

        assertEquals(ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD, ex.getErrorCode());
        assertEquals("PENDING", existing.getStatus());
        verifyNoInteractions(auditService);
    }

    @Test
    void approveRequestRequiresAssignedDealer() {
        CreditRequest existing = request(916L, null, new BigDecimal("600"), null, "PENDING");
        when(creditRequestRepository.findByCompanyAndId(company, 916L)).thenReturn(Optional.of(existing));

        ApplicationException ex = assertThrows(ApplicationException.class,
                () -> service.approveRequest(916L, "Approved"));

        assertEquals(ErrorCode.BUSINESS_INVALID_STATE, ex.getErrorCode());
        verify(dealerRepository, never()).lockByCompanyAndId(any(), any());
        verifyNoInteractions(auditService);
    }

    @Test
    void approveRequestRequiresAssignedDealerId() {
        Dealer dealer = new Dealer();
        dealer.setCompany(company);
        CreditRequest existing = request(9161L, dealer, new BigDecimal("600"), null, "PENDING");
        when(creditRequestRepository.findByCompanyAndId(company, 9161L)).thenReturn(Optional.of(existing));

        ApplicationException ex = assertThrows(ApplicationException.class,
                () -> service.approveRequest(9161L, "Approved"));

        assertEquals(ErrorCode.BUSINESS_INVALID_STATE, ex.getErrorCode());
        verify(dealerRepository, never()).lockByCompanyAndId(any(), any());
        verifyNoInteractions(auditService);
    }

    @Test
    void approveRequestFailsClosedWhenDealerCannotBeLocked() {
        Dealer dealer = dealer(81L, "Dealer", new BigDecimal("2000"));
        CreditRequest existing = request(917L, dealer, new BigDecimal("600"), null, "PENDING");
        when(creditRequestRepository.findByCompanyAndId(company, 917L)).thenReturn(Optional.of(existing));
        when(dealerRepository.lockByCompanyAndId(company, 81L)).thenReturn(Optional.empty());

        ApplicationException ex = assertThrows(ApplicationException.class,
                () -> service.approveRequest(917L, "Approved"));

        assertEquals(ErrorCode.VALIDATION_INVALID_REFERENCE, ex.getErrorCode());
        assertEquals(new BigDecimal("2000"), dealer.getCreditLimit());
        verify(auditService, never()).logSuccess(any(), any());
    }

    @Test
    void approveRequestFailsClosedWhenDealerCreditLimitIsMissing() {
        Dealer dealer = dealer(83L, "Dealer", null);
        CreditRequest existing = request(919L, dealer, new BigDecimal("600"), null, "PENDING");
        when(creditRequestRepository.findByCompanyAndId(company, 919L)).thenReturn(Optional.of(existing));
        when(dealerRepository.lockByCompanyAndId(company, 83L)).thenReturn(Optional.of(dealer));

        ApplicationException ex = assertThrows(ApplicationException.class,
                () -> service.approveRequest(919L, "Approved"));

        assertEquals(ErrorCode.BUSINESS_INVALID_STATE, ex.getErrorCode());
        verify(auditService, never()).logSuccess(any(), any());
    }

    @Test
    void approveRequestFailsClosedWhenAmountRequestedIsMissing() {
        Dealer dealer = dealer(821L, "Dealer", new BigDecimal("2000"));
        CreditRequest existing = request(9181L, dealer, null, null, "PENDING");
        when(creditRequestRepository.findByCompanyAndId(company, 9181L)).thenReturn(Optional.of(existing));
        when(dealerRepository.lockByCompanyAndId(company, 821L)).thenReturn(Optional.of(dealer));

        ApplicationException ex = assertThrows(ApplicationException.class,
                () -> service.approveRequest(9181L, "Approved"));

        assertEquals(ErrorCode.VALIDATION_INVALID_INPUT, ex.getErrorCode());
        verify(auditService, never()).logSuccess(any(), any());
    }

    @Test
    void approveRequestFailsClosedWhenAmountRequestedIsInvalid() {
        Dealer dealer = dealer(82L, "Dealer", new BigDecimal("2000"));
        CreditRequest existing = request(918L, dealer, BigDecimal.ZERO, null, "PENDING");
        when(creditRequestRepository.findByCompanyAndId(company, 918L)).thenReturn(Optional.of(existing));
        when(dealerRepository.lockByCompanyAndId(company, 82L)).thenReturn(Optional.of(dealer));

        ApplicationException ex = assertThrows(ApplicationException.class,
                () -> service.approveRequest(918L, "Approved"));

        assertEquals(ErrorCode.VALIDATION_INVALID_INPUT, ex.getErrorCode());
        assertEquals(new BigDecimal("2000"), dealer.getCreditLimit());
        verify(auditService, never()).logSuccess(any(), any());
    }

    @Test
    void approveRequestFailsClosedWhenDealerCreditLimitIsNegative() {
        Dealer dealer = dealer(831L, "Dealer", new BigDecimal("-1"));
        CreditRequest existing = request(9191L, dealer, new BigDecimal("600"), null, "PENDING");
        when(creditRequestRepository.findByCompanyAndId(company, 9191L)).thenReturn(Optional.of(existing));
        when(dealerRepository.lockByCompanyAndId(company, 831L)).thenReturn(Optional.of(dealer));

        ApplicationException ex = assertThrows(ApplicationException.class,
                () -> service.approveRequest(9191L, "Approved"));

        assertEquals(ErrorCode.BUSINESS_INVALID_STATE, ex.getErrorCode());
        verify(auditService, never()).logSuccess(any(), any());
    }

    @Test
    void rejectRequestRejectsNonPendingStatus() {
        CreditRequest existing = request(920L, null, new BigDecimal("725"), null, "REJECTED");
        when(creditRequestRepository.findByCompanyAndId(company, 920L)).thenReturn(Optional.of(existing));

        ApplicationException ex = assertThrows(ApplicationException.class,
                () -> service.rejectRequest(920L, "Already rejected"));

        assertEquals(ErrorCode.BUSINESS_INVALID_STATE, ex.getErrorCode());
        verifyNoInteractions(auditService);
    }

    @Test
    void rejectRequestAuditsSparseMetadataWhenRequestIdentifiersAreMissing() {
        CreditRequest existing = new CreditRequest();
        existing.setCompany(company);
        existing.setStatus("PENDING");
        existing.setReason("No identifiers yet");
        when(creditRequestRepository.findByCompanyAndId(company, 921L)).thenReturn(Optional.of(existing));

        service.rejectRequest(921L, "Missing paperwork");

        ArgumentCaptor<Map<String, String>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditService).logSuccess(eq(AuditEvent.TRANSACTION_REJECTED), metadataCaptor.capture());
        assertThat(metadataCaptor.getValue())
                .containsEntry("resourceType", "credit_limit_request")
                .containsEntry("decisionStatus", "REJECTED")
                .containsEntry("decisionReason", "Missing paperwork")
                .doesNotContainKeys("requestId", "requestPublicId", "dealerId", "amountRequested");
    }

    @Test
    void rejectRequestUpdatesStatusAndAuditsDecisionReason() {
        Dealer dealer = dealer(78L, "Dealer", new BigDecimal("3000"));
        CreditRequest existing = request(911L, dealer, new BigDecimal("725"), "Dealer requested durable increase", "PENDING");
        when(creditRequestRepository.findByCompanyAndId(company, 911L)).thenReturn(Optional.of(existing));

        CreditLimitRequestDto dto = service.rejectRequest(911L, " Insufficient collateral documentation ");

        assertEquals("REJECTED", dto.status());
        verify(creditRequestRepository).save(existing);

        ArgumentCaptor<Map<String, String>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditService).logSuccess(eq(AuditEvent.TRANSACTION_REJECTED), metadataCaptor.capture());
        assertThat(metadataCaptor.getValue())
                .containsEntry("resourceType", "credit_limit_request")
                .containsEntry("decisionStatus", "REJECTED")
                .containsEntry("decisionReason", "Insufficient collateral documentation");
    }

    @Test
    void rejectRequestRequiresDecisionReason() {
        CreditRequest existing = request(915L, null, new BigDecimal("725"), null, "PENDING");
        when(creditRequestRepository.findByCompanyAndId(company, 915L)).thenReturn(Optional.of(existing));

        ApplicationException ex = assertThrows(ApplicationException.class,
                () -> service.rejectRequest(915L, " "));

        assertEquals(ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD, ex.getErrorCode());
        assertEquals("PENDING", existing.getStatus());
        verifyNoInteractions(auditService);
    }

    private Dealer dealer(Long id, String name, BigDecimal creditLimit) {
        Dealer dealer = new Dealer();
        dealer.setCompany(company);
        dealer.setName(name);
        dealer.setCreditLimit(creditLimit);
        setField(dealer, "id", id);
        return dealer;
    }

    private CreditRequest request(Long id,
                                  Dealer dealer,
                                  BigDecimal amountRequested,
                                  String reason,
                                  String status) {
        CreditRequest request = new CreditRequest();
        request.setCompany(company);
        request.setDealer(dealer);
        request.setAmountRequested(amountRequested);
        request.setReason(reason);
        request.setStatus(status);
        setField(request, "id", id);
        setField(request, "publicId", UUID.randomUUID());
        setField(request, "createdAt", Instant.parse("2026-03-23T10:15:30Z"));
        return request;
    }

    private void setField(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (NoSuchFieldException | IllegalAccessException ex) {
            throw new RuntimeException(ex);
        }
    }
}
