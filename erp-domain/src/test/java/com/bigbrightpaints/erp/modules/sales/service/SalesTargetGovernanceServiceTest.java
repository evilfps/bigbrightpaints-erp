package com.bigbrightpaints.erp.modules.sales.service;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingFacade;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingService;
import com.bigbrightpaints.erp.modules.accounting.service.CompanyAccountingSettingsService;
import com.bigbrightpaints.erp.modules.accounting.service.CompanyDefaultAccountsService;
import com.bigbrightpaints.erp.modules.accounting.service.DealerLedgerService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.factory.domain.FactoryTaskRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlipRepository;
import com.bigbrightpaints.erp.modules.inventory.service.FinishedGoodsService;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceRepository;
import com.bigbrightpaints.erp.modules.invoice.service.InvoiceNumberService;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProductRepository;
import com.bigbrightpaints.erp.modules.sales.domain.CreditRequestRepository;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import com.bigbrightpaints.erp.modules.sales.domain.PromotionRepository;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrderRepository;
import com.bigbrightpaints.erp.modules.sales.domain.SalesTarget;
import com.bigbrightpaints.erp.modules.sales.domain.SalesTargetRepository;
import com.bigbrightpaints.erp.modules.sales.dto.SalesTargetRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SalesTargetGovernanceServiceTest {

    @Mock
    private CompanyContextService companyContextService;
    @Mock
    private DealerRepository dealerRepository;
    @Mock
    private SalesOrderRepository salesOrderRepository;
    @Mock
    private PromotionRepository promotionRepository;
    @Mock
    private SalesTargetRepository salesTargetRepository;
    @Mock
    private CreditRequestRepository creditRequestRepository;
    @Mock
    private OrderNumberService orderNumberService;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private ProductionProductRepository productionProductRepository;
    @Mock
    private DealerLedgerService dealerLedgerService;
    @Mock
    private FinishedGoodRepository finishedGoodRepository;
    @Mock
    private AccountRepository accountRepository;
    @Mock
    private CompanyEntityLookup companyEntityLookup;
    @Mock
    private PackagingSlipRepository packagingSlipRepository;
    @Mock
    private FinishedGoodsService finishedGoodsService;
    @Mock
    private AccountingService accountingService;
    @Mock
    private AccountingFacade accountingFacade;
    @Mock
    private com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository journalEntryRepository;
    @Mock
    private InvoiceNumberService invoiceNumberService;
    @Mock
    private InvoiceRepository invoiceRepository;
    @Mock
    private FactoryTaskRepository factoryTaskRepository;
    @Mock
    private CompanyDefaultAccountsService companyDefaultAccountsService;
    @Mock
    private CompanyAccountingSettingsService companyAccountingSettingsService;
    @Mock
    private CreditLimitOverrideService creditLimitOverrideService;
    @Mock
    private AuditService auditService;
    @Mock
    private CompanyClock companyClock;

    private final PlatformTransactionManager transactionManager = new NoopTransactionManager();

    private SalesService salesService;
    private Company company;

    @BeforeEach
    void setUp() {
        salesService = new SalesService(
                companyContextService,
                dealerRepository,
                salesOrderRepository,
                promotionRepository,
                salesTargetRepository,
                creditRequestRepository,
                orderNumberService,
                eventPublisher,
                productionProductRepository,
                dealerLedgerService,
                finishedGoodRepository,
                accountRepository,
                companyEntityLookup,
                packagingSlipRepository,
                finishedGoodsService,
                accountingService,
                accountingFacade,
                journalEntryRepository,
                invoiceNumberService,
                invoiceRepository,
                factoryTaskRepository,
                companyDefaultAccountsService,
                companyAccountingSettingsService,
                creditLimitOverrideService,
                auditService,
                companyClock,
                transactionManager);

        company = new Company();
        company.setCode("COMP");
        company.setTimezone("UTC");
        lenient().when(companyContextService.requireCurrentCompany()).thenReturn(company);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void createTargetRejectsNonAdminAuthority() {
        authenticate("sales@comp.com", "ROLE_SALES");

        assertThrows(AccessDeniedException.class, () -> salesService.createTarget(requestFor("rep@comp.com", "Plan kickoff")));

        verify(salesTargetRepository, never()).save(any(SalesTarget.class));
    }

    @Test
    void createTargetRejectsUnauthenticatedActor() {
        SecurityContextHolder.clearContext();

        assertThrows(AccessDeniedException.class, () -> salesService.createTarget(requestFor("rep@comp.com", "Plan kickoff")));

        verify(salesTargetRepository, never()).save(any(SalesTarget.class));
    }

    @Test
    void createTargetRejectsSelfAssignmentForAdminActor() {
        authenticate("admin@comp.com", "ROLE_ADMIN");

        assertThrows(AccessDeniedException.class, () -> salesService.createTarget(requestFor("ADMIN@comp.com", "Self assign attempt")));

        verify(salesTargetRepository, never()).save(any(SalesTarget.class));
    }

    @Test
    void createTargetRequiresAssigneeIdentity() {
        authenticate("admin@comp.com", "ROLE_ADMIN");

        ApplicationException ex = assertThrows(ApplicationException.class, () -> salesService.createTarget(
                new SalesTargetRequest(
                        "North Region Monthly",
                        LocalDate.of(2026, 1, 1),
                        LocalDate.of(2026, 1, 31),
                        new BigDecimal("150000"),
                        BigDecimal.ZERO,
                        "   ",
                        "Quarter 2 target assignment")
        ));

        assertEquals(ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD, ex.getErrorCode());
        verify(salesTargetRepository, never()).save(any(SalesTarget.class));
    }

    @Test
    void createTargetWritesAuditWithActorAndReasonMetadata() {
        authenticate("admin@comp.com", "ROLE_ADMIN");
        when(salesTargetRepository.save(any(SalesTarget.class))).thenAnswer(invocation -> {
            SalesTarget target = invocation.getArgument(0);
            setField(target, "id", 77L);
            setField(target, "publicId", UUID.fromString("11111111-1111-1111-1111-111111111111"));
            return target;
        });

        salesService.createTarget(requestFor("rep@comp.com", "Quarter 2 target assignment"));

        ArgumentCaptor<Map<String, String>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditService).logSuccess(eq(AuditEvent.DATA_CREATE), metadataCaptor.capture());
        assertEquals("admin@comp.com", metadataCaptor.getValue().get("actor"));
        assertEquals("Quarter 2 target assignment", metadataCaptor.getValue().get("reason"));
        assertEquals("rep@comp.com", metadataCaptor.getValue().get("assignee"));
    }

    @Test
    void updateTargetRejectsSelfApprovalByAssigneeActor() {
        authenticate("owner@comp.com", "ROLE_ADMIN");
        SalesTarget existing = existingTarget(501L, "owner@comp.com");
        when(companyEntityLookup.requireSalesTarget(company, 501L)).thenReturn(existing);

        assertThrows(AccessDeniedException.class, () -> salesService.updateTarget(
                501L,
                requestFor("rep@comp.com", "Attempted self approval")
        ));
    }

    @Test
    void updateTargetRejectsSelfAssignmentInIncomingRequestBeforeEntityLookup() {
        authenticate("owner@comp.com", "ROLE_ADMIN");

        assertThrows(AccessDeniedException.class, () -> salesService.updateTarget(
                502L,
                requestFor("owner@comp.com", "Attempted self assignment in request")
        ));

        verify(companyEntityLookup, never()).requireSalesTarget(company, 502L);
    }

    @Test
    void deleteTargetRequiresChangeReason() {
        authenticate("admin@comp.com", "ROLE_ADMIN");

        ApplicationException ex = assertThrows(ApplicationException.class, () -> salesService.deleteTarget(99L, "   "));

        assertEquals(ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD, ex.getErrorCode());
        verify(salesTargetRepository, never()).delete(any(SalesTarget.class));
    }

    @Test
    void deleteTargetAuditsActorAndReason() {
        authenticate("admin@comp.com", "ROLE_ADMIN");
        SalesTarget existing = existingTarget(601L, "rep@comp.com");
        when(companyEntityLookup.requireSalesTarget(company, 601L)).thenReturn(existing);

        salesService.deleteTarget(601L, "Duplicate target cleanup");

        verify(salesTargetRepository).delete(existing);
        ArgumentCaptor<Map<String, String>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditService).logSuccess(eq(AuditEvent.DATA_DELETE), metadataCaptor.capture());
        assertEquals("admin@comp.com", metadataCaptor.getValue().get("actor"));
        assertEquals("Duplicate target cleanup", metadataCaptor.getValue().get("reason"));
        assertEquals("rep@comp.com", metadataCaptor.getValue().get("assignee"));
    }

    private SalesTargetRequest requestFor(String assignee, String reason) {
        return new SalesTargetRequest(
                "North Region Monthly",
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 1, 31),
                new BigDecimal("150000"),
                BigDecimal.ZERO,
                assignee,
                reason
        );
    }

    private SalesTarget existingTarget(Long id, String assignee) {
        SalesTarget target = new SalesTarget();
        target.setCompany(company);
        target.setName("Existing target");
        target.setPeriodStart(LocalDate.of(2026, 1, 1));
        target.setPeriodEnd(LocalDate.of(2026, 1, 31));
        target.setTargetAmount(new BigDecimal("100000"));
        target.setAssignee(assignee);
        setField(target, "id", id);
        setField(target, "publicId", UUID.fromString("22222222-2222-2222-2222-222222222222"));
        return target;
    }

    private void authenticate(String username, String... authorities) {
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                username,
                "n/a",
                List.of(authorities).stream().map(SimpleGrantedAuthority::new).toList()
        );
        SecurityContextHolder.getContext().setAuthentication(authentication);
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

    private static class NoopTransactionManager implements PlatformTransactionManager {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
        }

        @Override
        public void rollback(TransactionStatus status) {
        }
    }
}
