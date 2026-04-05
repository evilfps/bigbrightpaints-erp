package com.bigbrightpaints.erp.modules.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.batch.support.transaction.ResourcelessTransactionManager;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.audit.IntegrationFailureMetadataSchema;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriod;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodStatus;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalCorrectionType;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryType;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLine;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLineRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalReferenceMapping;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalReferenceMappingRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation;
import com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocationRepository;
import com.bigbrightpaints.erp.modules.accounting.dto.AccrualRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.AutoSettlementRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.DealerReceiptRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.DealerReceiptSplitRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.DealerSettlementRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.CreditNoteRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.DebitNoteRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.InventoryRevaluationRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalCreationRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.LandedCostRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryReversalRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalListItemDto;
import com.bigbrightpaints.erp.modules.accounting.dto.ManualJournalRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.PartnerSettlementResponse;
import com.bigbrightpaints.erp.modules.accounting.dto.PayrollPaymentRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.SettlementAllocationApplication;
import com.bigbrightpaints.erp.modules.accounting.dto.SettlementAllocationRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.SettlementPaymentRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.SupplierPaymentRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.SupplierSettlementRequest;
import com.bigbrightpaints.erp.modules.accounting.event.AccountingEventStore;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRun;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRunLineRepository;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRunRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovementRepository;
import com.bigbrightpaints.erp.modules.invoice.domain.Invoice;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceRepository;
import com.bigbrightpaints.erp.modules.invoice.service.InvoiceSettlementPolicy;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchase;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchaseRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.Supplier;
import com.bigbrightpaints.erp.modules.purchasing.domain.SupplierRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.SupplierStatus;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import com.bigbrightpaints.erp.shared.dto.PageResponse;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AccountingServiceTest {

  @Mock private CompanyContextService companyContextService;
  @Mock private AccountRepository accountRepository;
  @Mock private JournalEntryRepository journalEntryRepository;
  @Mock private DealerLedgerService dealerLedgerService;
  @Mock private SupplierLedgerService supplierLedgerService;
  @Mock private PayrollRunRepository payrollRunRepository;
  @Mock private PayrollRunLineRepository payrollRunLineRepository;
  @Mock private ReferenceNumberService referenceNumberService;
  @Mock private ApplicationEventPublisher eventPublisher;
  @Mock private CompanyClock companyClock;
  @Mock private CompanyEntityLookup companyEntityLookup;
  @Mock private PartnerSettlementAllocationRepository settlementAllocationRepository;
  @Mock private RawMaterialPurchaseRepository rawMaterialPurchaseRepository;
  @Mock private InvoiceRepository invoiceRepository;
  @Mock private RawMaterialMovementRepository rawMaterialMovementRepository;
  @Mock private RawMaterialBatchRepository rawMaterialBatchRepository;
  @Mock private FinishedGoodBatchRepository finishedGoodBatchRepository;
  @Mock private DealerRepository dealerRepository;
  @Mock private SupplierRepository supplierRepository;
  @Mock private InvoiceSettlementPolicy invoiceSettlementPolicy;
  @Mock private JournalReferenceResolver journalReferenceResolver;
  @Mock private JournalReferenceMappingRepository journalReferenceMappingRepository;
  @Mock private AccountingPeriodRepository accountingPeriodRepository;
  @Mock private JournalLineRepository journalLineRepository;
  @Mock private AccountingPeriodService accountingPeriodService;
  @Mock private jakarta.persistence.EntityManager entityManager;
  @Mock private com.bigbrightpaints.erp.core.config.SystemSettingsService systemSettingsService;
  @Mock private AuditService auditService;
  @Mock private AccountingEventStore accountingEventStore;

  private AccountingService accountingService;
  private JournalEntryService journalEntryService;
  private DealerReceiptService dealerReceiptService;
  private SettlementService settlementService;
  private CreditDebitNoteService creditDebitNoteService;
  private InventoryAccountingService inventoryAccountingService;
  private PayrollAccountingService payrollAccountingService;
  private org.springframework.beans.factory.ObjectProvider<AccountingFacade>
      accountingFacadeProvider;
  private AccountingFacade accountingFacade;
  private Company company;
  private MockEnvironment environment;

  @BeforeEach
  void setup() {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "policy.admin", "n/a", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    journalEntryService =
        spy(
            new JournalEntryService(
                companyContextService,
                accountRepository,
                journalEntryRepository,
                dealerLedgerService,
                supplierLedgerService,
                payrollRunRepository,
                payrollRunLineRepository,
                accountingPeriodService,
                referenceNumberService,
                eventPublisher,
                companyClock,
                companyEntityLookup,
                settlementAllocationRepository,
                rawMaterialPurchaseRepository,
                invoiceRepository,
                rawMaterialMovementRepository,
                rawMaterialBatchRepository,
                finishedGoodBatchRepository,
                dealerRepository,
                supplierRepository,
                invoiceSettlementPolicy,
                journalReferenceResolver,
                journalReferenceMappingRepository,
                entityManager,
                systemSettingsService,
                auditService,
                accountingEventStore));
    creditDebitNoteService =
        spy(
            new CreditDebitNoteService(
                companyContextService,
                accountRepository,
                journalEntryRepository,
                dealerLedgerService,
                supplierLedgerService,
                payrollRunRepository,
                payrollRunLineRepository,
                accountingPeriodService,
                referenceNumberService,
                eventPublisher,
                companyClock,
                companyEntityLookup,
                settlementAllocationRepository,
                rawMaterialPurchaseRepository,
                invoiceRepository,
                rawMaterialMovementRepository,
                rawMaterialBatchRepository,
                finishedGoodBatchRepository,
                dealerRepository,
                supplierRepository,
                invoiceSettlementPolicy,
                journalReferenceResolver,
                journalReferenceMappingRepository,
                entityManager,
                systemSettingsService,
                auditService,
                accountingEventStore,
                journalEntryService));
    inventoryAccountingService =
        spy(
            new InventoryAccountingService(
                companyContextService,
                accountRepository,
                journalEntryRepository,
                dealerLedgerService,
                supplierLedgerService,
                payrollRunRepository,
                payrollRunLineRepository,
                accountingPeriodService,
                referenceNumberService,
                eventPublisher,
                companyClock,
                companyEntityLookup,
                settlementAllocationRepository,
                rawMaterialPurchaseRepository,
                invoiceRepository,
                rawMaterialMovementRepository,
                rawMaterialBatchRepository,
                finishedGoodBatchRepository,
                dealerRepository,
                supplierRepository,
                invoiceSettlementPolicy,
                journalReferenceResolver,
                journalReferenceMappingRepository,
                entityManager,
                systemSettingsService,
                auditService,
                accountingEventStore,
                journalEntryService));
    dealerReceiptService =
        spy(
            new DealerReceiptService(
                companyContextService,
                accountRepository,
                journalEntryRepository,
                dealerLedgerService,
                supplierLedgerService,
                payrollRunRepository,
                payrollRunLineRepository,
                accountingPeriodService,
                referenceNumberService,
                eventPublisher,
                companyClock,
                companyEntityLookup,
                settlementAllocationRepository,
                rawMaterialPurchaseRepository,
                invoiceRepository,
                rawMaterialMovementRepository,
                rawMaterialBatchRepository,
                finishedGoodBatchRepository,
                dealerRepository,
                supplierRepository,
                invoiceSettlementPolicy,
                journalReferenceResolver,
                journalReferenceMappingRepository,
                entityManager,
                systemSettingsService,
                auditService,
                accountingEventStore,
                journalEntryService));
    settlementService =
        spy(
            new SettlementService(
                companyContextService,
                accountRepository,
                journalEntryRepository,
                dealerLedgerService,
                supplierLedgerService,
                payrollRunRepository,
                payrollRunLineRepository,
                accountingPeriodService,
                referenceNumberService,
                eventPublisher,
                companyClock,
                companyEntityLookup,
                settlementAllocationRepository,
                rawMaterialPurchaseRepository,
                invoiceRepository,
                rawMaterialMovementRepository,
                rawMaterialBatchRepository,
                finishedGoodBatchRepository,
                dealerRepository,
                supplierRepository,
                invoiceSettlementPolicy,
                journalReferenceResolver,
                journalReferenceMappingRepository,
                entityManager,
                systemSettingsService,
                auditService,
                accountingEventStore,
                journalEntryService,
                dealerReceiptService));
    payrollAccountingService =
        spy(
            new PayrollAccountingService(
                companyContextService,
                accountRepository,
                journalEntryRepository,
                dealerLedgerService,
                supplierLedgerService,
                payrollRunRepository,
                payrollRunLineRepository,
                accountingPeriodService,
                referenceNumberService,
                eventPublisher,
                companyClock,
                companyEntityLookup,
                settlementAllocationRepository,
                rawMaterialPurchaseRepository,
                invoiceRepository,
                rawMaterialMovementRepository,
                rawMaterialBatchRepository,
                finishedGoodBatchRepository,
                dealerRepository,
                supplierRepository,
                invoiceSettlementPolicy,
                journalReferenceResolver,
                journalReferenceMappingRepository,
                entityManager,
                systemSettingsService,
                auditService,
                accountingEventStore,
                journalEntryService));
    accountingFacadeProvider = mock(org.springframework.beans.factory.ObjectProvider.class);
    accountingService =
        new AccountingService(
            companyContextService,
            accountRepository,
            journalEntryRepository,
            dealerLedgerService,
            supplierLedgerService,
            payrollRunRepository,
            payrollRunLineRepository,
            accountingPeriodService,
            referenceNumberService,
            eventPublisher,
            companyClock,
            companyEntityLookup,
            settlementAllocationRepository,
            rawMaterialPurchaseRepository,
            invoiceRepository,
            rawMaterialMovementRepository,
            rawMaterialBatchRepository,
            finishedGoodBatchRepository,
            dealerRepository,
            supplierRepository,
            invoiceSettlementPolicy,
            journalReferenceResolver,
            journalReferenceMappingRepository,
            entityManager,
            systemSettingsService,
            auditService,
            accountingEventStore,
            journalEntryService,
            dealerReceiptService,
            settlementService,
            creditDebitNoteService,
            inventoryAccountingService,
            payrollAccountingService,
            accountingFacadeProvider);
    accountingFacade =
        spy(
            new AccountingFacade(
                companyContextService,
                accountRepository,
                accountingService,
                journalEntryRepository,
                referenceNumberService,
                dealerRepository,
                supplierRepository,
                companyClock,
                companyEntityLookup,
                mock(CompanyAccountingSettingsService.class),
                journalReferenceResolver,
                journalReferenceMappingRepository));
    environment = new MockEnvironment();
    ReflectionTestUtils.setField(accountingService, "environment", environment);
    ReflectionTestUtils.setField(journalEntryService, "environment", environment);
    ReflectionTestUtils.setField(dealerReceiptService, "environment", environment);
    ReflectionTestUtils.setField(settlementService, "environment", environment);
    ReflectionTestUtils.setField(creditDebitNoteService, "environment", environment);
    ReflectionTestUtils.setField(inventoryAccountingService, "environment", environment);
    ReflectionTestUtils.setField(payrollAccountingService, "environment", environment);
    company = new Company();
    company.setBaseCurrency("INR");
    lenient().when(companyContextService.requireCurrentCompany()).thenReturn(company);
    lenient().when(accountingFacadeProvider.getIfAvailable()).thenReturn(accountingFacade);
    lenient().when(accountingFacadeProvider.getObject()).thenReturn(accountingFacade);
    lenient().when(companyClock.today(eq(company))).thenReturn(LocalDate.of(2024, 4, 15));
    lenient().when(systemSettingsService.isPeriodLockEnforced()).thenReturn(true);
    lenient()
        .when(accountingPeriodService.requireOpenPeriod(any(), any()))
        .thenReturn(new AccountingPeriod());
    lenient()
        .when(
            accountingPeriodService.requirePostablePeriod(
                any(), any(), any(), any(), any(), anyBoolean()))
        .thenReturn(new AccountingPeriod());
    lenient()
        .when(accountRepository.findByCompanyAndId(any(), any()))
        .thenAnswer(
            invocation -> {
              Long accountId = invocation.getArgument(1);
              if (accountId == null) {
                return Optional.empty();
              }
              Account account = new Account();
              account.setCompany(company);
              account.setCode("ACC-" + accountId);
              account.setType(AccountType.ASSET);
              account.setActive(true);
              ReflectionTestUtils.setField(account, "id", accountId);
              return Optional.of(account);
            });
    lenient()
        .when(accountRepository.lockByCompanyAndId(any(), any()))
        .thenAnswer(
            invocation -> {
              Long accountId = invocation.getArgument(1);
              if (accountId == null) {
                return Optional.empty();
              }
              Account account = new Account();
              account.setCompany(company);
              account.setCode("ACC-" + accountId);
              account.setType(AccountType.ASSET);
              account.setActive(true);
              ReflectionTestUtils.setField(account, "id", accountId);
              return Optional.of(account);
            });
    lenient()
        .when(dealerRepository.lockByCompanyAndId(any(), any()))
        .thenAnswer(
            invocation -> {
              Long dealerId = invocation.getArgument(1);
              if (dealerId == null) {
                return Optional.empty();
              }
              Dealer dealer = new Dealer();
              dealer.setCompany(company);
              dealer.setName("Dealer " + dealerId);
              ReflectionTestUtils.setField(dealer, "id", dealerId);
              Account receivable = new Account();
              receivable.setCompany(company);
              receivable.setCode("AR-" + dealerId);
              receivable.setType(AccountType.ASSET);
              receivable.setActive(true);
              ReflectionTestUtils.setField(receivable, "id", 10_000L + dealerId);
              dealer.setReceivableAccount(receivable);
              return Optional.of(dealer);
            });
    lenient()
        .when(supplierRepository.lockByCompanyAndId(any(), any()))
        .thenAnswer(
            invocation -> {
              Long supplierId = invocation.getArgument(1);
              if (supplierId == null) {
                return Optional.empty();
              }
              Supplier supplier = new Supplier();
              supplier.setCompany(company);
              supplier.setName("Supplier " + supplierId);
              supplier.setStatus(SupplierStatus.ACTIVE);
              ReflectionTestUtils.setField(supplier, "id", supplierId);
              Account payable = new Account();
              payable.setCompany(company);
              payable.setCode("AP-" + supplierId);
              payable.setType(AccountType.LIABILITY);
              payable.setActive(true);
              ReflectionTestUtils.setField(payable, "id", 20_000L + supplierId);
              supplier.setPayableAccount(payable);
              return Optional.of(supplier);
            });
    lenient()
        .when(companyEntityLookup.requireDealer(any(), any()))
        .thenAnswer(
            invocation -> {
              Long dealerId = invocation.getArgument(1);
              Dealer dealer = new Dealer();
              dealer.setCompany(company);
              dealer.setName("Dealer " + dealerId);
              ReflectionTestUtils.setField(dealer, "id", dealerId);
              Account receivable = new Account();
              receivable.setCompany(company);
              receivable.setCode("AR-" + dealerId);
              receivable.setType(AccountType.ASSET);
              receivable.setActive(true);
              ReflectionTestUtils.setField(receivable, "id", 30_000L + dealerId);
              dealer.setReceivableAccount(receivable);
              return dealer;
            });
    lenient()
        .when(companyEntityLookup.requireSupplier(any(), any()))
        .thenAnswer(
            invocation -> {
              Long supplierId = invocation.getArgument(1);
              Supplier supplier = new Supplier();
              supplier.setCompany(company);
              supplier.setName("Supplier " + supplierId);
              supplier.setStatus(SupplierStatus.ACTIVE);
              ReflectionTestUtils.setField(supplier, "id", supplierId);
              Account payable = new Account();
              payable.setCompany(company);
              payable.setCode("AP-" + supplierId);
              payable.setType(AccountType.LIABILITY);
              payable.setActive(true);
              ReflectionTestUtils.setField(payable, "id", 40_000L + supplierId);
              supplier.setPayableAccount(payable);
              return supplier;
            });
    lenient()
        .when(dealerRepository.findByCompanyAndReceivableAccountIn(any(), any()))
        .thenReturn(List.of());
    lenient()
        .when(supplierRepository.findByCompanyAndPayableAccountIn(any(), any()))
        .thenReturn(List.of());
    lenient()
        .when(referenceNumberService.dealerReceiptReference(any(), any()))
        .thenReturn("REF-SETTLE");
    lenient()
        .when(
            journalReferenceMappingRepository.reserveReferenceMapping(
                any(), any(), any(), any(), any()))
        .thenReturn(1);
    lenient()
        .when(
            journalReferenceMappingRepository.reserveManualReference(
                any(), any(), any(), any(), any()))
        .thenReturn(1);
    lenient()
        .when(
            journalReferenceMappingRepository.findByCompanyAndLegacyReferenceIgnoreCase(
                any(), any()))
        .thenReturn(Optional.empty());
  }

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void postPayrollRun_rejectsMissingRunIdentity() {
    assertThatThrownBy(
            () ->
                accountingService.postPayrollRun(
                    "   ", null, LocalDate.of(2026, 2, 12), "Payroll", List.of()))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Payroll run number or id is required for posting");
  }

  @Test
  void listJournalEntries_prefersMappedInvoiceReferenceWhenAvailable() {
    JournalEntry entry = new JournalEntry();
    ReflectionTestUtils.setField(entry, "id", 901L);
    entry.setCompany(company);
    entry.setReferenceNumber("INV-SO-901");
    entry.setEntryDate(LocalDate.of(2026, 2, 23));
    entry.setStatus("POSTED");

    JournalReferenceMapping invoiceAlias = new JournalReferenceMapping();
    invoiceAlias.setLegacyReference("TEST-INV-2026-00091");
    invoiceAlias.setCanonicalReference("INV-SO-901");

    when(journalEntryRepository.findByCompanyOrderByEntryDateDescIdDesc(
            eq(company), any(PageRequest.class)))
        .thenReturn(new PageImpl<>(List.of(entry)));
    when(journalReferenceMappingRepository.findAllByCompanyAndCanonicalReferenceIgnoreCase(
            company, "INV-SO-901"))
        .thenReturn(List.of(invoiceAlias));

    List<JournalEntryDto> listed = accountingService.listJournalEntries(null, null, 0, 50);

    assertThat(listed).hasSize(1);
    assertThat(listed.get(0).referenceNumber()).isEqualTo("TEST-INV-2026-00091");
  }

  @Test
  void listJournalEntries_rejectsDealerAndSupplierBothProvided() {
    assertThatThrownBy(() -> accountingService.listJournalEntries(10L, 20L, 0, 50))
        .isInstanceOfSatisfying(
            ApplicationException.class,
            ex -> {
              assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_INVALID_INPUT);
              assertThat(ex)
                  .hasMessageContaining("Only one of dealerId or supplierId can be provided");
            });
  }

  @Test
  @Tag("critical")
  void listJournals_rejectsInvalidDateRange() {
    LocalDate fromDate = LocalDate.of(2026, 3, 5);
    LocalDate toDate = LocalDate.of(2026, 3, 4);

    assertThatThrownBy(() -> accountingService.listJournals(fromDate, toDate, null, null, 0, 50))
        .isInstanceOfSatisfying(
            ApplicationException.class,
            ex -> {
              assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_INVALID_DATE);
              assertThat(ex).hasMessageContaining("fromDate cannot be after toDate");
              assertThat(ex.getDetails())
                  .containsEntry("fromDate", fromDate)
                  .containsEntry("toDate", toDate);
            });
  }

  @Test
  @Tag("critical")
  void listJournals_filtersByDateTypeAndSourceModule() {
    JournalEntry matching = new JournalEntry();
    ReflectionTestUtils.setField(matching, "id", 902L);
    matching.setCompany(company);
    matching.setReferenceNumber("JRN-MAN-1");
    matching.setEntryDate(LocalDate.of(2026, 3, 5));
    matching.setMemo("Manual match");
    matching.setStatus("POSTED");
    matching.setJournalType(JournalEntryType.MANUAL);
    matching.setSourceModule("MANUAL");
    matching.setSourceReference("MAN-SRC-1");
    matching.addLine(
            journalLine(
                matching,
                account(501L, "CASH", AccountType.ASSET),
                "Debit",
                new BigDecimal("125.00"),
                BigDecimal.ZERO));
    matching.addLine(
            journalLine(
                matching,
                account(502L, "REV", AccountType.REVENUE),
                "Credit",
                BigDecimal.ZERO,
                new BigDecimal("125.00")));

    JournalEntry wrongType = new JournalEntry();
    ReflectionTestUtils.setField(wrongType, "id", 903L);
    wrongType.setCompany(company);
    wrongType.setReferenceNumber("JRN-AUTO-1");
    wrongType.setEntryDate(LocalDate.of(2026, 3, 5));
    wrongType.setJournalType(JournalEntryType.AUTOMATED);
    wrongType.setSourceModule("MANUAL");

    JournalEntry wrongModule = new JournalEntry();
    ReflectionTestUtils.setField(wrongModule, "id", 904L);
    wrongModule.setCompany(company);
    wrongModule.setReferenceNumber("JRN-MAN-2");
    wrongModule.setEntryDate(LocalDate.of(2026, 3, 5));
    wrongModule.setJournalType(JournalEntryType.MANUAL);
    wrongModule.setSourceModule("SALES");

    JournalEntry wrongDate = new JournalEntry();
    ReflectionTestUtils.setField(wrongDate, "id", 905L);
    wrongDate.setCompany(company);
    wrongDate.setReferenceNumber("JRN-MAN-3");
    wrongDate.setEntryDate(LocalDate.of(2026, 2, 28));
    wrongDate.setJournalType(JournalEntryType.MANUAL);
    wrongDate.setSourceModule("MANUAL");

    when(journalEntryRepository.findAll(any(Specification.class), any(PageRequest.class)))
        .thenReturn(new PageImpl<>(List.of(matching), PageRequest.of(0, 200), 1));

    PageResponse<JournalListItemDto> listed =
        accountingService.listJournals(
            LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31), " manual ", " manual ", 0, 250);

    ArgumentCaptor<PageRequest> pageableCaptor = ArgumentCaptor.forClass(PageRequest.class);
    verify(journalEntryRepository).findAll(any(Specification.class), pageableCaptor.capture());
    verify(journalEntryRepository, never()).findByCompanyOrderByEntryDateDesc(company);
    assertThat(pageableCaptor.getValue().getPageNumber()).isEqualTo(0);
    assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(200);

    assertThat(listed.content())
        .singleElement()
        .satisfies(
            item -> {
              assertThat(item.id()).isEqualTo(902L);
              assertThat(item.referenceNumber()).isEqualTo("JRN-MAN-1");
              assertThat(item.journalType()).isEqualTo("MANUAL");
              assertThat(item.sourceModule()).isEqualTo("MANUAL");
              assertThat(item.totalDebit()).isEqualByComparingTo("125.00");
              assertThat(item.totalCredit()).isEqualByComparingTo("125.00");
            });
    assertThat(listed.page()).isEqualTo(0);
    assertThat(listed.size()).isEqualTo(200);
    assertThat(listed.totalElements()).isEqualTo(1);
  }

  @Test
  void createStandardJournal_buildsAutomatedJournalRequestFromResolvedLines() {
    ArgumentCaptor<JournalEntryRequest> requestCaptor =
        ArgumentCaptor.forClass(JournalEntryRequest.class);
    doReturn(stubEntry(501L)).when(journalEntryService).createJournalEntry(requestCaptor.capture());

    JournalEntryDto result =
        accountingService.createStandardJournal(
            new JournalCreationRequest(
                new BigDecimal("125.00"),
                null,
                null,
                "  Standard entry  ",
                " sales ",
                " SRC-125 ",
                null,
                List.of(
                    new JournalCreationRequest.LineRequest(
                        501L, new BigDecimal("125.00"), BigDecimal.ZERO, " Debit line "),
                    new JournalCreationRequest.LineRequest(
                        502L, BigDecimal.ZERO, new BigDecimal("125.00"), " Credit line ")),
                LocalDate.of(2026, 3, 5),
                77L,
                null,
                true,
                List.of("att-1", "att-2")));

    JournalEntryRequest forwarded = requestCaptor.getValue();
    assertThat(result.id()).isEqualTo(501L);
    assertThat(forwarded.referenceNumber()).isEqualTo("SRC-125");
    assertThat(forwarded.entryDate()).isEqualTo(LocalDate.of(2026, 3, 5));
    assertThat(forwarded.memo()).isEqualTo("Standard entry");
    assertThat(forwarded.dealerId()).isEqualTo(77L);
    assertThat(forwarded.adminOverride()).isTrue();
    assertThat(forwarded.sourceModule()).isEqualTo("sales");
    assertThat(forwarded.sourceReference()).isEqualTo("SRC-125");
    assertThat(forwarded.journalType()).isEqualTo(JournalEntryType.AUTOMATED.name());
    assertThat(forwarded.attachmentReferences()).containsExactly("att-1", "att-2");
    assertThat(forwarded.lines())
        .containsExactly(
            new JournalEntryRequest.JournalLineRequest(
                501L, " Debit line ", new BigDecimal("125.00"), BigDecimal.ZERO),
            new JournalEntryRequest.JournalLineRequest(
                502L, " Credit line ", BigDecimal.ZERO, new BigDecimal("125.00")));
  }

  @Test
  void createManualJournal_buildsManualEntryRequestAndDefaultsLineNarration() {
    ArgumentCaptor<JournalCreationRequest> requestCaptor =
        ArgumentCaptor.forClass(JournalCreationRequest.class);
    doReturn(stubEntry(601L)).when(accountingFacade).createStandardJournal(requestCaptor.capture());

    JournalEntryDto result =
        accountingService.createManualJournal(
            new ManualJournalRequest(
                LocalDate.of(2026, 3, 6),
                "  Manual reason  ",
                "MAN-KEY-1",
                true,
                List.of(
                    new ManualJournalRequest.LineRequest(
                        601L, new BigDecimal("50.00"), null, ManualJournalRequest.EntryType.DEBIT),
                    new ManualJournalRequest.LineRequest(
                        602L,
                        new BigDecimal("50.00"),
                        "  Cash offset  ",
                        ManualJournalRequest.EntryType.CREDIT)),
                List.of("att-manual")));

    JournalCreationRequest forwarded = requestCaptor.getValue();
    assertThat(result.id()).isEqualTo(601L);
    assertThat(forwarded.entryDate()).isEqualTo(LocalDate.of(2026, 3, 6));
    assertThat(forwarded.narration()).isEqualTo("Manual reason");
    assertThat(forwarded.adminOverride()).isTrue();
    assertThat(forwarded.sourceModule()).isEqualTo("MANUAL");
    assertThat(forwarded.sourceReference()).isEqualTo("MAN-KEY-1");
    assertThat(forwarded.attachmentReferences()).containsExactly("att-manual");
    assertThat(forwarded.lines())
        .containsExactly(
            new JournalCreationRequest.LineRequest(
                601L, new BigDecimal("50.00"), BigDecimal.ZERO, "Manual reason"),
            new JournalCreationRequest.LineRequest(
                602L, BigDecimal.ZERO, new BigDecimal("50.00"), "Cash offset"));
  }

  @Test
  void createManualJournal_requiresAccountingFacade() {
    when(accountingFacadeProvider.getIfAvailable()).thenReturn(null);

    assertThatThrownBy(
            () ->
                accountingService.createManualJournal(
                    new ManualJournalRequest(
                        LocalDate.of(2026, 3, 6),
                        "Manual reason",
                        "MAN-KEY-NULL",
                        false,
                        List.of(
                            new ManualJournalRequest.LineRequest(
                                601L,
                                new BigDecimal("50.00"),
                                null,
                                ManualJournalRequest.EntryType.DEBIT),
                            new ManualJournalRequest.LineRequest(
                                602L,
                                new BigDecimal("50.00"),
                                null,
                                ManualJournalRequest.EntryType.CREDIT)))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("AccountingFacade is required");
  }

  @Test
  void createManualJournalEntry_returnsExistingWhenIdempotencyReferenceAlreadyExists() {
    JournalEntry existing = new JournalEntry();
    ReflectionTestUtils.setField(existing, "id", 701L);
    existing.setCompany(company);
    existing.setReferenceNumber("MAN-EXIST-1");
    existing.setEntryDate(LocalDate.of(2026, 3, 7));
    existing.setMemo("Existing manual");
    existing.setStatus("POSTED");

    when(journalEntryRepository.findByCompanyAndReferenceNumber(company, "MAN-EXIST-1"))
        .thenReturn(Optional.of(existing));

    JournalEntryDto result =
        accountingService.createManualJournalEntry(
            new JournalEntryRequest(
                null,
                LocalDate.of(2026, 3, 7),
                "Existing manual",
                null,
                null,
                true,
                List.of(
                    new JournalEntryRequest.JournalLineRequest(
                        701L, "debit", new BigDecimal("10.00"), BigDecimal.ZERO),
                    new JournalEntryRequest.JournalLineRequest(
                        702L, "credit", BigDecimal.ZERO, new BigDecimal("10.00"))),
                null,
                null,
                "MANUAL",
                "MAN-EXIST-1",
                JournalEntryType.MANUAL.name(),
                List.of()),
            "MAN-EXIST-1");

    assertThat(result.id()).isEqualTo(701L);
    assertThat(result.referenceNumber()).isEqualTo("MAN-EXIST-1");
    verify(journalReferenceResolver, never()).findExistingEntry(any(), any());
  }

  @Test
  void createManualJournalEntry_returnsExistingWhenResolverFindsCanonicalReplay() {
    JournalEntry existing = journalEntry(702L, "MAN-RESOLVER-1");
    existing.setEntryDate(LocalDate.of(2026, 3, 8));
    existing.setMemo("Resolver replay");
    existing.setStatus("POSTED");

    when(journalEntryRepository.findByCompanyAndReferenceNumber(company, "MAN-RESOLVER-1"))
        .thenReturn(Optional.empty());
    when(journalReferenceResolver.findExistingEntry(company, "MAN-RESOLVER-1"))
        .thenReturn(Optional.of(existing));

    JournalEntryDto result =
        accountingService.createManualJournalEntry(
            new JournalEntryRequest(
                null,
                LocalDate.of(2026, 3, 8),
                "Resolver replay",
                null,
                null,
                true,
                List.of(
                    new JournalEntryRequest.JournalLineRequest(
                        702L, "debit", new BigDecimal("10.00"), BigDecimal.ZERO),
                    new JournalEntryRequest.JournalLineRequest(
                        703L, "credit", BigDecimal.ZERO, new BigDecimal("10.00"))),
                null,
                null,
                "MANUAL",
                "MAN-RESOLVER-1",
                JournalEntryType.MANUAL.name(),
                List.of()),
            "  MAN-RESOLVER-1  ");

    assertThat(result.id()).isEqualTo(702L);
    assertThat(result.referenceNumber()).isEqualTo("MAN-RESOLVER-1");
    verify(journalReferenceMappingRepository, never())
        .reserveManualReference(any(), any(), any(), any(), any());
  }

  @Test
  void createManualJournalEntry_returnsAwaitedReplayWhenReservationAlreadyHeld() {
    ReflectionTestUtils.setField(company, "id", 88L);
    JournalEntry existing = journalEntry(703L, "MAN-RACE-1");
    existing.setEntryDate(LocalDate.of(2026, 3, 9));
    existing.setMemo("Race replay");
    existing.setStatus("POSTED");

    when(journalEntryRepository.findByCompanyAndReferenceNumber(company, "MAN-RACE-1"))
        .thenReturn(Optional.empty());
    when(journalReferenceResolver.findExistingEntry(company, "MAN-RACE-1"))
        .thenReturn(Optional.empty(), Optional.empty(), Optional.of(existing));
    when(journalReferenceMappingRepository.reserveManualReference(
            eq(88L), any(), any(), eq("JOURNAL_ENTRY"), any()))
        .thenReturn(0);

    JournalEntryDto result =
        accountingService.createManualJournalEntry(
            new JournalEntryRequest(
                null,
                LocalDate.of(2026, 3, 9),
                "Race replay",
                null,
                null,
                true,
                List.of(
                    new JournalEntryRequest.JournalLineRequest(
                        703L, "debit", new BigDecimal("10.00"), BigDecimal.ZERO),
                    new JournalEntryRequest.JournalLineRequest(
                        704L, "credit", BigDecimal.ZERO, new BigDecimal("10.00"))),
                null,
                null,
                "MANUAL",
                "MAN-RACE-1",
                JournalEntryType.MANUAL.name(),
                List.of()),
            "MAN-RACE-1");

    assertThat(result.id()).isEqualTo(703L);
    assertThat(result.referenceNumber()).isEqualTo("MAN-RACE-1");
  }

  @Test
  void createManualJournalEntry_replaysAfterRetryableCreateFailure() {
    ReflectionTestUtils.setField(company, "id", 89L);
    JournalEntry existing = journalEntry(704L, "MAN-RETRY-1");
    existing.setEntryDate(LocalDate.of(2026, 3, 10));
    existing.setMemo("Retry replay");
    existing.setStatus("POSTED");

    when(journalEntryRepository.findByCompanyAndReferenceNumber(company, "MAN-RETRY-1"))
        .thenReturn(Optional.empty());
    when(journalReferenceResolver.findExistingEntry(company, "MAN-RETRY-1"))
        .thenReturn(Optional.empty(), Optional.empty(), Optional.of(existing));
    when(journalReferenceMappingRepository.reserveManualReference(
            eq(89L), any(), any(), eq("JOURNAL_ENTRY"), any()))
        .thenReturn(1);
    doThrow(new DataIntegrityViolationException("duplicate manual journal"))
        .when(journalEntryService)
        .createJournalEntry(any(JournalEntryRequest.class));

    JournalEntryDto result =
        accountingService.createManualJournalEntry(
            new JournalEntryRequest(
                null,
                LocalDate.of(2026, 3, 10),
                "Retry replay",
                null,
                null,
                true,
                List.of(
                    new JournalEntryRequest.JournalLineRequest(
                        704L, "debit", new BigDecimal("10.00"), BigDecimal.ZERO),
                    new JournalEntryRequest.JournalLineRequest(
                        705L, "credit", BigDecimal.ZERO, new BigDecimal("10.00"))),
                null,
                null,
                "MANUAL",
                "MAN-RETRY-1",
                JournalEntryType.MANUAL.name(),
                List.of()),
            "MAN-RETRY-1");

    assertThat(result.id()).isEqualTo(704L);
    assertThat(result.referenceNumber()).isEqualTo("MAN-RETRY-1");
  }

  @Test
  void createManualJournalEntry_updatesReservedMappingAfterSuccessfulCreate() {
    ReflectionTestUtils.setField(company, "id", 90L);
    JournalReferenceMapping mapping = new JournalReferenceMapping();
    JournalEntryDto created = journalEntryDto(705L, "JRN-705");

    when(journalEntryRepository.findByCompanyAndReferenceNumber(company, "MAN-CREATE-1"))
        .thenReturn(Optional.empty());
    when(journalReferenceResolver.findExistingEntry(company, "MAN-CREATE-1"))
        .thenReturn(Optional.empty());
    when(journalReferenceMappingRepository.reserveManualReference(
            eq(90L), any(), any(), eq("JOURNAL_ENTRY"), any()))
        .thenReturn(1);
    when(journalReferenceMappingRepository.findAllByCompanyAndLegacyReferenceIgnoreCase(
            eq(company), any()))
        .thenReturn(List.of(mapping));
    doReturn(created).when(journalEntryService).createJournalEntry(any(JournalEntryRequest.class));

    JournalEntryDto result =
        accountingService.createManualJournalEntry(
            new JournalEntryRequest(
                null,
                LocalDate.of(2026, 3, 11),
                "Created manual",
                null,
                null,
                true,
                List.of(
                    new JournalEntryRequest.JournalLineRequest(
                        705L, "debit", new BigDecimal("10.00"), BigDecimal.ZERO),
                    new JournalEntryRequest.JournalLineRequest(
                        706L, "credit", BigDecimal.ZERO, new BigDecimal("10.00"))),
                null,
                null,
                "MANUAL",
                "MAN-CREATE-1",
                JournalEntryType.MANUAL.name(),
                List.of()),
            "MAN-CREATE-1");

    assertThat(result.id()).isEqualTo(705L);
    assertThat(result.referenceNumber()).isEqualTo("JRN-705");
    assertThat(mapping.getCanonicalReference()).isEqualTo("JRN-705");
    assertThat(mapping.getEntityId()).isEqualTo(705L);
    verify(journalReferenceMappingRepository).save(mapping);
  }

  @Test
  void createJournalEntry_rejectsNullRequest() {
    assertThatThrownBy(() -> accountingService.createJournalEntry(null))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Journal entry request is required");
  }

  @Test
  void createJournalEntry_rejectsEmptyLines() {
    LocalDate today = LocalDate.of(2024, 4, 4);

    assertThatThrownBy(
            () ->
                accountingService.createJournalEntry(
                    new JournalEntryRequest(
                        "NO-LINES", today, "No lines", null, null, Boolean.FALSE, List.of())))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("At least one journal line is required");
  }

  @Test
  void createJournalEntry_requiresMemoForManualJournal() {
    LocalDate today = LocalDate.of(2024, 4, 4);

    assertThatThrownBy(
            () ->
                accountingService.createJournalEntry(
                    new JournalEntryRequest(
                        "MANUAL-NO-MEMO",
                        today,
                        "   ",
                        null,
                        null,
                        Boolean.FALSE,
                        List.of(
                            new JournalEntryRequest.JournalLineRequest(
                                1L, "Line", new BigDecimal("10.00"), BigDecimal.ZERO)),
                        null,
                        null,
                        "manual",
                        "manual-ref",
                        JournalEntryType.MANUAL.name(),
                        List.of())))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Manual journal reason is required");
  }

  @Test
  void postPayrollRun_usesLegacyRunTokenForReferenceAndMemo() {
    when(companyClock.today(company)).thenReturn(LocalDate.of(2026, 2, 12));
    List<JournalEntryRequest.JournalLineRequest> lines =
        List.of(
            new JournalEntryRequest.JournalLineRequest(
                11L, "Payroll expense", new BigDecimal("1000.00"), BigDecimal.ZERO),
            new JournalEntryRequest.JournalLineRequest(
                12L, "Payroll payable", BigDecimal.ZERO, new BigDecimal("1000.00")));

    ArgumentCaptor<JournalEntryRequest> requestCaptor =
        ArgumentCaptor.forClass(JournalEntryRequest.class);
    doReturn(null).when(journalEntryService).createJournalEntry(requestCaptor.capture());

    accountingService.postPayrollRun(null, 44L, null, null, lines);

    JournalEntryRequest posted = requestCaptor.getValue();
    assertThat(posted.referenceNumber()).isEqualTo("PAYROLL-LEGACY-44");
    assertThat(posted.entryDate()).isEqualTo(LocalDate.of(2026, 2, 12));
    assertThat(posted.memo()).isEqualTo("Payroll - LEGACY-44");
    assertThat(posted.lines()).isEqualTo(lines);
  }

  @Test
  void recordPayrollPayment_rejectsRunsThatAreNotPostedOrPaid() {
    PayrollRun run = new PayrollRun();
    ReflectionTestUtils.setField(run, "id", 44L);
    run.setStatus(PayrollRun.PayrollStatus.DRAFT);
    when(companyEntityLookup.lockPayrollRun(company, 44L)).thenReturn(run);

    PayrollPaymentRequest request =
        new PayrollPaymentRequest(
            44L, 10L, 20L, new BigDecimal("100.00"), "PAY-44", "payment");

    assertThatThrownBy(() -> accountingService.recordPayrollPayment(request))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Payroll must be posted to accounting before recording payment");
  }

  @Test
  void recordPayrollPayment_createsPaymentJournalForPostedRun() {
    PayrollRun run = new PayrollRun();
    ReflectionTestUtils.setField(run, "id", 44L);
    run.setStatus(PayrollRun.PayrollStatus.POSTED);
    run.setJournalEntryId(501L);
    run.setRunNumber("RUN-44");
    run.setRunDate(LocalDate.of(2026, 2, 12));

    Account cashAccount = account(10L, "BANK", AccountType.ASSET);
    Account salaryPayableAccount = account(20L, "SALARY-PAYABLE", AccountType.LIABILITY);
    JournalEntry postingJournal = new JournalEntry();
    ReflectionTestUtils.setField(postingJournal, "id", 501L);
    ReflectionTestUtils.setField(
        postingJournal,
        "lines",
        List.of(
            journalLine(
                postingJournal,
                salaryPayableAccount,
                "Payroll payable",
                BigDecimal.ZERO,
                new BigDecimal("100.00"))));

    JournalEntry paymentJournal = new JournalEntry();
    ReflectionTestUtils.setField(paymentJournal, "id", 701L);

    when(companyEntityLookup.lockPayrollRun(company, 44L)).thenReturn(run);
    when(companyEntityLookup.requireAccount(company, 10L)).thenReturn(cashAccount);
    when(accountRepository.findByCompanyAndCodeIgnoreCase(company, "SALARY-PAYABLE"))
        .thenReturn(Optional.of(salaryPayableAccount));
    when(companyEntityLookup.requireJournalEntry(company, 501L)).thenReturn(postingJournal);
    when(companyEntityLookup.requireJournalEntry(company, 701L)).thenReturn(paymentJournal);
    doReturn(stubEntry(701L)).when(payrollAccountingService).createJournalEntry(any());

    PayrollPaymentRequest request =
        new PayrollPaymentRequest(44L, 10L, 20L, new BigDecimal("100.00"), null, "payment");

    JournalEntryDto result = accountingService.recordPayrollPayment(request);

    assertThat(result.id()).isEqualTo(701L);
    assertThat(run.getPaymentJournalEntryId()).isEqualTo(701L);
    verify(payrollRunRepository).save(run);
  }

  @Test
  @Tag("critical")
  void resolvePayrollRunToken_appendsRunIdWhenRunNumberDoesNotContainSuffix() {
    String token =
        ReflectionTestUtils.invokeMethod(
            payrollAccountingService, "resolvePayrollRunToken", "FEB-2026", 44L);

    assertThat(token).isEqualTo("FEB-2026-44");
  }

  @Test
  @Tag("critical")
  void resolvePayrollRunToken_preservesLegacyAndSuffixRunNumbers() {
    String legacyToken =
        ReflectionTestUtils.invokeMethod(
            payrollAccountingService, "resolvePayrollRunToken", "LEGACY-44", 44L);
    String suffixedToken =
        ReflectionTestUtils.invokeMethod(
            payrollAccountingService, "resolvePayrollRunToken", "FEB-2026-44", 44L);

    assertThat(legacyToken).isEqualTo("LEGACY-44");
    assertThat(suffixedToken).isEqualTo("FEB-2026-44");
  }

  @Test
  void resolvePayrollPaymentReference_usesLegacyTokenWhenRunNumberMissing() {
    PayrollRun run = new PayrollRun();
    ReflectionTestUtils.setField(run, "id", 77L);
    PayrollPaymentRequest request =
        new PayrollPaymentRequest(77L, 2L, 1L, new BigDecimal("800.00"), null, "Payroll clear");

    String reference =
        ReflectionTestUtils.invokeMethod(
            payrollAccountingService, "resolvePayrollPaymentReference", run, request, company);

    assertThat(reference).isEqualTo("PAYROLL-PAY-LEGACY-77");
  }

  @Test
  void resolvePayrollPaymentReference_fallsBackToSequenceWhenRunIdentityMissing() {
    PayrollRun run = new PayrollRun();
    PayrollPaymentRequest request =
        new PayrollPaymentRequest(7L, 2L, 1L, new BigDecimal("800.00"), null, "Payroll clear");
    when(referenceNumberService.payrollPaymentReference(company)).thenReturn("PAYROLL-PAY-AUTO-1");

    String reference =
        ReflectionTestUtils.invokeMethod(
            payrollAccountingService, "resolvePayrollPaymentReference", run, request, company);

    assertThat(reference).isEqualTo("PAYROLL-PAY-AUTO-1");
  }

  @Test
  void createJournalEntry_rejectsEntriesOlderThan30Days() {
    LocalDate today = LocalDate.of(2024, 1, 31);
    when(companyClock.today(company)).thenReturn(today);

    JournalEntryRequest request =
        new JournalEntryRequest(
            "OLD-REF",
            today.minusDays(31),
            "Old period posting",
            null,
            null,
            Boolean.FALSE,
            List.of(
                new JournalEntryRequest.JournalLineRequest(
                    1L, "Old", new BigDecimal("10.00"), BigDecimal.ZERO)));

    assertThatThrownBy(() -> accountingService.createJournalEntry(request))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Entry date cannot be more than 30 days old");
  }

  @Test
  void createJournalEntry_invalidDateSkipsPeriodCreationWhenLocksDisabled() {
    LocalDate today = LocalDate.of(2024, 1, 31);
    when(companyClock.today(company)).thenReturn(today);
    when(systemSettingsService.isPeriodLockEnforced()).thenReturn(false);

    JournalEntryRequest request =
        new JournalEntryRequest(
            "OLD-NO-PERIOD",
            today.minusDays(31),
            "Old period posting",
            null,
            null,
            Boolean.FALSE,
            List.of(
                new JournalEntryRequest.JournalLineRequest(
                    1L, "Old", new BigDecimal("10.00"), BigDecimal.ZERO)));

    assertThatThrownBy(() -> accountingService.createJournalEntry(request))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Entry date cannot be more than 30 days old");

    verify(accountingPeriodService, never()).ensurePeriod(any(), any());
    verify(accountingPeriodService, never())
        .requirePostablePeriod(any(), any(), any(), any(), any(), anyBoolean());
  }

  @Test
  void createJournalEntry_prodIgnoresBenchmarkDateValidationBypass() {
    LocalDate today = LocalDate.of(2024, 1, 31);
    when(companyClock.today(company)).thenReturn(today);
    environment.setActiveProfiles("prod");
    ReflectionTestUtils.setField(accountingService, "skipDateValidation", true);

    JournalEntryRequest request =
        new JournalEntryRequest(
            "OLD-PROD-REF",
            today.minusDays(31),
            "Old period posting",
            null,
            null,
            Boolean.FALSE,
            List.of(
                new JournalEntryRequest.JournalLineRequest(
                    1L, "Old", new BigDecimal("10.00"), BigDecimal.ZERO)));

    assertThatThrownBy(() -> accountingService.createJournalEntry(request))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Entry date cannot be more than 30 days old");
  }

  @Test
  void createJournalEntry_rejectsClosedPeriod() {
    LocalDate today = LocalDate.of(2024, 2, 1);
    when(companyClock.today(company)).thenReturn(today);
    doThrow(
            new ApplicationException(
                ErrorCode.VALIDATION_INVALID_INPUT, "Accounting period is closed"))
        .when(accountingPeriodService)
        .requirePostablePeriod(any(), any(), any(), any(), any(), anyBoolean());

    JournalEntryRequest request =
        new JournalEntryRequest(
            "CLOSED-REF",
            today,
            "Closed period",
            null,
            null,
            Boolean.FALSE,
            List.of(
                new JournalEntryRequest.JournalLineRequest(
                    1L, "Closed", new BigDecimal("25.00"), BigDecimal.ZERO)));

    assertThatThrownBy(() -> accountingService.createJournalEntry(request))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Accounting period is closed");
  }

  @Test
  void createJournalEntry_adminOverridePassesDocumentContextToPeriodAuthorization() {
    LocalDate today = LocalDate.of(2024, 2, 1);
    when(companyClock.today(company)).thenReturn(today);
    when(accountingPeriodService.requirePostablePeriod(
            eq(company), eq(today), any(), any(), any(), anyBoolean()))
        .thenReturn(openPeriod(today));

    Account debitAccount = new Account();
    debitAccount.setCompany(company);
    debitAccount.setActive(true);
    debitAccount.setType(AccountType.ASSET);
    debitAccount.setBalance(BigDecimal.ZERO);
    ReflectionTestUtils.setField(debitAccount, "id", 1L);

    Account creditAccount = new Account();
    creditAccount.setCompany(company);
    creditAccount.setActive(true);
    creditAccount.setType(AccountType.LIABILITY);
    creditAccount.setBalance(BigDecimal.ZERO);
    ReflectionTestUtils.setField(creditAccount, "id", 2L);

    when(accountRepository.lockByCompanyAndId(eq(company), eq(1L)))
        .thenReturn(Optional.of(debitAccount));
    when(accountRepository.lockByCompanyAndId(eq(company), eq(2L)))
        .thenReturn(Optional.of(creditAccount));
    when(accountRepository.updateBalanceAtomic(eq(company), any(), any())).thenReturn(1);
    when(journalEntryRepository.save(any(JournalEntry.class)))
        .thenAnswer(
            invocation -> {
              JournalEntry entry = invocation.getArgument(0);
              ReflectionTestUtils.setField(entry, "id", 4001L);
              return entry;
            });

    JournalEntryRequest request =
        new JournalEntryRequest(
            "OVERRIDE-REF",
            today,
            "Admin override close-period posting",
            null,
            null,
            Boolean.TRUE,
            List.of(
                new JournalEntryRequest.JournalLineRequest(
                    1L, "Debit", new BigDecimal("25.00"), BigDecimal.ZERO),
                new JournalEntryRequest.JournalLineRequest(
                    2L, "Credit", BigDecimal.ZERO, new BigDecimal("25.00"))),
            null,
            null,
            null,
            null,
            null,
            List.of("scan-1"));

    JournalEntryDto result = accountingService.createJournalEntry(request);

    assertThat(result.id()).isEqualTo(4001L);
    verify(accountingPeriodService)
        .requirePostablePeriod(
            eq(company),
            eq(today),
            eq("JOURNAL_ENTRY"),
            eq("OVERRIDE-REF"),
            eq("Admin override close-period posting"),
            eq(true));
  }

  @Test
  void createJournalEntry_manualSourcePreservesAttachmentsAndLinksClosedPeriodException() {
    ClosedPeriodPostingExceptionService exceptionService =
        org.mockito.Mockito.mock(ClosedPeriodPostingExceptionService.class);
    ReflectionTestUtils.setField(
        accountingService, "closedPeriodPostingExceptionService", exceptionService);
    ReflectionTestUtils.setField(
        journalEntryService, "closedPeriodPostingExceptionService", exceptionService);

    LocalDate today = LocalDate.of(2024, 2, 2);
    AccountingPeriod closedPeriod = openPeriod(today);
    closedPeriod.setStatus(AccountingPeriodStatus.CLOSED);
    when(companyClock.today(company)).thenReturn(today);
    when(accountingPeriodService.requirePostablePeriod(
            eq(company), eq(today), any(), any(), any(), anyBoolean()))
        .thenReturn(closedPeriod);

    Account debitAccount = new Account();
    debitAccount.setCompany(company);
    debitAccount.setActive(true);
    debitAccount.setType(AccountType.ASSET);
    debitAccount.setBalance(BigDecimal.ZERO);
    ReflectionTestUtils.setField(debitAccount, "id", 11L);

    Account creditAccount = new Account();
    creditAccount.setCompany(company);
    creditAccount.setActive(true);
    creditAccount.setType(AccountType.LIABILITY);
    creditAccount.setBalance(BigDecimal.ZERO);
    ReflectionTestUtils.setField(creditAccount, "id", 12L);

    when(accountRepository.lockByCompanyAndId(eq(company), eq(11L)))
        .thenReturn(Optional.of(debitAccount));
    when(accountRepository.lockByCompanyAndId(eq(company), eq(12L)))
        .thenReturn(Optional.of(creditAccount));
    when(accountRepository.updateBalanceAtomic(eq(company), any(), any())).thenReturn(1);
    when(journalEntryRepository.save(any(JournalEntry.class)))
        .thenAnswer(
            invocation -> {
              JournalEntry entry = invocation.getArgument(0);
              ReflectionTestUtils.setField(entry, "id", 4002L);
              return entry;
            });

    JournalEntryRequest request =
        new JournalEntryRequest(
            "MAN-ATT-1",
            today,
            "Manual attachment proof",
            null,
            null,
            Boolean.FALSE,
            List.of(
                new JournalEntryRequest.JournalLineRequest(
                    11L, "Debit", new BigDecimal("25.00"), BigDecimal.ZERO),
                new JournalEntryRequest.JournalLineRequest(
                    12L, "Credit", BigDecimal.ZERO, new BigDecimal("25.00"))),
            null,
            null,
            "MANUAL",
            "MAN-ATT-1",
            null,
            List.of(" scan-1 ", "", "scan-2", "scan-1"));

    JournalEntryDto result = accountingService.createJournalEntry(request);

    assertThat(result.id()).isEqualTo(4002L);

    ArgumentCaptor<JournalEntry> entryCaptor = ArgumentCaptor.forClass(JournalEntry.class);
    verify(journalEntryRepository).save(entryCaptor.capture());
    JournalEntry saved = entryCaptor.getValue();
    assertThat(saved.getSourceModule()).isEqualTo("MANUAL");
    assertThat(saved.getAttachmentReferences()).isEqualTo("scan-1\nscan-2");
    assertThat(saved.getAccountingPeriod()).isSameAs(closedPeriod);
    verify(exceptionService).linkJournalEntry(company, "MANUAL", "MAN-ATT-1", saved);
  }

  @Test
  void settleSupplierInvoices_requiresReasonWhenAdminOverrideRequested() {
    Supplier supplier = new Supplier();
    supplier.setStatus(SupplierStatus.ACTIVE);
    ReflectionTestUtils.setField(supplier, "id", 1L);

    Account payable = new Account();
    payable.setCompany(company);
    payable.setCode("AP-OVERRIDE");
    payable.setType(AccountType.LIABILITY);
    ReflectionTestUtils.setField(payable, "id", 10L);
    supplier.setPayableAccount(payable);

    Account cash = new Account();
    cash.setCompany(company);
    cash.setCode("CASH-OVERRIDE");
    cash.setType(AccountType.ASSET);
    ReflectionTestUtils.setField(cash, "id", 20L);

    RawMaterialPurchase purchase = new RawMaterialPurchase();
    purchase.setCompany(company);
    purchase.setSupplier(supplier);
    purchase.setTotalAmount(new BigDecimal("100.00"));
    purchase.setTaxAmount(BigDecimal.ZERO);
    purchase.setOutstandingAmount(new BigDecimal("100.00"));
    ReflectionTestUtils.setField(purchase, "id", 7003L);

    JournalEntry purchaseJournal = new JournalEntry();
    ReflectionTestUtils.setField(purchaseJournal, "id", 9703L);
    purchaseJournal.setSupplier(supplier);
    purchaseJournal.setReferenceNumber("RMP-OVERRIDE-1");
    purchaseJournal.addLine(
            journalLine(
                purchaseJournal,
                cash,
                "Purchase invoice",
                new BigDecimal("100.00"),
                BigDecimal.ZERO));
    purchaseJournal.addLine(
            journalLine(
                purchaseJournal,
                payable,
                "Purchase invoice",
                BigDecimal.ZERO,
                new BigDecimal("100.00")));
    purchase.setJournalEntry(purchaseJournal);

    when(supplierRepository.lockByCompanyAndId(eq(company), eq(1L)))
        .thenReturn(Optional.of(supplier));

    SupplierSettlementRequest request =
        new SupplierSettlementRequest(
            1L,
            20L,
            null,
            null,
            null,
            null,
            LocalDate.of(2024, 4, 9),
            "SUP-OVERRIDE-1",
            "   ",
            "IDEMP-SUP-OVERRIDE-1",
            Boolean.TRUE,
            List.of(
                new SettlementAllocationRequest(
                    null,
                    7003L,
                    new BigDecimal("10.00"),
                    BigDecimal.ONE,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    null)));

    assertThatThrownBy(() -> accountingService.settleSupplierInvoices(request))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Settlement override reason is required");
  }

  @Test
  void settleDealerInvoices_rejectsHeaderAmountMismatchWithPaymentTotal() {
    Dealer dealer = new Dealer();
    dealer.setName("Mismatch Dealer");
    ReflectionTestUtils.setField(dealer, "id", 1L);

    Account receivable = new Account();
    receivable.setCompany(company);
    receivable.setCode("AR-MISMATCH");
    receivable.setType(AccountType.ASSET);
    ReflectionTestUtils.setField(receivable, "id", 10L);
    dealer.setReceivableAccount(receivable);

    when(dealerRepository.lockByCompanyAndId(eq(company), eq(1L))).thenReturn(Optional.of(dealer));

    DealerSettlementRequest request =
        new DealerSettlementRequest(
            1L,
            20L,
            null,
            null,
            null,
            null,
            new BigDecimal("150.00"),
            null,
            LocalDate.of(2024, 4, 9),
            "DR-MISMATCH-1",
            "Dealer mismatch",
            "IDEMP-DR-MISMATCH-1",
            Boolean.FALSE,
            null,
            List.of(new SettlementPaymentRequest(20L, new BigDecimal("120.00"), "BANK")));

    assertThatThrownBy(() -> accountingService.settleDealerInvoices(request))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("must match the total payment amount");
  }

  @Test
  void settleDealerInvoices_defaultsHeaderAllocationsFromPaymentLinesWhenAmountMissing() {
    AccountingService service = spy(accountingService);

    Dealer dealer = new Dealer();
    dealer.setName("Payment Dealer");
    ReflectionTestUtils.setField(dealer, "id", 1L);

    Account receivable = new Account();
    receivable.setCompany(company);
    receivable.setCode("AR-PAYMENT");
    receivable.setType(AccountType.ASSET);
    ReflectionTestUtils.setField(receivable, "id", 10L);
    dealer.setReceivableAccount(receivable);

    Account cash = new Account();
    cash.setCompany(company);
    cash.setCode("BANK");
    cash.setType(AccountType.ASSET);
    ReflectionTestUtils.setField(cash, "id", 20L);

    Invoice first = new Invoice();
    first.setCompany(company);
    first.setDealer(dealer);
    first.setCurrency("INR");
    first.setOutstandingAmount(new BigDecimal("100.00"));
    first.setTotalAmount(new BigDecimal("100.00"));
    ReflectionTestUtils.setField(first, "id", 711L);

    Invoice second = new Invoice();
    second.setCompany(company);
    second.setDealer(dealer);
    second.setCurrency("INR");
    second.setOutstandingAmount(new BigDecimal("80.00"));
    second.setTotalAmount(new BigDecimal("80.00"));
    ReflectionTestUtils.setField(second, "id", 712L);

    when(dealerRepository.lockByCompanyAndId(eq(company), eq(1L))).thenReturn(Optional.of(dealer));
    when(invoiceRepository.lockOpenInvoicesForSettlement(eq(company), eq(dealer)))
        .thenReturn(List.of(first, second));
    when(invoiceRepository.lockByCompanyAndId(eq(company), eq(711L)))
        .thenReturn(Optional.of(first));
    when(invoiceRepository.lockByCompanyAndId(eq(company), eq(712L)))
        .thenReturn(Optional.of(second));
    when(companyEntityLookup.requireAccount(eq(company), eq(20L))).thenReturn(cash);
    when(companyEntityLookup.requireJournalEntry(eq(company), eq(904L)))
        .thenReturn(new JournalEntry());
    doReturn(stubEntry(904L))
        .when(journalEntryService)
        .createJournalEntry(any(JournalEntryRequest.class));

    DealerSettlementRequest request =
        new DealerSettlementRequest(
            1L,
            20L,
            null,
            null,
            null,
            null,
            null,
            null,
            LocalDate.of(2024, 4, 9),
            "DR-PAYMENT-DEFAULT-1",
            "Dealer payment default",
            "IDEMP-DR-PAYMENT-DEFAULT-1",
            Boolean.FALSE,
            null,
            List.of(
                new SettlementPaymentRequest(20L, new BigDecimal("70.00"), "BANK"),
                new SettlementPaymentRequest(20L, new BigDecimal("50.00"), "BANK")));

    PartnerSettlementResponse response = service.settleDealerInvoices(request);

    assertThat(response.totalApplied()).isEqualByComparingTo("120.00");
    assertThat(response.cashAmount()).isEqualByComparingTo("120.00");
    assertThat(response.allocations()).hasSize(2);
    assertThat(response.allocations().get(0).invoiceId()).isEqualTo(711L);
    assertThat(response.allocations().get(0).appliedAmount()).isEqualByComparingTo("100.00");
    assertThat(response.allocations().get(1).invoiceId()).isEqualTo(712L);
    assertThat(response.allocations().get(1).appliedAmount()).isEqualByComparingTo("20.00");
  }

  @Test
  void settleDealerInvoices_requiresAmountOrPaymentsWhenHeaderAllocationsMissing() {
    Dealer dealer = new Dealer();
    dealer.setName("No Amount Dealer");
    ReflectionTestUtils.setField(dealer, "id", 1L);

    Account receivable = new Account();
    receivable.setCompany(company);
    receivable.setCode("AR-NO-AMOUNT");
    receivable.setType(AccountType.ASSET);
    ReflectionTestUtils.setField(receivable, "id", 10L);
    dealer.setReceivableAccount(receivable);

    when(dealerRepository.lockByCompanyAndId(eq(company), eq(1L))).thenReturn(Optional.of(dealer));

    DealerSettlementRequest request =
        new DealerSettlementRequest(
            1L,
            20L,
            null,
            null,
            null,
            null,
            null,
            null,
            LocalDate.of(2024, 4, 9),
            "DR-NO-AMOUNT-1",
            "Dealer missing amount",
            "IDEMP-DR-NO-AMOUNT-1",
            Boolean.FALSE,
            null,
            null);

    assertThatThrownBy(() -> accountingService.settleDealerInvoices(request))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining(
            "Provide allocations or an amount (or payment lines) for dealer settlements");
  }

  @Test
  void settleDealerInvoices_requiresUnappliedApplicationWhenHeaderAmountExceedsOutstanding() {
    Dealer dealer = new Dealer();
    dealer.setName("Overflow Dealer");
    ReflectionTestUtils.setField(dealer, "id", 1L);

    Account receivable = new Account();
    receivable.setCompany(company);
    receivable.setCode("AR-OVERFLOW");
    receivable.setType(AccountType.ASSET);
    ReflectionTestUtils.setField(receivable, "id", 10L);
    dealer.setReceivableAccount(receivable);

    Invoice invoice = new Invoice();
    invoice.setCompany(company);
    invoice.setDealer(dealer);
    invoice.setOutstandingAmount(new BigDecimal("75.00"));
    ReflectionTestUtils.setField(invoice, "id", 701L);

    when(dealerRepository.lockByCompanyAndId(eq(company), eq(1L))).thenReturn(Optional.of(dealer));
    when(invoiceRepository.lockOpenInvoicesForSettlement(eq(company), eq(dealer)))
        .thenReturn(List.of(invoice));

    DealerSettlementRequest request =
        new DealerSettlementRequest(
            1L,
            20L,
            null,
            null,
            null,
            null,
            new BigDecimal("100.00"),
            null,
            LocalDate.of(2024, 4, 10),
            "DR-OVERFLOW-1",
            "Dealer overflow",
            "IDEMP-DR-OVERFLOW-1",
            Boolean.FALSE,
            null,
            null);

    assertThatThrownBy(() -> accountingService.settleDealerInvoices(request))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("choose ON_ACCOUNT or FUTURE_APPLICATION");
  }

  @Test
  void settleDealerInvoices_requiresUnappliedApplicationWhenNoOpenInvoicesExist() {
    Dealer dealer = new Dealer();
    dealer.setName("No Open Dealer");
    ReflectionTestUtils.setField(dealer, "id", 1L);

    Account receivable = new Account();
    receivable.setCompany(company);
    receivable.setCode("AR-NO-OPEN");
    receivable.setType(AccountType.ASSET);
    ReflectionTestUtils.setField(receivable, "id", 10L);
    dealer.setReceivableAccount(receivable);

    when(dealerRepository.lockByCompanyAndId(eq(company), eq(1L))).thenReturn(Optional.of(dealer));
    when(invoiceRepository.lockOpenInvoicesForSettlement(eq(company), eq(dealer)))
        .thenReturn(List.of());

    DealerSettlementRequest request =
        new DealerSettlementRequest(
            1L,
            20L,
            null,
            null,
            null,
            null,
            new BigDecimal("25.00"),
            null,
            LocalDate.of(2024, 4, 10),
            "DR-NO-OPEN-1",
            "Dealer no open invoices",
            "IDEMP-DR-NO-OPEN-1",
            Boolean.FALSE,
            null,
            null);

    assertThatThrownBy(() -> accountingService.settleDealerInvoices(request))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("No open invoices are available");
  }

  @Test
  void settleDealerInvoices_rejectsDocumentAsHeaderUnappliedApplication() {
    Dealer dealer = new Dealer();
    dealer.setName("Document Dealer");
    ReflectionTestUtils.setField(dealer, "id", 1L);

    Account receivable = new Account();
    receivable.setCompany(company);
    receivable.setCode("AR-DOCUMENT");
    receivable.setType(AccountType.ASSET);
    ReflectionTestUtils.setField(receivable, "id", 10L);
    dealer.setReceivableAccount(receivable);

    Account cash = new Account();
    cash.setCompany(company);
    cash.setCode("CASH-DOCUMENT");
    cash.setType(AccountType.ASSET);
    cash.setActive(true);
    ReflectionTestUtils.setField(cash, "id", 20L);

    Invoice invoice = new Invoice();
    invoice.setCompany(company);
    invoice.setDealer(dealer);
    invoice.setOutstandingAmount(new BigDecimal("25.00"));
    ReflectionTestUtils.setField(invoice, "id", 702L);

    when(dealerRepository.lockByCompanyAndId(eq(company), eq(1L))).thenReturn(Optional.of(dealer));

    DealerSettlementRequest request =
        new DealerSettlementRequest(
            1L,
            20L,
            null,
            null,
            null,
            null,
            new BigDecimal("25.00"),
            SettlementAllocationApplication.DOCUMENT,
            LocalDate.of(2024, 4, 10),
            "DR-DOCUMENT-1",
            "Dealer document unapplied",
            "IDEMP-DR-DOCUMENT-1",
            Boolean.FALSE,
            null,
            null);

    assertThatThrownBy(() -> accountingService.settleDealerInvoices(request))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Unapplied amount handling must be ON_ACCOUNT or FUTURE_APPLICATION");
  }

  @Test
  void settleDealerInvoices_rejectsExplicitAllocationAmountMismatch() {
    Dealer dealer = new Dealer();
    dealer.setName("Explicit Dealer");
    ReflectionTestUtils.setField(dealer, "id", 1L);

    Account receivable = new Account();
    receivable.setCompany(company);
    receivable.setCode("AR-EXPLICIT");
    receivable.setType(AccountType.ASSET);
    ReflectionTestUtils.setField(receivable, "id", 10L);
    dealer.setReceivableAccount(receivable);

    when(dealerRepository.lockByCompanyAndId(eq(company), eq(1L))).thenReturn(Optional.of(dealer));

    DealerSettlementRequest request =
        new DealerSettlementRequest(
            1L,
            20L,
            null,
            null,
            null,
            null,
            new BigDecimal("100.00"),
            null,
            LocalDate.of(2024, 4, 10),
            "DR-EXPLICIT-MISMATCH-1",
            "Dealer explicit mismatch",
            "IDEMP-DR-EXPLICIT-MISMATCH-1",
            Boolean.FALSE,
            List.of(
                new SettlementAllocationRequest(
                    701L,
                    null,
                    new BigDecimal("75.00"),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    SettlementAllocationApplication.DOCUMENT,
                    "Explicit invoice allocation")),
            null);

    assertThatThrownBy(() -> accountingService.settleDealerInvoices(request))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining(
            "Explicit dealer settlement allocations must add up to the request amount");
  }

  @Test
  void settleDealerInvoices_rejectsOnAccountAllocationWithAdjustments() {
    Dealer dealer = new Dealer();
    dealer.setName("Dealer On Account");
    ReflectionTestUtils.setField(dealer, "id", 1L);

    Account receivable = new Account();
    receivable.setCompany(company);
    receivable.setCode("AR-ON-ACCOUNT");
    receivable.setType(AccountType.ASSET);
    ReflectionTestUtils.setField(receivable, "id", 10L);
    dealer.setReceivableAccount(receivable);

    when(dealerRepository.lockByCompanyAndId(eq(company), eq(1L))).thenReturn(Optional.of(dealer));

    DealerSettlementRequest request =
        new DealerSettlementRequest(
            1L,
            20L,
            21L,
            null,
            null,
            null,
            new BigDecimal("25.00"),
            null,
            LocalDate.of(2024, 4, 11),
            "DR-ON-ACCOUNT-1",
            "dealer on account",
            "IDEMP-DR-ON-ACCOUNT-1",
            Boolean.FALSE,
            List.of(
                new SettlementAllocationRequest(
                    null,
                    null,
                    new BigDecimal("25.00"),
                    new BigDecimal("1.00"),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    SettlementAllocationApplication.ON_ACCOUNT,
                    "keep on account")),
            null);

    assertThatThrownBy(() -> accountingService.settleDealerInvoices(request))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining(
            "On-account dealer settlement allocations cannot include discount/write-off/FX"
                + " adjustments");
  }

  @Test
  void settleDealerInvoices_requiresExplicitAdminOverrideForDiscountSettlement() {
    Dealer dealer = new Dealer();
    dealer.setName("Dealer Override");
    ReflectionTestUtils.setField(dealer, "id", 1L);

    Account receivable = new Account();
    receivable.setCompany(company);
    receivable.setCode("AR-OVERRIDE");
    receivable.setType(AccountType.ASSET);
    ReflectionTestUtils.setField(receivable, "id", 10L);
    dealer.setReceivableAccount(receivable);

    when(dealerRepository.lockByCompanyAndId(eq(company), eq(1L))).thenReturn(Optional.of(dealer));

    DealerSettlementRequest request =
        new DealerSettlementRequest(
            1L,
            20L,
            21L,
            null,
            null,
            null,
            new BigDecimal("25.00"),
            null,
            LocalDate.of(2024, 4, 11),
            "DR-OVERRIDE-1",
            "discount without override",
            "IDEMP-DR-OVERRIDE-1",
            Boolean.FALSE,
            List.of(
                new SettlementAllocationRequest(
                    701L,
                    null,
                    new BigDecimal("25.00"),
                    new BigDecimal("1.00"),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    SettlementAllocationApplication.DOCUMENT,
                    "discount allocation")),
            null);

    assertThatThrownBy(() -> accountingService.settleDealerInvoices(request))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Settlement override requires an explicit admin override");
  }

  @Test
  void settleDealerInvoices_postsDiscountSettlementWhenAdminOverrideApproved() {
    SettlementService settlementService =
        new SettlementService(
            companyContextService,
            accountRepository,
            journalEntryRepository,
            dealerLedgerService,
            supplierLedgerService,
            payrollRunRepository,
            payrollRunLineRepository,
            accountingPeriodService,
            referenceNumberService,
            eventPublisher,
            companyClock,
            companyEntityLookup,
            settlementAllocationRepository,
            rawMaterialPurchaseRepository,
            invoiceRepository,
            rawMaterialMovementRepository,
            rawMaterialBatchRepository,
            finishedGoodBatchRepository,
            dealerRepository,
            supplierRepository,
            invoiceSettlementPolicy,
            journalReferenceResolver,
            journalReferenceMappingRepository,
            entityManager,
            systemSettingsService,
            auditService,
            accountingEventStore,
            journalEntryService,
            dealerReceiptService);
    ReflectionTestUtils.setField(settlementService, "environment", environment);
    ReflectionTestUtils.setField(accountingService, "settlementService", settlementService);

    Dealer dealer =
        dealer(92L, "Dealer Override Approved", account(9202L, "AR-9202", AccountType.ASSET));
    Account cash = account(9203L, "BANK-9203", AccountType.ASSET);
    Account discount = account(9204L, "DISC-9204", AccountType.EXPENSE);
    Invoice invoice = invoice(92020L, dealer, "INV-92020", new BigDecimal("25.00"));

    when(dealerRepository.lockByCompanyAndId(eq(company), eq(92L))).thenReturn(Optional.of(dealer));
    when(invoiceRepository.lockByCompanyAndId(eq(company), eq(92020L)))
        .thenReturn(Optional.of(invoice));
    when(companyEntityLookup.requireAccount(eq(company), eq(9203L))).thenReturn(cash);
    when(companyEntityLookup.requireAccount(eq(company), eq(9204L))).thenReturn(discount);
    when(companyEntityLookup.requireJournalEntry(eq(company), eq(906L)))
        .thenReturn(new JournalEntry());
    doReturn(stubEntry(906L))
        .when(journalEntryService)
        .createJournalEntry(any(JournalEntryRequest.class));

    DealerSettlementRequest request =
        new DealerSettlementRequest(
            92L,
            9203L,
            9204L,
            null,
            null,
            null,
            new BigDecimal("25.00"),
            null,
            LocalDate.of(2024, 5, 6),
            "HDR-DEALER-OVERRIDE-OK",
            "Approved dealer discount override",
            "IDEMP-HDR-DEALER-OVERRIDE-OK",
            Boolean.TRUE,
            List.of(
                new SettlementAllocationRequest(
                    92020L,
                    null,
                    new BigDecimal("25.00"),
                    new BigDecimal("1.00"),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    SettlementAllocationApplication.DOCUMENT,
                    "dealer override allocation")),
            null);

    PartnerSettlementResponse response = accountingService.settleDealerInvoices(request);

    assertThat(response.totalApplied()).isEqualByComparingTo("25.00");
    assertThat(response.totalDiscount()).isEqualByComparingTo("1.00");
    assertThat(response.allocations())
        .singleElement()
        .satisfies(
            allocation -> {
              assertThat(allocation.invoiceId()).isEqualTo(92020L);
              assertThat(allocation.discountAmount()).isEqualByComparingTo("1.00");
            });
    verify(invoiceSettlementPolicy)
        .applySettlement(
            eq(invoice), eq(new BigDecimal("25.00")), eq("HDR-DEALER-OVERRIDE-OK-INV-92020"));
  }

  @Test
  void reverseJournalEntry_rejectsLockedPeriod() {
    LocalDate today = LocalDate.of(2024, 4, 1);
    when(companyClock.today(company)).thenReturn(today);

    AccountingPeriod lockedPeriod = new AccountingPeriod();
    lockedPeriod.setStatus(AccountingPeriodStatus.LOCKED);
    lockedPeriod.setYear(2024);
    lockedPeriod.setMonth(3);

    var entry = new com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry();
    entry.setStatus("POSTED");
    entry.setReferenceNumber("REV-LOCK");
    entry.setEntryDate(today.minusDays(1));
    entry.setAccountingPeriod(lockedPeriod);

    when(companyEntityLookup.requireJournalEntry(company, 44L)).thenReturn(entry);

    JournalEntryReversalRequest request =
        new JournalEntryReversalRequest(
            today, false, "Test reversal", "Locked period reversal", Boolean.FALSE);

    assertThatThrownBy(() -> accountingService.reverseJournalEntry(44L, request))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("LOCKED period");
  }

  @Test
  void reverseJournalEntry_rejectsClosedPeriodWithoutOverride() {
    LocalDate today = LocalDate.of(2024, 4, 1);
    when(companyClock.today(company)).thenReturn(today);

    AccountingPeriod closedPeriod = new AccountingPeriod();
    closedPeriod.setStatus(AccountingPeriodStatus.CLOSED);
    closedPeriod.setYear(2024);
    closedPeriod.setMonth(2);

    var entry = new com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry();
    entry.setStatus("POSTED");
    entry.setReferenceNumber("REV-CLOSED");
    entry.setEntryDate(today.minusDays(1));
    entry.setAccountingPeriod(closedPeriod);

    when(companyEntityLookup.requireJournalEntry(company, 45L)).thenReturn(entry);

    JournalEntryReversalRequest request =
        new JournalEntryReversalRequest(
            today, false, "Test reversal", "Closed period reversal", null);

    assertThatThrownBy(() -> accountingService.reverseJournalEntry(45L, request))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("CLOSED period");
  }

  @Test
  void reverseJournalEntry_invalidDateSkipsPeriodLookup() {
    LocalDate today = LocalDate.of(2024, 4, 1);
    when(companyClock.today(company)).thenReturn(today);

    var entry = new com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry();
    entry.setStatus("POSTED");
    entry.setReferenceNumber("REV-OLD-DATE");
    entry.setEntryDate(today.minusDays(1));
    entry.setAccountingPeriod(openPeriod(today.minusDays(1)));
    when(companyEntityLookup.requireJournalEntry(company, 46L)).thenReturn(entry);

    JournalEntryReversalRequest request =
        new JournalEntryReversalRequest(
            today.minusDays(31), false, "Test reversal", "Old reversal", Boolean.FALSE);

    assertThatThrownBy(() -> accountingService.reverseJournalEntry(46L, request))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Entry date cannot be more than 30 days old");

    verify(accountingPeriodService, never())
        .requirePostablePeriod(
            eq(company), eq(today.minusDays(31)), any(), any(), any(), anyBoolean());
  }

  @Test
  void reverseJournalEntry_suppressesLegacySummaryAudit_afterCommit() {
    LocalDate today = LocalDate.of(2024, 4, 1);
    when(companyClock.today(company)).thenReturn(today);
    AccountingPeriod openPeriod = openPeriod(today);
    when(accountingPeriodService.requirePostablePeriod(
            eq(company), eq(today), any(), any(), any(), anyBoolean()))
        .thenReturn(openPeriod);

    JournalEntry original = reversalSourceEntry(500L, "REV-AUDIT-OK", today);
    JournalEntry reversal = new JournalEntry();
    ReflectionTestUtils.setField(reversal, "id", 900L);

    when(companyEntityLookup.requireJournalEntry(company, 500L)).thenReturn(original);
    when(companyEntityLookup.requireJournalEntry(company, 900L)).thenReturn(reversal);
    when(journalEntryRepository.save(any(JournalEntry.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    doReturn(stubEntry(900L))
        .when(journalEntryService)
        .createJournalEntry(any(JournalEntryRequest.class));

    JournalEntryReversalRequest request =
        new JournalEntryReversalRequest(
            today, false, "Test reversal", "Audit commit order", Boolean.FALSE);

    TransactionTemplate transactionTemplate =
        new TransactionTemplate(new ResourcelessTransactionManager());
    transactionTemplate.executeWithoutResult(
        status -> {
          JournalEntryDto result = accountingService.reverseJournalEntry(500L, request);
          assertThat(result.id()).isEqualTo(900L);
          verify(auditService, never()).logSuccess(eq(AuditEvent.JOURNAL_ENTRY_REVERSED), any());
        });

    verify(auditService, never()).logSuccess(eq(AuditEvent.JOURNAL_ENTRY_REVERSED), any());
  }

  @Test
  void reverseJournalEntry_skipsSuccessAudit_whenTransactionRollsBack() {
    LocalDate today = LocalDate.of(2024, 4, 1);
    when(companyClock.today(company)).thenReturn(today);
    AccountingPeriod openPeriod = openPeriod(today);
    when(accountingPeriodService.requirePostablePeriod(
            eq(company), eq(today), any(), any(), any(), anyBoolean()))
        .thenReturn(openPeriod);

    JournalEntry original = reversalSourceEntry(501L, "REV-AUDIT-ROLLBACK", today);
    JournalEntry reversal = new JournalEntry();
    ReflectionTestUtils.setField(reversal, "id", 901L);

    when(companyEntityLookup.requireJournalEntry(company, 501L)).thenReturn(original);
    when(companyEntityLookup.requireJournalEntry(company, 901L)).thenReturn(reversal);
    when(journalEntryRepository.save(any(JournalEntry.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    doReturn(stubEntry(901L))
        .when(journalEntryService)
        .createJournalEntry(any(JournalEntryRequest.class));

    JournalEntryReversalRequest request =
        new JournalEntryReversalRequest(
            today, false, "Test reversal", "Audit rollback order", Boolean.FALSE);

    TransactionTemplate transactionTemplate =
        new TransactionTemplate(new ResourcelessTransactionManager());
    transactionTemplate.executeWithoutResult(
        status -> {
          accountingService.reverseJournalEntry(501L, request);
          status.setRollbackOnly();
          verify(auditService, never()).logSuccess(eq(AuditEvent.JOURNAL_ENTRY_REVERSED), any());
        });

    verify(auditService, never()).logSuccess(eq(AuditEvent.JOURNAL_ENTRY_REVERSED), any());
  }

  @Test
  void reverseJournalEntry_skipsLegacySummaryAudit_withoutToggle() {
    LocalDate today = LocalDate.of(2024, 4, 1);
    when(companyClock.today(company)).thenReturn(today);
    AccountingPeriod openPeriod = openPeriod(today);
    when(accountingPeriodService.requirePostablePeriod(
            eq(company), eq(today), any(), any(), any(), anyBoolean()))
        .thenReturn(openPeriod);

    JournalEntry original = reversalSourceEntry(502L, "REV-AUDIT-POLICY", today);
    JournalEntry reversal = new JournalEntry();
    ReflectionTestUtils.setField(reversal, "id", 902L);

    when(companyEntityLookup.requireJournalEntry(company, 502L)).thenReturn(original);
    when(companyEntityLookup.requireJournalEntry(company, 902L)).thenReturn(reversal);
    when(journalEntryRepository.save(any(JournalEntry.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    doReturn(stubEntry(902L))
        .when(journalEntryService)
        .createJournalEntry(any(JournalEntryRequest.class));

    JournalEntryReversalRequest request =
        new JournalEntryReversalRequest(
            today, false, "Test reversal", "Audit suppression policy", Boolean.FALSE);

    TransactionTemplate transactionTemplate =
        new TransactionTemplate(new ResourcelessTransactionManager());
    transactionTemplate.executeWithoutResult(
        status -> {
          JournalEntryDto result = accountingService.reverseJournalEntry(502L, request);
          assertThat(result.id()).isEqualTo(902L);
        });

    verify(auditService, never()).logSuccess(eq(AuditEvent.JOURNAL_ENTRY_REVERSED), any());
  }

  @Test
  void reverseJournalEntry_returnsDtoFromSavedEntityForStandardPath() {
    LocalDate today = LocalDate.of(2024, 4, 2);
    when(companyClock.today(company)).thenReturn(today);
    AccountingPeriod openPeriod = openPeriod(today);
    when(accountingPeriodService.requirePostablePeriod(
            eq(company), eq(today), any(), any(), any(), anyBoolean()))
        .thenReturn(openPeriod);

    JournalEntry original = reversalSourceEntry(503L, "REV-CONSISTENT-DTO", today);
    JournalEntry reversal = new JournalEntry();
    ReflectionTestUtils.setField(reversal, "id", 903L);

    when(companyEntityLookup.requireJournalEntry(company, 503L)).thenReturn(original);
    when(companyEntityLookup.requireJournalEntry(company, 903L)).thenReturn(reversal);
    when(journalEntryRepository.save(any(JournalEntry.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    doReturn(stubEntry(903L))
        .when(journalEntryService)
        .createJournalEntry(any(JournalEntryRequest.class));

    JournalEntryReversalRequest request =
        new JournalEntryReversalRequest(
            today, false, "Consistent dto", "Standard reversal", Boolean.FALSE);

    JournalEntryDto result = accountingService.reverseJournalEntry(503L, request);

    assertThat(result.id()).isEqualTo(903L);
    assertThat(result.correctionType()).isEqualTo(JournalCorrectionType.REVERSAL.name());
    assertThat(result.correctionReason()).isEqualTo("Consistent dto");
  }

  @Test
  void createJournalEntry_rejectsDealerWithoutReceivableAccount() {
    LocalDate today = LocalDate.of(2024, 3, 15);
    when(companyClock.today(company)).thenReturn(today);
    when(accountingPeriodService.requirePostablePeriod(
            eq(company), eq(today), any(), any(), any(), anyBoolean()))
        .thenReturn(new AccountingPeriod());
    when(journalEntryRepository.findByCompanyAndReferenceNumber(eq(company), eq("DEALER-REF")))
        .thenReturn(Optional.empty());

    Dealer dealer = new Dealer();
    dealer.setName("Test Dealer");
    when(companyEntityLookup.requireDealer(company, 99L)).thenReturn(dealer);

    Account ar = new Account();
    ReflectionTestUtils.setField(ar, "id", 1L);
    ar.setCompany(company);
    ar.setCode("AR-TEST");
    ar.setName("Accounts Receivable");
    ar.setType(AccountType.ASSET);

    Account revenue = new Account();
    ReflectionTestUtils.setField(revenue, "id", 2L);
    revenue.setCompany(company);
    revenue.setCode("REV-TEST");
    revenue.setName("Revenue");
    revenue.setType(AccountType.REVENUE);

    when(accountRepository.lockByCompanyAndId(eq(company), eq(1L))).thenReturn(Optional.of(ar));
    when(accountRepository.lockByCompanyAndId(eq(company), eq(2L)))
        .thenReturn(Optional.of(revenue));

    JournalEntryRequest request =
        new JournalEntryRequest(
            "DEALER-REF",
            today,
            "Dealer missing AR",
            99L,
            null,
            Boolean.FALSE,
            List.of(
                new JournalEntryRequest.JournalLineRequest(
                    1L, "AR", new BigDecimal("50.00"), BigDecimal.ZERO),
                new JournalEntryRequest.JournalLineRequest(
                    2L, "Revenue", BigDecimal.ZERO, new BigDecimal("50.00"))));

    assertThatThrownBy(() -> accountingService.createJournalEntry(request))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Dealer Test Dealer is missing a receivable account");
  }

  @Test
  void createJournalEntry_allowsDealerContextWithoutArWhenDealerReceivableMissing() {
    LocalDate today = LocalDate.of(2024, 3, 16);
    when(companyClock.today(company)).thenReturn(today);
    AccountingPeriod period = new AccountingPeriod();
    period.setYear(today.getYear());
    period.setMonth(today.getMonthValue());
    when(accountingPeriodService.requirePostablePeriod(
            eq(company), eq(today), any(), any(), any(), anyBoolean()))
        .thenReturn(period);
    when(journalEntryRepository.findByCompanyAndReferenceNumber(eq(company), eq("DEALER-NON-AR")))
        .thenReturn(Optional.empty());
    when(journalEntryRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(accountRepository.updateBalanceAtomic(eq(company), any(), any())).thenReturn(1);

    Dealer dealer = new Dealer();
    dealer.setName("Dealer Without AR");
    when(companyEntityLookup.requireDealer(company, 100L)).thenReturn(dealer);

    Account expense = new Account();
    ReflectionTestUtils.setField(expense, "id", 11L);
    expense.setCompany(company);
    expense.setCode("EXP-TEST");
    expense.setName("Service Expense");
    expense.setType(AccountType.EXPENSE);

    Account cash = new Account();
    ReflectionTestUtils.setField(cash, "id", 12L);
    cash.setCompany(company);
    cash.setCode("CASH");
    cash.setName("Cash");
    cash.setType(AccountType.ASSET);

    when(accountRepository.lockByCompanyAndId(eq(company), eq(11L)))
        .thenReturn(Optional.of(expense));
    when(accountRepository.lockByCompanyAndId(eq(company), eq(12L))).thenReturn(Optional.of(cash));

    JournalEntryRequest request =
        new JournalEntryRequest(
            "DEALER-NON-AR",
            today,
            "Dealer tagged cash expense",
            100L,
            null,
            Boolean.FALSE,
            List.of(
                new JournalEntryRequest.JournalLineRequest(
                    11L, "Expense", new BigDecimal("2000.00"), BigDecimal.ZERO),
                new JournalEntryRequest.JournalLineRequest(
                    12L, "Cash", BigDecimal.ZERO, new BigDecimal("2000.00"))));

    JournalEntryDto result = accountingService.createJournalEntry(request);

    assertThat(result).isNotNull();
    assertThat(result.referenceNumber()).isEqualTo("DEALER-NON-AR");
    verify(dealerLedgerService, never()).recordLedgerEntry(any(), any());
  }

  @Test
  void createJournalEntry_rejectsArWithoutDealerContext() {
    LocalDate today = LocalDate.of(2024, 4, 6);
    when(companyClock.today(company)).thenReturn(today);
    when(journalEntryRepository.findByCompanyAndReferenceNumber(eq(company), eq("AR-NO-DEALER")))
        .thenReturn(Optional.empty());

    Account ar = new Account();
    ReflectionTestUtils.setField(ar, "id", 1L);
    ar.setCompany(company);
    ar.setCode("AR-100");
    ar.setName("Accounts Receivable");
    ar.setType(AccountType.ASSET);

    Account revenue = new Account();
    ReflectionTestUtils.setField(revenue, "id", 2L);
    revenue.setCompany(company);
    revenue.setCode("REV-100");
    revenue.setName("Revenue");
    revenue.setType(AccountType.REVENUE);

    when(accountRepository.lockByCompanyAndId(eq(company), eq(1L))).thenReturn(Optional.of(ar));
    when(accountRepository.lockByCompanyAndId(eq(company), eq(2L)))
        .thenReturn(Optional.of(revenue));

    JournalEntryRequest request =
        new JournalEntryRequest(
            "AR-NO-DEALER",
            today,
            "AR without dealer",
            null,
            null,
            Boolean.FALSE,
            List.of(
                new JournalEntryRequest.JournalLineRequest(
                    1L, "AR", new BigDecimal("100.00"), BigDecimal.ZERO),
                new JournalEntryRequest.JournalLineRequest(
                    2L, "Revenue", BigDecimal.ZERO, new BigDecimal("100.00"))));

    assertThatThrownBy(() -> accountingService.createJournalEntry(request))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("dealer context");
  }

  @Test
  void createJournalEntry_rejectsApWithoutSupplierContext() {
    LocalDate today = LocalDate.of(2024, 4, 7);
    when(companyClock.today(company)).thenReturn(today);
    when(journalEntryRepository.findByCompanyAndReferenceNumber(eq(company), eq("AP-NO-SUPPLIER")))
        .thenReturn(Optional.empty());

    Account ap = new Account();
    ReflectionTestUtils.setField(ap, "id", 3L);
    ap.setCompany(company);
    ap.setCode("AP-200");
    ap.setName("Accounts Payable");
    ap.setType(AccountType.LIABILITY);

    Account inventory = new Account();
    ReflectionTestUtils.setField(inventory, "id", 4L);
    inventory.setCompany(company);
    inventory.setCode("INV-200");
    inventory.setName("Inventory");
    inventory.setType(AccountType.ASSET);

    when(accountRepository.lockByCompanyAndId(eq(company), eq(3L))).thenReturn(Optional.of(ap));
    when(accountRepository.lockByCompanyAndId(eq(company), eq(4L)))
        .thenReturn(Optional.of(inventory));

    JournalEntryRequest request =
        new JournalEntryRequest(
            "AP-NO-SUPPLIER",
            today,
            "AP without supplier",
            null,
            null,
            Boolean.FALSE,
            List.of(
                new JournalEntryRequest.JournalLineRequest(
                    4L, "Inventory", new BigDecimal("50.00"), BigDecimal.ZERO),
                new JournalEntryRequest.JournalLineRequest(
                    3L, "AP", BigDecimal.ZERO, new BigDecimal("50.00"))));

    assertThatThrownBy(() -> accountingService.createJournalEntry(request))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("supplier context");
  }

  @Test
  void createJournalEntry_requiresSupplierContextForOwnedPayableAccount() {
    LocalDate today = LocalDate.of(2024, 4, 7);
    when(companyClock.today(company)).thenReturn(today);
    when(journalEntryRepository.findByCompanyAndReferenceNumber(
            eq(company), eq("AP-INFER-SUPPLIER")))
        .thenReturn(Optional.empty());

    Account payable = new Account();
    ReflectionTestUtils.setField(payable, "id", 31L);
    payable.setCompany(company);
    payable.setCode("AP-SKEINA");
    payable.setName("Skeina Payable");
    payable.setType(AccountType.LIABILITY);

    Account cash = new Account();
    ReflectionTestUtils.setField(cash, "id", 32L);
    cash.setCompany(company);
    cash.setCode("CASH");
    cash.setName("Cash");
    cash.setType(AccountType.ASSET);

    when(accountRepository.lockByCompanyAndId(eq(company), eq(31L)))
        .thenReturn(Optional.of(payable));
    when(accountRepository.lockByCompanyAndId(eq(company), eq(32L))).thenReturn(Optional.of(cash));

    JournalEntryRequest request =
        new JournalEntryRequest(
            "AP-INFER-SUPPLIER",
            today,
            "Supplier inferred from payable account",
            null,
            null,
            Boolean.FALSE,
            List.of(
                new JournalEntryRequest.JournalLineRequest(
                    32L, "Cash", new BigDecimal("2000.00"), BigDecimal.ZERO),
                new JournalEntryRequest.JournalLineRequest(
                    31L, "Supplier AP", BigDecimal.ZERO, new BigDecimal("2000.00"))));

    assertThatThrownBy(() -> accountingService.createJournalEntry(request))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("requires a supplier context");
  }

  @Test
  void createJournalEntry_rejectsAmbiguousSupplierInference() {
    LocalDate today = LocalDate.of(2024, 4, 7);
    when(companyClock.today(company)).thenReturn(today);
    when(journalEntryRepository.findByCompanyAndReferenceNumber(
            eq(company), eq("AP-AMBIG-SUPPLIER")))
        .thenReturn(Optional.empty());

    Account payable = new Account();
    ReflectionTestUtils.setField(payable, "id", 33L);
    payable.setCompany(company);
    payable.setCode("AP-SHARED");
    payable.setName("Shared AP");
    payable.setType(AccountType.LIABILITY);

    Account cash = new Account();
    ReflectionTestUtils.setField(cash, "id", 34L);
    cash.setCompany(company);
    cash.setCode("CASH");
    cash.setName("Cash");
    cash.setType(AccountType.ASSET);

    Supplier supplierA = new Supplier();
    ReflectionTestUtils.setField(supplierA, "id", 92L);
    supplierA.setName("SKEINA");
    supplierA.setStatus(SupplierStatus.ACTIVE);
    supplierA.setPayableAccount(payable);

    Supplier supplierB = new Supplier();
    ReflectionTestUtils.setField(supplierB, "id", 93L);
    supplierB.setName("OTHER");
    supplierB.setStatus(SupplierStatus.ACTIVE);
    supplierB.setPayableAccount(payable);

    when(accountRepository.lockByCompanyAndId(eq(company), eq(33L)))
        .thenReturn(Optional.of(payable));
    when(accountRepository.lockByCompanyAndId(eq(company), eq(34L))).thenReturn(Optional.of(cash));
    when(supplierRepository.findByCompanyAndPayableAccountIn(eq(company), any()))
        .thenReturn(List.of(supplierA, supplierB));

    JournalEntryRequest request =
        new JournalEntryRequest(
            "AP-AMBIG-SUPPLIER",
            today,
            "Ambiguous supplier inference",
            null,
            null,
            Boolean.FALSE,
            List.of(
                new JournalEntryRequest.JournalLineRequest(
                    34L, "Cash", new BigDecimal("1000.00"), BigDecimal.ZERO),
                new JournalEntryRequest.JournalLineRequest(
                    33L, "Supplier AP", BigDecimal.ZERO, new BigDecimal("1000.00"))));

    assertThatThrownBy(() -> accountingService.createJournalEntry(request))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("requires a supplier context");
  }

  @Test
  void createJournalEntry_requiresDealerContextForOwnedReceivableAccount() {
    LocalDate today = LocalDate.of(2024, 4, 7);
    when(companyClock.today(company)).thenReturn(today);
    when(journalEntryRepository.findByCompanyAndReferenceNumber(
            eq(company), eq("AR-OWNED-NO-DEALER")))
        .thenReturn(Optional.empty());

    Account receivable = account(35L, "AR-OWNED", AccountType.ASSET);
    Account revenue = account(36L, "REV-OWNED", AccountType.REVENUE);
    Dealer owner = dealer(501L, "Owned Dealer", receivable);

    when(accountRepository.lockByCompanyAndId(eq(company), eq(35L)))
        .thenReturn(Optional.of(receivable));
    when(accountRepository.lockByCompanyAndId(eq(company), eq(36L)))
        .thenReturn(Optional.of(revenue));
    when(dealerRepository.findByCompanyAndReceivableAccountIn(eq(company), any()))
        .thenReturn(List.of(owner));

    JournalEntryRequest request =
        new JournalEntryRequest(
            "AR-OWNED-NO-DEALER",
            today,
            "Owned receivable without context",
            null,
            null,
            Boolean.FALSE,
            List.of(
                new JournalEntryRequest.JournalLineRequest(
                    35L, "AR", new BigDecimal("100.00"), BigDecimal.ZERO),
                new JournalEntryRequest.JournalLineRequest(
                    36L, "Revenue", BigDecimal.ZERO, new BigDecimal("100.00"))));

    assertThatThrownBy(() -> accountingService.createJournalEntry(request))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("requires a dealer context");
  }

  @Test
  void createJournalEntry_rejectsMismatchedDealerContextForOwnedReceivableAccount() {
    LocalDate today = LocalDate.of(2024, 4, 7);
    when(companyClock.today(company)).thenReturn(today);
    when(journalEntryRepository.findByCompanyAndReferenceNumber(
            eq(company), eq("AR-OWNED-MISMATCH")))
        .thenReturn(Optional.empty());

    Account receivable = account(37L, "AR-MATCH", AccountType.ASSET);
    Account revenue = account(38L, "REV-MATCH", AccountType.REVENUE);
    Dealer owner = dealer(601L, "Owner Dealer", receivable);
    Dealer requestDealer = dealer(602L, "Request Dealer", receivable);

    when(companyEntityLookup.requireDealer(company, 602L)).thenReturn(requestDealer);
    when(accountRepository.lockByCompanyAndId(eq(company), eq(37L)))
        .thenReturn(Optional.of(receivable));
    when(accountRepository.lockByCompanyAndId(eq(company), eq(38L)))
        .thenReturn(Optional.of(revenue));
    when(dealerRepository.findByCompanyAndReceivableAccountIn(eq(company), any()))
        .thenReturn(List.of(owner));

    JournalEntryRequest request =
        new JournalEntryRequest(
            "AR-OWNED-MISMATCH",
            today,
            "Owned receivable mismatch",
            602L,
            null,
            Boolean.FALSE,
            List.of(
                new JournalEntryRequest.JournalLineRequest(
                    37L, "AR", new BigDecimal("100.00"), BigDecimal.ZERO),
                new JournalEntryRequest.JournalLineRequest(
                    38L, "Revenue", BigDecimal.ZERO, new BigDecimal("100.00"))));

    assertThatThrownBy(() -> accountingService.createJournalEntry(request))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("requires matching dealer context");
  }

  @Test
  void createJournalEntry_rejectsMismatchedSupplierContextForOwnedPayableAccount() {
    LocalDate today = LocalDate.of(2024, 4, 7);
    when(companyClock.today(company)).thenReturn(today);
    when(journalEntryRepository.findByCompanyAndReferenceNumber(
            eq(company), eq("AP-OWNED-MISMATCH")))
        .thenReturn(Optional.empty());

    Account payable = account(39L, "AP-MATCH", AccountType.LIABILITY);
    Account expense = account(40L, "EXP-MATCH", AccountType.EXPENSE);
    Supplier owner = supplier(701L, "Owner Supplier", payable);
    Supplier requestSupplier = supplier(702L, "Request Supplier", payable);

    when(companyEntityLookup.requireSupplier(company, 702L)).thenReturn(requestSupplier);
    when(accountRepository.lockByCompanyAndId(eq(company), eq(39L)))
        .thenReturn(Optional.of(payable));
    when(accountRepository.lockByCompanyAndId(eq(company), eq(40L)))
        .thenReturn(Optional.of(expense));
    when(supplierRepository.findByCompanyAndPayableAccountIn(eq(company), any()))
        .thenReturn(List.of(owner));

    JournalEntryRequest request =
        new JournalEntryRequest(
            "AP-OWNED-MISMATCH",
            today,
            "Owned payable mismatch",
            null,
            702L,
            Boolean.FALSE,
            List.of(
                new JournalEntryRequest.JournalLineRequest(
                    40L, "Expense", new BigDecimal("100.00"), BigDecimal.ZERO),
                new JournalEntryRequest.JournalLineRequest(
                    39L, "AP", BigDecimal.ZERO, new BigDecimal("100.00"))));

    assertThatThrownBy(() -> accountingService.createJournalEntry(request))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("requires matching supplier context");
  }

  @Test
  void createJournalEntry_recordsDealerLedgerForMultipleReceivableLinesWithOverride() {
    LocalDate today = LocalDate.of(2024, 4, 8);
    when(companyClock.today(company)).thenReturn(today);
    AccountingPeriod period = openPeriod(today);
    when(accountingPeriodService.requirePostablePeriod(
            eq(company), eq(today), any(), any(), any(), anyBoolean()))
        .thenReturn(period);
    when(journalEntryRepository.findByCompanyAndReferenceNumber(
            eq(company), eq("AR-MULTI-OVERRIDE")))
        .thenReturn(Optional.empty());
    when(journalEntryRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(accountRepository.updateBalanceAtomic(eq(company), any(), any())).thenReturn(1);

    Account receivable = account(41L, "AR-LEDGER", AccountType.ASSET);
    Account revenue = account(42L, "REV-LEDGER", AccountType.REVENUE);
    Dealer dealer = dealer(801L, "Ledger Dealer", receivable);

    when(companyEntityLookup.requireDealer(company, 801L)).thenReturn(dealer);
    when(accountRepository.lockByCompanyAndId(eq(company), eq(41L)))
        .thenReturn(Optional.of(receivable));
    when(accountRepository.lockByCompanyAndId(eq(company), eq(42L)))
        .thenReturn(Optional.of(revenue));

    JournalEntryDto result =
        accountingService.createJournalEntry(
            new JournalEntryRequest(
                "AR-MULTI-OVERRIDE",
                today,
                "Dealer receivable split",
                801L,
                null,
                Boolean.TRUE,
                List.of(
                    new JournalEntryRequest.JournalLineRequest(
                        41L, "AR-1", new BigDecimal("60.00"), BigDecimal.ZERO),
                    new JournalEntryRequest.JournalLineRequest(
                        41L, "AR-2", new BigDecimal("40.00"), BigDecimal.ZERO),
                    new JournalEntryRequest.JournalLineRequest(
                        42L, "Revenue", BigDecimal.ZERO, new BigDecimal("100.00")))));

    assertThat(result.referenceNumber()).isEqualTo("AR-MULTI-OVERRIDE");
    verify(dealerLedgerService).recordLedgerEntry(eq(dealer), any());
  }

  @Test
  void createJournalEntry_recordsSupplierLedgerForMultiplePayableLinesWithOverride() {
    LocalDate today = LocalDate.of(2024, 4, 8);
    when(companyClock.today(company)).thenReturn(today);
    AccountingPeriod period = openPeriod(today);
    when(accountingPeriodService.requirePostablePeriod(
            eq(company), eq(today), any(), any(), any(), anyBoolean()))
        .thenReturn(period);
    when(journalEntryRepository.findByCompanyAndReferenceNumber(
            eq(company), eq("AP-MULTI-OVERRIDE")))
        .thenReturn(Optional.empty());
    when(journalEntryRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(accountRepository.updateBalanceAtomic(eq(company), any(), any())).thenReturn(1);

    Account payable = account(43L, "AP-LEDGER", AccountType.LIABILITY);
    Account expense = account(44L, "EXP-LEDGER", AccountType.EXPENSE);
    Supplier supplier = supplier(901L, "Ledger Supplier", payable);

    when(companyEntityLookup.requireSupplier(company, 901L)).thenReturn(supplier);
    when(accountRepository.lockByCompanyAndId(eq(company), eq(43L)))
        .thenReturn(Optional.of(payable));
    when(accountRepository.lockByCompanyAndId(eq(company), eq(44L)))
        .thenReturn(Optional.of(expense));

    JournalEntryDto result =
        accountingService.createJournalEntry(
            new JournalEntryRequest(
                "AP-MULTI-OVERRIDE",
                today,
                "Supplier payable split",
                null,
                901L,
                Boolean.TRUE,
                List.of(
                    new JournalEntryRequest.JournalLineRequest(
                        44L, "Expense", new BigDecimal("100.00"), BigDecimal.ZERO),
                    new JournalEntryRequest.JournalLineRequest(
                        43L, "AP-1", BigDecimal.ZERO, new BigDecimal("60.00")),
                    new JournalEntryRequest.JournalLineRequest(
                        43L, "AP-2", BigDecimal.ZERO, new BigDecimal("40.00")))));

    assertThat(result.referenceNumber()).isEqualTo("AP-MULTI-OVERRIDE");
    verify(supplierLedgerService).recordLedgerEntry(eq(supplier), any());
  }

  @Test
  void createJournalEntry_rejectsMixedArAp() {
    LocalDate today = LocalDate.of(2024, 4, 8);
    when(companyClock.today(company)).thenReturn(today);
    when(journalEntryRepository.findByCompanyAndReferenceNumber(eq(company), eq("AR-AP-MIX")))
        .thenReturn(Optional.empty());

    Account ar = new Account();
    ReflectionTestUtils.setField(ar, "id", 5L);
    ar.setCompany(company);
    ar.setCode("AR-300");
    ar.setName("Accounts Receivable");
    ar.setType(AccountType.ASSET);

    Account ap = new Account();
    ReflectionTestUtils.setField(ap, "id", 6L);
    ap.setCompany(company);
    ap.setCode("AP-300");
    ap.setName("Accounts Payable");
    ap.setType(AccountType.LIABILITY);

    when(accountRepository.lockByCompanyAndId(eq(company), eq(5L))).thenReturn(Optional.of(ar));
    when(accountRepository.lockByCompanyAndId(eq(company), eq(6L))).thenReturn(Optional.of(ap));

    JournalEntryRequest request =
        new JournalEntryRequest(
            "AR-AP-MIX",
            today,
            "AR/AP mix",
            null,
            null,
            Boolean.FALSE,
            List.of(
                new JournalEntryRequest.JournalLineRequest(
                    5L, "AR", new BigDecimal("25.00"), BigDecimal.ZERO),
                new JournalEntryRequest.JournalLineRequest(
                    6L, "AP", BigDecimal.ZERO, new BigDecimal("25.00"))));

    assertThatThrownBy(() -> accountingService.createJournalEntry(request))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("combine AR and AP");
  }

  @Test
  void createJournalEntry_allowsNonArApWithoutCounterparty() {
    LocalDate today = LocalDate.of(2024, 4, 9);
    when(companyClock.today(company)).thenReturn(today);
    when(journalEntryRepository.findByCompanyAndReferenceNumber(eq(company), eq("NO-AR-AP")))
        .thenReturn(Optional.empty());
    AccountingPeriod period = new AccountingPeriod();
    period.setYear(today.getYear());
    period.setMonth(today.getMonthValue());
    when(accountingPeriodService.requirePostablePeriod(
            eq(company), eq(today), any(), any(), any(), anyBoolean()))
        .thenReturn(period);

    Account cash = new Account();
    ReflectionTestUtils.setField(cash, "id", 7L);
    cash.setCompany(company);
    cash.setCode("CASH");
    cash.setName("Cash");
    cash.setType(AccountType.ASSET);

    Account revenue = new Account();
    ReflectionTestUtils.setField(revenue, "id", 8L);
    revenue.setCompany(company);
    revenue.setCode("REV-200");
    revenue.setName("Revenue");
    revenue.setType(AccountType.REVENUE);

    when(accountRepository.lockByCompanyAndId(eq(company), eq(7L))).thenReturn(Optional.of(cash));
    when(accountRepository.lockByCompanyAndId(eq(company), eq(8L)))
        .thenReturn(Optional.of(revenue));
    when(journalEntryRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(accountRepository.updateBalanceAtomic(eq(company), any(), any())).thenReturn(1);

    JournalEntryRequest request =
        new JournalEntryRequest(
            "NO-AR-AP",
            today,
            "Cash sale",
            null,
            null,
            Boolean.FALSE,
            List.of(
                new JournalEntryRequest.JournalLineRequest(
                    7L, "Cash", new BigDecimal("10.00"), BigDecimal.ZERO),
                new JournalEntryRequest.JournalLineRequest(
                    8L, "Revenue", BigDecimal.ZERO, new BigDecimal("10.00"))));

    JournalEntryDto result = accountingService.createJournalEntry(request);
    assertThat(result).isNotNull();
    assertThat(result.referenceNumber()).isEqualTo("NO-AR-AP");
  }

  @Test
  void createJournalEntry_returnsExistingOnDuplicateReference() {
    // Test idempotent behavior: return existing entry instead of throwing exception
    LocalDate today = LocalDate.of(2024, 3, 20);
    var existingEntry = new com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry();
    existingEntry.setCompany(company);
    existingEntry.setReferenceNumber("DUP-REF");
    existingEntry.setEntryDate(today);
    existingEntry.setMemo("Duplicate ref");
    existingEntry.setStatus("POSTED");
    org.springframework.test.util.ReflectionTestUtils.setField(existingEntry, "id", 999L);

    var debitAccount = new com.bigbrightpaints.erp.modules.accounting.domain.Account();
    ReflectionTestUtils.setField(debitAccount, "id", 1L);
    debitAccount.setCompany(company);
    debitAccount.setCode("ACC-1");
    var creditAccount = new com.bigbrightpaints.erp.modules.accounting.domain.Account();
    ReflectionTestUtils.setField(creditAccount, "id", 2L);
    creditAccount.setCompany(company);
    creditAccount.setCode("ACC-2");
    when(accountRepository.lockByCompanyAndId(eq(company), eq(1L)))
        .thenReturn(Optional.of(debitAccount));
    when(accountRepository.lockByCompanyAndId(eq(company), eq(2L)))
        .thenReturn(Optional.of(creditAccount));

    var debitLine = new com.bigbrightpaints.erp.modules.accounting.domain.JournalLine();
    debitLine.setJournalEntry(existingEntry);
    debitLine.setAccount(debitAccount);
    debitLine.setDebit(new BigDecimal("10.00"));
    debitLine.setCredit(BigDecimal.ZERO);
    existingEntry.addLine(debitLine);

    var creditLine = new com.bigbrightpaints.erp.modules.accounting.domain.JournalLine();
    creditLine.setJournalEntry(existingEntry);
    creditLine.setAccount(creditAccount);
    creditLine.setDebit(BigDecimal.ZERO);
    creditLine.setCredit(new BigDecimal("10.00"));
    existingEntry.addLine(creditLine);

    when(journalEntryRepository.findByCompanyAndReferenceNumber(eq(company), eq("DUP-REF")))
        .thenReturn(Optional.of(existingEntry));

    JournalEntryRequest request =
        new JournalEntryRequest(
            "DUP-REF",
            today,
            "Duplicate ref",
            null,
            null,
            Boolean.FALSE,
            List.of(
                new JournalEntryRequest.JournalLineRequest(
                    1L, "Debit", new BigDecimal("10.00"), BigDecimal.ZERO),
                new JournalEntryRequest.JournalLineRequest(
                    2L, "Credit", BigDecimal.ZERO, new BigDecimal("10.00"))));

    // Should return existing entry (idempotent) instead of throwing
    JournalEntryDto result = accountingService.createJournalEntry(request);
    assertThat(result).isNotNull();
    assertThat(result.id()).isEqualTo(999L);
    assertThat(result.referenceNumber()).isEqualTo("DUP-REF");
  }

  @Test
  void createJournalEntry_dataIntegrityConflictReturnsExistingOnSaveRace() {
    LocalDate today = LocalDate.of(2024, 3, 26);
    when(companyClock.today(company)).thenReturn(today);
    AccountingPeriod period = new AccountingPeriod();
    period.setYear(today.getYear());
    period.setMonth(today.getMonthValue());
    when(accountingPeriodService.requirePostablePeriod(
            eq(company), eq(today), any(), any(), any(), anyBoolean()))
        .thenReturn(period);

    Account debitAccount = new Account();
    ReflectionTestUtils.setField(debitAccount, "id", 241L);
    debitAccount.setCompany(company);
    debitAccount.setBalance(BigDecimal.ZERO);
    debitAccount.setCode("DEBIT-RACE-EXISTING");
    Account creditAccount = new Account();
    ReflectionTestUtils.setField(creditAccount, "id", 242L);
    creditAccount.setCompany(company);
    creditAccount.setBalance(BigDecimal.ZERO);
    creditAccount.setCode("CREDIT-RACE-EXISTING");
    when(accountRepository.lockByCompanyAndId(eq(company), eq(241L)))
        .thenReturn(Optional.of(debitAccount));
    when(accountRepository.lockByCompanyAndId(eq(company), eq(242L)))
        .thenReturn(Optional.of(creditAccount));

    JournalEntry existingEntry = new JournalEntry();
    existingEntry.setCompany(company);
    existingEntry.setReferenceNumber("JE-RACE-EXISTING");
    existingEntry.setEntryDate(today);
    existingEntry.setMemo("save-race duplicate");
    existingEntry.setStatus("POSTED");
    ReflectionTestUtils.setField(existingEntry, "id", 777L);

    JournalLine debitLine = new JournalLine();
    debitLine.setJournalEntry(existingEntry);
    debitLine.setAccount(debitAccount);
    debitLine.setDebit(new BigDecimal("30.00"));
    debitLine.setCredit(BigDecimal.ZERO);
    existingEntry.addLine(debitLine);

    JournalLine creditLine = new JournalLine();
    creditLine.setJournalEntry(existingEntry);
    creditLine.setAccount(creditAccount);
    creditLine.setDebit(BigDecimal.ZERO);
    creditLine.setCredit(new BigDecimal("30.00"));
    existingEntry.addLine(creditLine);

    when(journalEntryRepository.findByCompanyAndReferenceNumber(
            eq(company), eq("JE-RACE-EXISTING")))
        .thenReturn(Optional.empty(), Optional.of(existingEntry));
    when(journalEntryRepository.save(any()))
        .thenThrow(new DataIntegrityViolationException("duplicate-journal"));

    JournalEntryRequest request =
        new JournalEntryRequest(
            "JE-RACE-EXISTING",
            today,
            "save-race duplicate",
            null,
            null,
            Boolean.FALSE,
            List.of(
                new JournalEntryRequest.JournalLineRequest(
                    241L, "Debit line", new BigDecimal("30.00"), BigDecimal.ZERO),
                new JournalEntryRequest.JournalLineRequest(
                    242L, "Credit line", BigDecimal.ZERO, new BigDecimal("30.00"))));

    JournalEntryDto result = accountingService.createJournalEntry(request);
    assertThat(result).isNotNull();
    assertThat(result.id()).isEqualTo(777L);
    assertThat(result.referenceNumber()).isEqualTo("JE-RACE-EXISTING");
    verify(accountRepository, never()).updateBalanceAtomic(any(), any(), any());
    verify(accountingEventStore, never()).recordJournalEntryPosted(any(), any());
  }

  @Test
  void createJournalEntry_failsWhenAccountBalanceNotUpdated() {
    LocalDate today = LocalDate.of(2024, 3, 21);
    when(companyClock.today(company)).thenReturn(today);
    when(accountingPeriodService.requirePostablePeriod(
            eq(company), eq(today), any(), any(), any(), anyBoolean()))
        .thenReturn(new AccountingPeriod());
    when(journalEntryRepository.findByCompanyAndReferenceNumber(eq(company), eq("ACC-UPD")))
        .thenReturn(Optional.empty());
    // Return locked accounts with zero balance (need two accounts for balanced entry)
    var debitAccount = new com.bigbrightpaints.erp.modules.accounting.domain.Account();
    ReflectionTestUtils.setField(debitAccount, "id", 1L);
    debitAccount.setCompany(company);
    debitAccount.setBalance(BigDecimal.ZERO);
    debitAccount.setCode("DEBIT-ACC");
    var creditAccount = new com.bigbrightpaints.erp.modules.accounting.domain.Account();
    ReflectionTestUtils.setField(creditAccount, "id", 2L);
    creditAccount.setCompany(company);
    creditAccount.setBalance(BigDecimal.ZERO);
    creditAccount.setCode("CREDIT-ACC");
    when(accountRepository.lockByCompanyAndId(eq(company), eq(1L)))
        .thenReturn(Optional.of(debitAccount));
    when(accountRepository.lockByCompanyAndId(eq(company), eq(2L)))
        .thenReturn(Optional.of(creditAccount));
    when(journalEntryRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    // Force atomic updater to report no rows updated (for any account)
    when(accountRepository.updateBalanceAtomic(eq(company), any(), any())).thenReturn(0);

    JournalEntryRequest request =
        new JournalEntryRequest(
            "ACC-UPD",
            today,
            "Atomic balance update failure",
            null,
            null,
            Boolean.FALSE,
            List.of(
                new JournalEntryRequest.JournalLineRequest(
                    1L, "Debit line", new BigDecimal("5.00"), BigDecimal.ZERO),
                new JournalEntryRequest.JournalLineRequest(
                    2L, "Credit line", BigDecimal.ZERO, new BigDecimal("5.00"))));

    assertThatThrownBy(() -> accountingService.createJournalEntry(request))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Account balance update failed");
  }

  @Test
  void createJournalEntry_failsWhenEventTrailPersistenceFailsInStrictMode() {
    LocalDate today = LocalDate.of(2024, 3, 22);
    when(companyClock.today(company)).thenReturn(today);
    AccountingPeriod period = new AccountingPeriod();
    period.setYear(today.getYear());
    period.setMonth(today.getMonthValue());
    when(accountingPeriodService.requirePostablePeriod(
            eq(company), eq(today), any(), any(), any(), anyBoolean()))
        .thenReturn(period);
    when(journalEntryRepository.findByCompanyAndReferenceNumber(eq(company), eq("EVT-STRICT")))
        .thenReturn(Optional.empty());

    Account debitAccount = new Account();
    ReflectionTestUtils.setField(debitAccount, "id", 101L);
    debitAccount.setCompany(company);
    debitAccount.setBalance(BigDecimal.ZERO);
    debitAccount.setCode("DEBIT-STRICT");
    Account creditAccount = new Account();
    ReflectionTestUtils.setField(creditAccount, "id", 102L);
    creditAccount.setCompany(company);
    creditAccount.setBalance(BigDecimal.ZERO);
    creditAccount.setCode("CREDIT-STRICT");
    when(accountRepository.lockByCompanyAndId(eq(company), eq(101L)))
        .thenReturn(Optional.of(debitAccount));
    when(accountRepository.lockByCompanyAndId(eq(company), eq(102L)))
        .thenReturn(Optional.of(creditAccount));
    when(journalEntryRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(accountRepository.updateBalanceAtomic(eq(company), any(), any())).thenReturn(1);
    when(accountingEventStore.recordJournalEntryPosted(any(), any()))
        .thenThrow(new IllegalStateException("event-store-down"));

    JournalEntryRequest request =
        new JournalEntryRequest(
            "EVT-STRICT",
            today,
            "Strict event trail failure test",
            null,
            null,
            Boolean.FALSE,
            List.of(
                new JournalEntryRequest.JournalLineRequest(
                    101L, "Debit line", new BigDecimal("20.00"), BigDecimal.ZERO),
                new JournalEntryRequest.JournalLineRequest(
                    102L, "Credit line", BigDecimal.ZERO, new BigDecimal("20.00"))));

    assertThatThrownBy(() -> accountingService.createJournalEntry(request))
        .isInstanceOf(ApplicationException.class)
        .satisfies(
            ex ->
                assertThat(((ApplicationException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.SYSTEM_DATABASE_ERROR))
        .hasMessageContaining("Accounting event trail persistence failed");
    ArgumentCaptor<Map<String, String>> integrationFailureCaptor =
        ArgumentCaptor.forClass(Map.class);
    verify(auditService)
        .logFailure(eq(AuditEvent.INTEGRATION_FAILURE), integrationFailureCaptor.capture());
    assertThat(integrationFailureCaptor.getValue())
        .containsEntry("eventTrailOperation", "JOURNAL_ENTRY_POSTED")
        .containsEntry("policy", "STRICT")
        .containsEntry(
            IntegrationFailureMetadataSchema.KEY_FAILURE_CODE,
            "ACCOUNTING_EVENT_TRAIL_PERSISTENCE_FAILURE")
        .containsEntry(IntegrationFailureMetadataSchema.KEY_ERROR_CATEGORY, "PERSISTENCE")
        .containsEntry("errorType", "IllegalStateException")
        .containsEntry(
            IntegrationFailureMetadataSchema.KEY_ALERT_ROUTING_VERSION, "ACCOUNTING_EVENT_TRAIL_V1")
        .containsEntry(IntegrationFailureMetadataSchema.KEY_ALERT_ROUTE, "SEV2_URGENT")
        .doesNotContainKey("error");
  }

  @Test
  void createJournalEntry_bestEffortEventTrailFailureStillPosts() {
    setCreateJournalEntryEventTrailStrictness(false);
    LocalDate today = LocalDate.of(2024, 3, 23);
    when(companyClock.today(company)).thenReturn(today);
    AccountingPeriod period = new AccountingPeriod();
    period.setYear(today.getYear());
    period.setMonth(today.getMonthValue());
    when(accountingPeriodService.requirePostablePeriod(
            eq(company), eq(today), any(), any(), any(), anyBoolean()))
        .thenReturn(period);
    when(journalEntryRepository.findByCompanyAndReferenceNumber(eq(company), eq("EVT-BEST-EFFORT")))
        .thenReturn(Optional.empty());

    Account debitAccount = new Account();
    ReflectionTestUtils.setField(debitAccount, "id", 201L);
    debitAccount.setCompany(company);
    debitAccount.setBalance(BigDecimal.ZERO);
    debitAccount.setCode("DEBIT-BE");
    Account creditAccount = new Account();
    ReflectionTestUtils.setField(creditAccount, "id", 202L);
    creditAccount.setCompany(company);
    creditAccount.setBalance(BigDecimal.ZERO);
    creditAccount.setCode("CREDIT-BE");
    when(accountRepository.lockByCompanyAndId(eq(company), eq(201L)))
        .thenReturn(Optional.of(debitAccount));
    when(accountRepository.lockByCompanyAndId(eq(company), eq(202L)))
        .thenReturn(Optional.of(creditAccount));
    when(journalEntryRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(accountRepository.updateBalanceAtomic(eq(company), any(), any())).thenReturn(1);
    when(accountingEventStore.recordJournalEntryPosted(any(), any()))
        .thenThrow(new IllegalStateException("event-store-down"));

    JournalEntryRequest request =
        new JournalEntryRequest(
            "EVT-BEST-EFFORT",
            today,
            "Best effort event trail failure test",
            null,
            null,
            Boolean.FALSE,
            List.of(
                new JournalEntryRequest.JournalLineRequest(
                    201L, "Debit line", new BigDecimal("15.00"), BigDecimal.ZERO),
                new JournalEntryRequest.JournalLineRequest(
                    202L, "Credit line", BigDecimal.ZERO, new BigDecimal("15.00"))));

    JournalEntryDto result = accountingService.createJournalEntry(request);
    assertThat(result.referenceNumber()).isEqualTo("EVT-BEST-EFFORT");
    ArgumentCaptor<Map<String, String>> integrationFailureCaptor =
        ArgumentCaptor.forClass(Map.class);
    verify(auditService)
        .logFailure(eq(AuditEvent.INTEGRATION_FAILURE), integrationFailureCaptor.capture());
    assertThat(integrationFailureCaptor.getValue())
        .containsEntry("eventTrailOperation", "JOURNAL_ENTRY_POSTED")
        .containsEntry("policy", "BEST_EFFORT")
        .containsEntry(
            IntegrationFailureMetadataSchema.KEY_FAILURE_CODE,
            "ACCOUNTING_EVENT_TRAIL_PERSISTENCE_FAILURE")
        .containsEntry(IntegrationFailureMetadataSchema.KEY_ERROR_CATEGORY, "PERSISTENCE")
        .containsEntry("errorType", "IllegalStateException")
        .containsEntry(
            IntegrationFailureMetadataSchema.KEY_ALERT_ROUTING_VERSION, "ACCOUNTING_EVENT_TRAIL_V1")
        .containsEntry(IntegrationFailureMetadataSchema.KEY_ALERT_ROUTE, "SEV3_TICKET")
        .doesNotContainKey("error");
    verify(auditService, never()).logSuccess(eq(AuditEvent.JOURNAL_ENTRY_POSTED), any());
  }

  @Test
  void createJournalEntry_bestEffortEventTrailValidationFailureClassified() {
    setCreateJournalEntryEventTrailStrictness(false);
    LocalDate today = LocalDate.of(2024, 3, 24);
    when(companyClock.today(company)).thenReturn(today);
    AccountingPeriod period = new AccountingPeriod();
    period.setYear(today.getYear());
    period.setMonth(today.getMonthValue());
    when(accountingPeriodService.requirePostablePeriod(
            eq(company), eq(today), any(), any(), any(), anyBoolean()))
        .thenReturn(period);
    when(journalEntryRepository.findByCompanyAndReferenceNumber(eq(company), eq("EVT-VALIDATION")))
        .thenReturn(Optional.empty());

    Account debitAccount = new Account();
    ReflectionTestUtils.setField(debitAccount, "id", 211L);
    debitAccount.setCompany(company);
    debitAccount.setBalance(BigDecimal.ZERO);
    debitAccount.setCode("DEBIT-VALIDATION");
    Account creditAccount = new Account();
    ReflectionTestUtils.setField(creditAccount, "id", 212L);
    creditAccount.setCompany(company);
    creditAccount.setBalance(BigDecimal.ZERO);
    creditAccount.setCode("CREDIT-VALIDATION");
    when(accountRepository.lockByCompanyAndId(eq(company), eq(211L)))
        .thenReturn(Optional.of(debitAccount));
    when(accountRepository.lockByCompanyAndId(eq(company), eq(212L)))
        .thenReturn(Optional.of(creditAccount));
    when(journalEntryRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(accountRepository.updateBalanceAtomic(eq(company), any(), any())).thenReturn(1);
    when(accountingEventStore.recordJournalEntryPosted(any(), any()))
        .thenThrow(new IllegalArgumentException("bad-event-payload"));

    JournalEntryRequest request =
        new JournalEntryRequest(
            "EVT-VALIDATION",
            today,
            "Best effort event trail validation classification",
            null,
            null,
            Boolean.FALSE,
            List.of(
                new JournalEntryRequest.JournalLineRequest(
                    211L, "Debit line", new BigDecimal("25.00"), BigDecimal.ZERO),
                new JournalEntryRequest.JournalLineRequest(
                    212L, "Credit line", BigDecimal.ZERO, new BigDecimal("25.00"))));

    JournalEntryDto result = accountingService.createJournalEntry(request);
    assertThat(result.referenceNumber()).isEqualTo("EVT-VALIDATION");
    ArgumentCaptor<Map<String, String>> integrationFailureCaptor =
        ArgumentCaptor.forClass(Map.class);
    verify(auditService)
        .logFailure(eq(AuditEvent.INTEGRATION_FAILURE), integrationFailureCaptor.capture());
    assertThat(integrationFailureCaptor.getValue())
        .containsEntry(
            IntegrationFailureMetadataSchema.KEY_FAILURE_CODE,
            "ACCOUNTING_EVENT_TRAIL_PERSISTENCE_FAILURE")
        .containsEntry(IntegrationFailureMetadataSchema.KEY_ERROR_CATEGORY, "VALIDATION")
        .containsEntry("errorType", "IllegalArgumentException")
        .containsEntry(
            IntegrationFailureMetadataSchema.KEY_ALERT_ROUTING_VERSION, "ACCOUNTING_EVENT_TRAIL_V1")
        .containsEntry(IntegrationFailureMetadataSchema.KEY_ALERT_ROUTE, "SEV2_URGENT");
  }

  @Test
  void createJournalEntry_bestEffortEventTrailDataIntegrityFailureClassified() {
    setCreateJournalEntryEventTrailStrictness(false);
    LocalDate today = LocalDate.of(2024, 3, 25);
    when(companyClock.today(company)).thenReturn(today);
    AccountingPeriod period = new AccountingPeriod();
    period.setYear(today.getYear());
    period.setMonth(today.getMonthValue());
    when(accountingPeriodService.requirePostablePeriod(
            eq(company), eq(today), any(), any(), any(), anyBoolean()))
        .thenReturn(period);
    when(journalEntryRepository.findByCompanyAndReferenceNumber(eq(company), eq("EVT-INTEGRITY")))
        .thenReturn(Optional.empty());

    Account debitAccount = new Account();
    ReflectionTestUtils.setField(debitAccount, "id", 221L);
    debitAccount.setCompany(company);
    debitAccount.setBalance(BigDecimal.ZERO);
    debitAccount.setCode("DEBIT-INTEGRITY");
    Account creditAccount = new Account();
    ReflectionTestUtils.setField(creditAccount, "id", 222L);
    creditAccount.setCompany(company);
    creditAccount.setBalance(BigDecimal.ZERO);
    creditAccount.setCode("CREDIT-INTEGRITY");
    when(accountRepository.lockByCompanyAndId(eq(company), eq(221L)))
        .thenReturn(Optional.of(debitAccount));
    when(accountRepository.lockByCompanyAndId(eq(company), eq(222L)))
        .thenReturn(Optional.of(creditAccount));
    when(journalEntryRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(accountRepository.updateBalanceAtomic(eq(company), any(), any())).thenReturn(1);
    when(accountingEventStore.recordJournalEntryPosted(any(), any()))
        .thenThrow(new DataIntegrityViolationException("duplicate-event-sequence"));

    JournalEntryRequest request =
        new JournalEntryRequest(
            "EVT-INTEGRITY",
            today,
            "Best effort event trail data integrity classification",
            null,
            null,
            Boolean.FALSE,
            List.of(
                new JournalEntryRequest.JournalLineRequest(
                    221L, "Debit line", new BigDecimal("30.00"), BigDecimal.ZERO),
                new JournalEntryRequest.JournalLineRequest(
                    222L, "Credit line", BigDecimal.ZERO, new BigDecimal("30.00"))));

    JournalEntryDto result = accountingService.createJournalEntry(request);
    assertThat(result.referenceNumber()).isEqualTo("EVT-INTEGRITY");
    ArgumentCaptor<Map<String, String>> integrationFailureCaptor =
        ArgumentCaptor.forClass(Map.class);
    verify(auditService)
        .logFailure(eq(AuditEvent.INTEGRATION_FAILURE), integrationFailureCaptor.capture());
    assertThat(integrationFailureCaptor.getValue())
        .containsEntry(
            IntegrationFailureMetadataSchema.KEY_FAILURE_CODE,
            "ACCOUNTING_EVENT_TRAIL_PERSISTENCE_FAILURE")
        .containsEntry(IntegrationFailureMetadataSchema.KEY_ERROR_CATEGORY, "DATA_INTEGRITY")
        .containsEntry("errorType", "DataIntegrityViolationException")
        .containsEntry(
            IntegrationFailureMetadataSchema.KEY_ALERT_ROUTING_VERSION, "ACCOUNTING_EVENT_TRAIL_V1")
        .containsEntry(IntegrationFailureMetadataSchema.KEY_ALERT_ROUTE, "SEV1_PAGE");
  }

  @Test
  void createJournalEntry_dataIntegrityConflictRethrowsForRetryBoundary() {
    LocalDate today = LocalDate.of(2024, 3, 26);
    when(companyClock.today(company)).thenReturn(today);
    AccountingPeriod period = new AccountingPeriod();
    period.setYear(today.getYear());
    period.setMonth(today.getMonthValue());
    when(accountingPeriodService.requirePostablePeriod(
            eq(company), eq(today), any(), any(), any(), anyBoolean()))
        .thenReturn(period);
    when(journalEntryRepository.findByCompanyAndReferenceNumber(eq(company), eq("JE-RACE-1")))
        .thenReturn(Optional.empty());

    Account debitAccount = new Account();
    ReflectionTestUtils.setField(debitAccount, "id", 231L);
    debitAccount.setCompany(company);
    debitAccount.setBalance(BigDecimal.ZERO);
    debitAccount.setCode("DEBIT-RACE");
    Account creditAccount = new Account();
    ReflectionTestUtils.setField(creditAccount, "id", 232L);
    creditAccount.setCompany(company);
    creditAccount.setBalance(BigDecimal.ZERO);
    creditAccount.setCode("CREDIT-RACE");
    when(accountRepository.lockByCompanyAndId(eq(company), eq(231L)))
        .thenReturn(Optional.of(debitAccount));
    when(accountRepository.lockByCompanyAndId(eq(company), eq(232L)))
        .thenReturn(Optional.of(creditAccount));
    when(journalEntryRepository.save(any()))
        .thenThrow(new DataIntegrityViolationException("duplicate-journal"));

    JournalEntryRequest request =
        new JournalEntryRequest(
            "JE-RACE-1",
            today,
            "retry boundary conflict",
            null,
            null,
            Boolean.FALSE,
            List.of(
                new JournalEntryRequest.JournalLineRequest(
                    231L, "Debit line", new BigDecimal("30.00"), BigDecimal.ZERO),
                new JournalEntryRequest.JournalLineRequest(
                    232L, "Credit line", BigDecimal.ZERO, new BigDecimal("30.00"))));

    assertThatThrownBy(() -> accountingService.createJournalEntry(request))
        .isInstanceOf(DataIntegrityViolationException.class);
    verify(accountRepository, never()).updateBalanceAtomic(any(), any(), any());
    verify(accountingEventStore, never()).recordJournalEntryPosted(any(), any());
  }

  @Test
  void createJournalEntry_rejectsEventTrailIncompatibleReferenceLength() {
    LocalDate today = LocalDate.of(2024, 3, 24);
    when(companyClock.today(company)).thenReturn(today);
    AccountingPeriod period = new AccountingPeriod();
    period.setYear(today.getYear());
    period.setMonth(today.getMonthValue());
    when(accountingPeriodService.requirePostablePeriod(
            eq(company), eq(today), any(), any(), any(), anyBoolean()))
        .thenReturn(period);
    String oversizedReference = "R".repeat(101);
    when(journalEntryRepository.findByCompanyAndReferenceNumber(
            eq(company), eq(oversizedReference)))
        .thenReturn(Optional.empty());

    Account debitAccount = new Account();
    ReflectionTestUtils.setField(debitAccount, "id", 301L);
    debitAccount.setCompany(company);
    debitAccount.setBalance(BigDecimal.ZERO);
    debitAccount.setCode("DEBIT-LIMIT");
    Account creditAccount = new Account();
    ReflectionTestUtils.setField(creditAccount, "id", 302L);
    creditAccount.setCompany(company);
    creditAccount.setBalance(BigDecimal.ZERO);
    creditAccount.setCode("CREDIT-LIMIT");
    when(accountRepository.lockByCompanyAndId(eq(company), eq(301L)))
        .thenReturn(Optional.of(debitAccount));
    when(accountRepository.lockByCompanyAndId(eq(company), eq(302L)))
        .thenReturn(Optional.of(creditAccount));
    when(journalEntryRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(accountRepository.updateBalanceAtomic(eq(company), any(), any())).thenReturn(1);

    JournalEntryRequest request =
        new JournalEntryRequest(
            oversizedReference,
            today,
            "Event compatibility length violation",
            null,
            null,
            Boolean.FALSE,
            List.of(
                new JournalEntryRequest.JournalLineRequest(
                    301L, "Debit line", new BigDecimal("10.00"), BigDecimal.ZERO),
                new JournalEntryRequest.JournalLineRequest(
                    302L, "Credit line", BigDecimal.ZERO, new BigDecimal("10.00"))));

    assertThatThrownBy(() -> accountingService.createJournalEntry(request))
        .isInstanceOf(ApplicationException.class)
        .satisfies(
            ex ->
                assertThat(((ApplicationException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.VALIDATION_INVALID_INPUT))
        .hasMessageContaining("Accounting event-trail field exceeds allowed length");

    verify(accountingEventStore, never()).recordJournalEntryPosted(any(), any());
  }

  @Test
  void reverseJournalEntry_failsWhenEventTrailPersistenceFailsInStrictMode() {
    LocalDate today = LocalDate.of(2024, 4, 2);
    when(companyClock.today(company)).thenReturn(today);
    AccountingPeriod openPeriod = openPeriod(today);
    when(accountingPeriodService.requirePostablePeriod(
            eq(company), eq(today), any(), any(), any(), anyBoolean()))
        .thenReturn(openPeriod);

    AccountingService service = spy(accountingService);
    JournalEntry original = reversalSourceEntry(610L, "REV-EVT-STRICT", today);
    JournalEntry reversal = new JournalEntry();
    ReflectionTestUtils.setField(reversal, "id", 910L);

    when(companyEntityLookup.requireJournalEntry(company, 610L)).thenReturn(original);
    when(companyEntityLookup.requireJournalEntry(company, 910L)).thenReturn(reversal);
    when(journalEntryRepository.save(any(JournalEntry.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    doReturn(stubEntry(910L))
        .when(journalEntryService)
        .createJournalEntry(any(JournalEntryRequest.class));
    when(accountingEventStore.recordJournalEntryReversed(any(), any(), any()))
        .thenThrow(new IllegalStateException("event-store-down"));

    JournalEntryReversalRequest request =
        new JournalEntryReversalRequest(
            today,
            false,
            "Strict reversal event failure",
            "Reversal strict failure test",
            Boolean.FALSE);

    assertThatThrownBy(() -> service.reverseJournalEntry(610L, request))
        .isInstanceOf(ApplicationException.class)
        .satisfies(
            ex ->
                assertThat(((ApplicationException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.SYSTEM_DATABASE_ERROR))
        .hasMessageContaining("Accounting event trail persistence failed");

    ArgumentCaptor<Map<String, String>> integrationFailureCaptor =
        ArgumentCaptor.forClass(Map.class);
    verify(auditService)
        .logFailure(eq(AuditEvent.INTEGRATION_FAILURE), integrationFailureCaptor.capture());
    assertThat(integrationFailureCaptor.getValue())
        .containsEntry("eventTrailOperation", "JOURNAL_ENTRY_REVERSED")
        .containsEntry("policy", "STRICT")
        .containsEntry(
            IntegrationFailureMetadataSchema.KEY_FAILURE_CODE,
            "ACCOUNTING_EVENT_TRAIL_PERSISTENCE_FAILURE")
        .containsEntry(IntegrationFailureMetadataSchema.KEY_ERROR_CATEGORY, "PERSISTENCE")
        .containsEntry("errorType", "IllegalStateException")
        .containsEntry(
            IntegrationFailureMetadataSchema.KEY_ALERT_ROUTING_VERSION, "ACCOUNTING_EVENT_TRAIL_V1")
        .containsEntry(IntegrationFailureMetadataSchema.KEY_ALERT_ROUTE, "SEV2_URGENT")
        .doesNotContainKey("error");
    verify(auditService, never()).logSuccess(eq(AuditEvent.JOURNAL_ENTRY_REVERSED), any());
  }

  @Test
  void reverseJournalEntry_bestEffortEventTrailValidationApplicationExceptionClassified() {
    ReflectionTestUtils.setField(journalEntryService, "strictAccountingEventTrail", false);
    LocalDate today = LocalDate.of(2024, 4, 3);
    when(companyClock.today(company)).thenReturn(today);
    AccountingPeriod openPeriod = openPeriod(today);
    when(accountingPeriodService.requirePostablePeriod(
            eq(company), eq(today), any(), any(), any(), anyBoolean()))
        .thenReturn(openPeriod);

    AccountingService service = spy(accountingService);
    JournalEntry original = reversalSourceEntry(612L, "REV-EVT-VALIDATION", today);
    JournalEntry reversal = new JournalEntry();
    ReflectionTestUtils.setField(reversal, "id", 912L);

    when(companyEntityLookup.requireJournalEntry(company, 612L)).thenReturn(original);
    when(companyEntityLookup.requireJournalEntry(company, 912L)).thenReturn(reversal);
    when(journalEntryRepository.save(any(JournalEntry.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    doReturn(stubEntry(912L))
        .when(journalEntryService)
        .createJournalEntry(any(JournalEntryRequest.class));
    when(accountingEventStore.recordJournalEntryReversed(any(), any(), any()))
        .thenThrow(
            new ApplicationException(
                ErrorCode.VALIDATION_INVALID_REFERENCE, "invalid reversal event payload"));

    JournalEntryReversalRequest request =
        new JournalEntryReversalRequest(
            today,
            false,
            "Best effort reversal validation classification",
            "Reversal validation classification test",
            Boolean.FALSE);

    JournalEntryDto result = service.reverseJournalEntry(612L, request);
    assertThat(result.id()).isEqualTo(912L);

    ArgumentCaptor<Map<String, String>> integrationFailureCaptor =
        ArgumentCaptor.forClass(Map.class);
    verify(auditService)
        .logFailure(eq(AuditEvent.INTEGRATION_FAILURE), integrationFailureCaptor.capture());
    assertThat(integrationFailureCaptor.getValue())
        .containsEntry("eventTrailOperation", "JOURNAL_ENTRY_REVERSED")
        .containsEntry("policy", "BEST_EFFORT")
        .containsEntry(
            IntegrationFailureMetadataSchema.KEY_FAILURE_CODE,
            "ACCOUNTING_EVENT_TRAIL_PERSISTENCE_FAILURE")
        .containsEntry(IntegrationFailureMetadataSchema.KEY_ERROR_CATEGORY, "VALIDATION")
        .containsEntry("errorType", "ApplicationException")
        .containsEntry(
            IntegrationFailureMetadataSchema.KEY_ALERT_ROUTING_VERSION, "ACCOUNTING_EVENT_TRAIL_V1")
        .containsEntry(IntegrationFailureMetadataSchema.KEY_ALERT_ROUTE, "SEV2_URGENT");
    verify(auditService, never()).logSuccess(eq(AuditEvent.JOURNAL_ENTRY_REVERSED), any());
  }

  @Test
  void reverseJournalEntry_bestEffortEventTrailDataIntegrityApplicationExceptionClassified() {
    ReflectionTestUtils.setField(journalEntryService, "strictAccountingEventTrail", false);
    LocalDate today = LocalDate.of(2024, 4, 4);
    when(companyClock.today(company)).thenReturn(today);
    AccountingPeriod openPeriod = openPeriod(today);
    when(accountingPeriodService.requirePostablePeriod(
            eq(company), eq(today), any(), any(), any(), anyBoolean()))
        .thenReturn(openPeriod);

    AccountingService service = spy(accountingService);
    JournalEntry original = reversalSourceEntry(613L, "REV-EVT-INTEGRITY", today);
    JournalEntry reversal = new JournalEntry();
    ReflectionTestUtils.setField(reversal, "id", 913L);

    when(companyEntityLookup.requireJournalEntry(company, 613L)).thenReturn(original);
    when(companyEntityLookup.requireJournalEntry(company, 913L)).thenReturn(reversal);
    when(journalEntryRepository.save(any(JournalEntry.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    doReturn(stubEntry(913L))
        .when(journalEntryService)
        .createJournalEntry(any(JournalEntryRequest.class));
    when(accountingEventStore.recordJournalEntryReversed(any(), any(), any()))
        .thenThrow(
            new ApplicationException(
                ErrorCode.INTERNAL_CONCURRENCY_FAILURE, "duplicate reversal event key"));

    JournalEntryReversalRequest request =
        new JournalEntryReversalRequest(
            today,
            false,
            "Best effort reversal data integrity classification",
            "Reversal data integrity classification test",
            Boolean.FALSE);

    JournalEntryDto result = service.reverseJournalEntry(613L, request);
    assertThat(result.id()).isEqualTo(913L);

    ArgumentCaptor<Map<String, String>> integrationFailureCaptor =
        ArgumentCaptor.forClass(Map.class);
    verify(auditService)
        .logFailure(eq(AuditEvent.INTEGRATION_FAILURE), integrationFailureCaptor.capture());
    assertThat(integrationFailureCaptor.getValue())
        .containsEntry("eventTrailOperation", "JOURNAL_ENTRY_REVERSED")
        .containsEntry("policy", "BEST_EFFORT")
        .containsEntry(
            IntegrationFailureMetadataSchema.KEY_FAILURE_CODE,
            "ACCOUNTING_EVENT_TRAIL_PERSISTENCE_FAILURE")
        .containsEntry(IntegrationFailureMetadataSchema.KEY_ERROR_CATEGORY, "DATA_INTEGRITY")
        .containsEntry("errorType", "ApplicationException")
        .containsEntry(
            IntegrationFailureMetadataSchema.KEY_ALERT_ROUTING_VERSION, "ACCOUNTING_EVENT_TRAIL_V1")
        .containsEntry(IntegrationFailureMetadataSchema.KEY_ALERT_ROUTE, "SEV1_PAGE");
    verify(auditService, never()).logSuccess(eq(AuditEvent.JOURNAL_ENTRY_REVERSED), any());
  }

  @Test
  void reverseJournalEntry_bestEffortEventTrailFailureContinuesWhenAuditMarkerFails() {
    ReflectionTestUtils.setField(journalEntryService, "strictAccountingEventTrail", false);
    LocalDate today = LocalDate.of(2024, 4, 3);
    when(companyClock.today(company)).thenReturn(today);
    AccountingPeriod openPeriod = openPeriod(today);
    when(accountingPeriodService.requirePostablePeriod(
            eq(company), eq(today), any(), any(), any(), anyBoolean()))
        .thenReturn(openPeriod);

    AccountingService service = spy(accountingService);
    JournalEntry original = reversalSourceEntry(611L, "REV-EVT-BEST", today);
    JournalEntry reversal = new JournalEntry();
    ReflectionTestUtils.setField(reversal, "id", 911L);

    when(companyEntityLookup.requireJournalEntry(company, 611L)).thenReturn(original);
    when(companyEntityLookup.requireJournalEntry(company, 911L)).thenReturn(reversal);
    when(journalEntryRepository.save(any(JournalEntry.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    doReturn(stubEntry(911L))
        .when(journalEntryService)
        .createJournalEntry(any(JournalEntryRequest.class));
    when(accountingEventStore.recordJournalEntryReversed(any(), any(), any()))
        .thenThrow(new IllegalStateException("event-store-down"));
    doThrow(new IllegalStateException("audit-log-down"))
        .when(auditService)
        .logFailure(
            eq(AuditEvent.INTEGRATION_FAILURE),
            org.mockito.ArgumentMatchers.<Map<String, String>>any());

    JournalEntryReversalRequest request =
        new JournalEntryReversalRequest(
            today,
            false,
            "Best effort reversal event failure",
            "Reversal best effort failure test",
            Boolean.FALSE);

    JournalEntryDto result = service.reverseJournalEntry(611L, request);
    assertThat(result.id()).isEqualTo(911L);
    verify(auditService)
        .logFailure(
            eq(AuditEvent.INTEGRATION_FAILURE),
            org.mockito.ArgumentMatchers.<Map<String, String>>any());
    verify(auditService, never()).logSuccess(eq(AuditEvent.JOURNAL_ENTRY_REVERSED), any());
  }

  @Test
  void createJournalEntry_convertsForeignCurrencyToBaseAmounts() {
    LocalDate today = LocalDate.of(2024, 4, 1);
    when(companyClock.today(company)).thenReturn(today);
    AccountingPeriod period = new AccountingPeriod();
    period.setYear(today.getYear());
    period.setMonth(today.getMonthValue());
    period.setStartDate(today.withDayOfMonth(1));
    period.setEndDate(today.withDayOfMonth(today.lengthOfMonth()));
    when(accountingPeriodService.requirePostablePeriod(
            eq(company), eq(today), any(), any(), any(), anyBoolean()))
        .thenReturn(period);
    when(journalEntryRepository.findByCompanyAndReferenceNumber(eq(company), eq("FX-REF")))
        .thenReturn(Optional.empty());

    var debitAccount = new com.bigbrightpaints.erp.modules.accounting.domain.Account();
    ReflectionTestUtils.setField(debitAccount, "id", 1L);
    debitAccount.setCompany(company);
    debitAccount.setCode("DEBIT");
    var creditAccount = new com.bigbrightpaints.erp.modules.accounting.domain.Account();
    ReflectionTestUtils.setField(creditAccount, "id", 2L);
    creditAccount.setCompany(company);
    creditAccount.setCode("CREDIT");

    when(accountRepository.lockByCompanyAndId(eq(company), eq(1L)))
        .thenReturn(Optional.of(debitAccount));
    when(accountRepository.lockByCompanyAndId(eq(company), eq(2L)))
        .thenReturn(Optional.of(creditAccount));
    when(journalEntryRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(accountRepository.updateBalanceAtomic(eq(company), any(), any())).thenReturn(1);

    JournalEntryRequest request =
        new JournalEntryRequest(
            "FX-REF",
            today,
            "USD journal",
            null,
            null,
            Boolean.FALSE,
            List.of(
                new JournalEntryRequest.JournalLineRequest(
                    1L, "Debit", new BigDecimal("1.00"), BigDecimal.ZERO),
                new JournalEntryRequest.JournalLineRequest(
                    2L, "Credit", BigDecimal.ZERO, new BigDecimal("1.00"))),
            "USD",
            new BigDecimal("80.0"));

    accountingService.createJournalEntry(request);

    verify(accountRepository).updateBalanceAtomic(eq(company), eq(1L), eq(new BigDecimal("80.00")));
    verify(accountRepository)
        .updateBalanceAtomic(eq(company), eq(2L), eq(new BigDecimal("-80.00")));
  }

  @Test
  void updatePurchaseStatus_setsPaidWhenOutstandingZero() {
    RawMaterialPurchase purchase = new RawMaterialPurchase();
    purchase.setTotalAmount(new BigDecimal("120.00"));
    purchase.setOutstandingAmount(BigDecimal.ZERO);
    purchase.setStatus("POSTED");

    accountingService.updatePurchaseStatus(purchase);

    assertThat(purchase.getStatus()).isEqualTo("PAID");
  }

  @Test
  void updatePurchaseStatus_setsPartialWhenOutstandingBetween() {
    RawMaterialPurchase purchase = new RawMaterialPurchase();
    purchase.setTotalAmount(new BigDecimal("120.00"));
    purchase.setOutstandingAmount(new BigDecimal("50.00"));
    purchase.setStatus("POSTED");

    accountingService.updatePurchaseStatus(purchase);

    assertThat(purchase.getStatus()).isEqualTo("PARTIAL");
  }

  @Test
  void updatePurchaseStatus_setsPostedWhenOutstandingEqualsTotal() {
    RawMaterialPurchase purchase = new RawMaterialPurchase();
    purchase.setTotalAmount(new BigDecimal("120.00"));
    purchase.setOutstandingAmount(new BigDecimal("120.00"));
    purchase.setStatus("POSTED");

    accountingService.updatePurchaseStatus(purchase);

    assertThat(purchase.getStatus()).isEqualTo("POSTED");
  }

  @Test
  void createJournalEntry_requiresFxRateForForeignCurrency() {
    LocalDate today = LocalDate.of(2024, 4, 2);
    JournalEntryRequest request =
        new JournalEntryRequest(
            "FX-NORATE",
            today,
            "USD journal",
            null,
            null,
            Boolean.FALSE,
            List.of(
                new JournalEntryRequest.JournalLineRequest(
                    1L, "Debit", new BigDecimal("1.00"), BigDecimal.ZERO),
                new JournalEntryRequest.JournalLineRequest(
                    2L, "Credit", BigDecimal.ZERO, new BigDecimal("1.00"))),
            "USD",
            null);

    assertThatThrownBy(() -> accountingService.createJournalEntry(request))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("FX rate is required");
  }

  @Test
  void createJournalEntry_rejectsLineWithMissingAccount() {
    LocalDate today = LocalDate.of(2024, 4, 3);
    when(companyClock.today(company)).thenReturn(today);

    JournalEntryRequest request =
        new JournalEntryRequest(
            "NO-ACC",
            today,
            "Missing account",
            null,
            null,
            Boolean.FALSE,
            List.of(
                new JournalEntryRequest.JournalLineRequest(
                    null, "Line", new BigDecimal("10.00"), BigDecimal.ZERO)));

    assertThatThrownBy(() -> accountingService.createJournalEntry(request))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Account is required for every journal line");
  }

  @Test
  void createJournalEntry_rejectsLineWithBothDebitAndCredit() {
    LocalDate today = LocalDate.of(2024, 4, 4);
    when(companyClock.today(company)).thenReturn(today);

    var account = new com.bigbrightpaints.erp.modules.accounting.domain.Account();
    ReflectionTestUtils.setField(account, "id", 1L);
    account.setCompany(company);
    account.setCode("ACC-1");
    when(accountRepository.lockByCompanyAndId(eq(company), eq(1L)))
        .thenReturn(Optional.of(account));

    JournalEntryRequest request =
        new JournalEntryRequest(
            "BOTH-DC",
            today,
            "Invalid line",
            null,
            null,
            Boolean.FALSE,
            List.of(
                new JournalEntryRequest.JournalLineRequest(
                    1L, "Line", new BigDecimal("10.00"), new BigDecimal("1.00"))));

    assertThatThrownBy(() -> accountingService.createJournalEntry(request))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Debit and credit cannot both be non-zero on the same line");
  }

  @Test
  void createJournalEntry_rejectsNegativeAmounts() {
    LocalDate today = LocalDate.of(2024, 4, 5);
    when(companyClock.today(company)).thenReturn(today);

    var account = new com.bigbrightpaints.erp.modules.accounting.domain.Account();
    ReflectionTestUtils.setField(account, "id", 1L);
    account.setCompany(company);
    account.setCode("ACC-1");
    when(accountRepository.lockByCompanyAndId(eq(company), eq(1L)))
        .thenReturn(Optional.of(account));

    JournalEntryRequest request =
        new JournalEntryRequest(
            "NEG-AMT",
            today,
            "Negative debit",
            null,
            null,
            Boolean.FALSE,
            List.of(
                new JournalEntryRequest.JournalLineRequest(
                    1L, "Line", new BigDecimal("-1.00"), BigDecimal.ZERO)));

    assertThatThrownBy(() -> accountingService.createJournalEntry(request))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Debit/Credit cannot be negative");
  }

  @Test
  void createJournalEntry_rejectsUnbalancedJournalBeyondFxRoundingTolerance() {
    LocalDate today = LocalDate.of(2024, 4, 6);
    when(companyClock.today(company)).thenReturn(today);

    var debitAccount = new com.bigbrightpaints.erp.modules.accounting.domain.Account();
    ReflectionTestUtils.setField(debitAccount, "id", 1L);
    debitAccount.setCompany(company);
    debitAccount.setCode("DEBIT");
    var creditAccount = new com.bigbrightpaints.erp.modules.accounting.domain.Account();
    ReflectionTestUtils.setField(creditAccount, "id", 2L);
    creditAccount.setCompany(company);
    creditAccount.setCode("CREDIT");

    when(accountRepository.lockByCompanyAndId(eq(company), eq(1L)))
        .thenReturn(Optional.of(debitAccount));
    when(accountRepository.lockByCompanyAndId(eq(company), eq(2L)))
        .thenReturn(Optional.of(creditAccount));

    JournalEntryRequest request =
        new JournalEntryRequest(
            "UNBAL",
            today,
            "Not balanced",
            null,
            null,
            Boolean.FALSE,
            List.of(
                new JournalEntryRequest.JournalLineRequest(
                    1L, "Debit", new BigDecimal("10.00"), BigDecimal.ZERO),
                new JournalEntryRequest.JournalLineRequest(
                    2L, "Credit", BigDecimal.ZERO, new BigDecimal("9.00"))));

    assertThatThrownBy(() -> accountingService.createJournalEntry(request))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Journal entry must balance");
  }

  @Test
  void createJournalEntry_adjustsMinorFxRoundingDeltaWithinTolerance() {
    LocalDate today = LocalDate.of(2024, 4, 7);
    when(companyClock.today(company)).thenReturn(today);
    AccountingPeriod period = new AccountingPeriod();
    period.setYear(today.getYear());
    period.setMonth(today.getMonthValue());
    period.setStartDate(today.withDayOfMonth(1));
    period.setEndDate(today.withDayOfMonth(today.lengthOfMonth()));
    when(accountingPeriodService.requirePostablePeriod(
            eq(company), eq(today), any(), any(), any(), anyBoolean()))
        .thenReturn(period);
    when(journalEntryRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(accountRepository.updateBalanceAtomic(eq(company), any(), any())).thenReturn(1);

    var debitAccount = new com.bigbrightpaints.erp.modules.accounting.domain.Account();
    ReflectionTestUtils.setField(debitAccount, "id", 1L);
    debitAccount.setCompany(company);
    debitAccount.setCode("DEBIT");
    var creditAccount1 = new com.bigbrightpaints.erp.modules.accounting.domain.Account();
    ReflectionTestUtils.setField(creditAccount1, "id", 2L);
    creditAccount1.setCompany(company);
    creditAccount1.setCode("CREDIT-1");
    var creditAccount2 = new com.bigbrightpaints.erp.modules.accounting.domain.Account();
    ReflectionTestUtils.setField(creditAccount2, "id", 3L);
    creditAccount2.setCompany(company);
    creditAccount2.setCode("CREDIT-2");

    when(accountRepository.lockByCompanyAndId(eq(company), eq(1L)))
        .thenReturn(Optional.of(debitAccount));
    when(accountRepository.lockByCompanyAndId(eq(company), eq(2L)))
        .thenReturn(Optional.of(creditAccount1));
    when(accountRepository.lockByCompanyAndId(eq(company), eq(3L)))
        .thenReturn(Optional.of(creditAccount2));

    JournalEntryRequest request =
        new JournalEntryRequest(
            "FX-ROUND",
            today,
            "FX rounding",
            null,
            null,
            Boolean.FALSE,
            List.of(
                new JournalEntryRequest.JournalLineRequest(
                    1L, "Debit", new BigDecimal("0.02"), BigDecimal.ZERO),
                new JournalEntryRequest.JournalLineRequest(
                    2L, "Credit 1", BigDecimal.ZERO, new BigDecimal("0.01")),
                new JournalEntryRequest.JournalLineRequest(
                    3L, "Credit 2", BigDecimal.ZERO, new BigDecimal("0.01"))),
            "USD",
            new BigDecimal("1.333333"));

    accountingService.createJournalEntry(request);

    verify(accountRepository).updateBalanceAtomic(eq(company), eq(1L), eq(new BigDecimal("0.03")));
    verify(accountRepository).updateBalanceAtomic(eq(company), eq(2L), eq(new BigDecimal("-0.02")));
    verify(accountRepository).updateBalanceAtomic(eq(company), eq(3L), eq(new BigDecimal("-0.01")));
  }

  @Test
  void createJournalEntry_adjustsMinorBaseRoundingDeltaWithinTolerance() {
    LocalDate today = LocalDate.of(2024, 4, 8);
    when(companyClock.today(company)).thenReturn(today);
    AccountingPeriod period = new AccountingPeriod();
    period.setYear(today.getYear());
    period.setMonth(today.getMonthValue());
    period.setStartDate(today.withDayOfMonth(1));
    period.setEndDate(today.withDayOfMonth(today.lengthOfMonth()));
    when(accountingPeriodService.requirePostablePeriod(
            eq(company), eq(today), any(), any(), any(), anyBoolean()))
        .thenReturn(period);
    when(journalEntryRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(accountRepository.updateBalanceAtomic(eq(company), any(), any())).thenReturn(1);

    var debitAccount = new com.bigbrightpaints.erp.modules.accounting.domain.Account();
    ReflectionTestUtils.setField(debitAccount, "id", 1L);
    debitAccount.setCompany(company);
    debitAccount.setCode("DEBIT");
    var creditAccount = new com.bigbrightpaints.erp.modules.accounting.domain.Account();
    ReflectionTestUtils.setField(creditAccount, "id", 2L);
    creditAccount.setCompany(company);
    creditAccount.setCode("CREDIT");

    when(accountRepository.lockByCompanyAndId(eq(company), eq(1L)))
        .thenReturn(Optional.of(debitAccount));
    when(accountRepository.lockByCompanyAndId(eq(company), eq(2L)))
        .thenReturn(Optional.of(creditAccount));

    JournalEntryRequest request =
        new JournalEntryRequest(
            "BASE-ROUND",
            today,
            "Base rounding",
            null,
            null,
            Boolean.FALSE,
            List.of(
                new JournalEntryRequest.JournalLineRequest(
                    1L, "Debit", new BigDecimal("10.005"), BigDecimal.ZERO),
                new JournalEntryRequest.JournalLineRequest(
                    2L, "Credit", BigDecimal.ZERO, new BigDecimal("10.00"))));

    accountingService.createJournalEntry(request);
  }

  @Test
  void recordSupplierPayment_rejectsReferenceOnlySupplierWithExplicitReason() {
    AccountingService service = spy(accountingService);

    Supplier supplier = new Supplier();
    supplier.setName("Supplier");
    supplier.setStatus(SupplierStatus.SUSPENDED);
    Account payable = new Account();
    ReflectionTestUtils.setField(payable, "id", 501L);
    supplier.setPayableAccount(payable);
    ReflectionTestUtils.setField(supplier, "id", 1L);

    Account cash = new Account();
    cash.setCompany(company);
    cash.setCode("BANK-REFONLY");
    cash.setType(AccountType.ASSET);
    ReflectionTestUtils.setField(cash, "id", 20L);

    when(supplierRepository.lockByCompanyAndId(eq(company), eq(1L)))
        .thenReturn(Optional.of(supplier));
    when(companyEntityLookup.requireAccount(eq(company), eq(20L))).thenReturn(cash);

    SupplierPaymentRequest request =
        new SupplierPaymentRequest(
            1L,
            20L,
            new BigDecimal("50.00"),
            "SUP-PAY-REFONLY-1",
            "Supplier payment",
            "IDEMP-SUP-PAY-REFONLY-1",
            List.of(
                new SettlementAllocationRequest(
                    null,
                    7000L,
                    new BigDecimal("50.00"),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    null,
                    null)));

    assertThatThrownBy(() -> service.recordSupplierPayment(request))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("suspended")
        .hasMessageContaining("reference only");

    verify(service, never()).createJournalEntry(any(JournalEntryRequest.class));
  }

  @Test
  void settleSupplierInvoices_rejectsReferenceOnlySupplierWithExplicitReason() {
    AccountingService service = spy(accountingService);

    Supplier supplier = new Supplier();
    supplier.setName("Supplier");
    supplier.setStatus(SupplierStatus.PENDING);
    Account payable = new Account();
    ReflectionTestUtils.setField(payable, "id", 502L);
    supplier.setPayableAccount(payable);
    ReflectionTestUtils.setField(supplier, "id", 1L);

    when(supplierRepository.lockByCompanyAndId(eq(company), eq(1L)))
        .thenReturn(Optional.of(supplier));

    SupplierSettlementRequest request =
        new SupplierSettlementRequest(
            1L,
            20L,
            null,
            null,
            null,
            null,
            LocalDate.of(2024, 4, 9),
            "SUP-SET-REFONLY-1",
            "Supplier settlement",
            "IDEMP-SUP-SET-REFONLY-1",
            Boolean.FALSE,
            List.of(
                new SettlementAllocationRequest(
                    null,
                    7001L,
                    new BigDecimal("50.00"),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    null)));

    assertThatThrownBy(() -> service.settleSupplierInvoices(request))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("pending approval")
        .hasMessageContaining("reference only");

    verify(service, never()).createJournalEntry(any(JournalEntryRequest.class));
  }

  @Test
  void settleSupplierInvoices_cashAmountAccountsForDiscountAndFxGain() {
    AccountingService service = spy(accountingService);

    Supplier supplier = new Supplier();
    supplier.setName("Supplier");
    supplier.setStatus(SupplierStatus.ACTIVE);
    Account payable = new Account();
    payable.setCompany(company);
    payable.setCode("AP");
    payable.setType(AccountType.LIABILITY);
    ReflectionTestUtils.setField(payable, "id", 10L);
    supplier.setPayableAccount(payable);
    ReflectionTestUtils.setField(supplier, "id", 1L);

    Account cash = new Account();
    cash.setCompany(company);
    cash.setCode("CASH");
    ReflectionTestUtils.setField(cash, "id", 20L);

    Account discount = new Account();
    discount.setCompany(company);
    discount.setCode("DISC");
    ReflectionTestUtils.setField(discount, "id", 21L);

    Account fxGain = new Account();
    fxGain.setCompany(company);
    fxGain.setCode("FXGAIN");
    ReflectionTestUtils.setField(fxGain, "id", 22L);

    RawMaterialPurchase purchase = new RawMaterialPurchase();
    purchase.setCompany(company);
    purchase.setSupplier(supplier);
    purchase.setTotalAmount(new BigDecimal("200.00"));
    purchase.setTaxAmount(BigDecimal.ZERO);
    purchase.setOutstandingAmount(new BigDecimal("200.00"));
    ReflectionTestUtils.setField(purchase, "id", 2L);

    Account inventory = new Account();
    inventory.setCompany(company);
    inventory.setCode("INV-AP");
    inventory.setType(AccountType.ASSET);
    ReflectionTestUtils.setField(inventory, "id", 23L);

    JournalEntry purchaseJournal = new JournalEntry();
    ReflectionTestUtils.setField(purchaseJournal, "id", 2002L);
    purchaseJournal.setSupplier(supplier);
    purchaseJournal.addLine(
            journalLine(
                purchaseJournal,
                inventory,
                "Purchase invoice",
                new BigDecimal("200.00"),
                BigDecimal.ZERO));
    purchaseJournal.addLine(
            journalLine(
                purchaseJournal,
                payable,
                "Purchase invoice",
                BigDecimal.ZERO,
                new BigDecimal("200.00")));
    purchase.setJournalEntry(purchaseJournal);

    when(supplierRepository.lockByCompanyAndId(eq(company), eq(1L)))
        .thenReturn(Optional.of(supplier));
    when(rawMaterialPurchaseRepository.lockByCompanyAndId(eq(company), eq(2L)))
        .thenReturn(Optional.of(purchase));
    when(settlementAllocationRepository.findByCompanyAndIdempotencyKey(any(), any()))
        .thenReturn(List.of());
    lenient().when(companyEntityLookup.requireAccount(eq(company), eq(20L))).thenReturn(cash);
    when(companyEntityLookup.requireAccount(eq(company), eq(21L))).thenReturn(discount);
    when(companyEntityLookup.requireAccount(eq(company), eq(22L))).thenReturn(fxGain);

    JournalEntryDto journalEntryDto = stubEntry(55L);
    ArgumentCaptor<JournalEntryRequest> journalCaptor =
        ArgumentCaptor.forClass(JournalEntryRequest.class);
    doReturn(journalEntryDto)
        .when(journalEntryService)
        .createJournalEntry(journalCaptor.capture());
    when(companyEntityLookup.requireJournalEntry(eq(company), eq(55L)))
        .thenReturn(new com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry());

    SettlementAllocationRequest allocation =
        new SettlementAllocationRequest(
            null,
            2L,
            new BigDecimal("100.00"),
            new BigDecimal("10.00"),
            BigDecimal.ZERO,
            new BigDecimal("5.00"),
            "settle");

    SupplierSettlementRequest request =
        new SupplierSettlementRequest(
            1L,
            20L,
            21L,
            null,
            22L,
            null,
            LocalDate.of(2024, 4, 9),
            "REF-AP-1",
            "Supplier settlement",
            "IDEMP-AP-1",
            Boolean.TRUE,
            List.of(allocation));

    service.settleSupplierInvoices(request);
    assertThat(purchase.getOutstandingAmount()).isEqualByComparingTo("100.00");

    JournalEntryRequest captured = journalCaptor.getValue();
    JournalEntryRequest.JournalLineRequest cashLine =
        captured.lines().stream()
            .filter(line -> line.accountId().equals(20L))
            .findFirst()
            .orElseThrow();
    JournalEntryRequest.JournalLineRequest discountLine =
        captured.lines().stream()
            .filter(line -> line.accountId().equals(21L))
            .findFirst()
            .orElseThrow();
    JournalEntryRequest.JournalLineRequest fxGainLine =
        captured.lines().stream()
            .filter(line -> line.accountId().equals(22L))
            .findFirst()
            .orElseThrow();

    assertThat(cashLine.credit()).isEqualByComparingTo("85.00");
    assertThat(discountLine.credit()).isEqualByComparingTo("10.00");
    assertThat(fxGainLine.credit()).isEqualByComparingTo("5.00");
  }

  @Test
  void settleSupplierInvoices_rejectsGstPurchaseWhenInputTaxPostingMissing() {
    AccountingService service = spy(accountingService);

    Supplier supplier = new Supplier();
    supplier.setName("Supplier");
    supplier.setStatus(SupplierStatus.ACTIVE);
    ReflectionTestUtils.setField(supplier, "id", 1L);

    Account payable = new Account();
    payable.setCompany(company);
    payable.setCode("AP");
    payable.setType(AccountType.LIABILITY);
    ReflectionTestUtils.setField(payable, "id", 10L);
    supplier.setPayableAccount(payable);

    Account cash = new Account();
    cash.setCompany(company);
    cash.setCode("CASH");
    cash.setType(AccountType.ASSET);
    ReflectionTestUtils.setField(cash, "id", 20L);

    Account inventory = new Account();
    inventory.setCompany(company);
    inventory.setCode("INV");
    inventory.setType(AccountType.ASSET);
    ReflectionTestUtils.setField(inventory, "id", 21L);

    RawMaterialPurchase purchase = new RawMaterialPurchase();
    purchase.setCompany(company);
    purchase.setSupplier(supplier);
    purchase.setTotalAmount(new BigDecimal("118.00"));
    purchase.setTaxAmount(new BigDecimal("18.00"));
    purchase.setOutstandingAmount(new BigDecimal("118.00"));
    ReflectionTestUtils.setField(purchase, "id", 7001L);

    JournalEntry purchaseJournal = new JournalEntry();
    ReflectionTestUtils.setField(purchaseJournal, "id", 9701L);
    purchaseJournal.setSupplier(supplier);
    purchaseJournal.setReferenceNumber("RMP-GST-MISS-1");
    purchaseJournal.addLine(
            journalLine(
                purchaseJournal,
                inventory,
                "Purchase invoice GST missing",
                new BigDecimal("118.00"),
                BigDecimal.ZERO));
    purchaseJournal.addLine(
            journalLine(
                purchaseJournal,
                payable,
                "Purchase invoice GST missing",
                BigDecimal.ZERO,
                new BigDecimal("118.00")));
    purchase.setJournalEntry(purchaseJournal);

    when(supplierRepository.lockByCompanyAndId(eq(company), eq(1L)))
        .thenReturn(Optional.of(supplier));
    when(rawMaterialPurchaseRepository.lockByCompanyAndId(eq(company), eq(7001L)))
        .thenReturn(Optional.of(purchase));
    lenient().when(companyEntityLookup.requireAccount(eq(company), eq(20L))).thenReturn(cash);

    SupplierSettlementRequest request =
        new SupplierSettlementRequest(
            1L,
            20L,
            null,
            null,
            null,
            null,
            LocalDate.of(2024, 4, 9),
            "SUP-GST-MISS-1",
            "Supplier settlement GST mismatch",
            "IDEMP-SUP-GST-MISS-1",
            Boolean.FALSE,
            List.of(
                new SettlementAllocationRequest(
                    null,
                    7001L,
                    new BigDecimal("50.00"),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    null)));

    assertThatThrownBy(() -> service.settleSupplierInvoices(request))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("GST purchase input tax posting mismatch");
    verify(service, never()).createJournalEntry(any(JournalEntryRequest.class));
  }

  @Test
  void settleSupplierInvoices_rejectsNonGstPurchaseWhenInputTaxPostingExists() {
    AccountingService service = spy(accountingService);

    Supplier supplier = new Supplier();
    supplier.setName("Supplier");
    supplier.setStatus(SupplierStatus.ACTIVE);
    ReflectionTestUtils.setField(supplier, "id", 1L);

    Account payable = new Account();
    payable.setCompany(company);
    payable.setCode("AP");
    payable.setType(AccountType.LIABILITY);
    ReflectionTestUtils.setField(payable, "id", 10L);
    supplier.setPayableAccount(payable);

    Account cash = new Account();
    cash.setCompany(company);
    cash.setCode("CASH");
    cash.setType(AccountType.ASSET);
    ReflectionTestUtils.setField(cash, "id", 20L);

    Account inventory = new Account();
    inventory.setCompany(company);
    inventory.setCode("INV");
    inventory.setType(AccountType.ASSET);
    ReflectionTestUtils.setField(inventory, "id", 21L);

    Account inputTax = new Account();
    inputTax.setCompany(company);
    inputTax.setCode("GST-IN");
    inputTax.setType(AccountType.ASSET);
    ReflectionTestUtils.setField(inputTax, "id", 22L);

    RawMaterialPurchase purchase = new RawMaterialPurchase();
    purchase.setCompany(company);
    purchase.setSupplier(supplier);
    purchase.setTotalAmount(new BigDecimal("100.00"));
    purchase.setTaxAmount(BigDecimal.ZERO);
    purchase.setOutstandingAmount(new BigDecimal("100.00"));
    ReflectionTestUtils.setField(purchase, "id", 7002L);

    JournalEntry purchaseJournal = new JournalEntry();
    ReflectionTestUtils.setField(purchaseJournal, "id", 9702L);
    purchaseJournal.setSupplier(supplier);
    purchaseJournal.setReferenceNumber("RMP-NONGST-MISS-1");
    purchaseJournal.addLine(
            journalLine(
                purchaseJournal,
                inventory,
                "Purchase invoice non-GST",
                new BigDecimal("82.00"),
                BigDecimal.ZERO));
    purchaseJournal.addLine(
            journalLine(
                purchaseJournal,
                inputTax,
                "Input tax for purchase invoice non-GST",
                new BigDecimal("18.00"),
                BigDecimal.ZERO));
    purchaseJournal.addLine(
            journalLine(
                purchaseJournal,
                payable,
                "Purchase invoice non-GST",
                BigDecimal.ZERO,
                new BigDecimal("100.00")));
    purchase.setJournalEntry(purchaseJournal);

    when(supplierRepository.lockByCompanyAndId(eq(company), eq(1L)))
        .thenReturn(Optional.of(supplier));
    when(rawMaterialPurchaseRepository.lockByCompanyAndId(eq(company), eq(7002L)))
        .thenReturn(Optional.of(purchase));
    lenient().when(companyEntityLookup.requireAccount(eq(company), eq(20L))).thenReturn(cash);

    SupplierSettlementRequest request =
        new SupplierSettlementRequest(
            1L,
            20L,
            null,
            null,
            null,
            null,
            LocalDate.of(2024, 4, 9),
            "SUP-NONGST-MISS-1",
            "Supplier settlement non-GST mismatch",
            "IDEMP-SUP-NONGST-MISS-1",
            Boolean.FALSE,
            List.of(
                new SettlementAllocationRequest(
                    null,
                    7002L,
                    new BigDecimal("50.00"),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    null)));

    assertThatThrownBy(() -> service.settleSupplierInvoices(request))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("non-GST purchase has input tax posting");
    verify(service, never()).createJournalEntry(any(JournalEntryRequest.class));
  }

  @Test
  void settleDealerInvoices_rejectsOldSettlementDateBeforeMutation() {
    LocalDate today = LocalDate.of(2024, 4, 30);
    when(companyClock.today(company)).thenReturn(today);
    when(journalEntryRepository.findByCompanyAndReferenceNumber(eq(company), any()))
        .thenReturn(Optional.empty());

    Dealer dealer = new Dealer();
    dealer.setName("Closed-period dealer");
    ReflectionTestUtils.setField(dealer, "id", 1L);

    Account receivable = new Account();
    receivable.setCompany(company);
    receivable.setCode("AR-OLD");
    receivable.setType(AccountType.ASSET);
    ReflectionTestUtils.setField(receivable, "id", 10L);
    dealer.setReceivableAccount(receivable);

    Account cash = new Account();
    cash.setCompany(company);
    cash.setCode("CASH-OLD");
    cash.setType(AccountType.ASSET);
    ReflectionTestUtils.setField(cash, "id", 20L);

    com.bigbrightpaints.erp.modules.invoice.domain.Invoice invoice =
        new com.bigbrightpaints.erp.modules.invoice.domain.Invoice();
    invoice.setCompany(company);
    invoice.setDealer(dealer);
    invoice.setCurrency("INR");
    invoice.setOutstandingAmount(new BigDecimal("100.00"));
    invoice.setTotalAmount(new BigDecimal("100.00"));
    ReflectionTestUtils.setField(invoice, "id", 730L);

    when(dealerRepository.lockByCompanyAndId(eq(company), eq(1L))).thenReturn(Optional.of(dealer));
    when(invoiceRepository.lockByCompanyAndId(eq(company), eq(730L)))
        .thenReturn(Optional.of(invoice));
    lenient().when(companyEntityLookup.requireAccount(eq(company), eq(20L))).thenReturn(cash);

    DealerSettlementRequest request =
        new DealerSettlementRequest(
            1L,
            20L,
            null,
            null,
            null,
            null,
            today.minusDays(31),
            "DR-OLD-1",
            "Old dealer settlement",
            "IDEMP-DR-OLD-1",
            Boolean.FALSE,
            List.of(
                new SettlementAllocationRequest(
                    730L,
                    null,
                    new BigDecimal("100.00"),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    null)),
            null);

    assertThatThrownBy(() -> accountingService.settleDealerInvoices(request))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Entry date cannot be more than 30 days old");

    assertThat(invoice.getOutstandingAmount()).isEqualByComparingTo("100.00");
    verify(invoiceSettlementPolicy, never()).applySettlement(any(), any(), any());
    verify(settlementAllocationRepository, never()).saveAll(any());
    verify(invoiceRepository, never()).saveAll(any());
  }

  @Test
  void settleSupplierInvoices_rejectsOldSettlementDateBeforeMutation() {
    LocalDate today = LocalDate.of(2024, 4, 30);
    when(companyClock.today(company)).thenReturn(today);
    when(journalEntryRepository.findByCompanyAndReferenceNumber(eq(company), any()))
        .thenReturn(Optional.empty());

    Supplier supplier = new Supplier();
    supplier.setName("Closed-period supplier");
    supplier.setStatus(SupplierStatus.ACTIVE);
    ReflectionTestUtils.setField(supplier, "id", 1L);

    Account payable = new Account();
    payable.setCompany(company);
    payable.setCode("AP-OLD");
    payable.setType(AccountType.LIABILITY);
    ReflectionTestUtils.setField(payable, "id", 10L);
    supplier.setPayableAccount(payable);

    Account cash = new Account();
    cash.setCompany(company);
    cash.setCode("CASH-OLD");
    cash.setType(AccountType.ASSET);
    ReflectionTestUtils.setField(cash, "id", 20L);

    RawMaterialPurchase purchase = new RawMaterialPurchase();
    purchase.setCompany(company);
    purchase.setSupplier(supplier);
    purchase.setTotalAmount(new BigDecimal("100.00"));
    purchase.setTaxAmount(BigDecimal.ZERO);
    purchase.setOutstandingAmount(new BigDecimal("100.00"));
    ReflectionTestUtils.setField(purchase, "id", 740L);

    Account inventory = new Account();
    inventory.setCompany(company);
    inventory.setCode("INV-OLD");
    inventory.setType(AccountType.ASSET);
    ReflectionTestUtils.setField(inventory, "id", 30L);

    JournalEntry purchaseJournal = new JournalEntry();
    ReflectionTestUtils.setField(purchaseJournal, "id", 1740L);
    purchaseJournal.setSupplier(supplier);
    purchaseJournal.addLine(
            journalLine(
                purchaseJournal,
                inventory,
                "Purchase invoice old",
                new BigDecimal("100.00"),
                BigDecimal.ZERO));
    purchaseJournal.addLine(
            journalLine(
                purchaseJournal,
                payable,
                "Purchase invoice old",
                BigDecimal.ZERO,
                new BigDecimal("100.00")));
    purchase.setJournalEntry(purchaseJournal);

    when(supplierRepository.lockByCompanyAndId(eq(company), eq(1L)))
        .thenReturn(Optional.of(supplier));
    lenient().when(companyEntityLookup.requireAccount(eq(company), eq(20L))).thenReturn(cash);
    when(rawMaterialPurchaseRepository.lockByCompanyAndId(eq(company), eq(740L)))
        .thenReturn(Optional.of(purchase));

    SupplierSettlementRequest request =
        new SupplierSettlementRequest(
            1L,
            20L,
            null,
            null,
            null,
            null,
            today.minusDays(31),
            "SUP-OLD-1",
            "Old supplier settlement",
            "IDEMP-SUP-OLD-1",
            Boolean.FALSE,
            List.of(
                new SettlementAllocationRequest(
                    null,
                    740L,
                    new BigDecimal("100.00"),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    null)));

    assertThatThrownBy(() -> accountingService.settleSupplierInvoices(request))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Entry date cannot be more than 30 days old");

    assertThat(purchase.getOutstandingAmount()).isEqualByComparingTo("100.00");
    verify(settlementAllocationRepository, never()).saveAll(any());
    verify(rawMaterialPurchaseRepository, never()).saveAll(any());
  }

  @Test
  void recordSupplierPayment_requiresPurchaseAllocation() {
    Supplier supplier = new Supplier();
    supplier.setName("Supplier");
    supplier.setStatus(SupplierStatus.ACTIVE);
    ReflectionTestUtils.setField(supplier, "id", 1L);

    Account payable = new Account();
    payable.setCompany(company);
    payable.setCode("AP-SUP");
    payable.setType(AccountType.LIABILITY);
    ReflectionTestUtils.setField(payable, "id", 10L);
    supplier.setPayableAccount(payable);

    when(supplierRepository.lockByCompanyAndId(eq(company), eq(1L)))
        .thenReturn(Optional.of(supplier));

    SettlementAllocationRequest allocation =
        new SettlementAllocationRequest(
            null, null, new BigDecimal("100.00"), BigDecimal.ZERO, BigDecimal.ZERO, null, null);

    SupplierPaymentRequest request =
        new SupplierPaymentRequest(
            1L,
            20L,
            new BigDecimal("100.00"),
            "PAY-ONACC-1",
            "On-account attempt",
            "IDEMP-PAY-ONACC-1",
            List.of(allocation));

    assertThatThrownBy(() -> accountingService.recordSupplierPayment(request))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Purchase allocation is required for supplier payments");
  }

  @Test
  void recordDealerReceipt_nonLeaderReplayRepairsReferenceMapping() {
    Dealer dealer = new Dealer();
    dealer.setName("Replay Dealer");
    ReflectionTestUtils.setField(dealer, "id", 1L);

    Account receivable = new Account();
    receivable.setCompany(company);
    receivable.setCode("AR-REPLAY");
    receivable.setType(AccountType.ASSET);
    ReflectionTestUtils.setField(receivable, "id", 10L);
    dealer.setReceivableAccount(receivable);

    Account cash = new Account();
    cash.setCompany(company);
    cash.setCode("CASH-REPLAY");
    cash.setType(AccountType.ASSET);
    ReflectionTestUtils.setField(cash, "id", 20L);

    com.bigbrightpaints.erp.modules.invoice.domain.Invoice invoice =
        new com.bigbrightpaints.erp.modules.invoice.domain.Invoice();
    invoice.setCompany(company);
    invoice.setDealer(dealer);
    ReflectionTestUtils.setField(invoice, "id", 501L);

    JournalEntry existingEntry = new JournalEntry();
    ReflectionTestUtils.setField(existingEntry, "id", 901L);
    existingEntry.setDealer(dealer);
    existingEntry.setReferenceNumber("DR-REPLAY-1");
    existingEntry.setMemo("Dealer receipt replay");
    existingEntry.addLine(
            journalLine(
                existingEntry,
                cash,
                "Dealer receipt replay",
                new BigDecimal("100.00"),
                BigDecimal.ZERO));
    existingEntry.addLine(
            journalLine(
                existingEntry,
                receivable,
                "Dealer receipt replay",
                BigDecimal.ZERO,
                new BigDecimal("100.00")));

    com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation existingRow =
        new com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation();
    existingRow.setCompany(company);
    existingRow.setPartnerType(
        com.bigbrightpaints.erp.modules.accounting.domain.PartnerType.DEALER);
    existingRow.setDealer(dealer);
    existingRow.setInvoice(invoice);
    existingRow.setJournalEntry(existingEntry);
    existingRow.setSettlementDate(LocalDate.of(2024, 4, 9));
    existingRow.setAllocationAmount(new BigDecimal("100.00"));
    existingRow.setDiscountAmount(BigDecimal.ZERO);
    existingRow.setWriteOffAmount(BigDecimal.ZERO);
    existingRow.setFxDifferenceAmount(BigDecimal.ZERO);
    existingRow.setIdempotencyKey("IDEMP-DR-REPLAY");
    existingRow.setMemo(null);

    JournalReferenceMapping mapping = new JournalReferenceMapping();
    mapping.setCompany(company);
    mapping.setLegacyReference("idemp-dr-replay");
    mapping.setCanonicalReference("DR-REPLAY-1");
    mapping.setEntityType("DEALER_RECEIPT");
    mapping.setEntityId(null);

    when(dealerRepository.lockByCompanyAndId(eq(company), eq(1L))).thenReturn(Optional.of(dealer));
    lenient().when(companyEntityLookup.requireAccount(eq(company), eq(20L))).thenReturn(cash);
    when(journalReferenceMappingRepository.findAllByCompanyAndLegacyReferenceIgnoreCase(
            eq(company), eq("idemp-dr-replay")))
        .thenReturn(List.of(mapping));
    when(journalReferenceResolver.findExistingEntry(eq(company), eq("DR-REPLAY-1")))
        .thenReturn(Optional.of(existingEntry));
    when(settlementAllocationRepository.findByCompanyAndJournalEntryOrderByCreatedAtAsc(
            eq(company), eq(existingEntry)))
        .thenReturn(List.of(existingRow));

    SettlementAllocationRequest allocation =
        new SettlementAllocationRequest(
            501L,
            null,
            new BigDecimal("100.00"),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            null);

    DealerReceiptRequest request =
        new DealerReceiptRequest(
            1L,
            20L,
            new BigDecimal("100.00"),
            "DR-REPLAY-1",
            "Dealer receipt replay",
            "IDEMP-DR-REPLAY",
            List.of(allocation));

    JournalEntryDto response = accountingService.recordDealerReceipt(request);

    assertThat(response.id()).isEqualTo(901L);
    verify(journalReferenceMappingRepository).save(mapping);
    assertThat(mapping.getEntityId()).isEqualTo(901L);
    assertThat(mapping.getEntityType()).isEqualTo("DEALER_RECEIPT");
    assertThat(mapping.getCanonicalReference()).isEqualTo("DR-REPLAY-1");
  }

  @Test
  void recordDealerReceiptSplit_nonLeaderReplayRepairsReferenceMapping() {
    Dealer dealer = new Dealer();
    dealer.setName("Replay Dealer");
    ReflectionTestUtils.setField(dealer, "id", 1L);

    Account receivable = new Account();
    receivable.setCompany(company);
    receivable.setCode("AR-SPLIT");
    receivable.setType(AccountType.ASSET);
    ReflectionTestUtils.setField(receivable, "id", 10L);
    dealer.setReceivableAccount(receivable);

    Account cash = new Account();
    cash.setCompany(company);
    cash.setCode("CASH-SPLIT");
    cash.setType(AccountType.ASSET);
    ReflectionTestUtils.setField(cash, "id", 20L);

    Account bank = new Account();
    bank.setCompany(company);
    bank.setCode("BANK-SPLIT");
    bank.setType(AccountType.ASSET);
    ReflectionTestUtils.setField(bank, "id", 21L);

    JournalEntry existingEntry = new JournalEntry();
    ReflectionTestUtils.setField(existingEntry, "id", 902L);
    existingEntry.setDealer(dealer);
    existingEntry.setReferenceNumber("DR-SPLIT-REPLAY-1");
    existingEntry.setMemo("Dealer split replay");
    existingEntry.addLine(
            journalLine(
                existingEntry,
                cash,
                "Dealer split replay",
                new BigDecimal("70.00"),
                BigDecimal.ZERO));
    existingEntry.addLine(
            journalLine(
                existingEntry,
                bank,
                "Dealer split replay",
                new BigDecimal("30.00"),
                BigDecimal.ZERO));
    existingEntry.addLine(
            journalLine(
                existingEntry,
                receivable,
                "Dealer split replay",
                BigDecimal.ZERO,
                new BigDecimal("100.00")));

    com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation existingRow =
        new com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation();
    existingRow.setCompany(company);
    existingRow.setPartnerType(
        com.bigbrightpaints.erp.modules.accounting.domain.PartnerType.DEALER);
    existingRow.setDealer(dealer);
    existingRow.setJournalEntry(existingEntry);
    existingRow.setSettlementDate(LocalDate.of(2024, 4, 9));
    existingRow.setAllocationAmount(new BigDecimal("100.00"));
    existingRow.setDiscountAmount(BigDecimal.ZERO);
    existingRow.setWriteOffAmount(BigDecimal.ZERO);
    existingRow.setFxDifferenceAmount(BigDecimal.ZERO);
    existingRow.setIdempotencyKey("IDEMP-DR-SPLIT-REPLAY");
    existingRow.setMemo(null);

    JournalReferenceMapping mapping = new JournalReferenceMapping();
    mapping.setCompany(company);
    mapping.setLegacyReference("idemp-dr-split-replay");
    mapping.setCanonicalReference("DR-SPLIT-REPLAY-1");
    mapping.setEntityType("DEALER_RECEIPT_SPLIT");
    mapping.setEntityId(null);

    when(dealerRepository.lockByCompanyAndId(eq(company), eq(1L))).thenReturn(Optional.of(dealer));
    lenient().when(companyEntityLookup.requireAccount(eq(company), eq(20L))).thenReturn(cash);
    when(companyEntityLookup.requireAccount(eq(company), eq(21L))).thenReturn(bank);
    when(journalReferenceMappingRepository.findAllByCompanyAndLegacyReferenceIgnoreCase(
            eq(company), eq("idemp-dr-split-replay")))
        .thenReturn(List.of(mapping));
    when(journalReferenceResolver.findExistingEntry(eq(company), eq("DR-SPLIT-REPLAY-1")))
        .thenReturn(Optional.of(existingEntry));
    when(settlementAllocationRepository.findByCompanyAndJournalEntryOrderByCreatedAtAsc(
            eq(company), eq(existingEntry)))
        .thenReturn(List.of(existingRow));

    DealerReceiptSplitRequest request =
        new DealerReceiptSplitRequest(
            1L,
            List.of(
                new DealerReceiptSplitRequest.IncomingLine(20L, new BigDecimal("70.00")),
                new DealerReceiptSplitRequest.IncomingLine(21L, new BigDecimal("30.00"))),
            "DR-SPLIT-REPLAY-1",
            "Dealer split replay",
            "IDEMP-DR-SPLIT-REPLAY");

    JournalEntryDto response = accountingService.recordDealerReceiptSplit(request);

    assertThat(response.id()).isEqualTo(902L);
    verify(journalReferenceMappingRepository).save(mapping);
    assertThat(mapping.getEntityId()).isEqualTo(902L);
    assertThat(mapping.getEntityType()).isEqualTo("DEALER_RECEIPT_SPLIT");
    assertThat(mapping.getCanonicalReference()).isEqualTo("DR-SPLIT-REPLAY-1");
  }

  @Test
  void recordDealerReceipt_nonLeaderReplayMissingAllocationIncludesPartnerDetails() {
    Dealer dealer = new Dealer();
    dealer.setName("Replay Dealer");
    ReflectionTestUtils.setField(dealer, "id", 1L);

    Account receivable = new Account();
    receivable.setCompany(company);
    receivable.setCode("AR-RACE-MISS");
    receivable.setType(AccountType.ASSET);
    ReflectionTestUtils.setField(receivable, "id", 10L);
    dealer.setReceivableAccount(receivable);

    Account cash = new Account();
    cash.setCompany(company);
    cash.setCode("CASH-RACE-MISS");
    cash.setType(AccountType.ASSET);
    ReflectionTestUtils.setField(cash, "id", 20L);

    JournalEntry existingEntry = new JournalEntry();
    ReflectionTestUtils.setField(existingEntry, "id", 921L);
    existingEntry.setDealer(dealer);
    existingEntry.setReferenceNumber("DR-RACE-MISS-1");
    existingEntry.setMemo("Dealer receipt replay race");

    JournalReferenceMapping mapping = new JournalReferenceMapping();
    mapping.setCompany(company);
    mapping.setLegacyReference("idemp-dr-race-miss");
    mapping.setCanonicalReference("DR-RACE-MISS-1");
    mapping.setEntityType("DEALER_RECEIPT");
    mapping.setEntityId(null);

    when(dealerRepository.lockByCompanyAndId(eq(company), eq(1L))).thenReturn(Optional.of(dealer));
    lenient().when(companyEntityLookup.requireAccount(eq(company), eq(20L))).thenReturn(cash);
    when(journalReferenceMappingRepository.findAllByCompanyAndLegacyReferenceIgnoreCase(
            eq(company), eq("idemp-dr-race-miss")))
        .thenReturn(List.of(mapping));
    when(journalReferenceResolver.findExistingEntry(eq(company), eq("DR-RACE-MISS-1")))
        .thenReturn(Optional.of(existingEntry));
    when(settlementAllocationRepository.findByCompanyAndJournalEntryOrderByCreatedAtAsc(
            eq(company), eq(existingEntry)))
        .thenReturn(List.of());
    when(settlementAllocationRepository
            .findByCompanyAndIdempotencyKeyIgnoreCaseOrderByCreatedAtAscIdAsc(
                eq(company), eq("IDEMP-DR-RACE-MISS")))
        .thenReturn(List.of());
    when(settlementAllocationRepository.findByCompanyAndIdempotencyKey(
            eq(company), eq("IDEMP-DR-RACE-MISS")))
        .thenReturn(List.of());

    SettlementAllocationRequest allocation =
        new SettlementAllocationRequest(
            501L,
            null,
            new BigDecimal("100.00"),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            null);

    DealerReceiptRequest request =
        new DealerReceiptRequest(
            1L,
            20L,
            new BigDecimal("100.00"),
            "DR-RACE-MISS-1",
            "Dealer receipt replay race",
            "IDEMP-DR-RACE-MISS",
            List.of(allocation));

    assertThatThrownBy(() -> accountingService.recordDealerReceipt(request))
        .isInstanceOfSatisfying(
            ApplicationException.class,
            ex -> {
              assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INTERNAL_CONCURRENCY_FAILURE);
              assertPartnerReplayDetails(ex, "IDEMP-DR-RACE-MISS", "DEALER", 1L);
            })
        .hasMessageContaining(
            "Dealer receipt idempotency key is reserved but allocation not found");
  }

  @Test
  void recordDealerReceiptSplit_nonLeaderReplayMissingAllocationIncludesPartnerDetails() {
    Dealer dealer = new Dealer();
    dealer.setName("Replay Dealer");
    ReflectionTestUtils.setField(dealer, "id", 1L);

    Account receivable = new Account();
    receivable.setCompany(company);
    receivable.setCode("AR-SPLIT-RACE-MISS");
    receivable.setType(AccountType.ASSET);
    ReflectionTestUtils.setField(receivable, "id", 10L);
    dealer.setReceivableAccount(receivable);

    Account cash = new Account();
    cash.setCompany(company);
    cash.setCode("CASH-SPLIT-RACE-MISS");
    cash.setType(AccountType.ASSET);
    ReflectionTestUtils.setField(cash, "id", 20L);

    Account bank = new Account();
    bank.setCompany(company);
    bank.setCode("BANK-SPLIT-RACE-MISS");
    bank.setType(AccountType.ASSET);
    ReflectionTestUtils.setField(bank, "id", 21L);

    JournalEntry existingEntry = new JournalEntry();
    ReflectionTestUtils.setField(existingEntry, "id", 922L);
    existingEntry.setDealer(dealer);
    existingEntry.setReferenceNumber("DR-SPLIT-RACE-MISS-1");
    existingEntry.setMemo("Dealer split replay race");

    JournalReferenceMapping mapping = new JournalReferenceMapping();
    mapping.setCompany(company);
    mapping.setLegacyReference("idemp-dr-split-race-miss");
    mapping.setCanonicalReference("DR-SPLIT-RACE-MISS-1");
    mapping.setEntityType("DEALER_RECEIPT_SPLIT");
    mapping.setEntityId(null);

    when(dealerRepository.lockByCompanyAndId(eq(company), eq(1L))).thenReturn(Optional.of(dealer));
    lenient().when(companyEntityLookup.requireAccount(eq(company), eq(20L))).thenReturn(cash);
    when(companyEntityLookup.requireAccount(eq(company), eq(21L))).thenReturn(bank);
    when(journalReferenceMappingRepository.findAllByCompanyAndLegacyReferenceIgnoreCase(
            eq(company), eq("idemp-dr-split-race-miss")))
        .thenReturn(List.of(mapping));
    when(journalReferenceResolver.findExistingEntry(eq(company), eq("DR-SPLIT-RACE-MISS-1")))
        .thenReturn(Optional.of(existingEntry));
    when(settlementAllocationRepository.findByCompanyAndJournalEntryOrderByCreatedAtAsc(
            eq(company), eq(existingEntry)))
        .thenReturn(List.of());
    when(settlementAllocationRepository
            .findByCompanyAndIdempotencyKeyIgnoreCaseOrderByCreatedAtAscIdAsc(
                eq(company), eq("IDEMP-DR-SPLIT-RACE-MISS")))
        .thenReturn(List.of());
    when(settlementAllocationRepository.findByCompanyAndIdempotencyKey(
            eq(company), eq("IDEMP-DR-SPLIT-RACE-MISS")))
        .thenReturn(List.of());

    DealerReceiptSplitRequest request =
        new DealerReceiptSplitRequest(
            1L,
            List.of(
                new DealerReceiptSplitRequest.IncomingLine(20L, new BigDecimal("70.00")),
                new DealerReceiptSplitRequest.IncomingLine(21L, new BigDecimal("30.00"))),
            "DR-SPLIT-RACE-MISS-1",
            "Dealer split replay race",
            "IDEMP-DR-SPLIT-RACE-MISS");

    assertThatThrownBy(() -> accountingService.recordDealerReceiptSplit(request))
        .isInstanceOfSatisfying(
            ApplicationException.class,
            ex -> {
              assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INTERNAL_CONCURRENCY_FAILURE);
              assertPartnerReplayDetails(ex, "IDEMP-DR-SPLIT-RACE-MISS", "DEALER", 1L);
            })
        .hasMessageContaining(
            "Dealer receipt idempotency key is reserved but allocation not found");
  }

  @Test
  void recordSupplierPayment_nonLeaderReplayRepairsReferenceMapping() {
    Supplier supplier = new Supplier();
    supplier.setName("Replay Supplier");
    supplier.setStatus(SupplierStatus.ACTIVE);
    ReflectionTestUtils.setField(supplier, "id", 1L);

    Account payable = new Account();
    payable.setCompany(company);
    payable.setCode("AP-REPLAY");
    payable.setType(AccountType.LIABILITY);
    ReflectionTestUtils.setField(payable, "id", 10L);
    supplier.setPayableAccount(payable);

    Account cash = new Account();
    cash.setCompany(company);
    cash.setCode("CASH-REPLAY");
    cash.setType(AccountType.ASSET);
    ReflectionTestUtils.setField(cash, "id", 20L);

    RawMaterialPurchase purchase = new RawMaterialPurchase();
    purchase.setCompany(company);
    purchase.setSupplier(supplier);
    ReflectionTestUtils.setField(purchase, "id", 601L);

    JournalEntry existingEntry = new JournalEntry();
    ReflectionTestUtils.setField(existingEntry, "id", 903L);
    existingEntry.setSupplier(supplier);
    existingEntry.setReferenceNumber("SUP-PAY-REPLAY-1");
    existingEntry.setMemo("Supplier payment replay");
    existingEntry.addLine(
            journalLine(
                existingEntry,
                payable,
                "Supplier payment replay",
                new BigDecimal("100.00"),
                BigDecimal.ZERO));
    existingEntry.addLine(
            journalLine(
                existingEntry,
                cash,
                "Supplier payment replay",
                BigDecimal.ZERO,
                new BigDecimal("100.00")));

    com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation existingRow =
        new com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation();
    existingRow.setCompany(company);
    existingRow.setPartnerType(
        com.bigbrightpaints.erp.modules.accounting.domain.PartnerType.SUPPLIER);
    existingRow.setSupplier(supplier);
    existingRow.setPurchase(purchase);
    existingRow.setJournalEntry(existingEntry);
    existingRow.setSettlementDate(LocalDate.of(2024, 4, 9));
    existingRow.setAllocationAmount(new BigDecimal("100.00"));
    existingRow.setDiscountAmount(BigDecimal.ZERO);
    existingRow.setWriteOffAmount(BigDecimal.ZERO);
    existingRow.setFxDifferenceAmount(BigDecimal.ZERO);
    existingRow.setIdempotencyKey("IDEMP-SUP-PAY-REPLAY");
    existingRow.setMemo(null);

    JournalReferenceMapping mapping = new JournalReferenceMapping();
    mapping.setCompany(company);
    mapping.setLegacyReference("idemp-sup-pay-replay");
    mapping.setCanonicalReference("SUP-PAY-REPLAY-1");
    mapping.setEntityType("SUPPLIER_PAYMENT");
    mapping.setEntityId(null);

    when(supplierRepository.lockByCompanyAndId(eq(company), eq(1L)))
        .thenReturn(Optional.of(supplier));
    lenient().when(companyEntityLookup.requireAccount(eq(company), eq(20L))).thenReturn(cash);
    when(journalReferenceMappingRepository.findAllByCompanyAndLegacyReferenceIgnoreCase(
            eq(company), eq("idemp-sup-pay-replay")))
        .thenReturn(List.of(mapping));
    when(journalReferenceResolver.findExistingEntry(eq(company), eq("SUP-PAY-REPLAY-1")))
        .thenReturn(Optional.of(existingEntry));
    when(settlementAllocationRepository
            .findByCompanyAndIdempotencyKeyIgnoreCaseOrderByCreatedAtAscIdAsc(
                eq(company), eq("IDEMP-SUP-PAY-REPLAY")))
        .thenReturn(List.of(existingRow));

    SettlementAllocationRequest allocation =
        new SettlementAllocationRequest(
            null,
            601L,
            new BigDecimal("100.00"),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            null);

    SupplierPaymentRequest request =
        new SupplierPaymentRequest(
            1L,
            20L,
            new BigDecimal("100.00"),
            "SUP-PAY-REPLAY-1",
            "Supplier payment replay",
            "IDEMP-SUP-PAY-REPLAY",
            List.of(allocation));

    JournalEntryDto response = accountingService.recordSupplierPayment(request);

    assertThat(response.id()).isEqualTo(903L);
    verify(journalReferenceMappingRepository).save(mapping);
    assertThat(mapping.getEntityId()).isEqualTo(903L);
    assertThat(mapping.getEntityType()).isEqualTo("SUPPLIER_PAYMENT");
    assertThat(mapping.getCanonicalReference()).isEqualTo("SUP-PAY-REPLAY-1");
  }

  @Test
  void recordSupplierPayment_nonLeaderReplayMissingAllocationIncludesPartnerDetails() {
    Supplier supplier = new Supplier();
    supplier.setName("Replay Supplier");
    supplier.setStatus(SupplierStatus.ACTIVE);
    ReflectionTestUtils.setField(supplier, "id", 1L);

    Account payable = new Account();
    payable.setCompany(company);
    payable.setCode("AP-RACE-MISS");
    payable.setType(AccountType.LIABILITY);
    ReflectionTestUtils.setField(payable, "id", 10L);
    supplier.setPayableAccount(payable);

    Account cash = new Account();
    cash.setCompany(company);
    cash.setCode("CASH-RACE-MISS");
    cash.setType(AccountType.ASSET);
    ReflectionTestUtils.setField(cash, "id", 20L);

    JournalEntry existingEntry = new JournalEntry();
    ReflectionTestUtils.setField(existingEntry, "id", 923L);
    existingEntry.setSupplier(supplier);
    existingEntry.setReferenceNumber("SUP-PAY-RACE-MISS-1");
    existingEntry.setMemo("Supplier payment replay race");

    JournalReferenceMapping mapping = new JournalReferenceMapping();
    mapping.setCompany(company);
    mapping.setLegacyReference("idemp-sup-pay-race-miss");
    mapping.setCanonicalReference("SUP-PAY-RACE-MISS-1");
    mapping.setEntityType("SUPPLIER_PAYMENT");
    mapping.setEntityId(null);

    when(supplierRepository.lockByCompanyAndId(eq(company), eq(1L)))
        .thenReturn(Optional.of(supplier));
    lenient().when(companyEntityLookup.requireAccount(eq(company), eq(20L))).thenReturn(cash);
    when(journalReferenceMappingRepository.findAllByCompanyAndLegacyReferenceIgnoreCase(
            eq(company), eq("idemp-sup-pay-race-miss")))
        .thenReturn(List.of(mapping));
    when(journalReferenceResolver.findExistingEntry(eq(company), eq("SUP-PAY-RACE-MISS-1")))
        .thenReturn(Optional.of(existingEntry));
    when(settlementAllocationRepository
            .findByCompanyAndIdempotencyKeyIgnoreCaseOrderByCreatedAtAscIdAsc(
                eq(company), eq("IDEMP-SUP-PAY-RACE-MISS")))
        .thenReturn(List.of());
    when(settlementAllocationRepository.findByCompanyAndIdempotencyKey(
            eq(company), eq("IDEMP-SUP-PAY-RACE-MISS")))
        .thenReturn(List.of());

    SettlementAllocationRequest allocation =
        new SettlementAllocationRequest(
            null,
            601L,
            new BigDecimal("100.00"),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            null);

    SupplierPaymentRequest request =
        new SupplierPaymentRequest(
            1L,
            20L,
            new BigDecimal("100.00"),
            "SUP-PAY-RACE-MISS-1",
            "Supplier payment replay race",
            "IDEMP-SUP-PAY-RACE-MISS",
            List.of(allocation));

    assertThatThrownBy(() -> accountingService.recordSupplierPayment(request))
        .isInstanceOfSatisfying(
            ApplicationException.class,
            ex -> {
              assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INTERNAL_CONCURRENCY_FAILURE);
              assertPartnerReplayDetails(ex, "IDEMP-SUP-PAY-RACE-MISS", "SUPPLIER", 1L);
            })
        .hasMessageContaining(
            "Supplier payment idempotency key is reserved but allocation not found");
  }

  @Test
  void recordDealerReceipt_replayRejectsMappingAllocationJournalMismatch() {
    Dealer dealer = new Dealer();
    dealer.setName("Mismatch Dealer");
    ReflectionTestUtils.setField(dealer, "id", 1L);

    Account receivable = new Account();
    receivable.setCompany(company);
    receivable.setCode("AR-MISMATCH");
    receivable.setType(AccountType.ASSET);
    ReflectionTestUtils.setField(receivable, "id", 10L);
    dealer.setReceivableAccount(receivable);

    Account cash = new Account();
    cash.setCompany(company);
    cash.setCode("CASH-MISMATCH");
    cash.setType(AccountType.ASSET);
    ReflectionTestUtils.setField(cash, "id", 20L);

    com.bigbrightpaints.erp.modules.invoice.domain.Invoice invoice =
        new com.bigbrightpaints.erp.modules.invoice.domain.Invoice();
    invoice.setCompany(company);
    invoice.setDealer(dealer);
    ReflectionTestUtils.setField(invoice, "id", 501L);

    JournalEntry mappingEntry = new JournalEntry();
    ReflectionTestUtils.setField(mappingEntry, "id", 904L);
    mappingEntry.setDealer(dealer);
    mappingEntry.setReferenceNumber("DR-MAP-MISMATCH-1");

    JournalEntry allocationEntry = new JournalEntry();
    ReflectionTestUtils.setField(allocationEntry, "id", 905L);
    allocationEntry.setDealer(dealer);
    allocationEntry.setReferenceNumber("DR-ALLOC-MISMATCH-1");

    com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation existingRow =
        new com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation();
    existingRow.setCompany(company);
    existingRow.setPartnerType(
        com.bigbrightpaints.erp.modules.accounting.domain.PartnerType.DEALER);
    existingRow.setDealer(dealer);
    existingRow.setInvoice(invoice);
    existingRow.setJournalEntry(allocationEntry);
    existingRow.setSettlementDate(LocalDate.of(2024, 4, 9));
    existingRow.setAllocationAmount(new BigDecimal("100.00"));
    existingRow.setDiscountAmount(BigDecimal.ZERO);
    existingRow.setWriteOffAmount(BigDecimal.ZERO);
    existingRow.setFxDifferenceAmount(BigDecimal.ZERO);
    existingRow.setIdempotencyKey("IDEMP-DR-MISMATCH");

    JournalReferenceMapping mapping = new JournalReferenceMapping();
    mapping.setCompany(company);
    mapping.setLegacyReference("idemp-dr-mismatch");
    mapping.setCanonicalReference("DR-MAP-MISMATCH-1");
    mapping.setEntityType("DEALER_RECEIPT");
    mapping.setEntityId(null);

    when(dealerRepository.lockByCompanyAndId(eq(company), eq(1L))).thenReturn(Optional.of(dealer));
    when(companyEntityLookup.requireAccount(eq(company), eq(20L))).thenReturn(cash);
    when(journalReferenceMappingRepository.findAllByCompanyAndLegacyReferenceIgnoreCase(
            eq(company), eq("idemp-dr-mismatch")))
        .thenReturn(List.of(mapping));
    when(journalReferenceResolver.findExistingEntry(eq(company), eq("DR-MAP-MISMATCH-1")))
        .thenReturn(Optional.of(mappingEntry));
    when(settlementAllocationRepository.findByCompanyAndJournalEntryOrderByCreatedAtAsc(
            eq(company), eq(mappingEntry)))
        .thenReturn(List.of());
    when(settlementAllocationRepository
            .findByCompanyAndIdempotencyKeyIgnoreCaseOrderByCreatedAtAscIdAsc(
                eq(company), eq("IDEMP-DR-MISMATCH")))
        .thenReturn(List.of(existingRow));

    DealerReceiptRequest request =
        new DealerReceiptRequest(
            1L,
            20L,
            new BigDecimal("100.00"),
            "DR-MAP-MISMATCH-1",
            "Dealer receipt replay",
            "IDEMP-DR-MISMATCH",
            List.of(
                new SettlementAllocationRequest(
                    501L,
                    null,
                    new BigDecimal("100.00"),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    null)));

    assertThatThrownBy(() -> accountingService.recordDealerReceipt(request))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("different journal than settled allocations");
  }

  @Test
  void recordDealerReceipt_replayRejectsMappingAllocationJournalMismatchOnLeaderFastPath() {
    Dealer dealer = new Dealer();
    dealer.setName("Mismatch Dealer");
    ReflectionTestUtils.setField(dealer, "id", 1L);

    Account receivable = new Account();
    receivable.setCompany(company);
    receivable.setCode("AR-MISMATCH");
    receivable.setType(AccountType.ASSET);
    ReflectionTestUtils.setField(receivable, "id", 10L);
    dealer.setReceivableAccount(receivable);

    Account cash = new Account();
    cash.setCompany(company);
    cash.setCode("CASH-MISMATCH");
    cash.setType(AccountType.ASSET);
    ReflectionTestUtils.setField(cash, "id", 20L);

    com.bigbrightpaints.erp.modules.invoice.domain.Invoice invoice =
        new com.bigbrightpaints.erp.modules.invoice.domain.Invoice();
    invoice.setCompany(company);
    invoice.setDealer(dealer);
    ReflectionTestUtils.setField(invoice, "id", 501L);

    JournalEntry mappingEntry = new JournalEntry();
    ReflectionTestUtils.setField(mappingEntry, "id", 1904L);
    mappingEntry.setDealer(dealer);
    mappingEntry.setReferenceNumber("DR-MAP-MISMATCH-LEADER-1");

    JournalEntry allocationEntry = new JournalEntry();
    ReflectionTestUtils.setField(allocationEntry, "id", 1905L);
    allocationEntry.setDealer(dealer);
    allocationEntry.setReferenceNumber("DR-ALLOC-MISMATCH-LEADER-1");

    com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation existingRow =
        new com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation();
    existingRow.setCompany(company);
    existingRow.setPartnerType(
        com.bigbrightpaints.erp.modules.accounting.domain.PartnerType.DEALER);
    existingRow.setDealer(dealer);
    existingRow.setInvoice(invoice);
    existingRow.setJournalEntry(allocationEntry);
    existingRow.setSettlementDate(LocalDate.of(2024, 4, 9));
    existingRow.setAllocationAmount(new BigDecimal("100.00"));
    existingRow.setDiscountAmount(BigDecimal.ZERO);
    existingRow.setWriteOffAmount(BigDecimal.ZERO);
    existingRow.setFxDifferenceAmount(BigDecimal.ZERO);
    existingRow.setIdempotencyKey("IDEMP-DR-MISMATCH-LEADER");

    when(dealerRepository.lockByCompanyAndId(eq(company), eq(1L))).thenReturn(Optional.of(dealer));
    when(companyEntityLookup.requireAccount(eq(company), eq(20L))).thenReturn(cash);
    when(journalReferenceMappingRepository.findAllByCompanyAndLegacyReferenceIgnoreCase(
            eq(company), eq("idemp-dr-mismatch-leader")))
        .thenReturn(List.of());
    when(journalReferenceResolver.findExistingEntry(eq(company), eq("DR-MAP-MISMATCH-LEADER-1")))
        .thenReturn(Optional.of(mappingEntry));
    when(settlementAllocationRepository
            .findByCompanyAndIdempotencyKeyIgnoreCaseOrderByCreatedAtAscIdAsc(
                eq(company), eq("IDEMP-DR-MISMATCH-LEADER")))
        .thenReturn(List.of(existingRow));

    DealerReceiptRequest request =
        new DealerReceiptRequest(
            1L,
            20L,
            new BigDecimal("100.00"),
            "DR-MAP-MISMATCH-LEADER-1",
            "Dealer receipt replay",
            "IDEMP-DR-MISMATCH-LEADER",
            List.of(
                new SettlementAllocationRequest(
                    501L,
                    null,
                    new BigDecimal("100.00"),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    null)));

    assertThatThrownBy(() -> accountingService.recordDealerReceipt(request))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("different journal than settled allocations");
  }

  @Test
  void recordDealerReceiptSplit_replayRejectsMappingAllocationJournalMismatch() {
    Dealer dealer = new Dealer();
    dealer.setName("Mismatch Dealer");
    ReflectionTestUtils.setField(dealer, "id", 1L);

    Account receivable = new Account();
    receivable.setCompany(company);
    receivable.setCode("AR-SPLIT-MISMATCH");
    receivable.setType(AccountType.ASSET);
    ReflectionTestUtils.setField(receivable, "id", 10L);
    dealer.setReceivableAccount(receivable);

    Account cash = new Account();
    cash.setCompany(company);
    cash.setCode("CASH-SPLIT-MISMATCH");
    cash.setType(AccountType.ASSET);
    ReflectionTestUtils.setField(cash, "id", 20L);

    JournalEntry mappingEntry = new JournalEntry();
    ReflectionTestUtils.setField(mappingEntry, "id", 906L);
    mappingEntry.setDealer(dealer);
    mappingEntry.setReferenceNumber("DR-SPLIT-MAP-MISMATCH-1");

    JournalEntry allocationEntry = new JournalEntry();
    ReflectionTestUtils.setField(allocationEntry, "id", 907L);
    allocationEntry.setDealer(dealer);
    allocationEntry.setReferenceNumber("DR-SPLIT-ALLOC-MISMATCH-1");

    com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation existingRow =
        new com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation();
    existingRow.setCompany(company);
    existingRow.setPartnerType(
        com.bigbrightpaints.erp.modules.accounting.domain.PartnerType.DEALER);
    existingRow.setDealer(dealer);
    existingRow.setJournalEntry(allocationEntry);
    existingRow.setSettlementDate(LocalDate.of(2024, 4, 9));
    existingRow.setAllocationAmount(new BigDecimal("100.00"));
    existingRow.setDiscountAmount(BigDecimal.ZERO);
    existingRow.setWriteOffAmount(BigDecimal.ZERO);
    existingRow.setFxDifferenceAmount(BigDecimal.ZERO);
    existingRow.setIdempotencyKey("IDEMP-DR-SPLIT-MISMATCH");

    JournalReferenceMapping mapping = new JournalReferenceMapping();
    mapping.setCompany(company);
    mapping.setLegacyReference("idemp-dr-split-mismatch");
    mapping.setCanonicalReference("DR-SPLIT-MAP-MISMATCH-1");
    mapping.setEntityType("DEALER_RECEIPT_SPLIT");
    mapping.setEntityId(null);

    when(dealerRepository.lockByCompanyAndId(eq(company), eq(1L))).thenReturn(Optional.of(dealer));
    when(companyEntityLookup.requireAccount(eq(company), eq(20L))).thenReturn(cash);
    when(journalReferenceMappingRepository.findAllByCompanyAndLegacyReferenceIgnoreCase(
            eq(company), eq("idemp-dr-split-mismatch")))
        .thenReturn(List.of(mapping));
    when(journalReferenceResolver.findExistingEntry(eq(company), eq("DR-SPLIT-MAP-MISMATCH-1")))
        .thenReturn(Optional.of(mappingEntry));
    when(settlementAllocationRepository.findByCompanyAndJournalEntryOrderByCreatedAtAsc(
            eq(company), eq(mappingEntry)))
        .thenReturn(List.of());
    when(settlementAllocationRepository
            .findByCompanyAndIdempotencyKeyIgnoreCaseOrderByCreatedAtAscIdAsc(
                eq(company), eq("IDEMP-DR-SPLIT-MISMATCH")))
        .thenReturn(List.of(existingRow));

    DealerReceiptSplitRequest request =
        new DealerReceiptSplitRequest(
            1L,
            List.of(new DealerReceiptSplitRequest.IncomingLine(20L, new BigDecimal("100.00"))),
            "DR-SPLIT-MAP-MISMATCH-1",
            "Dealer split replay",
            "IDEMP-DR-SPLIT-MISMATCH");

    assertThatThrownBy(() -> accountingService.recordDealerReceiptSplit(request))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("different journal than settled allocations");
  }

  @Test
  void recordDealerReceiptSplit_replayRejectsMappingAllocationJournalMismatchOnLeaderFastPath() {
    Dealer dealer = new Dealer();
    dealer.setName("Mismatch Dealer");
    ReflectionTestUtils.setField(dealer, "id", 1L);

    Account receivable = new Account();
    receivable.setCompany(company);
    receivable.setCode("AR-SPLIT-MISMATCH");
    receivable.setType(AccountType.ASSET);
    ReflectionTestUtils.setField(receivable, "id", 10L);
    dealer.setReceivableAccount(receivable);

    Account cash = new Account();
    cash.setCompany(company);
    cash.setCode("CASH-SPLIT-MISMATCH");
    cash.setType(AccountType.ASSET);
    ReflectionTestUtils.setField(cash, "id", 20L);

    JournalEntry mappingEntry = new JournalEntry();
    ReflectionTestUtils.setField(mappingEntry, "id", 1906L);
    mappingEntry.setDealer(dealer);
    mappingEntry.setReferenceNumber("DR-SPLIT-MAP-MISMATCH-LEADER-1");

    JournalEntry allocationEntry = new JournalEntry();
    ReflectionTestUtils.setField(allocationEntry, "id", 1907L);
    allocationEntry.setDealer(dealer);
    allocationEntry.setReferenceNumber("DR-SPLIT-ALLOC-MISMATCH-LEADER-1");

    com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation existingRow =
        new com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation();
    existingRow.setCompany(company);
    existingRow.setPartnerType(
        com.bigbrightpaints.erp.modules.accounting.domain.PartnerType.DEALER);
    existingRow.setDealer(dealer);
    existingRow.setJournalEntry(allocationEntry);
    existingRow.setSettlementDate(LocalDate.of(2024, 4, 9));
    existingRow.setAllocationAmount(new BigDecimal("100.00"));
    existingRow.setDiscountAmount(BigDecimal.ZERO);
    existingRow.setWriteOffAmount(BigDecimal.ZERO);
    existingRow.setFxDifferenceAmount(BigDecimal.ZERO);
    existingRow.setIdempotencyKey("IDEMP-DR-SPLIT-MISMATCH-LEADER");

    when(dealerRepository.lockByCompanyAndId(eq(company), eq(1L))).thenReturn(Optional.of(dealer));
    when(companyEntityLookup.requireAccount(eq(company), eq(20L))).thenReturn(cash);
    when(journalReferenceMappingRepository.findAllByCompanyAndLegacyReferenceIgnoreCase(
            eq(company), eq("idemp-dr-split-mismatch-leader")))
        .thenReturn(List.of());
    when(journalReferenceResolver.findExistingEntry(
            eq(company), eq("DR-SPLIT-MAP-MISMATCH-LEADER-1")))
        .thenReturn(Optional.of(mappingEntry));
    when(settlementAllocationRepository
            .findByCompanyAndIdempotencyKeyIgnoreCaseOrderByCreatedAtAscIdAsc(
                eq(company), eq("IDEMP-DR-SPLIT-MISMATCH-LEADER")))
        .thenReturn(List.of(existingRow));

    DealerReceiptSplitRequest request =
        new DealerReceiptSplitRequest(
            1L,
            List.of(new DealerReceiptSplitRequest.IncomingLine(20L, new BigDecimal("100.00"))),
            "DR-SPLIT-MAP-MISMATCH-LEADER-1",
            "Dealer split replay",
            "IDEMP-DR-SPLIT-MISMATCH-LEADER");

    assertThatThrownBy(() -> accountingService.recordDealerReceiptSplit(request))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("different journal than settled allocations");
  }

  @Test
  void recordSupplierPayment_replayRejectsMappingAllocationJournalMismatch() {
    Supplier supplier = new Supplier();
    supplier.setName("Mismatch Supplier");
    supplier.setStatus(SupplierStatus.ACTIVE);
    ReflectionTestUtils.setField(supplier, "id", 1L);

    Account payable = new Account();
    payable.setCompany(company);
    payable.setCode("AP-MISMATCH");
    payable.setType(AccountType.LIABILITY);
    ReflectionTestUtils.setField(payable, "id", 10L);
    supplier.setPayableAccount(payable);

    Account cash = new Account();
    cash.setCompany(company);
    cash.setCode("CASH-MISMATCH");
    cash.setType(AccountType.ASSET);
    ReflectionTestUtils.setField(cash, "id", 20L);

    RawMaterialPurchase purchase = new RawMaterialPurchase();
    purchase.setCompany(company);
    purchase.setSupplier(supplier);
    ReflectionTestUtils.setField(purchase, "id", 601L);

    JournalEntry mappingEntry = new JournalEntry();
    ReflectionTestUtils.setField(mappingEntry, "id", 908L);
    mappingEntry.setSupplier(supplier);
    mappingEntry.setReferenceNumber("SUP-PAY-MAP-MISMATCH-1");

    JournalEntry allocationEntry = new JournalEntry();
    ReflectionTestUtils.setField(allocationEntry, "id", 909L);
    allocationEntry.setSupplier(supplier);
    allocationEntry.setReferenceNumber("SUP-PAY-ALLOC-MISMATCH-1");

    com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation existingRow =
        new com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation();
    existingRow.setCompany(company);
    existingRow.setPartnerType(
        com.bigbrightpaints.erp.modules.accounting.domain.PartnerType.SUPPLIER);
    existingRow.setSupplier(supplier);
    existingRow.setPurchase(purchase);
    existingRow.setJournalEntry(allocationEntry);
    existingRow.setSettlementDate(LocalDate.of(2024, 4, 9));
    existingRow.setAllocationAmount(new BigDecimal("100.00"));
    existingRow.setDiscountAmount(BigDecimal.ZERO);
    existingRow.setWriteOffAmount(BigDecimal.ZERO);
    existingRow.setFxDifferenceAmount(BigDecimal.ZERO);
    existingRow.setIdempotencyKey("IDEMP-SUP-PAY-MISMATCH");

    JournalReferenceMapping mapping = new JournalReferenceMapping();
    mapping.setCompany(company);
    mapping.setLegacyReference("idemp-sup-pay-mismatch");
    mapping.setCanonicalReference("SUP-PAY-MAP-MISMATCH-1");
    mapping.setEntityType("SUPPLIER_PAYMENT");
    mapping.setEntityId(null);

    when(supplierRepository.lockByCompanyAndId(eq(company), eq(1L)))
        .thenReturn(Optional.of(supplier));
    when(companyEntityLookup.requireAccount(eq(company), eq(20L))).thenReturn(cash);
    when(journalReferenceMappingRepository.findAllByCompanyAndLegacyReferenceIgnoreCase(
            eq(company), eq("idemp-sup-pay-mismatch")))
        .thenReturn(List.of(mapping));
    when(journalReferenceResolver.findExistingEntry(eq(company), eq("SUP-PAY-MAP-MISMATCH-1")))
        .thenReturn(Optional.of(mappingEntry));
    when(settlementAllocationRepository
            .findByCompanyAndIdempotencyKeyIgnoreCaseOrderByCreatedAtAscIdAsc(
                eq(company), eq("IDEMP-SUP-PAY-MISMATCH")))
        .thenReturn(List.of(existingRow));

    SupplierPaymentRequest request =
        new SupplierPaymentRequest(
            1L,
            20L,
            new BigDecimal("100.00"),
            "SUP-PAY-MAP-MISMATCH-1",
            "Supplier payment replay",
            "IDEMP-SUP-PAY-MISMATCH",
            List.of(
                new SettlementAllocationRequest(
                    null,
                    601L,
                    new BigDecimal("100.00"),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    null)));

    assertThatThrownBy(() -> accountingService.recordSupplierPayment(request))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("different journal than settled allocations");
  }

  @Test
  void recordSupplierPayment_replayRejectsMappingAllocationJournalMismatchOnLeaderFastPath() {
    Supplier supplier = new Supplier();
    supplier.setName("Mismatch Supplier");
    supplier.setStatus(SupplierStatus.ACTIVE);
    ReflectionTestUtils.setField(supplier, "id", 1L);

    Account payable = new Account();
    payable.setCompany(company);
    payable.setCode("AP-MISMATCH");
    payable.setType(AccountType.LIABILITY);
    ReflectionTestUtils.setField(payable, "id", 10L);
    supplier.setPayableAccount(payable);

    Account cash = new Account();
    cash.setCompany(company);
    cash.setCode("CASH-MISMATCH");
    cash.setType(AccountType.ASSET);
    ReflectionTestUtils.setField(cash, "id", 20L);

    RawMaterialPurchase purchase = new RawMaterialPurchase();
    purchase.setCompany(company);
    purchase.setSupplier(supplier);
    ReflectionTestUtils.setField(purchase, "id", 601L);

    JournalEntry mappingEntry = new JournalEntry();
    ReflectionTestUtils.setField(mappingEntry, "id", 1908L);
    mappingEntry.setSupplier(supplier);
    mappingEntry.setReferenceNumber("SUP-PAY-MAP-MISMATCH-LEADER-1");

    JournalEntry allocationEntry = new JournalEntry();
    ReflectionTestUtils.setField(allocationEntry, "id", 1909L);
    allocationEntry.setSupplier(supplier);
    allocationEntry.setReferenceNumber("SUP-PAY-ALLOC-MISMATCH-LEADER-1");

    com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation existingRow =
        new com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation();
    existingRow.setCompany(company);
    existingRow.setPartnerType(
        com.bigbrightpaints.erp.modules.accounting.domain.PartnerType.SUPPLIER);
    existingRow.setSupplier(supplier);
    existingRow.setPurchase(purchase);
    existingRow.setJournalEntry(allocationEntry);
    existingRow.setSettlementDate(LocalDate.of(2024, 4, 9));
    existingRow.setAllocationAmount(new BigDecimal("100.00"));
    existingRow.setDiscountAmount(BigDecimal.ZERO);
    existingRow.setWriteOffAmount(BigDecimal.ZERO);
    existingRow.setFxDifferenceAmount(BigDecimal.ZERO);
    existingRow.setIdempotencyKey("IDEMP-SUP-PAY-MISMATCH-LEADER");

    when(supplierRepository.lockByCompanyAndId(eq(company), eq(1L)))
        .thenReturn(Optional.of(supplier));
    when(companyEntityLookup.requireAccount(eq(company), eq(20L))).thenReturn(cash);
    when(journalReferenceMappingRepository.findAllByCompanyAndLegacyReferenceIgnoreCase(
            eq(company), eq("idemp-sup-pay-mismatch-leader")))
        .thenReturn(List.of());
    when(journalReferenceResolver.findExistingEntry(
            eq(company), eq("SUP-PAY-MAP-MISMATCH-LEADER-1")))
        .thenReturn(Optional.of(mappingEntry));
    when(settlementAllocationRepository
            .findByCompanyAndIdempotencyKeyIgnoreCaseOrderByCreatedAtAscIdAsc(
                eq(company), eq("IDEMP-SUP-PAY-MISMATCH-LEADER")))
        .thenReturn(List.of(existingRow));

    SupplierPaymentRequest request =
        new SupplierPaymentRequest(
            1L,
            20L,
            new BigDecimal("100.00"),
            "SUP-PAY-MAP-MISMATCH-LEADER-1",
            "Supplier payment replay",
            "IDEMP-SUP-PAY-MISMATCH-LEADER",
            List.of(
                new SettlementAllocationRequest(
                    null,
                    601L,
                    new BigDecimal("100.00"),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    null)));

    assertThatThrownBy(() -> accountingService.recordSupplierPayment(request))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("different journal than settled allocations");
  }

  @Test
  void recordDealerReceipt_dataIntegrityConflictRethrowsForRetryBoundary() {
    AccountingService service = spy(accountingService);

    Dealer dealer = new Dealer();
    dealer.setName("Race Dealer");
    ReflectionTestUtils.setField(dealer, "id", 1L);

    Account receivable = new Account();
    receivable.setCompany(company);
    receivable.setCode("AR-RACE");
    receivable.setType(AccountType.ASSET);
    ReflectionTestUtils.setField(receivable, "id", 10L);
    dealer.setReceivableAccount(receivable);

    Account cash = new Account();
    cash.setCompany(company);
    cash.setCode("CASH-RACE");
    cash.setType(AccountType.ASSET);
    ReflectionTestUtils.setField(cash, "id", 20L);

    com.bigbrightpaints.erp.modules.invoice.domain.Invoice invoice =
        new com.bigbrightpaints.erp.modules.invoice.domain.Invoice();
    invoice.setCompany(company);
    invoice.setDealer(dealer);
    invoice.setCurrency("INR");
    invoice.setOutstandingAmount(new BigDecimal("100.00"));
    invoice.setTotalAmount(new BigDecimal("100.00"));
    ReflectionTestUtils.setField(invoice, "id", 502L);

    JournalEntry createdEntry = new JournalEntry();
    ReflectionTestUtils.setField(createdEntry, "id", 910L);
    createdEntry.setDealer(dealer);
    createdEntry.setReferenceNumber("DR-RACE-NEW-1");
    createdEntry.setMemo("Dealer receipt race");
    createdEntry.addLine(
            journalLine(
                createdEntry,
                cash,
                "Dealer receipt race",
                new BigDecimal("100.00"),
                BigDecimal.ZERO));
    createdEntry.addLine(
            journalLine(
                createdEntry,
                receivable,
                "Dealer receipt race",
                BigDecimal.ZERO,
                new BigDecimal("100.00")));

    JournalEntry concurrentEntry = new JournalEntry();
    ReflectionTestUtils.setField(concurrentEntry, "id", 911L);
    concurrentEntry.setDealer(dealer);
    concurrentEntry.setReferenceNumber("DR-RACE-EXIST-1");
    concurrentEntry.setMemo("Dealer receipt race");
    concurrentEntry.addLine(
            journalLine(
                concurrentEntry,
                cash,
                "Dealer receipt race",
                new BigDecimal("100.00"),
                BigDecimal.ZERO));
    concurrentEntry.addLine(
            journalLine(
                concurrentEntry,
                receivable,
                "Dealer receipt race",
                BigDecimal.ZERO,
                new BigDecimal("100.00")));

    com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation concurrentRow =
        new com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation();
    concurrentRow.setCompany(company);
    concurrentRow.setPartnerType(
        com.bigbrightpaints.erp.modules.accounting.domain.PartnerType.DEALER);
    concurrentRow.setDealer(dealer);
    concurrentRow.setInvoice(invoice);
    concurrentRow.setJournalEntry(concurrentEntry);
    concurrentRow.setSettlementDate(LocalDate.of(2024, 4, 9));
    concurrentRow.setAllocationAmount(new BigDecimal("100.00"));
    concurrentRow.setDiscountAmount(BigDecimal.ZERO);
    concurrentRow.setWriteOffAmount(BigDecimal.ZERO);
    concurrentRow.setFxDifferenceAmount(BigDecimal.ZERO);
    concurrentRow.setIdempotencyKey("IDEMP-DR-RACE");

    JournalReferenceMapping mapping = new JournalReferenceMapping();
    mapping.setCompany(company);
    mapping.setLegacyReference("idemp-dr-race");
    mapping.setCanonicalReference("DR-RACE-NEW-1");
    mapping.setEntityType("DEALER_RECEIPT");
    mapping.setEntityId(null);

    AtomicInteger mappingLookups = new AtomicInteger(0);
    when(journalReferenceMappingRepository.findAllByCompanyAndLegacyReferenceIgnoreCase(
            eq(company), eq("idemp-dr-race")))
        .thenAnswer(
            invocation -> mappingLookups.getAndIncrement() < 2 ? List.of() : List.of(mapping));

    AtomicInteger allocationLookups = new AtomicInteger(0);
    when(settlementAllocationRepository
            .findByCompanyAndIdempotencyKeyIgnoreCaseOrderByCreatedAtAscIdAsc(
                eq(company), eq("IDEMP-DR-RACE")))
        .thenAnswer(
            invocation ->
                allocationLookups.getAndIncrement() < 2 ? List.of() : List.of(concurrentRow));

    when(dealerRepository.lockByCompanyAndId(eq(company), eq(1L))).thenReturn(Optional.of(dealer));
    when(companyEntityLookup.requireAccount(eq(company), eq(20L))).thenReturn(cash);
    when(invoiceRepository.lockByCompanyAndId(eq(company), eq(502L)))
        .thenReturn(Optional.of(invoice));
    when(settlementAllocationRepository.findByCompanyAndJournalEntryOrderByCreatedAtAsc(
            eq(company), eq(createdEntry)))
        .thenReturn(List.of());
    doReturn(stubEntry(910L))
        .when(dealerReceiptService)
        .createJournalEntry(any(JournalEntryRequest.class));
    when(companyEntityLookup.requireJournalEntry(eq(company), eq(910L))).thenReturn(createdEntry);
    when(settlementAllocationRepository.saveAll(any()))
        .thenThrow(new DataIntegrityViolationException("duplicate"));

    DealerReceiptRequest request =
        new DealerReceiptRequest(
            1L,
            20L,
            new BigDecimal("100.00"),
            "DR-RACE-NEW-1",
            "Dealer receipt race",
            "IDEMP-DR-RACE",
            List.of(
                new SettlementAllocationRequest(
                    502L,
                    null,
                    new BigDecimal("100.00"),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    null)));

    assertThatThrownBy(() -> service.recordDealerReceipt(request))
        .isInstanceOf(DataIntegrityViolationException.class);
    verify(journalReferenceMappingRepository, atLeastOnce()).save(any());
    verify(invoiceSettlementPolicy, never()).applySettlement(any(), any(), any());
    verify(dealerLedgerService, never()).syncInvoiceLedger(any(), any());
  }

  @Test
  void recordDealerReceipt_dataIntegrityConflictWinsBeforeReplayValidation() {
    AccountingService service = spy(accountingService);

    Dealer dealer = new Dealer();
    dealer.setName("Race Dealer");
    ReflectionTestUtils.setField(dealer, "id", 1L);

    Account receivable = new Account();
    receivable.setCompany(company);
    receivable.setCode("AR-RACE");
    receivable.setType(AccountType.ASSET);
    ReflectionTestUtils.setField(receivable, "id", 10L);
    dealer.setReceivableAccount(receivable);

    Account cash = new Account();
    cash.setCompany(company);
    cash.setCode("CASH-RACE");
    cash.setType(AccountType.ASSET);
    ReflectionTestUtils.setField(cash, "id", 20L);

    com.bigbrightpaints.erp.modules.invoice.domain.Invoice invoice =
        new com.bigbrightpaints.erp.modules.invoice.domain.Invoice();
    invoice.setCompany(company);
    invoice.setDealer(dealer);
    invoice.setCurrency("INR");
    invoice.setOutstandingAmount(new BigDecimal("100.00"));
    invoice.setTotalAmount(new BigDecimal("100.00"));
    ReflectionTestUtils.setField(invoice, "id", 502L);

    JournalEntry createdEntry = new JournalEntry();
    ReflectionTestUtils.setField(createdEntry, "id", 1910L);
    createdEntry.setDealer(dealer);
    createdEntry.setReferenceNumber("DR-RACE-MISMATCH-NEW-1");
    createdEntry.setMemo("Dealer receipt race");
    createdEntry.addLine(
            journalLine(
                createdEntry,
                cash,
                "Dealer receipt race",
                new BigDecimal("100.00"),
                BigDecimal.ZERO));
    createdEntry.addLine(
            journalLine(
                createdEntry,
                receivable,
                "Dealer receipt race",
                BigDecimal.ZERO,
                new BigDecimal("100.00")));

    JournalEntry concurrentEntry = new JournalEntry();
    ReflectionTestUtils.setField(concurrentEntry, "id", 1912L);
    concurrentEntry.setDealer(dealer);
    concurrentEntry.setReferenceNumber("DR-RACE-MISMATCH-ALLOC-1");
    concurrentEntry.setMemo("Dealer receipt race");
    concurrentEntry.addLine(
            journalLine(
                concurrentEntry,
                cash,
                "Dealer receipt race",
                new BigDecimal("100.00"),
                BigDecimal.ZERO));
    concurrentEntry.addLine(
            journalLine(
                concurrentEntry,
                receivable,
                "Dealer receipt race",
                BigDecimal.ZERO,
                new BigDecimal("100.00")));

    com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation concurrentRow =
        new com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation();
    concurrentRow.setCompany(company);
    concurrentRow.setPartnerType(
        com.bigbrightpaints.erp.modules.accounting.domain.PartnerType.DEALER);
    concurrentRow.setDealer(dealer);
    concurrentRow.setInvoice(invoice);
    concurrentRow.setJournalEntry(concurrentEntry);
    concurrentRow.setSettlementDate(LocalDate.of(2024, 4, 9));
    concurrentRow.setAllocationAmount(new BigDecimal("100.00"));
    concurrentRow.setDiscountAmount(BigDecimal.ZERO);
    concurrentRow.setWriteOffAmount(BigDecimal.ZERO);
    concurrentRow.setFxDifferenceAmount(BigDecimal.ZERO);
    concurrentRow.setIdempotencyKey("IDEMP-DR-RACE-MISMATCH");

    AtomicInteger allocationLookups = new AtomicInteger(0);
    when(settlementAllocationRepository
            .findByCompanyAndIdempotencyKeyIgnoreCaseOrderByCreatedAtAscIdAsc(
                eq(company), eq("IDEMP-DR-RACE-MISMATCH")))
        .thenAnswer(
            invocation ->
                allocationLookups.getAndIncrement() < 2 ? List.of() : List.of(concurrentRow));

    when(dealerRepository.lockByCompanyAndId(eq(company), eq(1L))).thenReturn(Optional.of(dealer));
    when(companyEntityLookup.requireAccount(eq(company), eq(20L))).thenReturn(cash);
    when(invoiceRepository.lockByCompanyAndId(eq(company), eq(502L)))
        .thenReturn(Optional.of(invoice));
    when(settlementAllocationRepository.findByCompanyAndJournalEntryOrderByCreatedAtAsc(
            eq(company), eq(createdEntry)))
        .thenReturn(List.of());
    doReturn(stubEntry(1910L))
        .when(dealerReceiptService)
        .createJournalEntry(any(JournalEntryRequest.class));
    when(companyEntityLookup.requireJournalEntry(eq(company), eq(1910L))).thenReturn(createdEntry);
    when(settlementAllocationRepository.saveAll(any()))
        .thenThrow(new DataIntegrityViolationException("duplicate"));

    DealerReceiptRequest request =
        new DealerReceiptRequest(
            1L,
            20L,
            new BigDecimal("100.00"),
            "DR-RACE-MISMATCH-NEW-1",
            "Dealer receipt race",
            "IDEMP-DR-RACE-MISMATCH",
            List.of(
                new SettlementAllocationRequest(
                    502L,
                    null,
                    new BigDecimal("100.00"),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    null)));

    assertThatThrownBy(() -> service.recordDealerReceipt(request))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void recordDealerReceiptSplit_dataIntegrityConflictRethrowsForRetryBoundary() {
    AccountingService service = spy(accountingService);

    Dealer dealer = new Dealer();
    dealer.setName("Race Dealer");
    ReflectionTestUtils.setField(dealer, "id", 1L);

    Account receivable = new Account();
    receivable.setCompany(company);
    receivable.setCode("AR-SPLIT-RACE");
    receivable.setType(AccountType.ASSET);
    ReflectionTestUtils.setField(receivable, "id", 10L);
    dealer.setReceivableAccount(receivable);

    Account cash = new Account();
    cash.setCompany(company);
    cash.setCode("CASH-SPLIT-RACE");
    cash.setType(AccountType.ASSET);
    ReflectionTestUtils.setField(cash, "id", 20L);

    com.bigbrightpaints.erp.modules.invoice.domain.Invoice invoice =
        new com.bigbrightpaints.erp.modules.invoice.domain.Invoice();
    invoice.setCompany(company);
    invoice.setDealer(dealer);
    invoice.setCurrency("INR");
    invoice.setOutstandingAmount(new BigDecimal("100.00"));
    invoice.setTotalAmount(new BigDecimal("100.00"));
    ReflectionTestUtils.setField(invoice, "id", 602L);

    JournalEntry createdEntry = new JournalEntry();
    ReflectionTestUtils.setField(createdEntry, "id", 920L);
    createdEntry.setDealer(dealer);
    createdEntry.setReferenceNumber("DR-SPLIT-RACE-NEW-1");
    createdEntry.setMemo("Dealer split race");
    createdEntry.addLine(
            journalLine(
                createdEntry,
                cash,
                "Dealer split race",
                new BigDecimal("100.00"),
                BigDecimal.ZERO));
    createdEntry.addLine(
            journalLine(
                createdEntry,
                receivable,
                "Dealer split race",
                BigDecimal.ZERO,
                new BigDecimal("100.00")));

    JournalEntry concurrentEntry = new JournalEntry();
    ReflectionTestUtils.setField(concurrentEntry, "id", 921L);
    concurrentEntry.setDealer(dealer);
    concurrentEntry.setReferenceNumber("DR-SPLIT-RACE-EXIST-1");
    concurrentEntry.setMemo("Dealer split race");
    concurrentEntry.addLine(
            journalLine(
                concurrentEntry,
                cash,
                "Dealer split race",
                new BigDecimal("100.00"),
                BigDecimal.ZERO));
    concurrentEntry.addLine(
            journalLine(
                concurrentEntry,
                receivable,
                "Dealer split race",
                BigDecimal.ZERO,
                new BigDecimal("100.00")));

    com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation concurrentRow =
        new com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation();
    concurrentRow.setCompany(company);
    concurrentRow.setPartnerType(
        com.bigbrightpaints.erp.modules.accounting.domain.PartnerType.DEALER);
    concurrentRow.setDealer(dealer);
    concurrentRow.setInvoice(invoice);
    concurrentRow.setJournalEntry(concurrentEntry);
    concurrentRow.setSettlementDate(LocalDate.of(2024, 4, 9));
    concurrentRow.setAllocationAmount(new BigDecimal("100.00"));
    concurrentRow.setDiscountAmount(BigDecimal.ZERO);
    concurrentRow.setWriteOffAmount(BigDecimal.ZERO);
    concurrentRow.setFxDifferenceAmount(BigDecimal.ZERO);
    concurrentRow.setIdempotencyKey("IDEMP-DR-SPLIT-RACE");

    JournalReferenceMapping mapping = new JournalReferenceMapping();
    mapping.setCompany(company);
    mapping.setLegacyReference("idemp-dr-split-race");
    mapping.setCanonicalReference("DR-SPLIT-RACE-NEW-1");
    mapping.setEntityType("DEALER_RECEIPT_SPLIT");
    mapping.setEntityId(null);

    AtomicInteger mappingLookups = new AtomicInteger(0);
    when(journalReferenceMappingRepository.findAllByCompanyAndLegacyReferenceIgnoreCase(
            eq(company), eq("idemp-dr-split-race")))
        .thenAnswer(
            invocation -> mappingLookups.getAndIncrement() < 2 ? List.of() : List.of(mapping));

    AtomicInteger allocationLookups = new AtomicInteger(0);
    when(settlementAllocationRepository
            .findByCompanyAndIdempotencyKeyIgnoreCaseOrderByCreatedAtAscIdAsc(
                eq(company), eq("IDEMP-DR-SPLIT-RACE")))
        .thenAnswer(
            invocation ->
                allocationLookups.getAndIncrement() < 2 ? List.of() : List.of(concurrentRow));

    when(dealerRepository.lockByCompanyAndId(eq(company), eq(1L))).thenReturn(Optional.of(dealer));
    when(companyEntityLookup.requireAccount(eq(company), eq(20L))).thenReturn(cash);
    when(invoiceRepository.lockOpenInvoicesForSettlement(eq(company), eq(dealer)))
        .thenReturn(List.of(invoice));
    when(settlementAllocationRepository.findByCompanyAndJournalEntryOrderByCreatedAtAsc(
            eq(company), eq(createdEntry)))
        .thenReturn(List.of());
    doReturn(stubEntry(920L))
        .when(dealerReceiptService)
        .createJournalEntry(any(JournalEntryRequest.class));
    when(companyEntityLookup.requireJournalEntry(eq(company), eq(920L))).thenReturn(createdEntry);
    when(settlementAllocationRepository.saveAll(any()))
        .thenThrow(new DataIntegrityViolationException("duplicate"));

    DealerReceiptSplitRequest request =
        new DealerReceiptSplitRequest(
            1L,
            List.of(new DealerReceiptSplitRequest.IncomingLine(20L, new BigDecimal("100.00"))),
            "DR-SPLIT-RACE-NEW-1",
            "Dealer split race",
            "IDEMP-DR-SPLIT-RACE");

    assertThatThrownBy(() -> service.recordDealerReceiptSplit(request))
        .isInstanceOf(DataIntegrityViolationException.class);
    verify(journalReferenceMappingRepository, atLeastOnce()).save(any());
    verify(invoiceSettlementPolicy, never()).applySettlement(any(), any(), any());
    verify(dealerLedgerService, never()).syncInvoiceLedger(any(), any());
  }

  @Test
  void recordSupplierPayment_dataIntegrityConflictRethrowsForRetryBoundary() {
    AccountingService service = spy(accountingService);

    Supplier supplier = new Supplier();
    supplier.setName("Race Supplier");
    supplier.setStatus(SupplierStatus.ACTIVE);
    ReflectionTestUtils.setField(supplier, "id", 1L);

    Account payable = new Account();
    payable.setCompany(company);
    payable.setCode("AP-RACE");
    payable.setType(AccountType.LIABILITY);
    ReflectionTestUtils.setField(payable, "id", 10L);
    supplier.setPayableAccount(payable);

    Account cash = new Account();
    cash.setCompany(company);
    cash.setCode("CASH-RACE");
    cash.setType(AccountType.ASSET);
    ReflectionTestUtils.setField(cash, "id", 20L);

    RawMaterialPurchase purchase = new RawMaterialPurchase();
    purchase.setCompany(company);
    purchase.setSupplier(supplier);
    purchase.setOutstandingAmount(new BigDecimal("100.00"));
    ReflectionTestUtils.setField(purchase, "id", 603L);

    JournalEntry createdEntry = new JournalEntry();
    ReflectionTestUtils.setField(createdEntry, "id", 930L);
    createdEntry.setSupplier(supplier);
    createdEntry.setReferenceNumber("SUP-PAY-RACE-NEW-1");
    createdEntry.setMemo("Supplier payment race");
    createdEntry.addLine(
            journalLine(
                createdEntry,
                payable,
                "Supplier payment race",
                new BigDecimal("100.00"),
                BigDecimal.ZERO));
    createdEntry.addLine(
            journalLine(
                createdEntry,
                cash,
                "Supplier payment race",
                BigDecimal.ZERO,
                new BigDecimal("100.00")));

    JournalEntry concurrentEntry = new JournalEntry();
    ReflectionTestUtils.setField(concurrentEntry, "id", 931L);
    concurrentEntry.setSupplier(supplier);
    concurrentEntry.setReferenceNumber("SUP-PAY-RACE-EXIST-1");
    concurrentEntry.setMemo("Supplier payment race");
    concurrentEntry.addLine(
            journalLine(
                concurrentEntry,
                payable,
                "Supplier payment race",
                new BigDecimal("100.00"),
                BigDecimal.ZERO));
    concurrentEntry.addLine(
            journalLine(
                concurrentEntry,
                cash,
                "Supplier payment race",
                BigDecimal.ZERO,
                new BigDecimal("100.00")));

    com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation concurrentRow =
        new com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation();
    concurrentRow.setCompany(company);
    concurrentRow.setPartnerType(
        com.bigbrightpaints.erp.modules.accounting.domain.PartnerType.SUPPLIER);
    concurrentRow.setSupplier(supplier);
    concurrentRow.setPurchase(purchase);
    concurrentRow.setJournalEntry(concurrentEntry);
    concurrentRow.setSettlementDate(LocalDate.of(2024, 4, 9));
    concurrentRow.setAllocationAmount(new BigDecimal("100.00"));
    concurrentRow.setDiscountAmount(BigDecimal.ZERO);
    concurrentRow.setWriteOffAmount(BigDecimal.ZERO);
    concurrentRow.setFxDifferenceAmount(BigDecimal.ZERO);
    concurrentRow.setIdempotencyKey("IDEMP-SUP-PAY-RACE");

    JournalReferenceMapping mapping = new JournalReferenceMapping();
    mapping.setCompany(company);
    mapping.setLegacyReference("idemp-sup-pay-race");
    mapping.setCanonicalReference("SUP-PAY-RACE-NEW-1");
    mapping.setEntityType("SUPPLIER_PAYMENT");
    mapping.setEntityId(null);

    AtomicInteger mappingLookups = new AtomicInteger(0);
    when(journalReferenceMappingRepository.findAllByCompanyAndLegacyReferenceIgnoreCase(
            eq(company), eq("idemp-sup-pay-race")))
        .thenAnswer(
            invocation -> mappingLookups.getAndIncrement() < 2 ? List.of() : List.of(mapping));

    AtomicInteger allocationLookups = new AtomicInteger(0);
    when(settlementAllocationRepository
            .findByCompanyAndIdempotencyKeyIgnoreCaseOrderByCreatedAtAscIdAsc(
                eq(company), eq("IDEMP-SUP-PAY-RACE")))
        .thenAnswer(
            invocation ->
                allocationLookups.getAndIncrement() < 2 ? List.of() : List.of(concurrentRow));

    when(supplierRepository.lockByCompanyAndId(eq(company), eq(1L)))
        .thenReturn(Optional.of(supplier));
    when(companyEntityLookup.requireAccount(eq(company), eq(20L))).thenReturn(cash);
    when(rawMaterialPurchaseRepository.lockByCompanyAndId(eq(company), eq(603L)))
        .thenReturn(Optional.of(purchase));
    doReturn(stubEntry(930L))
        .when(journalEntryService)
        .createJournalEntry(any(JournalEntryRequest.class));
    when(companyEntityLookup.requireJournalEntry(eq(company), eq(930L))).thenReturn(createdEntry);
    when(settlementAllocationRepository.saveAll(any()))
        .thenThrow(new DataIntegrityViolationException("duplicate"));

    SupplierPaymentRequest request =
        new SupplierPaymentRequest(
            1L,
            20L,
            new BigDecimal("100.00"),
            "SUP-PAY-RACE-NEW-1",
            "Supplier payment race",
            "IDEMP-SUP-PAY-RACE",
            List.of(
                new SettlementAllocationRequest(
                    null,
                    603L,
                    new BigDecimal("100.00"),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    null)));

    assertThatThrownBy(() -> service.recordSupplierPayment(request))
        .isInstanceOf(DataIntegrityViolationException.class);
    verify(journalReferenceMappingRepository, atLeastOnce()).save(any());
    assertThat(purchase.getOutstandingAmount()).isEqualByComparingTo("100.00");
    verify(rawMaterialPurchaseRepository, never()).saveAll(any());
  }

  @Test
  void settleDealerInvoices_dataIntegrityConflictRethrowsForRetryBoundary() {
    AccountingService service = spy(accountingService);

    Dealer dealer = new Dealer();
    dealer.setName("Dealer");
    ReflectionTestUtils.setField(dealer, "id", 1L);

    Account receivable = new Account();
    receivable.setCompany(company);
    receivable.setCode("AR-SETTLE-RACE");
    receivable.setType(AccountType.ASSET);
    ReflectionTestUtils.setField(receivable, "id", 10L);
    dealer.setReceivableAccount(receivable);

    Account cash = new Account();
    cash.setCompany(company);
    cash.setCode("CASH-SETTLE-RACE");
    cash.setType(AccountType.ASSET);
    ReflectionTestUtils.setField(cash, "id", 20L);

    com.bigbrightpaints.erp.modules.invoice.domain.Invoice invoice =
        new com.bigbrightpaints.erp.modules.invoice.domain.Invoice();
    invoice.setCompany(company);
    invoice.setDealer(dealer);
    invoice.setCurrency("INR");
    invoice.setOutstandingAmount(new BigDecimal("100.00"));
    invoice.setTotalAmount(new BigDecimal("100.00"));
    ReflectionTestUtils.setField(invoice, "id", 702L);

    JournalEntry createdEntry = new JournalEntry();
    ReflectionTestUtils.setField(createdEntry, "id", 950L);
    createdEntry.setDealer(dealer);
    createdEntry.setReferenceNumber("DR-SETTLE-RACE-NEW-1");
    createdEntry.setMemo("Dealer settlement race");
    createdEntry.addLine(
            journalLine(
                createdEntry,
                cash,
                "Dealer settlement race",
                new BigDecimal("100.00"),
                BigDecimal.ZERO));
    createdEntry.addLine(
            journalLine(
                createdEntry,
                receivable,
                "Dealer settlement race",
                BigDecimal.ZERO,
                new BigDecimal("100.00")));

    JournalEntry concurrentEntry = new JournalEntry();
    ReflectionTestUtils.setField(concurrentEntry, "id", 951L);
    concurrentEntry.setDealer(dealer);
    concurrentEntry.setReferenceNumber("DR-SETTLE-RACE-EXIST-1");
    concurrentEntry.setMemo("Dealer settlement race");
    concurrentEntry.addLine(
            journalLine(
                concurrentEntry,
                cash,
                "Dealer settlement race",
                new BigDecimal("100.00"),
                BigDecimal.ZERO));
    concurrentEntry.addLine(
            journalLine(
                concurrentEntry,
                receivable,
                "Dealer settlement race",
                BigDecimal.ZERO,
                new BigDecimal("100.00")));

    com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation concurrentRow =
        new com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation();
    concurrentRow.setCompany(company);
    concurrentRow.setPartnerType(
        com.bigbrightpaints.erp.modules.accounting.domain.PartnerType.DEALER);
    concurrentRow.setDealer(dealer);
    concurrentRow.setInvoice(invoice);
    concurrentRow.setJournalEntry(concurrentEntry);
    concurrentRow.setSettlementDate(LocalDate.of(2024, 4, 9));
    concurrentRow.setAllocationAmount(new BigDecimal("100.00"));
    concurrentRow.setDiscountAmount(BigDecimal.ZERO);
    concurrentRow.setWriteOffAmount(BigDecimal.ZERO);
    concurrentRow.setFxDifferenceAmount(BigDecimal.ZERO);
    concurrentRow.setIdempotencyKey("IDEMP-DR-SETTLE-RACE");

    JournalReferenceMapping mapping = new JournalReferenceMapping();
    mapping.setCompany(company);
    mapping.setLegacyReference("idemp-dr-settle-race");
    mapping.setCanonicalReference("DR-SETTLE-RACE-NEW-1");
    mapping.setEntityType("DEALER_SETTLEMENT");
    mapping.setEntityId(null);

    AtomicInteger mappingLookups = new AtomicInteger(0);
    when(journalReferenceMappingRepository.findAllByCompanyAndLegacyReferenceIgnoreCase(
            eq(company), eq("idemp-dr-settle-race")))
        .thenAnswer(
            invocation -> mappingLookups.getAndIncrement() < 2 ? List.of() : List.of(mapping));

    AtomicInteger allocationLookups = new AtomicInteger(0);
    when(settlementAllocationRepository
            .findByCompanyAndIdempotencyKeyIgnoreCaseOrderByCreatedAtAscIdAsc(
                eq(company), eq("IDEMP-DR-SETTLE-RACE")))
        .thenAnswer(
            invocation ->
                allocationLookups.getAndIncrement() < 2 ? List.of() : List.of(concurrentRow));

    when(dealerRepository.lockByCompanyAndId(eq(company), eq(1L))).thenReturn(Optional.of(dealer));
    when(companyEntityLookup.requireAccount(eq(company), eq(20L))).thenReturn(cash);
    when(journalReferenceMappingRepository.reserveReferenceMapping(
            eq(company.getId()),
            eq("idemp-dr-settle-race"),
            eq("DR-SETTLE-RACE-NEW-1"),
            eq("DEALER_SETTLEMENT"),
            any()))
        .thenReturn(1);
    when(invoiceRepository.lockByCompanyAndId(eq(company), eq(702L)))
        .thenReturn(Optional.of(invoice));
    doReturn(stubEntry(950L))
        .when(journalEntryService)
        .createJournalEntry(any(JournalEntryRequest.class));
    when(companyEntityLookup.requireJournalEntry(eq(company), eq(950L))).thenReturn(createdEntry);
    when(settlementAllocationRepository.saveAll(any()))
        .thenThrow(new DataIntegrityViolationException("duplicate"));

    DealerSettlementRequest request =
        new DealerSettlementRequest(
            1L,
            20L,
            null,
            null,
            null,
            null,
            LocalDate.of(2024, 4, 9),
            "DR-SETTLE-RACE-NEW-1",
            "Dealer settlement race",
            "IDEMP-DR-SETTLE-RACE",
            Boolean.FALSE,
            List.of(
                new SettlementAllocationRequest(
                    702L,
                    null,
                    new BigDecimal("100.00"),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    null)),
            null);

    assertThatThrownBy(() -> service.settleDealerInvoices(request))
        .isInstanceOf(DataIntegrityViolationException.class);
    verify(journalReferenceMappingRepository, atLeastOnce()).save(any());
    verify(invoiceSettlementPolicy, never()).applySettlement(any(), any(), any());
  }

  @Test
  void settleSupplierInvoices_dataIntegrityConflictRethrowsForRetryBoundary() {
    AccountingService service = spy(accountingService);

    Supplier supplier = new Supplier();
    supplier.setName("Supplier");
    supplier.setStatus(SupplierStatus.ACTIVE);
    ReflectionTestUtils.setField(supplier, "id", 1L);

    Account payable = new Account();
    payable.setCompany(company);
    payable.setCode("AP-SETTLE-RACE");
    payable.setType(AccountType.LIABILITY);
    ReflectionTestUtils.setField(payable, "id", 10L);
    supplier.setPayableAccount(payable);

    Account cash = new Account();
    cash.setCompany(company);
    cash.setCode("CASH-SETTLE-RACE");
    cash.setType(AccountType.ASSET);
    ReflectionTestUtils.setField(cash, "id", 20L);

    RawMaterialPurchase purchase = new RawMaterialPurchase();
    purchase.setCompany(company);
    purchase.setSupplier(supplier);
    purchase.setTotalAmount(new BigDecimal("100.00"));
    purchase.setTaxAmount(BigDecimal.ZERO);
    purchase.setOutstandingAmount(new BigDecimal("100.00"));
    ReflectionTestUtils.setField(purchase, "id", 704L);

    Account inventory = new Account();
    inventory.setCompany(company);
    inventory.setCode("INV-SETTLE-RACE");
    inventory.setType(AccountType.ASSET);
    ReflectionTestUtils.setField(inventory, "id", 22L);

    JournalEntry purchaseJournal = new JournalEntry();
    ReflectionTestUtils.setField(purchaseJournal, "id", 959L);
    purchaseJournal.setSupplier(supplier);
    purchaseJournal.setReferenceNumber("RMP-SETTLE-RACE-1");
    purchaseJournal.addLine(
            journalLine(
                purchaseJournal,
                inventory,
                "Purchase invoice race",
                new BigDecimal("100.00"),
                BigDecimal.ZERO));
    purchaseJournal.addLine(
            journalLine(
                purchaseJournal,
                payable,
                "Purchase invoice race",
                BigDecimal.ZERO,
                new BigDecimal("100.00")));
    purchase.setJournalEntry(purchaseJournal);

    JournalEntry createdEntry = new JournalEntry();
    ReflectionTestUtils.setField(createdEntry, "id", 960L);
    createdEntry.setSupplier(supplier);
    createdEntry.setReferenceNumber("SUP-SETTLE-RACE-NEW-1");
    createdEntry.setMemo("Supplier settlement race");
    createdEntry.addLine(
            journalLine(
                createdEntry,
                payable,
                "Supplier settlement race",
                new BigDecimal("100.00"),
                BigDecimal.ZERO));
    createdEntry.addLine(
            journalLine(
                createdEntry,
                cash,
                "Supplier settlement race",
                BigDecimal.ZERO,
                new BigDecimal("100.00")));

    JournalEntry concurrentEntry = new JournalEntry();
    ReflectionTestUtils.setField(concurrentEntry, "id", 961L);
    concurrentEntry.setSupplier(supplier);
    concurrentEntry.setReferenceNumber("SUP-SETTLE-RACE-NEW-1");
    concurrentEntry.setMemo("Supplier settlement race");
    concurrentEntry.addLine(
            journalLine(
                concurrentEntry,
                payable,
                "Supplier settlement race",
                new BigDecimal("100.00"),
                BigDecimal.ZERO));
    concurrentEntry.addLine(
            journalLine(
                concurrentEntry,
                cash,
                "Supplier settlement race",
                BigDecimal.ZERO,
                new BigDecimal("100.00")));

    com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation concurrentRow =
        new com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation();
    concurrentRow.setCompany(company);
    concurrentRow.setPartnerType(
        com.bigbrightpaints.erp.modules.accounting.domain.PartnerType.SUPPLIER);
    concurrentRow.setSupplier(supplier);
    concurrentRow.setPurchase(purchase);
    concurrentRow.setJournalEntry(concurrentEntry);
    concurrentRow.setSettlementDate(LocalDate.of(2024, 4, 9));
    concurrentRow.setAllocationAmount(new BigDecimal("100.00"));
    concurrentRow.setDiscountAmount(BigDecimal.ZERO);
    concurrentRow.setWriteOffAmount(BigDecimal.ZERO);
    concurrentRow.setFxDifferenceAmount(BigDecimal.ZERO);
    concurrentRow.setIdempotencyKey("IDEMP-AP-SETTLE-RACE");
    concurrentRow.setMemo(null);

    JournalReferenceMapping mapping = new JournalReferenceMapping();
    mapping.setCompany(company);
    mapping.setLegacyReference("idemp-ap-settle-race");
    mapping.setCanonicalReference("SUP-SETTLE-RACE-NEW-1");
    mapping.setEntityType("SUPPLIER_SETTLEMENT");
    mapping.setEntityId(null);

    AtomicInteger mappingLookups = new AtomicInteger(0);
    when(journalReferenceMappingRepository.findAllByCompanyAndLegacyReferenceIgnoreCase(
            eq(company), eq("idemp-ap-settle-race")))
        .thenAnswer(
            invocation -> mappingLookups.getAndIncrement() < 2 ? List.of() : List.of(mapping));

    AtomicInteger allocationLookups = new AtomicInteger(0);
    when(settlementAllocationRepository
            .findByCompanyAndIdempotencyKeyIgnoreCaseOrderByCreatedAtAscIdAsc(
                eq(company), eq("IDEMP-AP-SETTLE-RACE")))
        .thenAnswer(
            invocation ->
                allocationLookups.getAndIncrement() < 2 ? List.of() : List.of(concurrentRow));

    when(supplierRepository.lockByCompanyAndId(eq(company), eq(1L)))
        .thenReturn(Optional.of(supplier));
    when(companyEntityLookup.requireAccount(eq(company), eq(20L))).thenReturn(cash);
    when(journalReferenceMappingRepository.reserveReferenceMapping(
            eq(company.getId()),
            eq("idemp-ap-settle-race"),
            eq("SUP-SETTLE-RACE-NEW-1"),
            eq("SUPPLIER_SETTLEMENT"),
            any()))
        .thenReturn(1);
    when(rawMaterialPurchaseRepository.lockByCompanyAndId(eq(company), eq(704L)))
        .thenReturn(Optional.of(purchase));
    doReturn(stubEntry(960L))
        .when(journalEntryService)
        .createJournalEntry(any(JournalEntryRequest.class));
    when(companyEntityLookup.requireJournalEntry(eq(company), eq(960L))).thenReturn(createdEntry);
    when(settlementAllocationRepository.saveAll(any()))
        .thenThrow(new DataIntegrityViolationException("duplicate"));

    SettlementAllocationRequest allocation =
        new SettlementAllocationRequest(
            null,
            704L,
            new BigDecimal("100.00"),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            null);

    SupplierSettlementRequest request =
        new SupplierSettlementRequest(
            1L,
            20L,
            null,
            null,
            null,
            null,
            LocalDate.of(2024, 4, 9),
            "SUP-SETTLE-RACE-NEW-1",
            "Supplier settlement race",
            "IDEMP-AP-SETTLE-RACE",
            Boolean.FALSE,
            List.of(allocation));

    assertThatThrownBy(() -> service.settleSupplierInvoices(request))
        .isInstanceOf(DataIntegrityViolationException.class);
    verify(journalReferenceMappingRepository, atLeastOnce()).save(any());
    assertThat(purchase.getOutstandingAmount()).isEqualByComparingTo("100.00");
    verify(rawMaterialPurchaseRepository, never()).saveAll(any());
  }

  @Test
  void settleDealerInvoices_replayRejectsMappingAllocationJournalMismatch() {
    Dealer dealer = new Dealer();
    dealer.setName("Mismatch Dealer");
    ReflectionTestUtils.setField(dealer, "id", 1L);

    Account receivable = new Account();
    receivable.setCompany(company);
    receivable.setCode("AR-SETTLE-MISMATCH");
    receivable.setType(AccountType.ASSET);
    ReflectionTestUtils.setField(receivable, "id", 10L);
    dealer.setReceivableAccount(receivable);

    Account cash = new Account();
    cash.setCompany(company);
    cash.setCode("CASH-SETTLE-MISMATCH");
    cash.setType(AccountType.ASSET);
    ReflectionTestUtils.setField(cash, "id", 20L);

    com.bigbrightpaints.erp.modules.invoice.domain.Invoice invoice =
        new com.bigbrightpaints.erp.modules.invoice.domain.Invoice();
    invoice.setCompany(company);
    invoice.setDealer(dealer);
    ReflectionTestUtils.setField(invoice, "id", 701L);

    JournalEntry mappingEntry = new JournalEntry();
    ReflectionTestUtils.setField(mappingEntry, "id", 940L);
    mappingEntry.setDealer(dealer);
    mappingEntry.setReferenceNumber("DR-SETTLE-MAP-MISMATCH-1");

    JournalEntry allocationEntry = new JournalEntry();
    ReflectionTestUtils.setField(allocationEntry, "id", 941L);
    allocationEntry.setDealer(dealer);
    allocationEntry.setReferenceNumber("DR-SETTLE-ALLOC-MISMATCH-1");

    com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation existingRow =
        new com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation();
    existingRow.setCompany(company);
    existingRow.setPartnerType(
        com.bigbrightpaints.erp.modules.accounting.domain.PartnerType.DEALER);
    existingRow.setDealer(dealer);
    existingRow.setInvoice(invoice);
    existingRow.setJournalEntry(allocationEntry);
    existingRow.setSettlementDate(LocalDate.of(2024, 4, 9));
    existingRow.setAllocationAmount(new BigDecimal("100.00"));
    existingRow.setDiscountAmount(BigDecimal.ZERO);
    existingRow.setWriteOffAmount(BigDecimal.ZERO);
    existingRow.setFxDifferenceAmount(BigDecimal.ZERO);
    existingRow.setIdempotencyKey("IDEMP-DR-SETTLE-MISMATCH");

    JournalReferenceMapping mapping = new JournalReferenceMapping();
    mapping.setCompany(company);
    mapping.setLegacyReference("idemp-dr-settle-mismatch");
    mapping.setCanonicalReference("DR-SETTLE-MAP-MISMATCH-1");
    mapping.setEntityType("DEALER_SETTLEMENT");
    mapping.setEntityId(null);

    when(dealerRepository.lockByCompanyAndId(eq(company), eq(1L))).thenReturn(Optional.of(dealer));
    when(companyEntityLookup.requireAccount(eq(company), eq(20L))).thenReturn(cash);
    when(journalReferenceMappingRepository.findAllByCompanyAndLegacyReferenceIgnoreCase(
            eq(company), eq("idemp-dr-settle-mismatch")))
        .thenReturn(List.of(mapping));
    when(journalReferenceResolver.findExistingEntry(eq(company), eq("DR-SETTLE-MAP-MISMATCH-1")))
        .thenReturn(Optional.of(mappingEntry));
    when(settlementAllocationRepository
            .findByCompanyAndIdempotencyKeyIgnoreCaseOrderByCreatedAtAscIdAsc(
                eq(company), eq("IDEMP-DR-SETTLE-MISMATCH")))
        .thenReturn(List.of(existingRow));

    DealerSettlementRequest request =
        new DealerSettlementRequest(
            1L,
            20L,
            null,
            null,
            null,
            null,
            LocalDate.of(2024, 4, 9),
            "DR-SETTLE-MAP-MISMATCH-1",
            "Dealer settlement replay",
            "IDEMP-DR-SETTLE-MISMATCH",
            Boolean.FALSE,
            List.of(
                new SettlementAllocationRequest(
                    701L,
                    null,
                    new BigDecimal("100.00"),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    null)),
            null);

    assertThatThrownBy(() -> accountingService.settleDealerInvoices(request))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("different journal than settled allocations");
  }

  @Test
  void settleDealerInvoices_replayRejectsMappingAllocationJournalMismatchOnLeaderFastPath() {
    Dealer dealer = new Dealer();
    dealer.setName("Mismatch Dealer");
    ReflectionTestUtils.setField(dealer, "id", 1L);

    Account receivable = new Account();
    receivable.setCompany(company);
    receivable.setCode("AR-SETTLE-MISMATCH");
    receivable.setType(AccountType.ASSET);
    ReflectionTestUtils.setField(receivable, "id", 10L);
    dealer.setReceivableAccount(receivable);

    Account cash = new Account();
    cash.setCompany(company);
    cash.setCode("CASH-SETTLE-MISMATCH");
    cash.setType(AccountType.ASSET);
    ReflectionTestUtils.setField(cash, "id", 20L);

    com.bigbrightpaints.erp.modules.invoice.domain.Invoice invoice =
        new com.bigbrightpaints.erp.modules.invoice.domain.Invoice();
    invoice.setCompany(company);
    invoice.setDealer(dealer);
    ReflectionTestUtils.setField(invoice, "id", 701L);

    JournalEntry mappingEntry = new JournalEntry();
    ReflectionTestUtils.setField(mappingEntry, "id", 1940L);
    mappingEntry.setDealer(dealer);
    mappingEntry.setReferenceNumber("DR-SETTLE-MAP-MISMATCH-LEADER-1");

    JournalEntry allocationEntry = new JournalEntry();
    ReflectionTestUtils.setField(allocationEntry, "id", 1941L);
    allocationEntry.setDealer(dealer);
    allocationEntry.setReferenceNumber("DR-SETTLE-ALLOC-MISMATCH-LEADER-1");

    com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation existingRow =
        new com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation();
    existingRow.setCompany(company);
    existingRow.setPartnerType(
        com.bigbrightpaints.erp.modules.accounting.domain.PartnerType.DEALER);
    existingRow.setDealer(dealer);
    existingRow.setInvoice(invoice);
    existingRow.setJournalEntry(allocationEntry);
    existingRow.setSettlementDate(LocalDate.of(2024, 4, 9));
    existingRow.setAllocationAmount(new BigDecimal("100.00"));
    existingRow.setDiscountAmount(BigDecimal.ZERO);
    existingRow.setWriteOffAmount(BigDecimal.ZERO);
    existingRow.setFxDifferenceAmount(BigDecimal.ZERO);
    existingRow.setIdempotencyKey("IDEMP-DR-SETTLE-MISMATCH-LEADER");

    when(dealerRepository.lockByCompanyAndId(eq(company), eq(1L))).thenReturn(Optional.of(dealer));
    when(companyEntityLookup.requireAccount(eq(company), eq(20L))).thenReturn(cash);
    when(journalReferenceMappingRepository.findAllByCompanyAndLegacyReferenceIgnoreCase(
            eq(company), eq("idemp-dr-settle-mismatch-leader")))
        .thenReturn(List.of());
    when(journalReferenceResolver.findExistingEntry(
            eq(company), eq("DR-SETTLE-MAP-MISMATCH-LEADER-1")))
        .thenReturn(Optional.of(mappingEntry));
    when(settlementAllocationRepository
            .findByCompanyAndIdempotencyKeyIgnoreCaseOrderByCreatedAtAscIdAsc(
                eq(company), eq("IDEMP-DR-SETTLE-MISMATCH-LEADER")))
        .thenReturn(List.of(existingRow));

    DealerSettlementRequest request =
        new DealerSettlementRequest(
            1L,
            20L,
            null,
            null,
            null,
            null,
            LocalDate.of(2024, 4, 9),
            "DR-SETTLE-MAP-MISMATCH-LEADER-1",
            "Dealer settlement replay",
            "IDEMP-DR-SETTLE-MISMATCH-LEADER",
            Boolean.FALSE,
            List.of(
                new SettlementAllocationRequest(
                    701L,
                    null,
                    new BigDecimal("100.00"),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    null)),
            null);

    assertThatThrownBy(() -> accountingService.settleDealerInvoices(request))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("different journal than settled allocations");
  }

  @Test
  @Tag("critical")
  void settleDealerInvoices_nonLeaderReplayMissingAllocationsIncludesPartnerDetails() {
    Dealer dealer = new Dealer();
    dealer.setName("Dealer");
    ReflectionTestUtils.setField(dealer, "id", 1L);

    Account receivable = new Account();
    receivable.setCompany(company);
    receivable.setCode("AR-RACE");
    receivable.setType(AccountType.ASSET);
    ReflectionTestUtils.setField(receivable, "id", 10L);
    dealer.setReceivableAccount(receivable);

    Account cash = new Account();
    cash.setCompany(company);
    cash.setCode("CASH-RACE");
    cash.setType(AccountType.ASSET);
    ReflectionTestUtils.setField(cash, "id", 20L);

    var invoice = new com.bigbrightpaints.erp.modules.invoice.domain.Invoice();
    invoice.setCompany(company);
    invoice.setDealer(dealer);
    invoice.setCurrency("INR");
    invoice.setOutstandingAmount(new BigDecimal("1000.00"));
    invoice.setTotalAmount(new BigDecimal("1000.00"));
    ReflectionTestUtils.setField(invoice, "id", 5L);

    JournalEntry existingEntry = new JournalEntry();
    ReflectionTestUtils.setField(existingEntry, "id", 991L);
    existingEntry.setDealer(dealer);
    existingEntry.setReferenceNumber("DR-SETTLE-RACE-1");

    JournalReferenceMapping mapping = new JournalReferenceMapping();
    mapping.setCompany(company);
    mapping.setLegacyReference("idemp-dr-race-miss");
    mapping.setCanonicalReference("DR-SETTLE-RACE-1");
    mapping.setEntityType("DEALER_SETTLEMENT");
    mapping.setEntityId(null);

    when(dealerRepository.lockByCompanyAndId(eq(company), eq(1L))).thenReturn(Optional.of(dealer));
    when(companyEntityLookup.requireAccount(eq(company), eq(20L))).thenReturn(cash);
    when(journalReferenceMappingRepository.findAllByCompanyAndLegacyReferenceIgnoreCase(
            eq(company), eq("idemp-dr-race-miss")))
        .thenReturn(List.of(mapping));
    when(settlementAllocationRepository.findByCompanyAndIdempotencyKey(
            eq(company), eq("IDEMP-DR-RACE-MISS")))
        .thenReturn(List.of());
    when(journalReferenceResolver.findExistingEntry(eq(company), eq("DR-SETTLE-RACE-1")))
        .thenReturn(Optional.of(existingEntry));
    when(settlementAllocationRepository
            .findByCompanyAndIdempotencyKeyIgnoreCaseOrderByCreatedAtAscIdAsc(
                eq(company), eq("IDEMP-DR-RACE-MISS")))
        .thenReturn(List.of());

    DealerSettlementRequest request =
        new DealerSettlementRequest(
            1L,
            20L,
            null,
            null,
            null,
            null,
            LocalDate.of(2024, 4, 9),
            "DR-SETTLE-RACE-1",
            "Dealer settlement replay race",
            "IDEMP-DR-RACE-MISS",
            Boolean.FALSE,
            List.of(
                new SettlementAllocationRequest(
                    5L,
                    null,
                    new BigDecimal("100.00"),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    null)),
            null);

    assertThatThrownBy(() -> accountingService.settleDealerInvoices(request))
        .isInstanceOfSatisfying(
            ApplicationException.class,
            ex -> {
              assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INTERNAL_CONCURRENCY_FAILURE);
              assertPartnerReplayDetails(ex, "IDEMP-DR-RACE-MISS", "DEALER", 1L);
            })
        .hasMessageContaining(
            "Dealer settlement idempotency key is reserved but allocation not found");
  }

  @Test
  @Tag("critical")
  void settleDealerInvoices_replayPartnerMismatchIncludesPartnerDetails() {
    Dealer dealer = new Dealer();
    dealer.setName("Dealer");
    ReflectionTestUtils.setField(dealer, "id", 1L);

    Dealer otherDealer = new Dealer();
    otherDealer.setName("Other Dealer");
    ReflectionTestUtils.setField(otherDealer, "id", 2L);

    Account receivable = new Account();
    receivable.setCompany(company);
    receivable.setCode("AR-RACE");
    receivable.setType(AccountType.ASSET);
    ReflectionTestUtils.setField(receivable, "id", 10L);
    dealer.setReceivableAccount(receivable);

    Account cash = new Account();
    cash.setCompany(company);
    cash.setCode("CASH-RACE");
    cash.setType(AccountType.ASSET);
    ReflectionTestUtils.setField(cash, "id", 20L);

    var invoice = new com.bigbrightpaints.erp.modules.invoice.domain.Invoice();
    invoice.setCompany(company);
    invoice.setDealer(dealer);
    invoice.setCurrency("INR");
    invoice.setOutstandingAmount(new BigDecimal("1000.00"));
    invoice.setTotalAmount(new BigDecimal("1000.00"));
    ReflectionTestUtils.setField(invoice, "id", 5L);

    JournalEntry existingEntry = new JournalEntry();
    ReflectionTestUtils.setField(existingEntry, "id", 993L);
    existingEntry.setDealer(otherDealer);
    existingEntry.setEntryDate(LocalDate.of(2024, 4, 9));
    existingEntry.setReferenceNumber("DR-SETTLE-RACE-PARTNER-1");

    com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation existingRow =
        new com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation();
    existingRow.setCompany(company);
    existingRow.setPartnerType(
        com.bigbrightpaints.erp.modules.accounting.domain.PartnerType.DEALER);
    existingRow.setDealer(dealer);
    existingRow.setInvoice(invoice);
    existingRow.setJournalEntry(existingEntry);
    existingRow.setSettlementDate(LocalDate.of(2024, 4, 9));
    existingRow.setAllocationAmount(new BigDecimal("100.00"));
    existingRow.setDiscountAmount(BigDecimal.ZERO);
    existingRow.setWriteOffAmount(BigDecimal.ZERO);
    existingRow.setFxDifferenceAmount(BigDecimal.ZERO);
    existingRow.setIdempotencyKey("IDEMP-DR-RACE-PARTNER");

    JournalReferenceMapping mapping = new JournalReferenceMapping();
    mapping.setCompany(company);
    mapping.setLegacyReference("idemp-dr-race-partner");
    mapping.setCanonicalReference("DR-SETTLE-RACE-PARTNER-1");
    mapping.setEntityType("DEALER_SETTLEMENT");
    mapping.setEntityId(null);

    when(dealerRepository.lockByCompanyAndId(eq(company), eq(1L))).thenReturn(Optional.of(dealer));
    when(companyEntityLookup.requireAccount(eq(company), eq(20L))).thenReturn(cash);
    when(journalReferenceMappingRepository.findAllByCompanyAndLegacyReferenceIgnoreCase(
            eq(company), eq("idemp-dr-race-partner")))
        .thenReturn(List.of(mapping));
    when(journalReferenceResolver.findExistingEntry(eq(company), eq("DR-SETTLE-RACE-PARTNER-1")))
        .thenReturn(Optional.of(existingEntry));
    when(settlementAllocationRepository
            .findByCompanyAndIdempotencyKeyIgnoreCaseOrderByCreatedAtAscIdAsc(
                eq(company), eq("IDEMP-DR-RACE-PARTNER")))
        .thenReturn(List.of(existingRow));

    DealerSettlementRequest request =
        new DealerSettlementRequest(
            1L,
            20L,
            null,
            null,
            null,
            null,
            LocalDate.of(2024, 4, 9),
            "DR-SETTLE-RACE-PARTNER-1",
            "Dealer settlement replay partner mismatch",
            "IDEMP-DR-RACE-PARTNER",
            Boolean.FALSE,
            List.of(
                new SettlementAllocationRequest(
                    5L,
                    null,
                    new BigDecimal("100.00"),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    null)),
            null);

    assertThatThrownBy(() -> accountingService.settleDealerInvoices(request))
        .isInstanceOfSatisfying(
            ApplicationException.class,
            ex -> {
              assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CONCURRENCY_CONFLICT);
              assertPartnerReplayDetails(ex, "IDEMP-DR-RACE-PARTNER", "DEALER", 1L);
            })
        .hasMessageContaining("another dealer");
  }

  @Test
  void settleDealerInvoices_replayPayloadMismatchWinsOverNetCashPrevalidation() {
    Dealer dealer = new Dealer();
    dealer.setName("Replay Dealer");
    ReflectionTestUtils.setField(dealer, "id", 1L);

    Account receivable = new Account();
    receivable.setCompany(company);
    receivable.setCode("AR-SETTLE-REPLAY");
    receivable.setType(AccountType.ASSET);
    ReflectionTestUtils.setField(receivable, "id", 10L);
    dealer.setReceivableAccount(receivable);

    Account cash = new Account();
    cash.setCompany(company);
    cash.setCode("CASH-SETTLE-REPLAY");
    cash.setType(AccountType.ASSET);
    ReflectionTestUtils.setField(cash, "id", 20L);

    Account discount = new Account();
    discount.setCompany(company);
    discount.setCode("DISC-SETTLE-REPLAY");
    ReflectionTestUtils.setField(discount, "id", 21L);

    com.bigbrightpaints.erp.modules.invoice.domain.Invoice invoiceA =
        new com.bigbrightpaints.erp.modules.invoice.domain.Invoice();
    invoiceA.setCompany(company);
    invoiceA.setDealer(dealer);
    ReflectionTestUtils.setField(invoiceA, "id", 701L);

    com.bigbrightpaints.erp.modules.invoice.domain.Invoice invoiceB =
        new com.bigbrightpaints.erp.modules.invoice.domain.Invoice();
    invoiceB.setCompany(company);
    invoiceB.setDealer(dealer);
    ReflectionTestUtils.setField(invoiceB, "id", 702L);

    JournalEntry existingEntry = new JournalEntry();
    ReflectionTestUtils.setField(existingEntry, "id", 1950L);
    existingEntry.setDealer(dealer);
    existingEntry.setReferenceNumber("DR-SETTLE-REPLAY-NETCASH-1");

    com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation existingRowA =
        new com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation();
    existingRowA.setCompany(company);
    existingRowA.setPartnerType(
        com.bigbrightpaints.erp.modules.accounting.domain.PartnerType.DEALER);
    existingRowA.setDealer(dealer);
    existingRowA.setInvoice(invoiceA);
    existingRowA.setJournalEntry(existingEntry);
    existingRowA.setSettlementDate(LocalDate.of(2024, 4, 9));
    existingRowA.setAllocationAmount(new BigDecimal("100.00"));
    existingRowA.setDiscountAmount(BigDecimal.ZERO);
    existingRowA.setWriteOffAmount(BigDecimal.ZERO);
    existingRowA.setFxDifferenceAmount(BigDecimal.ZERO);
    existingRowA.setIdempotencyKey("IDEMP-DR-SETTLE-REPLAY-NETCASH");

    com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation existingRowB =
        new com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation();
    existingRowB.setCompany(company);
    existingRowB.setPartnerType(
        com.bigbrightpaints.erp.modules.accounting.domain.PartnerType.DEALER);
    existingRowB.setDealer(dealer);
    existingRowB.setInvoice(invoiceB);
    existingRowB.setJournalEntry(existingEntry);
    existingRowB.setSettlementDate(LocalDate.of(2024, 4, 9));
    existingRowB.setAllocationAmount(new BigDecimal("200.00"));
    existingRowB.setDiscountAmount(BigDecimal.ZERO);
    existingRowB.setWriteOffAmount(BigDecimal.ZERO);
    existingRowB.setFxDifferenceAmount(BigDecimal.ZERO);
    existingRowB.setIdempotencyKey("IDEMP-DR-SETTLE-REPLAY-NETCASH");

    when(dealerRepository.lockByCompanyAndId(eq(company), eq(1L))).thenReturn(Optional.of(dealer));
    when(companyEntityLookup.requireAccount(eq(company), eq(20L))).thenReturn(cash);
    when(companyEntityLookup.requireAccount(eq(company), eq(21L))).thenReturn(discount);
    when(journalReferenceMappingRepository.findAllByCompanyAndLegacyReferenceIgnoreCase(
            eq(company), eq("idemp-dr-settle-replay-netcash")))
        .thenReturn(List.of());
    when(journalReferenceResolver.findExistingEntry(eq(company), eq("DR-SETTLE-REPLAY-NETCASH-1")))
        .thenReturn(Optional.empty());
    when(journalReferenceMappingRepository.reserveReferenceMapping(
            eq(company.getId()),
            eq("idemp-dr-settle-replay-netcash"),
            eq("DR-SETTLE-REPLAY-NETCASH-1"),
            eq("DEALER_SETTLEMENT"),
            any()))
        .thenReturn(1);
    when(settlementAllocationRepository
            .findByCompanyAndIdempotencyKeyIgnoreCaseOrderByCreatedAtAscIdAsc(
                eq(company), eq("IDEMP-DR-SETTLE-REPLAY-NETCASH")))
        .thenReturn(List.of(existingRowA, existingRowB));

    DealerSettlementRequest request =
        new DealerSettlementRequest(
            1L,
            20L,
            21L,
            null,
            null,
            null,
            LocalDate.of(2024, 4, 9),
            "DR-SETTLE-REPLAY-NETCASH-1",
            "Dealer settlement replay",
            "IDEMP-DR-SETTLE-REPLAY-NETCASH",
            Boolean.TRUE,
            List.of(
                new SettlementAllocationRequest(
                    701L,
                    null,
                    new BigDecimal("100.00"),
                    new BigDecimal("120.00"),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    null),
                new SettlementAllocationRequest(
                    702L,
                    null,
                    new BigDecimal("200.00"),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    null)),
            null);

    assertThatThrownBy(() -> accountingService.settleDealerInvoices(request))
        .isInstanceOfSatisfying(
            ApplicationException.class,
            ex -> {
              assertPartnerReplayDetails(ex, "IDEMP-DR-SETTLE-REPLAY-NETCASH", "DEALER", 1L);
              assertAllocationReplayDiagnostics(ex, 2, 2, "|applied=100", "|discount=120");
            })
        .hasMessageContaining("different settlement payload")
        .satisfies(ex -> assertThat(ex).hasMessageNotContaining("negative net cash contribution"));
  }

  @Test
  void settleSupplierInvoices_replayPayloadMismatchWinsOverNetCashPrevalidation() {
    Supplier supplier = new Supplier();
    supplier.setName("Replay Supplier");
    supplier.setStatus(SupplierStatus.ACTIVE);
    ReflectionTestUtils.setField(supplier, "id", 1L);

    Account payable = new Account();
    payable.setCompany(company);
    payable.setCode("AP-SETTLE-REPLAY");
    payable.setType(AccountType.LIABILITY);
    ReflectionTestUtils.setField(payable, "id", 10L);
    supplier.setPayableAccount(payable);

    Account cash = new Account();
    cash.setCompany(company);
    cash.setCode("CASH-SETTLE-REPLAY");
    cash.setType(AccountType.ASSET);
    ReflectionTestUtils.setField(cash, "id", 20L);

    Account discount = new Account();
    discount.setCompany(company);
    discount.setCode("DISC-SETTLE-REPLAY");
    ReflectionTestUtils.setField(discount, "id", 21L);

    RawMaterialPurchase purchaseA = new RawMaterialPurchase();
    purchaseA.setCompany(company);
    purchaseA.setSupplier(supplier);
    purchaseA.setTotalAmount(new BigDecimal("100.00"));
    purchaseA.setTaxAmount(BigDecimal.ZERO);
    ReflectionTestUtils.setField(purchaseA, "id", 801L);

    RawMaterialPurchase purchaseB = new RawMaterialPurchase();
    purchaseB.setCompany(company);
    purchaseB.setSupplier(supplier);
    purchaseB.setTotalAmount(new BigDecimal("200.00"));
    purchaseB.setTaxAmount(BigDecimal.ZERO);
    ReflectionTestUtils.setField(purchaseB, "id", 802L);

    Account inventoryA = new Account();
    inventoryA.setCompany(company);
    inventoryA.setCode("INV-SETTLE-REPLAY-A");
    inventoryA.setType(AccountType.ASSET);
    ReflectionTestUtils.setField(inventoryA, "id", 31L);

    Account inventoryB = new Account();
    inventoryB.setCompany(company);
    inventoryB.setCode("INV-SETTLE-REPLAY-B");
    inventoryB.setType(AccountType.ASSET);
    ReflectionTestUtils.setField(inventoryB, "id", 32L);

    JournalEntry purchaseJournalA = new JournalEntry();
    ReflectionTestUtils.setField(purchaseJournalA, "id", 1801L);
    purchaseJournalA.setSupplier(supplier);
    purchaseJournalA.addLine(
            journalLine(
                purchaseJournalA,
                inventoryA,
                "Purchase replay A",
                new BigDecimal("100.00"),
                BigDecimal.ZERO));
    purchaseJournalA.addLine(
            journalLine(
                purchaseJournalA,
                payable,
                "Purchase replay A",
                BigDecimal.ZERO,
                new BigDecimal("100.00")));
    purchaseA.setJournalEntry(purchaseJournalA);

    JournalEntry purchaseJournalB = new JournalEntry();
    ReflectionTestUtils.setField(purchaseJournalB, "id", 1802L);
    purchaseJournalB.setSupplier(supplier);
    purchaseJournalB.addLine(
            journalLine(
                purchaseJournalB,
                inventoryB,
                "Purchase replay B",
                new BigDecimal("200.00"),
                BigDecimal.ZERO));
    purchaseJournalB.addLine(
            journalLine(
                purchaseJournalB,
                payable,
                "Purchase replay B",
                BigDecimal.ZERO,
                new BigDecimal("200.00")));
    purchaseB.setJournalEntry(purchaseJournalB);

    JournalEntry existingEntry = new JournalEntry();
    ReflectionTestUtils.setField(existingEntry, "id", 1951L);
    existingEntry.setSupplier(supplier);
    existingEntry.setReferenceNumber("AP-SETTLE-REPLAY-NETCASH-1");

    com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation existingRowA =
        new com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation();
    existingRowA.setCompany(company);
    existingRowA.setPartnerType(
        com.bigbrightpaints.erp.modules.accounting.domain.PartnerType.SUPPLIER);
    existingRowA.setSupplier(supplier);
    existingRowA.setPurchase(purchaseA);
    existingRowA.setJournalEntry(existingEntry);
    existingRowA.setSettlementDate(LocalDate.of(2024, 4, 9));
    existingRowA.setAllocationAmount(new BigDecimal("100.00"));
    existingRowA.setDiscountAmount(BigDecimal.ZERO);
    existingRowA.setWriteOffAmount(BigDecimal.ZERO);
    existingRowA.setFxDifferenceAmount(BigDecimal.ZERO);
    existingRowA.setIdempotencyKey("IDEMP-AP-SETTLE-REPLAY-NETCASH");

    com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation existingRowB =
        new com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation();
    existingRowB.setCompany(company);
    existingRowB.setPartnerType(
        com.bigbrightpaints.erp.modules.accounting.domain.PartnerType.SUPPLIER);
    existingRowB.setSupplier(supplier);
    existingRowB.setPurchase(purchaseB);
    existingRowB.setJournalEntry(existingEntry);
    existingRowB.setSettlementDate(LocalDate.of(2024, 4, 9));
    existingRowB.setAllocationAmount(new BigDecimal("200.00"));
    existingRowB.setDiscountAmount(BigDecimal.ZERO);
    existingRowB.setWriteOffAmount(BigDecimal.ZERO);
    existingRowB.setFxDifferenceAmount(BigDecimal.ZERO);
    existingRowB.setIdempotencyKey("IDEMP-AP-SETTLE-REPLAY-NETCASH");

    when(supplierRepository.lockByCompanyAndId(eq(company), eq(1L)))
        .thenReturn(Optional.of(supplier));
    when(companyEntityLookup.requireAccount(eq(company), eq(20L))).thenReturn(cash);
    when(companyEntityLookup.requireAccount(eq(company), eq(21L))).thenReturn(discount);
    when(journalReferenceMappingRepository.findAllByCompanyAndLegacyReferenceIgnoreCase(
            eq(company), eq("idemp-ap-settle-replay-netcash")))
        .thenReturn(List.of());
    when(journalReferenceResolver.findExistingEntry(eq(company), eq("AP-SETTLE-REPLAY-NETCASH-1")))
        .thenReturn(Optional.empty());
    when(journalReferenceMappingRepository.reserveReferenceMapping(
            eq(company.getId()),
            eq("idemp-ap-settle-replay-netcash"),
            eq("AP-SETTLE-REPLAY-NETCASH-1"),
            eq("SUPPLIER_SETTLEMENT"),
            any()))
        .thenReturn(1);
    when(settlementAllocationRepository
            .findByCompanyAndIdempotencyKeyIgnoreCaseOrderByCreatedAtAscIdAsc(
                eq(company), eq("IDEMP-AP-SETTLE-REPLAY-NETCASH")))
        .thenReturn(List.of(existingRowA, existingRowB));

    SupplierSettlementRequest request =
        new SupplierSettlementRequest(
            1L,
            20L,
            21L,
            null,
            null,
            null,
            LocalDate.of(2024, 4, 9),
            "AP-SETTLE-REPLAY-NETCASH-1",
            "Supplier settlement replay",
            "IDEMP-AP-SETTLE-REPLAY-NETCASH",
            Boolean.TRUE,
            List.of(
                new SettlementAllocationRequest(
                    null,
                    801L,
                    new BigDecimal("100.00"),
                    new BigDecimal("120.00"),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    null),
                new SettlementAllocationRequest(
                    null,
                    802L,
                    new BigDecimal("200.00"),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    null)));

    assertThatThrownBy(() -> accountingService.settleSupplierInvoices(request))
        .isInstanceOfSatisfying(
            ApplicationException.class,
            ex -> {
              assertPartnerReplayDetails(ex, "IDEMP-AP-SETTLE-REPLAY-NETCASH", "SUPPLIER", 1L);
              assertAllocationReplayDiagnostics(ex, 2, 2, "|applied=100", "|discount=120");
            })
        .hasMessageContaining("different settlement payload")
        .satisfies(ex -> assertThat(ex).hasMessageNotContaining("negative net cash contribution"));
  }

  @Test
  void settleDealerInvoices_appliesGrossAmountToInvoice() {
    AccountingService service = spy(accountingService);

    Dealer dealer = new Dealer();
    dealer.setName("Dealer");
    Account receivable = new Account();
    receivable.setCompany(company);
    receivable.setCode("AR");
    receivable.setType(AccountType.ASSET);
    ReflectionTestUtils.setField(receivable, "id", 10L);
    dealer.setReceivableAccount(receivable);
    ReflectionTestUtils.setField(dealer, "id", 1L);

    Account cash = new Account();
    cash.setCompany(company);
    cash.setCode("CASH");
    ReflectionTestUtils.setField(cash, "id", 20L);

    Account discount = new Account();
    discount.setCompany(company);
    discount.setCode("DISC");
    ReflectionTestUtils.setField(discount, "id", 21L);

    var invoice = new com.bigbrightpaints.erp.modules.invoice.domain.Invoice();
    invoice.setDealer(dealer);
    invoice.setCurrency("INR");
    invoice.setOutstandingAmount(new BigDecimal("1000.00"));
    invoice.setTotalAmount(new BigDecimal("1000.00"));
    ReflectionTestUtils.setField(invoice, "id", 5L);

    when(dealerRepository.lockByCompanyAndId(eq(company), eq(1L))).thenReturn(Optional.of(dealer));
    when(invoiceRepository.lockByCompanyAndId(eq(company), eq(5L)))
        .thenReturn(Optional.of(invoice));
    when(settlementAllocationRepository.findByCompanyAndIdempotencyKey(any(), any()))
        .thenReturn(List.of());
    when(companyEntityLookup.requireAccount(eq(company), eq(20L))).thenReturn(cash);
    when(companyEntityLookup.requireAccount(eq(company), eq(21L))).thenReturn(discount);

    JournalEntryDto journalEntryDto = stubEntry(44L);
    doReturn(journalEntryDto).when(journalEntryService).createJournalEntry(any());
    when(companyEntityLookup.requireJournalEntry(eq(company), eq(44L)))
        .thenReturn(new com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry());

    SettlementAllocationRequest allocation =
        new SettlementAllocationRequest(
            5L,
            null,
            new BigDecimal("500.00"),
            new BigDecimal("50.00"),
            BigDecimal.ZERO,
            null,
            "settle");

    DealerSettlementRequest request =
        new DealerSettlementRequest(
            1L,
            20L,
            21L,
            null,
            null,
            null,
            LocalDate.of(2024, 4, 9),
            "REF-AR-1",
            "Dealer settlement",
            "IDEMP-AR-1",
            Boolean.TRUE,
            List.of(allocation),
            null);

    service.settleDealerInvoices(request);

    ArgumentCaptor<BigDecimal> amountCaptor = ArgumentCaptor.forClass(BigDecimal.class);
    verify(invoiceSettlementPolicy)
        .applySettlement(eq(invoice), amountCaptor.capture(), eq("REF-AR-1-INV-5"));
    assertThat(amountCaptor.getValue()).isEqualByComparingTo("500.00");
  }

  @Test
  @Tag("critical")
  void settleDealerInvoices_idempotentReplayReturnsSameCashAmount() {
    AccountingService service = spy(accountingService);

    Dealer dealer = new Dealer();
    dealer.setName("Dealer");
    ReflectionTestUtils.setField(dealer, "id", 1L);

    Account receivable = new Account();
    receivable.setCompany(company);
    receivable.setCode("AR");
    receivable.setType(AccountType.ASSET);
    ReflectionTestUtils.setField(receivable, "id", 10L);
    dealer.setReceivableAccount(receivable);

    Account cash = new Account();
    cash.setCompany(company);
    cash.setCode("CASH");
    ReflectionTestUtils.setField(cash, "id", 20L);

    Account discount = new Account();
    discount.setCompany(company);
    discount.setCode("DISC");
    ReflectionTestUtils.setField(discount, "id", 21L);

    var invoice = new com.bigbrightpaints.erp.modules.invoice.domain.Invoice();
    invoice.setCompany(company);
    invoice.setDealer(dealer);
    invoice.setCurrency("INR");
    invoice.setOutstandingAmount(new BigDecimal("1000.00"));
    invoice.setTotalAmount(new BigDecimal("1000.00"));
    ReflectionTestUtils.setField(invoice, "id", 5L);

    var journalEntry = new JournalEntry();
    ReflectionTestUtils.setField(journalEntry, "id", 44L);
    journalEntry.setDealer(dealer);
    journalEntry.setEntryDate(LocalDate.of(2024, 4, 9));
    journalEntry.setReferenceNumber("DR-TEST-1");
    journalEntry.setMemo("Dealer settlement");
    journalEntry.addLine(
            journalLine(
                journalEntry,
                cash,
                "Dealer settlement",
                new BigDecimal("450.00"),
                BigDecimal.ZERO));
    journalEntry.addLine(
            journalLine(
                journalEntry,
                discount,
                "Settlement discount",
                new BigDecimal("50.00"),
                BigDecimal.ZERO));
    journalEntry.addLine(
            journalLine(
                journalEntry,
                receivable,
                "Dealer settlement",
                BigDecimal.ZERO,
                new BigDecimal("500.00")));

    when(dealerRepository.lockByCompanyAndId(eq(company), eq(1L))).thenReturn(Optional.of(dealer));
    when(invoiceRepository.lockByCompanyAndId(eq(company), eq(5L)))
        .thenReturn(Optional.of(invoice));
    when(companyEntityLookup.requireAccount(eq(company), eq(20L))).thenReturn(cash);
    when(companyEntityLookup.requireAccount(eq(company), eq(21L))).thenReturn(discount);
    when(companyEntityLookup.requireJournalEntry(eq(company), eq(44L))).thenReturn(journalEntry);

    List<com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation> savedRows =
        new ArrayList<>();
    AtomicInteger allocationLookupCount = new AtomicInteger(0);
    when(settlementAllocationRepository.findByCompanyAndIdempotencyKey(
            eq(company), eq("IDEMP-AR-CASH")))
        .thenAnswer(
            invocation ->
                allocationLookupCount.getAndIncrement() == 0 ? List.of() : List.copyOf(savedRows));
    when(settlementAllocationRepository.saveAll(any()))
        .thenAnswer(
            invocation -> {
              @SuppressWarnings("unchecked")
              List<com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation>
                  incoming =
                      (List<
                              com.bigbrightpaints.erp.modules.accounting.domain
                                  .PartnerSettlementAllocation>)
                          invocation.getArgument(0);
              savedRows.clear();
              savedRows.addAll(incoming);
              return incoming;
            });

    doReturn(stubEntry(44L))
        .when(journalEntryService)
        .createJournalEntry(any(JournalEntryRequest.class));

    SettlementAllocationRequest allocation =
        new SettlementAllocationRequest(
            5L,
            null,
            new BigDecimal("500.00"),
            new BigDecimal("50.00"),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            "settle");

    DealerSettlementRequest request =
        new DealerSettlementRequest(
            1L,
            20L,
            21L,
            null,
            null,
            null,
            LocalDate.of(2024, 4, 9),
            "DR-TEST-1",
            "Dealer settlement",
            "IDEMP-AR-CASH",
            Boolean.TRUE,
            List.of(allocation),
            null);

    PartnerSettlementResponse first = service.settleDealerInvoices(request);
    PartnerSettlementResponse replay = service.settleDealerInvoices(request);

    assertThat(first.cashAmount()).isEqualByComparingTo("450.00");
    assertThat(replay.cashAmount()).isEqualByComparingTo(first.cashAmount());
  }

  @Test
  @Tag("critical")
  void revalueInventory_distributesAcrossFinishedGoodBatches() {
    Account inventory = new Account();
    inventory.setCompany(company);
    inventory.setCode("INV");
    ReflectionTestUtils.setField(inventory, "id", 11L);

    Account reval = new Account();
    reval.setCompany(company);
    reval.setCode("REVAL");
    ReflectionTestUtils.setField(reval, "id", 12L);

    FinishedGood fg = new FinishedGood();
    fg.setCompany(company);
    fg.setProductCode("FG-1");
    fg.setValuationAccountId(11L);

    FinishedGoodBatch batch1 = new FinishedGoodBatch();
    batch1.setFinishedGood(fg);
    batch1.setBatchCode("B1");
    batch1.setQuantityTotal(new BigDecimal("10"));
    batch1.setQuantityAvailable(new BigDecimal("10"));
    batch1.setUnitCost(new BigDecimal("100.000000"));

    FinishedGoodBatch batch2 = new FinishedGoodBatch();
    batch2.setFinishedGood(fg);
    batch2.setBatchCode("B2");
    batch2.setQuantityTotal(new BigDecimal("10"));
    batch2.setQuantityAvailable(new BigDecimal("10"));
    batch2.setUnitCost(new BigDecimal("200.000000"));

    when(companyEntityLookup.requireAccount(eq(company), eq(11L))).thenReturn(inventory);
    when(companyEntityLookup.requireAccount(eq(company), eq(12L))).thenReturn(reval);
    when(journalEntryRepository.findByCompanyAndReferenceNumber(eq(company), any()))
        .thenReturn(Optional.empty());
    when(finishedGoodBatchRepository.findByCompanyAndValuationAccountId(eq(company), eq(11L)))
        .thenReturn(List.of(batch1, batch2));

    JournalEntryDto journalEntryDto = stubEntry(77L);
    doReturn(journalEntryDto)
        .when(inventoryAccountingService)
        .createJournalEntry(any(JournalEntryRequest.class));

    accountingService.revalueInventory(
        new InventoryRevaluationRequest(
            11L,
            12L,
            new BigDecimal("20.00"),
            "Reval",
            LocalDate.of(2024, 4, 9),
            "REVAL-1",
            null,
            Boolean.FALSE));

    assertThat(batch1.getUnitCost()).isEqualByComparingTo("101.000000");
    assertThat(batch2.getUnitCost()).isEqualByComparingTo("201.000000");
    verify(accountingPeriodService).requireOpenPeriod(company, LocalDate.of(2024, 4, 9));
  }

  @Test
  @Tag("critical")
  void revalueInventory_preservesNegativeDeltaIntoWriteDownPostingPath() {
    Account inventory = account(9131L, "INV-9131", AccountType.ASSET);
    Account reval = account(9132L, "REVAL-9132", AccountType.EXPENSE);
    when(companyEntityLookup.requireAccount(eq(company), eq(9131L))).thenReturn(inventory);
    when(companyEntityLookup.requireAccount(eq(company), eq(9132L))).thenReturn(reval);
    when(journalEntryRepository.findByCompanyAndReferenceNumber(eq(company), any()))
        .thenReturn(Optional.empty());
    when(finishedGoodBatchRepository.findByCompanyAndValuationAccountId(eq(company), eq(9131L)))
        .thenReturn(List.of());

    ArgumentCaptor<JournalEntryRequest> requestCaptor =
        ArgumentCaptor.forClass(JournalEntryRequest.class);
    doReturn(stubEntry(9130L))
        .when(inventoryAccountingService)
        .createJournalEntry(requestCaptor.capture());

    accountingService.revalueInventory(
        new InventoryRevaluationRequest(
            9131L,
            9132L,
            new BigDecimal("-50.00"),
            "Write-down",
            LocalDate.of(2024, 4, 10),
            "REVAL-WRITEDOWN",
            null,
            Boolean.FALSE));

    JournalEntryRequest journalRequest = requestCaptor.getValue();
    assertThat(journalRequest.referenceNumber()).isEqualTo("REVAL-WRITEDOWN");
    assertThat(journalRequest.lines()).hasSize(2);
    assertThat(journalRequest.lines().get(0).accountId()).isEqualTo(9131L);
    assertThat(journalRequest.lines().get(0).debit()).isEqualByComparingTo("0");
    assertThat(journalRequest.lines().get(0).credit()).isEqualByComparingTo("50.00");
    assertThat(journalRequest.lines().get(1).accountId()).isEqualTo(9132L);
    assertThat(journalRequest.lines().get(1).debit()).isEqualByComparingTo("50.00");
    assertThat(journalRequest.lines().get(1).credit()).isEqualByComparingTo("0");
    verify(accountingPeriodService).requireOpenPeriod(company, LocalDate.of(2024, 4, 10));
  }

  @Test
  @Tag("critical")
  void revalueInventory_rejectsClosedPeriodBeforePostingOrMutating() {
    Account inventory = account(9131L, "INV-9131", AccountType.ASSET);
    Account reval = account(9132L, "REVAL-9132", AccountType.EXPENSE);
    when(companyEntityLookup.requireAccount(eq(company), eq(9131L))).thenReturn(inventory);
    when(companyEntityLookup.requireAccount(eq(company), eq(9132L))).thenReturn(reval);
    when(journalEntryRepository.findByCompanyAndReferenceNumber(eq(company), any()))
        .thenReturn(Optional.empty());
    doThrow(new ApplicationException(ErrorCode.BUSINESS_INVALID_STATE, "locked/closed"))
        .when(accountingPeriodService)
        .requireOpenPeriod(company, LocalDate.of(2024, 4, 9));

    assertThatThrownBy(
            () ->
                accountingService.revalueInventory(
                    new InventoryRevaluationRequest(
                        9131L,
                        9132L,
                        new BigDecimal("20.00"),
                        "Reval",
                        LocalDate.of(2024, 4, 9),
                        "REVAL-CLOSED",
                        null,
                        Boolean.FALSE)))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("locked/closed");

    verify(inventoryAccountingService, never()).createJournalEntry(any(JournalEntryRequest.class));
    verify(finishedGoodBatchRepository, never()).findByCompanyAndValuationAccountId(any(), any());
  }

  @Test
  @Tag("critical")
  void recordLandedCost_rejectsClosedPeriodBeforePostingOrMutating() {
    Account inventory = account(9141L, "INV-9141", AccountType.ASSET);
    Account offset = account(9142L, "OFFSET-9142", AccountType.EXPENSE);
    Supplier supplier = supplier(914L, "Supplier Landed", account(9143L, "AP-9143", AccountType.LIABILITY));
    RawMaterialPurchase purchase =
        purchase(
            9140L,
            "PUR-9140",
            supplier,
            new BigDecimal("100.00"),
            new BigDecimal("100.00"),
            "POSTED");
    purchase.setJournalEntry(journalEntry(9144L, "PUR-9140-JE"));

    when(companyEntityLookup.requireRawMaterialPurchase(eq(company), eq(9140L))).thenReturn(purchase);
    when(companyEntityLookup.requireAccount(eq(company), eq(9141L))).thenReturn(inventory);
    when(companyEntityLookup.requireAccount(eq(company), eq(9142L))).thenReturn(offset);
    when(journalEntryRepository.findByCompanyAndReferenceNumber(eq(company), any()))
        .thenReturn(Optional.empty());
    doThrow(new ApplicationException(ErrorCode.BUSINESS_INVALID_STATE, "locked/closed"))
        .when(accountingPeriodService)
        .requireOpenPeriod(company, LocalDate.of(2024, 4, 9));

    assertThatThrownBy(
            () ->
                accountingService.recordLandedCost(
                    new LandedCostRequest(
                        9140L,
                        new BigDecimal("10.00"),
                        9141L,
                        9142L,
                        LocalDate.of(2024, 4, 9),
                        "Landed cost",
                        "LC-CLOSED",
                        null,
                        Boolean.FALSE)))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("locked/closed");

    verify(inventoryAccountingService, never()).createJournalEntry(any(JournalEntryRequest.class));
  }

  @Test
  void buildDealerSettlementIdempotencyKey_isStableAcrossEquivalentSplitPaymentOrder() {
    DealerSettlementRequest requestA =
        new DealerSettlementRequest(
            1L,
            null,
            null,
            null,
            null,
            null,
            LocalDate.of(2024, 5, 1),
            null,
            null,
            null,
            Boolean.FALSE,
            List.of(
                new SettlementAllocationRequest(
                    701L,
                    null,
                    new BigDecimal("100.00"),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    null)),
            List.of(
                new SettlementPaymentRequest(20L, new BigDecimal("60.00"), "CASH"),
                new SettlementPaymentRequest(20L, new BigDecimal("40.00"), "BANK")));

    DealerSettlementRequest requestB =
        new DealerSettlementRequest(
            1L,
            null,
            null,
            null,
            null,
            null,
            LocalDate.of(2024, 5, 1),
            null,
            null,
            null,
            Boolean.FALSE,
            List.of(
                new SettlementAllocationRequest(
                    701L,
                    null,
                    new BigDecimal("100.00"),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    null)),
            List.of(
                new SettlementPaymentRequest(20L, new BigDecimal("40.00"), "BANK"),
                new SettlementPaymentRequest(20L, new BigDecimal("60.00"), "CASH")));

    String keyA =
        ReflectionTestUtils.invokeMethod(
            accountingService, "buildDealerSettlementIdempotencyKey", requestA);
    String keyB =
        ReflectionTestUtils.invokeMethod(
            accountingService, "buildDealerSettlementIdempotencyKey", requestB);

    assertThat(keyA).isEqualTo(keyB);
  }

  @Test
  void resolveSupplierSettlementIdempotencyKey_supportsNullProvidedAndReferenceFallbacks() {
    String nullResolved =
        ReflectionTestUtils.invokeMethod(
            accountingService, "resolveSupplierSettlementIdempotencyKey", new Object[] {null});
    assertThat(nullResolved).isEqualTo("");

    SupplierSettlementRequest providedKeyRequest =
        new SupplierSettlementRequest(
            1L,
            20L,
            null,
            null,
            null,
            null,
            new BigDecimal("25.00"),
            null,
            LocalDate.of(2024, 5, 1),
            "  SUP-REF-1  ",
            "Supplier settlement",
            "  SUP-IDEMP-1  ",
            Boolean.FALSE,
            null);

    String providedResolved =
        ReflectionTestUtils.invokeMethod(
            accountingService, "resolveSupplierSettlementIdempotencyKey", providedKeyRequest);
    assertThat(providedResolved).isEqualTo("SUP-IDEMP-1");

    SupplierSettlementRequest referenceFallbackRequest =
        new SupplierSettlementRequest(
            1L,
            20L,
            null,
            null,
            null,
            null,
            new BigDecimal("25.00"),
            null,
            LocalDate.of(2024, 5, 1),
            "  SUP-REF-ONLY  ",
            "Supplier settlement",
            "   ",
            Boolean.FALSE,
            null);

    String referenceResolved =
        ReflectionTestUtils.invokeMethod(
            accountingService, "resolveSupplierSettlementIdempotencyKey", referenceFallbackRequest);
    assertThat(referenceResolved).isEqualTo("SUP-REF-ONLY");
  }

  @Test
  void resolveSupplierSettlementIdempotencyKey_buildsDeterministicFallbackWhenReferenceMissing() {
    SupplierSettlementRequest request =
        new SupplierSettlementRequest(
            1L,
            20L,
            null,
            null,
            null,
            null,
            new BigDecimal("25.00"),
            null,
            LocalDate.of(2024, 5, 1),
            null,
            "Supplier settlement",
            null,
            Boolean.FALSE,
            List.of(
                new SettlementAllocationRequest(
                    null,
                    801L,
                    new BigDecimal("25.00"),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    SettlementAllocationApplication.FUTURE_APPLICATION,
                    "Keep for next bill")));

    String resolved =
        ReflectionTestUtils.invokeMethod(
            accountingService, "resolveSupplierSettlementIdempotencyKey", request);

    assertThat(resolved).startsWith("SUPPLIER-SETTLEMENT-");
  }

  @Test
  void resolveSettlementApplicationType_defaultsRequestAllocationsFromCurrentState() {
    SettlementAllocationRequest documentAllocation =
        new SettlementAllocationRequest(
            701L,
            null,
            new BigDecimal("10.00"),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            null,
            "Invoice allocation");
    SettlementAllocationRequest onAccountAllocation =
        new SettlementAllocationRequest(
            null,
            null,
            new BigDecimal("10.00"),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            null,
            "On-account allocation");
    SettlementAllocationRequest futureAllocation =
        new SettlementAllocationRequest(
            null,
            null,
            new BigDecimal("10.00"),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            SettlementAllocationApplication.FUTURE_APPLICATION,
            "Future allocation");

    SettlementAllocationApplication nullType =
        ReflectionTestUtils.invokeMethod(
            accountingService, "resolveSettlementApplicationType", new Object[] {null});
    SettlementAllocationApplication documentType =
        ReflectionTestUtils.invokeMethod(
            accountingService, "resolveSettlementApplicationType", documentAllocation);
    SettlementAllocationApplication onAccountType =
        ReflectionTestUtils.invokeMethod(
            accountingService, "resolveSettlementApplicationType", onAccountAllocation);
    SettlementAllocationApplication futureType =
        ReflectionTestUtils.invokeMethod(
            accountingService, "resolveSettlementApplicationType", futureAllocation);
    assertThat(nullType).isEqualTo(SettlementAllocationApplication.DOCUMENT);
    assertThat(documentType).isEqualTo(SettlementAllocationApplication.DOCUMENT);
    assertThat(onAccountType).isEqualTo(SettlementAllocationApplication.ON_ACCOUNT);
    assertThat(futureType).isEqualTo(SettlementAllocationApplication.FUTURE_APPLICATION);
  }

  @Test
  void replayAllocations_returnsEmptyForBlankOrMissingReplayKeys() {
    @SuppressWarnings("unchecked")
    List<SettlementAllocationRequest> blankReplay =
        ReflectionTestUtils.invokeMethod(accountingService, "replayAllocations", company, "   ");
    assertThat(blankReplay).isEqualTo(List.of());

    when(settlementAllocationRepository
            .findByCompanyAndIdempotencyKeyIgnoreCaseOrderByCreatedAtAscIdAsc(
                eq(company), eq("SUP-REPLAY-EMPTY")))
        .thenReturn(List.of());
    when(settlementAllocationRepository.findByCompanyAndIdempotencyKey(
            eq(company), eq("SUP-REPLAY-EMPTY")))
        .thenReturn(List.of());

    @SuppressWarnings("unchecked")
    List<SettlementAllocationRequest> missingReplay =
        ReflectionTestUtils.invokeMethod(
            accountingService, "replayAllocations", company, "SUP-REPLAY-EMPTY");
    assertThat(missingReplay).isEqualTo(List.of());
  }

  @Test
  void replayAllocations_decodesCurrentSettlementApplicationState() {
    Invoice invoice = new Invoice();
    ReflectionTestUtils.setField(invoice, "id", 701L);

    PartnerSettlementAllocation documentAllocation = new PartnerSettlementAllocation();
    documentAllocation.setCompany(company);
    documentAllocation.setInvoice(invoice);
    documentAllocation.setAllocationAmount(new BigDecimal("40.00"));
    documentAllocation.setDiscountAmount(BigDecimal.ZERO);
    documentAllocation.setWriteOffAmount(BigDecimal.ZERO);
    documentAllocation.setFxDifferenceAmount(BigDecimal.ZERO);
    documentAllocation.setMemo("  Invoice replay  ");

    PartnerSettlementAllocation onAccountAllocation = new PartnerSettlementAllocation();
    onAccountAllocation.setCompany(company);
    onAccountAllocation.setAllocationAmount(new BigDecimal("15.00"));
    onAccountAllocation.setDiscountAmount(BigDecimal.ZERO);
    onAccountAllocation.setWriteOffAmount(BigDecimal.ZERO);
    onAccountAllocation.setFxDifferenceAmount(BigDecimal.ZERO);
    onAccountAllocation.setMemo("[SETTLEMENT-APPLICATION:ON_ACCOUNT]   Carry forward ");

    PartnerSettlementAllocation malformedAllocation = new PartnerSettlementAllocation();
    malformedAllocation.setCompany(company);
    malformedAllocation.setAllocationAmount(new BigDecimal("5.00"));
    malformedAllocation.setDiscountAmount(BigDecimal.ZERO);
    malformedAllocation.setWriteOffAmount(BigDecimal.ZERO);
    malformedAllocation.setFxDifferenceAmount(BigDecimal.ZERO);
    malformedAllocation.setMemo("[SETTLEMENT-APPLICATION:]  malformed ");

    when(settlementAllocationRepository
            .findByCompanyAndIdempotencyKeyIgnoreCaseOrderByCreatedAtAscIdAsc(
                eq(company), eq("SUP-REPLAY-1")))
        .thenReturn(List.of(documentAllocation, onAccountAllocation, malformedAllocation));

    @SuppressWarnings("unchecked")
    List<SettlementAllocationRequest> replayed =
        ReflectionTestUtils.invokeMethod(
            accountingService, "replayAllocations", company, "SUP-REPLAY-1");

    assertThat(replayed).hasSize(3);
    assertThat(replayed.get(0).invoiceId()).isEqualTo(701L);
    assertThat(replayed.get(0).applicationType())
        .isEqualTo(SettlementAllocationApplication.DOCUMENT);
    assertThat(replayed.get(0).memo()).isEqualTo("Invoice replay");
    assertThat(replayed.get(1).invoiceId()).isNull();
    assertThat(replayed.get(1).applicationType())
        .isEqualTo(SettlementAllocationApplication.ON_ACCOUNT);
    assertThat(replayed.get(1).memo()).isEqualTo("Carry forward");
    assertThat(replayed.get(2).applicationType())
        .isEqualTo(SettlementAllocationApplication.ON_ACCOUNT);
    assertThat(replayed.get(2).memo()).isEqualTo("[SETTLEMENT-APPLICATION:]  malformed");
  }

  @Test
  void resolveDealerSettlementIdempotencyKey_prefersLegacyAutoKeyWhenReplayExists() {
    DealerSettlementRequest request =
        new DealerSettlementRequest(
            1L,
            null,
            null,
            null,
            null,
            null,
            LocalDate.of(2024, 5, 1),
            null,
            null,
            null,
            Boolean.FALSE,
            List.of(
                new SettlementAllocationRequest(
                    701L,
                    null,
                    new BigDecimal("100.00"),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    null)),
            List.of(
                new SettlementPaymentRequest(20L, new BigDecimal("60.00"), "CASH"),
                new SettlementPaymentRequest(20L, new BigDecimal("40.00"), "BANK")));

    String canonicalKey =
        ReflectionTestUtils.invokeMethod(
            accountingService, "buildDealerSettlementIdempotencyKey", request);
    String legacyKey =
        ReflectionTestUtils.invokeMethod(
            accountingService, "buildLegacyDealerSettlementIdempotencyKey", request);
    assertThat(canonicalKey).isNotEqualTo(legacyKey);

    Dealer dealer = new Dealer();
    ReflectionTestUtils.setField(dealer, "id", 1L);

    var invoice = new com.bigbrightpaints.erp.modules.invoice.domain.Invoice();
    invoice.setCompany(company);
    invoice.setDealer(dealer);
    ReflectionTestUtils.setField(invoice, "id", 701L);

    var existing = new PartnerSettlementAllocation();
    existing.setCompany(company);
    existing.setDealer(dealer);
    existing.setInvoice(invoice);
    existing.setAllocationAmount(new BigDecimal("100.00"));
    existing.setDiscountAmount(BigDecimal.ZERO);
    existing.setWriteOffAmount(BigDecimal.ZERO);
    existing.setFxDifferenceAmount(BigDecimal.ZERO);
    existing.setIdempotencyKey(legacyKey);

    Account settlementCashAccount = new Account();
    settlementCashAccount.setCompany(company);
    settlementCashAccount.setType(AccountType.ASSET);
    settlementCashAccount.setCode("CASH");
    settlementCashAccount.setName("Cash");
    ReflectionTestUtils.setField(settlementCashAccount, "id", 20L);

    JournalEntry existingEntry = new JournalEntry();
    existingEntry.addLine(
            journalLine(
                existingEntry,
                settlementCashAccount,
                "payment-cash",
                new BigDecimal("60.00"),
                BigDecimal.ZERO));
    existingEntry.addLine(
            journalLine(
                existingEntry,
                settlementCashAccount,
                "payment-bank",
                new BigDecimal("40.00"),
                BigDecimal.ZERO));
    existing.setJournalEntry(existingEntry);

    when(invoiceRepository.findByCompanyAndId(eq(company), eq(701L)))
        .thenReturn(Optional.of(invoice));
    when(settlementAllocationRepository.findByCompanyAndInvoiceOrderByCreatedAtDesc(
            eq(company), eq(invoice)))
        .thenReturn(List.of(existing));
    when(settlementAllocationRepository
            .findByCompanyAndIdempotencyKeyIgnoreCaseOrderByCreatedAtAscIdAsc(
                eq(company), eq(legacyKey)))
        .thenReturn(List.of(existing));

    String resolved =
        ReflectionTestUtils.invokeMethod(
            accountingService, "resolveDealerSettlementIdempotencyKey", company, request);

    assertThat(resolved).isEqualTo(legacyKey);
  }

  @Test
  void resolveDealerSettlementIdempotencyKey_matchesLegacyReplayAcrossEquivalentPaymentReorder() {
    DealerSettlementRequest originalOrderRequest =
        new DealerSettlementRequest(
            1L,
            null,
            null,
            null,
            null,
            null,
            LocalDate.of(2024, 5, 1),
            null,
            null,
            null,
            Boolean.FALSE,
            List.of(
                new SettlementAllocationRequest(
                    701L,
                    null,
                    new BigDecimal("100.00"),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    null)),
            List.of(
                new SettlementPaymentRequest(20L, new BigDecimal("60.00"), "CASH"),
                new SettlementPaymentRequest(20L, new BigDecimal("40.00"), "BANK")));

    DealerSettlementRequest replayOrderRequest =
        new DealerSettlementRequest(
            1L,
            null,
            null,
            null,
            null,
            null,
            LocalDate.of(2024, 5, 1),
            null,
            null,
            null,
            Boolean.FALSE,
            List.of(
                new SettlementAllocationRequest(
                    701L,
                    null,
                    new BigDecimal("100.00"),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    null)),
            List.of(
                new SettlementPaymentRequest(20L, new BigDecimal("40.00"), "BANK"),
                new SettlementPaymentRequest(20L, new BigDecimal("60.00"), "CASH")));

    String canonicalReplayKey =
        ReflectionTestUtils.invokeMethod(
            accountingService, "buildDealerSettlementIdempotencyKey", replayOrderRequest);
    String legacyOriginalKey =
        ReflectionTestUtils.invokeMethod(
            accountingService, "buildLegacyDealerSettlementIdempotencyKey", originalOrderRequest);
    assertThat(canonicalReplayKey).isNotEqualTo(legacyOriginalKey);

    Dealer dealer = new Dealer();
    ReflectionTestUtils.setField(dealer, "id", 1L);

    var invoice = new com.bigbrightpaints.erp.modules.invoice.domain.Invoice();
    invoice.setCompany(company);
    invoice.setDealer(dealer);
    ReflectionTestUtils.setField(invoice, "id", 701L);

    var existing = new PartnerSettlementAllocation();
    existing.setCompany(company);
    existing.setDealer(dealer);
    existing.setInvoice(invoice);
    existing.setAllocationAmount(new BigDecimal("100.00"));
    existing.setDiscountAmount(BigDecimal.ZERO);
    existing.setWriteOffAmount(BigDecimal.ZERO);
    existing.setFxDifferenceAmount(BigDecimal.ZERO);
    existing.setIdempotencyKey(legacyOriginalKey);

    Account settlementCashAccount = new Account();
    settlementCashAccount.setCompany(company);
    settlementCashAccount.setType(AccountType.ASSET);
    settlementCashAccount.setCode("CASH");
    settlementCashAccount.setName("Cash");
    ReflectionTestUtils.setField(settlementCashAccount, "id", 20L);

    Account adjustmentAssetAccount = new Account();
    adjustmentAssetAccount.setCompany(company);
    adjustmentAssetAccount.setType(AccountType.ASSET);
    adjustmentAssetAccount.setCode("ADJ-ASSET");
    adjustmentAssetAccount.setName("Adjustment Asset");
    ReflectionTestUtils.setField(adjustmentAssetAccount, "id", 99L);

    JournalEntry existingEntry = new JournalEntry();
    existingEntry.addLine(
            journalLine(
                existingEntry,
                settlementCashAccount,
                "payment-cash",
                new BigDecimal("60.00"),
                BigDecimal.ZERO));
    existingEntry.addLine(
            journalLine(
                existingEntry,
                settlementCashAccount,
                "payment-bank",
                new BigDecimal("40.00"),
                BigDecimal.ZERO));
    existingEntry.addLine(
            journalLine(
                existingEntry,
                adjustmentAssetAccount,
                "non-payment-adjustment",
                new BigDecimal("5.00"),
                BigDecimal.ZERO));
    existing.setJournalEntry(existingEntry);

    when(invoiceRepository.findByCompanyAndId(eq(company), eq(701L)))
        .thenReturn(Optional.of(invoice));
    when(settlementAllocationRepository.findByCompanyAndInvoiceOrderByCreatedAtDesc(
            eq(company), eq(invoice)))
        .thenReturn(List.of(existing));
    when(settlementAllocationRepository
            .findByCompanyAndIdempotencyKeyIgnoreCaseOrderByCreatedAtAscIdAsc(
                eq(company), eq(legacyOriginalKey)))
        .thenReturn(List.of(existing));

    String resolved =
        ReflectionTestUtils.invokeMethod(
            accountingService,
            "resolveDealerSettlementIdempotencyKey",
            company,
            replayOrderRequest);

    assertThat(resolved).isEqualTo(legacyOriginalKey);
  }

  @Test
  void resolveDealerSettlementIdempotencyKey_rejectsLegacyReplayWhenPaymentSignaturesDiffer() {
    DealerSettlementRequest originalOrderRequest =
        new DealerSettlementRequest(
            1L,
            null,
            null,
            null,
            null,
            null,
            LocalDate.of(2024, 5, 1),
            null,
            null,
            null,
            Boolean.FALSE,
            List.of(
                new SettlementAllocationRequest(
                    701L,
                    null,
                    new BigDecimal("100.00"),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    null)),
            List.of(
                new SettlementPaymentRequest(20L, new BigDecimal("60.00"), "CASH"),
                new SettlementPaymentRequest(20L, new BigDecimal("40.00"), "BANK")));

    DealerSettlementRequest replayOrderRequest =
        new DealerSettlementRequest(
            1L,
            null,
            null,
            null,
            null,
            null,
            LocalDate.of(2024, 5, 1),
            null,
            null,
            null,
            Boolean.FALSE,
            List.of(
                new SettlementAllocationRequest(
                    701L,
                    null,
                    new BigDecimal("100.00"),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    null)),
            List.of(new SettlementPaymentRequest(20L, new BigDecimal("100.00"), "CASH")));

    String canonicalReplayKey =
        ReflectionTestUtils.invokeMethod(
            accountingService, "buildDealerSettlementIdempotencyKey", replayOrderRequest);
    String legacyOriginalKey =
        ReflectionTestUtils.invokeMethod(
            accountingService, "buildLegacyDealerSettlementIdempotencyKey", originalOrderRequest);
    assertThat(canonicalReplayKey).isNotEqualTo(legacyOriginalKey);

    Dealer dealer = new Dealer();
    ReflectionTestUtils.setField(dealer, "id", 1L);

    var invoice = new com.bigbrightpaints.erp.modules.invoice.domain.Invoice();
    invoice.setCompany(company);
    invoice.setDealer(dealer);
    ReflectionTestUtils.setField(invoice, "id", 701L);

    var existing = new PartnerSettlementAllocation();
    existing.setCompany(company);
    existing.setDealer(dealer);
    existing.setInvoice(invoice);
    existing.setAllocationAmount(new BigDecimal("100.00"));
    existing.setDiscountAmount(BigDecimal.ZERO);
    existing.setWriteOffAmount(BigDecimal.ZERO);
    existing.setFxDifferenceAmount(BigDecimal.ZERO);
    existing.setIdempotencyKey(legacyOriginalKey);

    Account settlementCashAccount = new Account();
    settlementCashAccount.setCompany(company);
    settlementCashAccount.setType(AccountType.ASSET);
    settlementCashAccount.setCode("CASH");
    settlementCashAccount.setName("Cash");
    ReflectionTestUtils.setField(settlementCashAccount, "id", 20L);

    JournalEntry existingEntry = new JournalEntry();
    existingEntry.addLine(
            journalLine(
                existingEntry,
                settlementCashAccount,
                "payment-cash",
                new BigDecimal("60.00"),
                BigDecimal.ZERO));
    existingEntry.addLine(
            journalLine(
                existingEntry,
                settlementCashAccount,
                "payment-bank",
                new BigDecimal("40.00"),
                BigDecimal.ZERO));
    existing.setJournalEntry(existingEntry);

    when(invoiceRepository.findByCompanyAndId(eq(company), eq(701L)))
        .thenReturn(Optional.of(invoice));
    when(settlementAllocationRepository.findByCompanyAndInvoiceOrderByCreatedAtDesc(
            eq(company), eq(invoice)))
        .thenReturn(List.of(existing));
    when(settlementAllocationRepository
            .findByCompanyAndIdempotencyKeyIgnoreCaseOrderByCreatedAtAscIdAsc(
                eq(company), eq(legacyOriginalKey)))
        .thenReturn(List.of(existing));

    String resolved =
        ReflectionTestUtils.invokeMethod(
            accountingService,
            "resolveDealerSettlementIdempotencyKey",
            company,
            replayOrderRequest);

    assertThat(resolved).isEqualTo(canonicalReplayKey);
  }

  @Test
  void buildDealerSettlementIdempotencyKey_distinguishesImplicitCashAccountWhenPaymentsOmitted() {
    DealerSettlementRequest cashRequest =
        new DealerSettlementRequest(
            1L,
            20L,
            null,
            null,
            null,
            null,
            LocalDate.of(2024, 5, 1),
            null,
            null,
            null,
            Boolean.FALSE,
            List.of(
                new SettlementAllocationRequest(
                    701L,
                    null,
                    new BigDecimal("100.00"),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    null)),
            null);
    DealerSettlementRequest bankRequest =
        new DealerSettlementRequest(
            1L,
            21L,
            null,
            null,
            null,
            null,
            LocalDate.of(2024, 5, 1),
            null,
            null,
            null,
            Boolean.FALSE,
            List.of(
                new SettlementAllocationRequest(
                    701L,
                    null,
                    new BigDecimal("100.00"),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    null)),
            null);

    String canonicalCash =
        ReflectionTestUtils.invokeMethod(
            accountingService, "buildDealerSettlementIdempotencyKey", cashRequest);
    String canonicalBank =
        ReflectionTestUtils.invokeMethod(
            accountingService, "buildDealerSettlementIdempotencyKey", bankRequest);
    String legacyCash =
        ReflectionTestUtils.invokeMethod(
            accountingService, "buildLegacyDealerSettlementIdempotencyKey", cashRequest);
    String legacyBank =
        ReflectionTestUtils.invokeMethod(
            accountingService, "buildLegacyDealerSettlementIdempotencyKey", bankRequest);

    assertThat(canonicalCash).isNotEqualTo(canonicalBank);
    assertThat(legacyCash).isEqualTo(legacyBank);
  }

  @Test
  void resolveDealerSettlementIdempotencyKey_rejectsLegacyReplayWhenImplicitCashAccountDiffers() {
    DealerSettlementRequest originalOrderRequest =
        new DealerSettlementRequest(
            1L,
            20L,
            null,
            null,
            null,
            null,
            LocalDate.of(2024, 5, 1),
            null,
            null,
            null,
            Boolean.FALSE,
            List.of(
                new SettlementAllocationRequest(
                    701L,
                    null,
                    new BigDecimal("100.00"),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    null)),
            null);
    DealerSettlementRequest replayOrderRequest =
        new DealerSettlementRequest(
            1L,
            21L,
            null,
            null,
            null,
            null,
            LocalDate.of(2024, 5, 1),
            null,
            null,
            null,
            Boolean.FALSE,
            List.of(
                new SettlementAllocationRequest(
                    701L,
                    null,
                    new BigDecimal("100.00"),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    null)),
            null);

    String canonicalReplayKey =
        ReflectionTestUtils.invokeMethod(
            accountingService, "buildDealerSettlementIdempotencyKey", replayOrderRequest);
    String legacyOriginalKey =
        ReflectionTestUtils.invokeMethod(
            accountingService, "buildLegacyDealerSettlementIdempotencyKey", originalOrderRequest);
    assertThat(canonicalReplayKey).isNotEqualTo(legacyOriginalKey);

    Dealer dealer = new Dealer();
    ReflectionTestUtils.setField(dealer, "id", 1L);

    var invoice = new com.bigbrightpaints.erp.modules.invoice.domain.Invoice();
    invoice.setCompany(company);
    invoice.setDealer(dealer);
    ReflectionTestUtils.setField(invoice, "id", 701L);

    var existing = new PartnerSettlementAllocation();
    existing.setCompany(company);
    existing.setDealer(dealer);
    existing.setInvoice(invoice);
    existing.setAllocationAmount(new BigDecimal("100.00"));
    existing.setDiscountAmount(BigDecimal.ZERO);
    existing.setWriteOffAmount(BigDecimal.ZERO);
    existing.setFxDifferenceAmount(BigDecimal.ZERO);
    existing.setIdempotencyKey(legacyOriginalKey);

    Account legacyCashAccount = new Account();
    legacyCashAccount.setCompany(company);
    legacyCashAccount.setType(AccountType.ASSET);
    legacyCashAccount.setCode("CASH");
    legacyCashAccount.setName("Cash");
    ReflectionTestUtils.setField(legacyCashAccount, "id", 20L);

    JournalEntry existingEntry = new JournalEntry();
    existingEntry.addLine(
            journalLine(
                existingEntry,
                legacyCashAccount,
                "legacy-cash-payment",
                new BigDecimal("100.00"),
                BigDecimal.ZERO));
    existing.setJournalEntry(existingEntry);

    when(invoiceRepository.findByCompanyAndId(eq(company), eq(701L)))
        .thenReturn(Optional.of(invoice));
    when(settlementAllocationRepository.findByCompanyAndInvoiceOrderByCreatedAtDesc(
            eq(company), eq(invoice)))
        .thenReturn(List.of(existing));
    when(settlementAllocationRepository
            .findByCompanyAndIdempotencyKeyIgnoreCaseOrderByCreatedAtAscIdAsc(
                eq(company), eq(legacyOriginalKey)))
        .thenReturn(List.of(existing));

    String resolved =
        ReflectionTestUtils.invokeMethod(
            accountingService,
            "resolveDealerSettlementIdempotencyKey",
            company,
            replayOrderRequest);

    assertThat(resolved).isEqualTo(canonicalReplayKey);
  }

  @Test
  void
      resolveDealerSettlementIdempotencyKey_acceptsLegacyReplayWhenDiscountDebitSharesCashAccount() {
    DealerSettlementRequest replayRequest =
        new DealerSettlementRequest(
            1L,
            20L,
            20L,
            null,
            null,
            null,
            LocalDate.of(2024, 5, 1),
            null,
            null,
            null,
            Boolean.FALSE,
            List.of(
                new SettlementAllocationRequest(
                    701L,
                    null,
                    new BigDecimal("100.00"),
                    new BigDecimal("40.00"),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    null)),
            null);

    String canonicalReplayKey =
        ReflectionTestUtils.invokeMethod(
            accountingService, "buildDealerSettlementIdempotencyKey", replayRequest);
    String legacyKey =
        ReflectionTestUtils.invokeMethod(
            accountingService, "buildLegacyDealerSettlementIdempotencyKey", replayRequest);
    assertThat(canonicalReplayKey).isNotEqualTo(legacyKey);

    Dealer dealer = new Dealer();
    ReflectionTestUtils.setField(dealer, "id", 1L);

    var invoice = new com.bigbrightpaints.erp.modules.invoice.domain.Invoice();
    invoice.setCompany(company);
    invoice.setDealer(dealer);
    ReflectionTestUtils.setField(invoice, "id", 701L);

    var existing = new PartnerSettlementAllocation();
    existing.setCompany(company);
    existing.setDealer(dealer);
    existing.setInvoice(invoice);
    existing.setAllocationAmount(new BigDecimal("100.00"));
    existing.setDiscountAmount(new BigDecimal("40.00"));
    existing.setWriteOffAmount(BigDecimal.ZERO);
    existing.setFxDifferenceAmount(BigDecimal.ZERO);
    existing.setIdempotencyKey(legacyKey);

    Account cashAccount = new Account();
    cashAccount.setCompany(company);
    cashAccount.setType(AccountType.ASSET);
    cashAccount.setCode("CASH");
    cashAccount.setName("Cash");
    ReflectionTestUtils.setField(cashAccount, "id", 20L);

    Account receivableAccount = new Account();
    receivableAccount.setCompany(company);
    receivableAccount.setType(AccountType.ASSET);
    receivableAccount.setCode("AR-DEALER");
    receivableAccount.setName("Dealer Receivable");
    ReflectionTestUtils.setField(receivableAccount, "id", 10L);

    JournalEntry existingEntry = new JournalEntry();
    existingEntry.addLine(
            journalLine(
                existingEntry,
                cashAccount,
                "Dealer settlement",
                new BigDecimal("60.00"),
                BigDecimal.ZERO));
    existingEntry.addLine(
            journalLine(
                existingEntry,
                cashAccount,
                "Settlement discount",
                new BigDecimal("40.00"),
                BigDecimal.ZERO));
    existingEntry.addLine(
            journalLine(
                existingEntry,
                receivableAccount,
                "Dealer settlement",
                BigDecimal.ZERO,
                new BigDecimal("100.00")));
    existing.setJournalEntry(existingEntry);

    when(invoiceRepository.findByCompanyAndId(eq(company), eq(701L)))
        .thenReturn(Optional.of(invoice));
    when(settlementAllocationRepository.findByCompanyAndInvoiceOrderByCreatedAtDesc(
            eq(company), eq(invoice)))
        .thenReturn(List.of(existing));
    when(settlementAllocationRepository
            .findByCompanyAndIdempotencyKeyIgnoreCaseOrderByCreatedAtAscIdAsc(
                eq(company), eq(legacyKey)))
        .thenReturn(List.of(existing));

    String resolved =
        ReflectionTestUtils.invokeMethod(
            accountingService, "resolveDealerSettlementIdempotencyKey", company, replayRequest);

    assertThat(resolved).isEqualTo(legacyKey);
  }

  @Test
  void
      resolveDealerSettlementIdempotencyKey_acceptsLegacyReplayWhenDiscountDebitSplitsAcrossLines() {
    DealerSettlementRequest replayRequest =
        new DealerSettlementRequest(
            1L,
            20L,
            20L,
            null,
            null,
            null,
            LocalDate.of(2024, 5, 1),
            null,
            null,
            null,
            Boolean.FALSE,
            List.of(
                new SettlementAllocationRequest(
                    701L,
                    null,
                    new BigDecimal("100.00"),
                    new BigDecimal("40.00"),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    null)),
            null);

    String canonicalReplayKey =
        ReflectionTestUtils.invokeMethod(
            accountingService, "buildDealerSettlementIdempotencyKey", replayRequest);
    String legacyKey =
        ReflectionTestUtils.invokeMethod(
            accountingService, "buildLegacyDealerSettlementIdempotencyKey", replayRequest);
    assertThat(canonicalReplayKey).isNotEqualTo(legacyKey);

    Dealer dealer = new Dealer();
    ReflectionTestUtils.setField(dealer, "id", 1L);

    var invoice = new com.bigbrightpaints.erp.modules.invoice.domain.Invoice();
    invoice.setCompany(company);
    invoice.setDealer(dealer);
    ReflectionTestUtils.setField(invoice, "id", 701L);

    var existing = new PartnerSettlementAllocation();
    existing.setCompany(company);
    existing.setDealer(dealer);
    existing.setInvoice(invoice);
    existing.setAllocationAmount(new BigDecimal("100.00"));
    existing.setDiscountAmount(new BigDecimal("40.00"));
    existing.setWriteOffAmount(BigDecimal.ZERO);
    existing.setFxDifferenceAmount(BigDecimal.ZERO);
    existing.setIdempotencyKey(legacyKey);

    Account cashAccount = new Account();
    cashAccount.setCompany(company);
    cashAccount.setType(AccountType.ASSET);
    cashAccount.setCode("CASH");
    cashAccount.setName("Cash");
    ReflectionTestUtils.setField(cashAccount, "id", 20L);

    Account receivableAccount = new Account();
    receivableAccount.setCompany(company);
    receivableAccount.setType(AccountType.ASSET);
    receivableAccount.setCode("AR-DEALER");
    receivableAccount.setName("Dealer Receivable");
    ReflectionTestUtils.setField(receivableAccount, "id", 10L);

    JournalEntry existingEntry = new JournalEntry();
    existingEntry.addLine(
            journalLine(
                existingEntry,
                cashAccount,
                "Dealer settlement",
                new BigDecimal("60.00"),
                BigDecimal.ZERO));
    existingEntry.addLine(
            journalLine(
                existingEntry,
                cashAccount,
                "Settlement discount",
                new BigDecimal("20.00"),
                BigDecimal.ZERO));
    existingEntry.addLine(
            journalLine(
                existingEntry,
                cashAccount,
                "Settlement discount",
                new BigDecimal("20.00"),
                BigDecimal.ZERO));
    existingEntry.addLine(
            journalLine(
                existingEntry,
                receivableAccount,
                "Dealer settlement",
                BigDecimal.ZERO,
                new BigDecimal("100.00")));
    existing.setJournalEntry(existingEntry);

    when(invoiceRepository.findByCompanyAndId(eq(company), eq(701L)))
        .thenReturn(Optional.of(invoice));
    when(settlementAllocationRepository.findByCompanyAndInvoiceOrderByCreatedAtDesc(
            eq(company), eq(invoice)))
        .thenReturn(List.of(existing));
    when(settlementAllocationRepository
            .findByCompanyAndIdempotencyKeyIgnoreCaseOrderByCreatedAtAscIdAsc(
                eq(company), eq(legacyKey)))
        .thenReturn(List.of(existing));

    String resolved =
        ReflectionTestUtils.invokeMethod(
            accountingService, "resolveDealerSettlementIdempotencyKey", company, replayRequest);

    assertThat(resolved).isEqualTo(legacyKey);
  }

  @Test
  void
      resolveDealerSettlementIdempotencyKey_acceptsLegacyReplayWhenDiscountDebitOnNonPaymentAssetAccount() {
    DealerSettlementRequest replayRequest =
        new DealerSettlementRequest(
            1L,
            20L,
            30L,
            null,
            null,
            null,
            LocalDate.of(2024, 5, 1),
            null,
            null,
            null,
            Boolean.FALSE,
            List.of(
                new SettlementAllocationRequest(
                    701L,
                    null,
                    new BigDecimal("120.00"),
                    new BigDecimal("40.00"),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    null)),
            null);

    String canonicalReplayKey =
        ReflectionTestUtils.invokeMethod(
            accountingService, "buildDealerSettlementIdempotencyKey", replayRequest);
    String legacyKey =
        ReflectionTestUtils.invokeMethod(
            accountingService, "buildLegacyDealerSettlementIdempotencyKey", replayRequest);
    assertThat(canonicalReplayKey).isNotEqualTo(legacyKey);

    Dealer dealer = new Dealer();
    ReflectionTestUtils.setField(dealer, "id", 1L);

    var invoice = new com.bigbrightpaints.erp.modules.invoice.domain.Invoice();
    invoice.setCompany(company);
    invoice.setDealer(dealer);
    ReflectionTestUtils.setField(invoice, "id", 701L);

    var existing = new PartnerSettlementAllocation();
    existing.setCompany(company);
    existing.setDealer(dealer);
    existing.setInvoice(invoice);
    existing.setAllocationAmount(new BigDecimal("120.00"));
    existing.setDiscountAmount(new BigDecimal("40.00"));
    existing.setWriteOffAmount(BigDecimal.ZERO);
    existing.setFxDifferenceAmount(BigDecimal.ZERO);
    existing.setIdempotencyKey(legacyKey);

    Account cashAccount = new Account();
    cashAccount.setCompany(company);
    cashAccount.setType(AccountType.ASSET);
    cashAccount.setCode("BANK-20");
    cashAccount.setName("Bank 20");
    ReflectionTestUtils.setField(cashAccount, "id", 20L);

    Account discountAssetAccount = new Account();
    discountAssetAccount.setCompany(company);
    discountAssetAccount.setType(AccountType.ASSET);
    discountAssetAccount.setCode("ASSET-ADJ");
    discountAssetAccount.setName("Adjustment Asset");
    ReflectionTestUtils.setField(discountAssetAccount, "id", 30L);

    Account receivableAccount = new Account();
    receivableAccount.setCompany(company);
    receivableAccount.setType(AccountType.ASSET);
    receivableAccount.setCode("AR-DEALER");
    receivableAccount.setName("Dealer Receivable");
    ReflectionTestUtils.setField(receivableAccount, "id", 10L);

    JournalEntry existingEntry = new JournalEntry();
    existingEntry.addLine(
            journalLine(
                existingEntry,
                cashAccount,
                "Dealer settlement",
                new BigDecimal("80.00"),
                BigDecimal.ZERO));
    existingEntry.addLine(
            journalLine(
                existingEntry,
                discountAssetAccount,
                "Settlement discount",
                new BigDecimal("40.00"),
                BigDecimal.ZERO));
    existingEntry.addLine(
            journalLine(
                existingEntry,
                receivableAccount,
                "Dealer settlement",
                BigDecimal.ZERO,
                new BigDecimal("120.00")));
    existing.setJournalEntry(existingEntry);

    when(invoiceRepository.findByCompanyAndId(eq(company), eq(701L)))
        .thenReturn(Optional.of(invoice));
    when(settlementAllocationRepository.findByCompanyAndInvoiceOrderByCreatedAtDesc(
            eq(company), eq(invoice)))
        .thenReturn(List.of(existing));
    when(settlementAllocationRepository
            .findByCompanyAndIdempotencyKeyIgnoreCaseOrderByCreatedAtAscIdAsc(
                eq(company), eq(legacyKey)))
        .thenReturn(List.of(existing));

    String resolved =
        ReflectionTestUtils.invokeMethod(
            accountingService, "resolveDealerSettlementIdempotencyKey", company, replayRequest);

    assertThat(resolved).isEqualTo(legacyKey);
  }

  @Test
  void resolveDealerSettlementIdempotencyKey_acceptsLegacyReplayWhenMemoMatchesAdjustmentLabel() {
    DealerSettlementRequest replayRequest =
        new DealerSettlementRequest(
            1L,
            20L,
            null,
            null,
            null,
            null,
            LocalDate.of(2024, 5, 1),
            null,
            "Settlement discount",
            null,
            Boolean.FALSE,
            List.of(
                new SettlementAllocationRequest(
                    701L,
                    null,
                    new BigDecimal("100.00"),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    null)),
            null);

    String canonicalReplayKey =
        ReflectionTestUtils.invokeMethod(
            accountingService, "buildDealerSettlementIdempotencyKey", replayRequest);
    String legacyKey =
        ReflectionTestUtils.invokeMethod(
            accountingService, "buildLegacyDealerSettlementIdempotencyKey", replayRequest);
    assertThat(canonicalReplayKey).isNotEqualTo(legacyKey);

    Dealer dealer = new Dealer();
    ReflectionTestUtils.setField(dealer, "id", 1L);

    var invoice = new com.bigbrightpaints.erp.modules.invoice.domain.Invoice();
    invoice.setCompany(company);
    invoice.setDealer(dealer);
    ReflectionTestUtils.setField(invoice, "id", 701L);

    var existing = new PartnerSettlementAllocation();
    existing.setCompany(company);
    existing.setDealer(dealer);
    existing.setInvoice(invoice);
    existing.setAllocationAmount(new BigDecimal("100.00"));
    existing.setDiscountAmount(BigDecimal.ZERO);
    existing.setWriteOffAmount(BigDecimal.ZERO);
    existing.setFxDifferenceAmount(BigDecimal.ZERO);
    existing.setIdempotencyKey(legacyKey);

    Account cashAccount = new Account();
    cashAccount.setCompany(company);
    cashAccount.setType(AccountType.ASSET);
    cashAccount.setCode("CASH");
    cashAccount.setName("Cash");
    ReflectionTestUtils.setField(cashAccount, "id", 20L);

    Account receivableAccount = new Account();
    receivableAccount.setCompany(company);
    receivableAccount.setType(AccountType.ASSET);
    receivableAccount.setCode("AR-DEALER");
    receivableAccount.setName("Dealer Receivable");
    ReflectionTestUtils.setField(receivableAccount, "id", 10L);

    JournalEntry existingEntry = new JournalEntry();
    existingEntry.addLine(
            journalLine(
                existingEntry,
                cashAccount,
                "Settlement discount",
                new BigDecimal("100.00"),
                BigDecimal.ZERO));
    existingEntry.addLine(
            journalLine(
                existingEntry,
                receivableAccount,
                "Settlement discount",
                BigDecimal.ZERO,
                new BigDecimal("100.00")));
    existing.setJournalEntry(existingEntry);

    when(invoiceRepository.findByCompanyAndId(eq(company), eq(701L)))
        .thenReturn(Optional.of(invoice));
    when(settlementAllocationRepository.findByCompanyAndInvoiceOrderByCreatedAtDesc(
            eq(company), eq(invoice)))
        .thenReturn(List.of(existing));
    when(settlementAllocationRepository
            .findByCompanyAndIdempotencyKeyIgnoreCaseOrderByCreatedAtAscIdAsc(
                eq(company), eq(legacyKey)))
        .thenReturn(List.of(existing));

    String resolved =
        ReflectionTestUtils.invokeMethod(
            accountingService, "resolveDealerSettlementIdempotencyKey", company, replayRequest);

    assertThat(resolved).isEqualTo(legacyKey);
  }

  @Test
  void
      resolveDealerSettlementIdempotencyKey_rejectsLegacyReplayWhenDiscountLineMasksMissingPaymentSplit() {
    DealerSettlementRequest replayRequest =
        new DealerSettlementRequest(
            1L,
            null,
            20L,
            null,
            null,
            null,
            LocalDate.of(2024, 5, 1),
            null,
            null,
            null,
            Boolean.FALSE,
            List.of(
                new SettlementAllocationRequest(
                    701L,
                    null,
                    new BigDecimal("120.00"),
                    new BigDecimal("40.00"),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    null)),
            List.of(
                new SettlementPaymentRequest(20L, new BigDecimal("40.00"), "BANK"),
                new SettlementPaymentRequest(20L, new BigDecimal("40.00"), "BANK")));

    String canonicalReplayKey =
        ReflectionTestUtils.invokeMethod(
            accountingService, "buildDealerSettlementIdempotencyKey", replayRequest);

    Dealer dealer = new Dealer();
    ReflectionTestUtils.setField(dealer, "id", 1L);

    var invoice = new com.bigbrightpaints.erp.modules.invoice.domain.Invoice();
    invoice.setCompany(company);
    invoice.setDealer(dealer);
    ReflectionTestUtils.setField(invoice, "id", 701L);

    DealerSettlementRequest legacyRequest =
        new DealerSettlementRequest(
            1L,
            null,
            20L,
            null,
            null,
            null,
            LocalDate.of(2024, 5, 1),
            null,
            null,
            null,
            Boolean.FALSE,
            List.of(
                new SettlementAllocationRequest(
                    701L,
                    null,
                    new BigDecimal("120.00"),
                    new BigDecimal("40.00"),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    null)),
            List.of(
                new SettlementPaymentRequest(20L, new BigDecimal("40.00"), "BANK"),
                new SettlementPaymentRequest(21L, new BigDecimal("40.00"), "BANK")));
    String legacyKey =
        ReflectionTestUtils.invokeMethod(
            accountingService, "buildLegacyDealerSettlementIdempotencyKey", legacyRequest);
    assertThat(canonicalReplayKey).isNotEqualTo(legacyKey);

    var existing = new PartnerSettlementAllocation();
    existing.setCompany(company);
    existing.setDealer(dealer);
    existing.setInvoice(invoice);
    existing.setAllocationAmount(new BigDecimal("120.00"));
    existing.setDiscountAmount(new BigDecimal("40.00"));
    existing.setWriteOffAmount(BigDecimal.ZERO);
    existing.setFxDifferenceAmount(BigDecimal.ZERO);
    existing.setIdempotencyKey(legacyKey);

    Account account20 = new Account();
    account20.setCompany(company);
    account20.setType(AccountType.ASSET);
    account20.setCode("BANK-20");
    account20.setName("Bank 20");
    ReflectionTestUtils.setField(account20, "id", 20L);

    Account account21 = new Account();
    account21.setCompany(company);
    account21.setType(AccountType.ASSET);
    account21.setCode("BANK-21");
    account21.setName("Bank 21");
    ReflectionTestUtils.setField(account21, "id", 21L);

    Account receivableAccount = new Account();
    receivableAccount.setCompany(company);
    receivableAccount.setType(AccountType.ASSET);
    receivableAccount.setCode("AR-DEALER");
    receivableAccount.setName("Dealer Receivable");
    ReflectionTestUtils.setField(receivableAccount, "id", 10L);

    JournalEntry existingEntry = new JournalEntry();
    existingEntry.addLine(
            journalLine(
                existingEntry,
                account20,
                "Dealer settlement",
                new BigDecimal("40.00"),
                BigDecimal.ZERO));
    existingEntry.addLine(
            journalLine(
                existingEntry,
                account21,
                "Dealer settlement",
                new BigDecimal("40.00"),
                BigDecimal.ZERO));
    existingEntry.addLine(
            journalLine(
                existingEntry,
                account20,
                "Settlement discount",
                new BigDecimal("40.00"),
                BigDecimal.ZERO));
    existingEntry.addLine(
            journalLine(
                existingEntry,
                receivableAccount,
                "Dealer settlement",
                BigDecimal.ZERO,
                new BigDecimal("120.00")));
    existing.setJournalEntry(existingEntry);

    when(invoiceRepository.findByCompanyAndId(eq(company), eq(701L)))
        .thenReturn(Optional.of(invoice));
    when(settlementAllocationRepository.findByCompanyAndInvoiceOrderByCreatedAtDesc(
            eq(company), eq(invoice)))
        .thenReturn(List.of(existing));
    when(settlementAllocationRepository
            .findByCompanyAndIdempotencyKeyIgnoreCaseOrderByCreatedAtAscIdAsc(
                eq(company), eq(legacyKey)))
        .thenReturn(List.of(existing));

    String resolved =
        ReflectionTestUtils.invokeMethod(
            accountingService, "resolveDealerSettlementIdempotencyKey", company, replayRequest);

    assertThat(resolved).isEqualTo(canonicalReplayKey);
  }

  @Test
  void
      resolveDealerSettlementIdempotencyKey_rejectsLegacyReplayWhenDiscountLabelMemoMasksMissingPaymentSplit() {
    DealerSettlementRequest replayRequest =
        new DealerSettlementRequest(
            1L,
            null,
            20L,
            null,
            null,
            null,
            LocalDate.of(2024, 5, 1),
            null,
            "Settlement discount",
            null,
            Boolean.FALSE,
            List.of(
                new SettlementAllocationRequest(
                    701L,
                    null,
                    new BigDecimal("120.00"),
                    new BigDecimal("40.00"),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    null)),
            List.of(
                new SettlementPaymentRequest(20L, new BigDecimal("40.00"), "BANK"),
                new SettlementPaymentRequest(20L, new BigDecimal("40.00"), "BANK")));

    String canonicalReplayKey =
        ReflectionTestUtils.invokeMethod(
            accountingService, "buildDealerSettlementIdempotencyKey", replayRequest);

    Dealer dealer = new Dealer();
    ReflectionTestUtils.setField(dealer, "id", 1L);

    var invoice = new com.bigbrightpaints.erp.modules.invoice.domain.Invoice();
    invoice.setCompany(company);
    invoice.setDealer(dealer);
    ReflectionTestUtils.setField(invoice, "id", 701L);

    DealerSettlementRequest legacyRequest =
        new DealerSettlementRequest(
            1L,
            null,
            20L,
            null,
            null,
            null,
            LocalDate.of(2024, 5, 1),
            null,
            "Settlement discount",
            null,
            Boolean.FALSE,
            List.of(
                new SettlementAllocationRequest(
                    701L,
                    null,
                    new BigDecimal("120.00"),
                    new BigDecimal("40.00"),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    null)),
            List.of(
                new SettlementPaymentRequest(20L, new BigDecimal("40.00"), "BANK"),
                new SettlementPaymentRequest(21L, new BigDecimal("40.00"), "BANK")));
    String legacyKey =
        ReflectionTestUtils.invokeMethod(
            accountingService, "buildLegacyDealerSettlementIdempotencyKey", legacyRequest);
    assertThat(canonicalReplayKey).isNotEqualTo(legacyKey);

    var existing = new PartnerSettlementAllocation();
    existing.setCompany(company);
    existing.setDealer(dealer);
    existing.setInvoice(invoice);
    existing.setAllocationAmount(new BigDecimal("120.00"));
    existing.setDiscountAmount(new BigDecimal("40.00"));
    existing.setWriteOffAmount(BigDecimal.ZERO);
    existing.setFxDifferenceAmount(BigDecimal.ZERO);
    existing.setIdempotencyKey(legacyKey);

    Account account20 = new Account();
    account20.setCompany(company);
    account20.setType(AccountType.ASSET);
    account20.setCode("BANK-20");
    account20.setName("Bank 20");
    ReflectionTestUtils.setField(account20, "id", 20L);

    Account account21 = new Account();
    account21.setCompany(company);
    account21.setType(AccountType.ASSET);
    account21.setCode("BANK-21");
    account21.setName("Bank 21");
    ReflectionTestUtils.setField(account21, "id", 21L);

    Account receivableAccount = new Account();
    receivableAccount.setCompany(company);
    receivableAccount.setType(AccountType.ASSET);
    receivableAccount.setCode("AR-DEALER");
    receivableAccount.setName("Dealer Receivable");
    ReflectionTestUtils.setField(receivableAccount, "id", 10L);

    JournalEntry existingEntry = new JournalEntry();
    existingEntry.addLine(
            journalLine(
                existingEntry,
                account20,
                "Settlement discount",
                new BigDecimal("40.00"),
                BigDecimal.ZERO));
    existingEntry.addLine(
            journalLine(
                existingEntry,
                account21,
                "Settlement discount",
                new BigDecimal("40.00"),
                BigDecimal.ZERO));
    existingEntry.addLine(
            journalLine(
                existingEntry,
                account20,
                "Settlement discount",
                new BigDecimal("40.00"),
                BigDecimal.ZERO));
    existingEntry.addLine(
            journalLine(
                existingEntry,
                receivableAccount,
                "Settlement discount",
                BigDecimal.ZERO,
                new BigDecimal("120.00")));
    existing.setJournalEntry(existingEntry);

    when(invoiceRepository.findByCompanyAndId(eq(company), eq(701L)))
        .thenReturn(Optional.of(invoice));
    when(settlementAllocationRepository.findByCompanyAndInvoiceOrderByCreatedAtDesc(
            eq(company), eq(invoice)))
        .thenReturn(List.of(existing));
    when(settlementAllocationRepository
            .findByCompanyAndIdempotencyKeyIgnoreCaseOrderByCreatedAtAscIdAsc(
                eq(company), eq(legacyKey)))
        .thenReturn(List.of(existing));

    String resolved =
        ReflectionTestUtils.invokeMethod(
            accountingService, "resolveDealerSettlementIdempotencyKey", company, replayRequest);

    assertThat(resolved).isEqualTo(canonicalReplayKey);
  }

  @Test
  void
      resolveDealerSettlementIdempotencyKey_rejectsLegacyReplayWhenDiscountLineOnUnrequestedPaymentAccount() {
    DealerSettlementRequest replayRequest =
        new DealerSettlementRequest(
            1L,
            null,
            30L,
            null,
            null,
            null,
            LocalDate.of(2024, 5, 1),
            null,
            null,
            null,
            Boolean.FALSE,
            List.of(
                new SettlementAllocationRequest(
                    701L,
                    null,
                    new BigDecimal("120.00"),
                    new BigDecimal("40.00"),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    null)),
            List.of(
                new SettlementPaymentRequest(20L, new BigDecimal("40.00"), "BANK"),
                new SettlementPaymentRequest(21L, new BigDecimal("40.00"), "BANK")));

    String canonicalReplayKey =
        ReflectionTestUtils.invokeMethod(
            accountingService, "buildDealerSettlementIdempotencyKey", replayRequest);

    Dealer dealer = new Dealer();
    ReflectionTestUtils.setField(dealer, "id", 1L);

    var invoice = new com.bigbrightpaints.erp.modules.invoice.domain.Invoice();
    invoice.setCompany(company);
    invoice.setDealer(dealer);
    ReflectionTestUtils.setField(invoice, "id", 701L);

    DealerSettlementRequest legacyRequest =
        new DealerSettlementRequest(
            replayRequest.dealerId(),
            20L,
            replayRequest.discountAccountId(),
            replayRequest.writeOffAccountId(),
            replayRequest.fxGainAccountId(),
            replayRequest.fxLossAccountId(),
            replayRequest.settlementDate(),
            replayRequest.referenceNumber(),
            replayRequest.memo(),
            replayRequest.idempotencyKey(),
            replayRequest.adminOverride(),
            replayRequest.allocations(),
            null);
    String legacyKey =
        ReflectionTestUtils.invokeMethod(
            accountingService, "buildLegacyDealerSettlementIdempotencyKey", legacyRequest);
    assertThat(canonicalReplayKey).isNotEqualTo(legacyKey);

    var existing = new PartnerSettlementAllocation();
    existing.setCompany(company);
    existing.setDealer(dealer);
    existing.setInvoice(invoice);
    existing.setAllocationAmount(new BigDecimal("120.00"));
    existing.setDiscountAmount(new BigDecimal("40.00"));
    existing.setWriteOffAmount(BigDecimal.ZERO);
    existing.setFxDifferenceAmount(BigDecimal.ZERO);
    existing.setIdempotencyKey(legacyKey);

    Account account20 = new Account();
    account20.setCompany(company);
    account20.setType(AccountType.ASSET);
    account20.setCode("BANK-20");
    account20.setName("Bank 20");
    ReflectionTestUtils.setField(account20, "id", 20L);

    Account account21 = new Account();
    account21.setCompany(company);
    account21.setType(AccountType.ASSET);
    account21.setCode("BANK-21");
    account21.setName("Bank 21");
    ReflectionTestUtils.setField(account21, "id", 21L);

    Account receivableAccount = new Account();
    receivableAccount.setCompany(company);
    receivableAccount.setType(AccountType.ASSET);
    receivableAccount.setCode("AR-DEALER");
    receivableAccount.setName("Dealer Receivable");
    ReflectionTestUtils.setField(receivableAccount, "id", 10L);

    JournalEntry existingEntry = new JournalEntry();
    existingEntry.addLine(
            journalLine(
                existingEntry,
                account20,
                "Dealer settlement",
                new BigDecimal("40.00"),
                BigDecimal.ZERO));
    existingEntry.addLine(
            journalLine(
                existingEntry,
                account21,
                "Dealer settlement",
                new BigDecimal("40.00"),
                BigDecimal.ZERO));
    existingEntry.addLine(
            journalLine(
                existingEntry,
                account21,
                "Settlement discount",
                new BigDecimal("40.00"),
                BigDecimal.ZERO));
    existingEntry.addLine(
            journalLine(
                existingEntry,
                receivableAccount,
                "Dealer settlement",
                BigDecimal.ZERO,
                new BigDecimal("120.00")));
    existing.setJournalEntry(existingEntry);

    when(invoiceRepository.findByCompanyAndId(eq(company), eq(701L)))
        .thenReturn(Optional.of(invoice));
    when(settlementAllocationRepository.findByCompanyAndInvoiceOrderByCreatedAtDesc(
            eq(company), eq(invoice)))
        .thenReturn(List.of(existing));
    when(settlementAllocationRepository
            .findByCompanyAndIdempotencyKeyIgnoreCaseOrderByCreatedAtAscIdAsc(
                eq(company), eq(legacyKey)))
        .thenReturn(List.of(existing));

    String resolved =
        ReflectionTestUtils.invokeMethod(
            accountingService, "resolveDealerSettlementIdempotencyKey", company, replayRequest);

    assertThat(resolved).isEqualTo(canonicalReplayKey);
  }

  @Test
  void settleDealerInvoices_requiresPaymentsToMatchCash() {
    // Setup company/dealer/invoice
    Dealer dealer = new Dealer();
    dealer.setName("Dealer");
    var arAccount = new com.bigbrightpaints.erp.modules.accounting.domain.Account();
    arAccount.setCompany(company);
    arAccount.setCode("AR");
    dealer.setReceivableAccount(arAccount);
    // Set IDs for equality checks
    ReflectionTestUtils.setField(dealer, "id", 1L);
    ReflectionTestUtils.setField(arAccount, "id", 10L);

    var cashAccount = new com.bigbrightpaints.erp.modules.accounting.domain.Account();
    cashAccount.setCompany(company);
    cashAccount.setCode("CASH");
    ReflectionTestUtils.setField(cashAccount, "id", 2L);

    var invoice = new com.bigbrightpaints.erp.modules.invoice.domain.Invoice();
    invoice.setDealer(dealer);
    invoice.setCurrency("INR");
    invoice.setOutstandingAmount(new BigDecimal("100.00"));
    invoice.setTotalAmount(new BigDecimal("100.00"));

    when(dealerRepository.lockByCompanyAndId(eq(company), eq(1L))).thenReturn(Optional.of(dealer));
    when(referenceNumberService.dealerReceiptReference(eq(company), eq(dealer)))
        .thenReturn("REF-1");

    // Allocation requires an invoice reference
    var allocation =
        new SettlementAllocationRequest(
            3L, null, new BigDecimal("100.00"), BigDecimal.ZERO, BigDecimal.ZERO, null, null);

    // Payments total 50, but cash needed is 100 -> should fail
    var payment = new SettlementPaymentRequest(2L, new BigDecimal("50.00"), "CASH");

    when(companyEntityLookup.requireAccount(eq(company), eq(2L))).thenReturn(cashAccount);

    DealerSettlementRequest request =
        new DealerSettlementRequest(
            1L,
            null, // legacy cashAccountId not used when payments provided
            null,
            null,
            null,
            null,
            LocalDate.now(),
            null,
            null,
            "IDEMP-TEST",
            Boolean.FALSE,
            List.of(allocation),
            List.of(payment));

    assertThatThrownBy(() -> accountingService.settleDealerInvoices(request))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Payment total")
        .hasMessageContaining("must equal net cash required");
  }

  @Test
  void settleDealerInvoices_rejectsAllocationWithNegativeNetCashContribution() {
    Dealer dealer = new Dealer();
    dealer.setName("Dealer");
    Account receivable = new Account();
    receivable.setCompany(company);
    receivable.setCode("AR");
    receivable.setType(AccountType.ASSET);
    ReflectionTestUtils.setField(receivable, "id", 10L);
    dealer.setReceivableAccount(receivable);
    ReflectionTestUtils.setField(dealer, "id", 1L);

    when(dealerRepository.lockByCompanyAndId(eq(company), eq(1L))).thenReturn(Optional.of(dealer));

    DealerSettlementRequest request =
        new DealerSettlementRequest(
            1L,
            20L,
            21L,
            null,
            null,
            null,
            LocalDate.of(2024, 5, 1),
            "REF-NEG-CASH-DEALER",
            "Dealer settlement",
            "IDEMP-NEG-CASH-DEALER",
            Boolean.FALSE,
            List.of(
                new SettlementAllocationRequest(
                    5L,
                    null,
                    new BigDecimal("100.00"),
                    new BigDecimal("120.00"),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    "over-discounted allocation"),
                new SettlementAllocationRequest(
                    6L,
                    null,
                    new BigDecimal("200.00"),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    "offsetting allocation")),
            null);

    assertThatThrownBy(() -> accountingService.settleDealerInvoices(request))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("negative net cash contribution");
    verify(journalReferenceMappingRepository, never())
        .reserveReferenceMapping(any(), any(), any(), any(), any());
  }

  @Test
  void settleDealerInvoices_allowsToleranceBoundaryForNegativeNetCashContribution() {
    Dealer dealer = new Dealer();
    dealer.setName("Dealer");
    Account receivable = new Account();
    receivable.setCompany(company);
    receivable.setCode("AR");
    receivable.setType(AccountType.ASSET);
    ReflectionTestUtils.setField(receivable, "id", 10L);
    dealer.setReceivableAccount(receivable);
    ReflectionTestUtils.setField(dealer, "id", 1L);

    when(dealerRepository.lockByCompanyAndId(eq(company), eq(1L))).thenReturn(Optional.of(dealer));
    when(journalReferenceMappingRepository.findAllByCompanyAndLegacyReferenceIgnoreCase(
            eq(company), eq("idemp-neg-cash-dealer-tolerance")))
        .thenReturn(List.of());
    when(journalReferenceMappingRepository.reserveReferenceMapping(
            eq(company.getId()),
            eq("idemp-neg-cash-dealer-tolerance"),
            eq("REF-NEG-CASH-DEALER-TOLERANCE"),
            eq("DEALER_SETTLEMENT"),
            any()))
        .thenReturn(1);

    DealerSettlementRequest request =
        new DealerSettlementRequest(
            1L,
            20L,
            null,
            null,
            null,
            null,
            LocalDate.of(2024, 5, 1),
            "REF-NEG-CASH-DEALER-TOLERANCE",
            "Dealer settlement",
            "IDEMP-NEG-CASH-DEALER-TOLERANCE",
            Boolean.TRUE,
            List.of(
                new SettlementAllocationRequest(
                    5L,
                    null,
                    new BigDecimal("100.00"),
                    new BigDecimal("100.01"),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    "tolerance-boundary allocation")),
            null);

    assertThatThrownBy(() -> accountingService.settleDealerInvoices(request))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Discount account is required when a discount is applied")
        .satisfies(ex -> assertThat(ex).hasMessageNotContaining("negative net cash contribution"));
    verify(journalReferenceMappingRepository, times(1))
        .reserveReferenceMapping(any(), any(), any(), any(), any());
  }

  @Test
  void settleDealerInvoices_rejectsMissingInvoiceAllocation() {
    Dealer dealer = new Dealer();
    dealer.setName("Dealer");
    Account arAccount = new Account();
    arAccount.setCompany(company);
    arAccount.setCode("AR");
    dealer.setReceivableAccount(arAccount);
    ReflectionTestUtils.setField(dealer, "id", 1L);
    ReflectionTestUtils.setField(arAccount, "id", 10L);

    when(dealerRepository.lockByCompanyAndId(eq(company), eq(1L))).thenReturn(Optional.of(dealer));

    SettlementAllocationRequest allocation =
        new SettlementAllocationRequest(
            null, 2L, new BigDecimal("100.00"), BigDecimal.ZERO, BigDecimal.ZERO, null, null);

    DealerSettlementRequest request =
        new DealerSettlementRequest(
            1L,
            2L,
            null,
            null,
            null,
            null,
            LocalDate.now(),
            null,
            null,
            "IDEMP-TEST",
            Boolean.FALSE,
            List.of(allocation),
            null);

    assertThatThrownBy(() -> accountingService.settleDealerInvoices(request))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Invoice allocation is required for dealer settlements");
  }

  @Test
  void settleDealerInvoices_rejectsDuplicateInvoiceAllocationTargets() {
    Dealer dealer = new Dealer();
    dealer.setName("Dealer");
    Account arAccount = new Account();
    arAccount.setCompany(company);
    arAccount.setCode("AR");
    arAccount.setType(AccountType.ASSET);
    dealer.setReceivableAccount(arAccount);
    ReflectionTestUtils.setField(dealer, "id", 1L);
    ReflectionTestUtils.setField(arAccount, "id", 10L);

    when(dealerRepository.lockByCompanyAndId(eq(company), eq(1L))).thenReturn(Optional.of(dealer));

    DealerSettlementRequest request =
        new DealerSettlementRequest(
            1L,
            20L,
            null,
            null,
            null,
            null,
            LocalDate.of(2024, 5, 1),
            "REF-DUP-DEALER",
            "Dealer settlement",
            "IDEMP-DUP-DEALER",
            Boolean.FALSE,
            List.of(
                new SettlementAllocationRequest(
                    5L,
                    null,
                    new BigDecimal("60.00"),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    "first slice"),
                new SettlementAllocationRequest(
                    5L,
                    null,
                    new BigDecimal("40.00"),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    "duplicate slice")),
            null);

    assertThatThrownBy(() -> accountingService.settleDealerInvoices(request))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("duplicate invoice allocations")
        .hasMessageContaining("combine repeated invoice lines into one allocation");
    verify(journalReferenceMappingRepository, never())
        .reserveReferenceMapping(any(), any(), any(), any(), any());
  }

  @Test
  @Tag("critical")
  void settleDealerInvoices_nonLeaderReplayAllowsInactiveCashAccountAndRepairsReferenceMapping() {
    Dealer dealer = new Dealer();
    dealer.setName("Replay Dealer");
    ReflectionTestUtils.setField(dealer, "id", 1L);

    Account receivable = new Account();
    receivable.setCompany(company);
    receivable.setCode("AR-REPLAY");
    receivable.setType(AccountType.ASSET);
    ReflectionTestUtils.setField(receivable, "id", 10L);
    dealer.setReceivableAccount(receivable);

    Account cash = new Account();
    cash.setCompany(company);
    cash.setCode("CASH-REPLAY");
    cash.setType(AccountType.ASSET);
    cash.setActive(false);
    ReflectionTestUtils.setField(cash, "id", 20L);

    var invoice = new com.bigbrightpaints.erp.modules.invoice.domain.Invoice();
    invoice.setCompany(company);
    invoice.setDealer(dealer);
    ReflectionTestUtils.setField(invoice, "id", 701L);

    JournalEntry existingEntry = new JournalEntry();
    ReflectionTestUtils.setField(existingEntry, "id", 901L);
    existingEntry.setDealer(dealer);
    existingEntry.setReferenceNumber("DR-SETTLE-REPLAY-1");
    existingEntry.setEntryDate(LocalDate.of(2024, 4, 9));
    existingEntry.setMemo("Dealer settlement replay");
    existingEntry.addLine(
            journalLine(
                existingEntry,
                cash,
                "Dealer settlement replay",
                new BigDecimal("100.00"),
                BigDecimal.ZERO));
    existingEntry.addLine(
            journalLine(
                existingEntry,
                receivable,
                "Dealer settlement replay",
                BigDecimal.ZERO,
                new BigDecimal("100.00")));

    PartnerSettlementAllocation existingRow = new PartnerSettlementAllocation();
    existingRow.setCompany(company);
    existingRow.setPartnerType(
        com.bigbrightpaints.erp.modules.accounting.domain.PartnerType.DEALER);
    existingRow.setDealer(dealer);
    existingRow.setInvoice(invoice);
    existingRow.setJournalEntry(existingEntry);
    existingRow.setSettlementDate(LocalDate.of(2024, 4, 9));
    existingRow.setAllocationAmount(new BigDecimal("100.00"));
    existingRow.setDiscountAmount(BigDecimal.ZERO);
    existingRow.setWriteOffAmount(BigDecimal.ZERO);
    existingRow.setFxDifferenceAmount(BigDecimal.ZERO);
    existingRow.setIdempotencyKey("IDEMP-DR-REPLAY");
    existingRow.setMemo(null);

    JournalReferenceMapping mapping = new JournalReferenceMapping();
    mapping.setCompany(company);
    mapping.setLegacyReference("idemp-dr-replay");
    mapping.setCanonicalReference("DR-SETTLE-REPLAY-1");
    mapping.setEntityType("DEALER_SETTLEMENT");
    mapping.setEntityId(null);

    when(dealerRepository.lockByCompanyAndId(eq(company), eq(1L))).thenReturn(Optional.of(dealer));
    when(companyEntityLookup.requireAccount(eq(company), eq(20L))).thenReturn(cash);
    when(journalReferenceMappingRepository.findAllByCompanyAndLegacyReferenceIgnoreCase(
            eq(company), eq("idemp-dr-replay")))
        .thenReturn(List.of(mapping));
    when(journalReferenceResolver.findExistingEntry(eq(company), eq("DR-SETTLE-REPLAY-1")))
        .thenReturn(Optional.of(existingEntry));
    when(settlementAllocationRepository
            .findByCompanyAndIdempotencyKeyIgnoreCaseOrderByCreatedAtAscIdAsc(
                eq(company), eq("IDEMP-DR-REPLAY")))
        .thenReturn(List.of(existingRow));

    DealerSettlementRequest request =
        new DealerSettlementRequest(
            1L,
            20L,
            null,
            null,
            null,
            null,
            LocalDate.of(2024, 4, 9),
            null,
            "Dealer settlement replay",
            "IDEMP-DR-REPLAY",
            Boolean.FALSE,
            List.of(
                new SettlementAllocationRequest(
                    701L,
                    null,
                    new BigDecimal("100.00"),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    null)),
            null);

    PartnerSettlementResponse response = accountingService.settleDealerInvoices(request);

    assertThat(response.journalEntry()).isNotNull();
    assertThat(response.journalEntry().id()).isEqualTo(901L);
    verify(journalReferenceMappingRepository).save(mapping);
    assertThat(mapping.getEntityId()).isEqualTo(901L);
    assertThat(mapping.getEntityType()).isEqualTo("DEALER_SETTLEMENT");
    assertThat(mapping.getCanonicalReference()).isEqualTo("DR-SETTLE-REPLAY-1");
  }

  @Test
  @Tag("critical")
  void settleDealerInvoices_nonLeaderReplayAllowsLegacyInvoiceDiscountAdjustments() {
    Dealer dealer = new Dealer();
    dealer.setName("Replay Dealer");
    ReflectionTestUtils.setField(dealer, "id", 1L);

    Account receivable = new Account();
    receivable.setCompany(company);
    receivable.setCode("AR-REPLAY");
    receivable.setType(AccountType.ASSET);
    ReflectionTestUtils.setField(receivable, "id", 10L);
    dealer.setReceivableAccount(receivable);

    Account cash = new Account();
    cash.setCompany(company);
    cash.setCode("CASH-REPLAY");
    cash.setType(AccountType.ASSET);
    ReflectionTestUtils.setField(cash, "id", 20L);

    Account discount = new Account();
    discount.setCompany(company);
    discount.setCode("DISC-REPLAY");
    discount.setType(AccountType.EXPENSE);
    ReflectionTestUtils.setField(discount, "id", 30L);

    var invoice = new com.bigbrightpaints.erp.modules.invoice.domain.Invoice();
    invoice.setCompany(company);
    invoice.setDealer(dealer);
    ReflectionTestUtils.setField(invoice, "id", 701L);

    JournalEntry existingEntry = new JournalEntry();
    ReflectionTestUtils.setField(existingEntry, "id", 904L);
    existingEntry.setDealer(dealer);
    existingEntry.setReferenceNumber("DR-SETTLE-REPLAY-ADJ-1");
    existingEntry.setEntryDate(LocalDate.of(2024, 4, 9));
    existingEntry.setMemo("Dealer settlement replay");
    List.of(
            journalLine(
                existingEntry,
                cash,
                "Dealer settlement replay",
                new BigDecimal("90.00"),
                BigDecimal.ZERO),
            journalLine(
                existingEntry,
                discount,
                "Settlement discount",
                new BigDecimal("10.00"),
                BigDecimal.ZERO),
            journalLine(
                existingEntry,
                receivable,
                "Dealer settlement replay",
                BigDecimal.ZERO,
                new BigDecimal("100.00")))
        .forEach(existingEntry::addLine);

    PartnerSettlementAllocation existingRow = new PartnerSettlementAllocation();
    existingRow.setCompany(company);
    existingRow.setPartnerType(
        com.bigbrightpaints.erp.modules.accounting.domain.PartnerType.DEALER);
    existingRow.setDealer(dealer);
    existingRow.setInvoice(invoice);
    existingRow.setJournalEntry(existingEntry);
    existingRow.setSettlementDate(LocalDate.of(2024, 4, 9));
    existingRow.setAllocationAmount(new BigDecimal("100.00"));
    existingRow.setDiscountAmount(new BigDecimal("10.00"));
    existingRow.setWriteOffAmount(BigDecimal.ZERO);
    existingRow.setFxDifferenceAmount(BigDecimal.ZERO);
    existingRow.setIdempotencyKey("IDEMP-DR-REPLAY-ADJ");
    existingRow.setMemo("Legacy invoice discount");

    JournalReferenceMapping mapping = new JournalReferenceMapping();
    mapping.setCompany(company);
    mapping.setLegacyReference("idemp-dr-replay-adj");
    mapping.setCanonicalReference("DR-SETTLE-REPLAY-ADJ-1");
    mapping.setEntityType("DEALER_SETTLEMENT");
    mapping.setEntityId(null);

    when(dealerRepository.lockByCompanyAndId(eq(company), eq(1L))).thenReturn(Optional.of(dealer));
    when(companyEntityLookup.requireAccount(eq(company), eq(20L))).thenReturn(cash);
    when(companyEntityLookup.requireAccount(eq(company), eq(30L))).thenReturn(discount);
    when(journalReferenceMappingRepository.findAllByCompanyAndLegacyReferenceIgnoreCase(
            eq(company), eq("idemp-dr-replay-adj")))
        .thenReturn(List.of(mapping));
    when(journalReferenceResolver.findExistingEntry(eq(company), eq("DR-SETTLE-REPLAY-ADJ-1")))
        .thenReturn(Optional.of(existingEntry));
    when(settlementAllocationRepository
            .findByCompanyAndIdempotencyKeyIgnoreCaseOrderByCreatedAtAscIdAsc(
                eq(company), eq("IDEMP-DR-REPLAY-ADJ")))
        .thenReturn(List.of(existingRow));

    DealerSettlementRequest request =
        new DealerSettlementRequest(
            1L,
            20L,
            30L,
            null,
            null,
            null,
            LocalDate.of(2024, 4, 9),
            null,
            "Dealer settlement replay",
            "IDEMP-DR-REPLAY-ADJ",
            Boolean.TRUE,
            List.of(
                new SettlementAllocationRequest(
                    701L,
                    null,
                    new BigDecimal("100.00"),
                    new BigDecimal("10.00"),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    "Legacy invoice discount")),
            null);

    PartnerSettlementResponse response = accountingService.settleDealerInvoices(request);

    assertThat(response.journalEntry()).isNotNull();
    assertThat(response.journalEntry().id()).isEqualTo(904L);
    assertThat(response.totalApplied()).isEqualByComparingTo("100.00");
    assertThat(response.totalDiscount()).isEqualByComparingTo("10.00");
    verify(journalReferenceMappingRepository).save(mapping);
    verify(journalReferenceMappingRepository, never())
        .reserveReferenceMapping(any(), any(), any(), any(), any());
    verify(journalEntryRepository, never()).save(any());
  }

  @Test
  @Tag("critical")
  void settleDealerInvoices_replayRejectsDifferentSettlementDate() {
    Dealer dealer = new Dealer();
    dealer.setName("Replay Dealer");
    ReflectionTestUtils.setField(dealer, "id", 1L);

    Account receivable = new Account();
    receivable.setCompany(company);
    receivable.setCode("AR-REPLAY");
    receivable.setType(AccountType.ASSET);
    ReflectionTestUtils.setField(receivable, "id", 10L);
    dealer.setReceivableAccount(receivable);

    Account cash = new Account();
    cash.setCompany(company);
    cash.setCode("CASH-REPLAY");
    cash.setType(AccountType.ASSET);
    ReflectionTestUtils.setField(cash, "id", 20L);

    Invoice invoice = new Invoice();
    invoice.setCompany(company);
    invoice.setDealer(dealer);
    ReflectionTestUtils.setField(invoice, "id", 701L);

    LocalDate persistedSettlementDate = LocalDate.of(2024, 4, 9);
    LocalDate replaySettlementDate = persistedSettlementDate.plusDays(1);

    JournalEntry existingEntry = new JournalEntry();
    ReflectionTestUtils.setField(existingEntry, "id", 911L);
    existingEntry.setDealer(dealer);
    existingEntry.setReferenceNumber("DR-SETTLE-DATE-REPLAY-1");
    existingEntry.setEntryDate(persistedSettlementDate);
    existingEntry.setMemo("Dealer settlement replay");
    existingEntry.addLine(
            journalLine(
                existingEntry,
                cash,
                "Dealer settlement replay",
                new BigDecimal("100.00"),
                BigDecimal.ZERO));
    existingEntry.addLine(
            journalLine(
                existingEntry,
                receivable,
                "Dealer settlement replay",
                BigDecimal.ZERO,
                new BigDecimal("100.00")));

    PartnerSettlementAllocation existingRow = new PartnerSettlementAllocation();
    existingRow.setCompany(company);
    existingRow.setPartnerType(
        com.bigbrightpaints.erp.modules.accounting.domain.PartnerType.DEALER);
    existingRow.setDealer(dealer);
    existingRow.setInvoice(invoice);
    existingRow.setJournalEntry(existingEntry);
    existingRow.setSettlementDate(persistedSettlementDate);
    existingRow.setAllocationAmount(new BigDecimal("100.00"));
    existingRow.setDiscountAmount(BigDecimal.ZERO);
    existingRow.setWriteOffAmount(BigDecimal.ZERO);
    existingRow.setFxDifferenceAmount(BigDecimal.ZERO);
    existingRow.setIdempotencyKey("IDEMP-DR-SETTLE-DATE");
    existingRow.setMemo(null);

    JournalReferenceMapping mapping = new JournalReferenceMapping();
    mapping.setCompany(company);
    mapping.setLegacyReference("idemp-dr-settle-date");
    mapping.setCanonicalReference("DR-SETTLE-DATE-REPLAY-1");
    mapping.setEntityType("DEALER_SETTLEMENT");
    mapping.setEntityId(null);

    when(dealerRepository.lockByCompanyAndId(eq(company), eq(1L))).thenReturn(Optional.of(dealer));
    when(companyEntityLookup.requireAccount(eq(company), eq(20L))).thenReturn(cash);
    when(journalReferenceMappingRepository.findAllByCompanyAndLegacyReferenceIgnoreCase(
            eq(company), eq("idemp-dr-settle-date")))
        .thenReturn(List.of(mapping));
    when(journalReferenceResolver.findExistingEntry(eq(company), eq("DR-SETTLE-DATE-REPLAY-1")))
        .thenReturn(Optional.of(existingEntry));
    when(settlementAllocationRepository
            .findByCompanyAndIdempotencyKeyIgnoreCaseOrderByCreatedAtAscIdAsc(
                eq(company), eq("IDEMP-DR-SETTLE-DATE")))
        .thenReturn(List.of(existingRow));

    DealerSettlementRequest request =
        new DealerSettlementRequest(
            1L,
            20L,
            null,
            null,
            null,
            null,
            replaySettlementDate,
            null,
            "Dealer settlement replay",
            "IDEMP-DR-SETTLE-DATE",
            Boolean.FALSE,
            List.of(
                new SettlementAllocationRequest(
                    701L,
                    null,
                    new BigDecimal("100.00"),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    null)),
            null);

    assertThatThrownBy(() -> accountingService.settleDealerInvoices(request))
        .isInstanceOfSatisfying(
            ApplicationException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CONCURRENCY_CONFLICT))
        .hasMessageContaining("settlement date");
  }

  @Test
  void settleSupplierInvoices_allowsOnAccountAllocationWithoutMemo() {
    AccountingService service = spy(accountingService);

    Supplier supplier = new Supplier();
    supplier.setName("Supplier");
    supplier.setStatus(SupplierStatus.ACTIVE);
    Account payable = new Account();
    payable.setCompany(company);
    payable.setCode("AP");
    payable.setType(AccountType.LIABILITY);
    ReflectionTestUtils.setField(payable, "id", 10L);
    supplier.setPayableAccount(payable);
    ReflectionTestUtils.setField(supplier, "id", 1L);

    Account cash = new Account();
    cash.setCompany(company);
    cash.setCode("CASH");
    cash.setType(AccountType.ASSET);
    ReflectionTestUtils.setField(cash, "id", 20L);

    when(supplierRepository.lockByCompanyAndId(eq(company), eq(1L)))
        .thenReturn(Optional.of(supplier));
    when(companyEntityLookup.requireAccount(eq(company), eq(20L))).thenReturn(cash);
    JournalEntryDto journalEntryDto = stubEntry(87L);
    doReturn(journalEntryDto)
        .when(journalEntryService)
        .createJournalEntry(any(JournalEntryRequest.class));
    when(companyEntityLookup.requireJournalEntry(eq(company), eq(87L)))
        .thenReturn(new com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry());

    SettlementAllocationRequest allocation =
        new SettlementAllocationRequest(
            null, null, new BigDecimal("100.00"), BigDecimal.ZERO, BigDecimal.ZERO, null, null);

    SupplierSettlementRequest request =
        new SupplierSettlementRequest(
            1L,
            20L,
            null,
            null,
            null,
            null,
            LocalDate.of(2024, 4, 9),
            "REF-AP-1",
            "Supplier settlement",
            "IDEMP-AP-1",
            Boolean.FALSE,
            List.of(allocation));

    var response = service.settleSupplierInvoices(request);
    assertThat(response.journalEntry()).isNotNull();
    assertThat(response.journalEntry().id()).isEqualTo(87L);
    assertThat(response.totalApplied()).isEqualByComparingTo("100.00");
    assertThat(response.allocations()).hasSize(1);
    assertThat(response.allocations().getFirst().purchaseId()).isNull();
    assertThat(response.allocations().getFirst().memo()).isNull();
  }

  @Test
  void settleSupplierInvoices_allowsOnAccountAllocationWithMemo() {
    AccountingService service = spy(accountingService);

    Supplier supplier = new Supplier();
    supplier.setName("Supplier");
    supplier.setStatus(SupplierStatus.ACTIVE);
    Account payable = new Account();
    payable.setCompany(company);
    payable.setCode("AP");
    payable.setType(AccountType.LIABILITY);
    ReflectionTestUtils.setField(payable, "id", 10L);
    supplier.setPayableAccount(payable);
    ReflectionTestUtils.setField(supplier, "id", 1L);

    Account cash = new Account();
    cash.setCompany(company);
    cash.setCode("CASH");
    cash.setType(AccountType.ASSET);
    ReflectionTestUtils.setField(cash, "id", 20L);

    when(supplierRepository.lockByCompanyAndId(eq(company), eq(1L)))
        .thenReturn(Optional.of(supplier));
    when(companyEntityLookup.requireAccount(eq(company), eq(20L))).thenReturn(cash);

    JournalEntryDto journalEntryDto = stubEntry(88L);
    doReturn(journalEntryDto)
        .when(journalEntryService)
        .createJournalEntry(any(JournalEntryRequest.class));
    when(companyEntityLookup.requireJournalEntry(eq(company), eq(88L)))
        .thenReturn(new com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry());

    SettlementAllocationRequest allocation =
        new SettlementAllocationRequest(
            null,
            null,
            new BigDecimal("100.00"),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            null,
            "AP on-account clearing");

    SupplierSettlementRequest request =
        new SupplierSettlementRequest(
            1L,
            20L,
            null,
            null,
            null,
            null,
            LocalDate.of(2024, 4, 9),
            "REF-AP-ONACC-1",
            "Supplier settlement",
            "IDEMP-AP-ONACC-1",
            Boolean.FALSE,
            List.of(allocation));

    var response = service.settleSupplierInvoices(request);

    assertThat(response.journalEntry()).isNotNull();
    assertThat(response.journalEntry().id()).isEqualTo(88L);
    assertThat(response.totalApplied()).isEqualByComparingTo("100.00");
    assertThat(response.allocations()).hasSize(1);
    assertThat(response.allocations().getFirst().purchaseId()).isNull();
    assertThat(response.allocations().getFirst().invoiceId()).isNull();
  }

  @Test
  void settleDealerInvoices_defaultsHeaderLevelFifoAllocationsForPartialAmount() {
    AccountingService service = spy(accountingService);

    Dealer dealer = new Dealer();
    dealer.setName("Dealer");
    ReflectionTestUtils.setField(dealer, "id", 1L);

    Account receivable = new Account();
    receivable.setCompany(company);
    receivable.setCode("AR");
    receivable.setType(AccountType.ASSET);
    ReflectionTestUtils.setField(receivable, "id", 10L);
    dealer.setReceivableAccount(receivable);

    Account cash = new Account();
    cash.setCompany(company);
    cash.setCode("BANK");
    cash.setType(AccountType.ASSET);
    ReflectionTestUtils.setField(cash, "id", 20L);

    Invoice first = new Invoice();
    first.setCompany(company);
    first.setDealer(dealer);
    first.setCurrency("INR");
    first.setOutstandingAmount(new BigDecimal("100.00"));
    first.setTotalAmount(new BigDecimal("100.00"));
    ReflectionTestUtils.setField(first, "id", 701L);

    Invoice second = new Invoice();
    second.setCompany(company);
    second.setDealer(dealer);
    second.setCurrency("INR");
    second.setOutstandingAmount(new BigDecimal("50.00"));
    second.setTotalAmount(new BigDecimal("50.00"));
    ReflectionTestUtils.setField(second, "id", 702L);

    when(dealerRepository.lockByCompanyAndId(eq(company), eq(1L))).thenReturn(Optional.of(dealer));
    when(invoiceRepository.lockOpenInvoicesForSettlement(eq(company), eq(dealer)))
        .thenReturn(List.of(first, second));
    when(invoiceRepository.lockByCompanyAndId(eq(company), eq(701L)))
        .thenReturn(Optional.of(first));
    when(invoiceRepository.lockByCompanyAndId(eq(company), eq(702L)))
        .thenReturn(Optional.of(second));
    when(companyEntityLookup.requireAccount(eq(company), eq(20L))).thenReturn(cash);
    when(companyEntityLookup.requireJournalEntry(eq(company), eq(901L)))
        .thenReturn(new JournalEntry());
    doReturn(stubEntry(901L))
        .when(journalEntryService)
        .createJournalEntry(any(JournalEntryRequest.class));

    DealerSettlementRequest request =
        new DealerSettlementRequest(
            1L,
            20L,
            null,
            null,
            null,
            null,
            new BigDecimal("120.00"),
            null,
            LocalDate.of(2024, 5, 1),
            "HDR-DEALER-DEFAULT",
            "Header dealer settlement",
            "IDEMP-HDR-DEALER-DEFAULT",
            Boolean.FALSE,
            null,
            null);

    PartnerSettlementResponse response = service.settleDealerInvoices(request);

    assertThat(response.totalApplied()).isEqualByComparingTo("120.00");
    assertThat(response.cashAmount()).isEqualByComparingTo("120.00");
    assertThat(response.allocations()).hasSize(2);
    assertThat(response.allocations().get(0).invoiceId()).isEqualTo(701L);
    assertThat(response.allocations().get(0).appliedAmount()).isEqualByComparingTo("100.00");
    assertThat(response.allocations().get(0).applicationType())
        .isEqualTo(SettlementAllocationApplication.DOCUMENT);
    assertThat(response.allocations().get(1).invoiceId()).isEqualTo(702L);
    assertThat(response.allocations().get(1).appliedAmount()).isEqualByComparingTo("20.00");
    assertThat(response.allocations().get(1).applicationType())
        .isEqualTo(SettlementAllocationApplication.DOCUMENT);

    verify(invoiceSettlementPolicy)
        .applySettlement(eq(first), eq(new BigDecimal("100.00")), eq("HDR-DEALER-DEFAULT-INV-701"));
    verify(invoiceSettlementPolicy)
        .applySettlement(eq(second), eq(new BigDecimal("20.00")), eq("HDR-DEALER-DEFAULT-INV-702"));
  }

  @Test
  void settleDealerInvoices_allowsOnAccountCarryWhenNoOpenInvoicesExist() {
    AccountingService service = spy(accountingService);

    Dealer dealer = dealer(91L, "Dealer No Open", account(9101L, "AR-9101", AccountType.ASSET));
    Account cash = account(9201L, "CASH-9201", AccountType.ASSET);

    when(dealerRepository.lockByCompanyAndId(eq(company), eq(91L))).thenReturn(Optional.of(dealer));
    when(invoiceRepository.lockOpenInvoicesForSettlement(eq(company), eq(dealer)))
        .thenReturn(List.of());
    when(companyEntityLookup.requireAccount(eq(company), eq(9201L))).thenReturn(cash);
    when(companyEntityLookup.requireJournalEntry(eq(company), eq(905L)))
        .thenReturn(new JournalEntry());
    doReturn(stubEntry(905L))
        .when(journalEntryService)
        .createJournalEntry(any(JournalEntryRequest.class));

    DealerSettlementRequest request =
        new DealerSettlementRequest(
            91L,
            9201L,
            null,
            null,
            null,
            null,
            new BigDecimal("25.00"),
            SettlementAllocationApplication.ON_ACCOUNT,
            LocalDate.of(2024, 5, 5),
            "HDR-DEALER-NO-OPEN",
            "Dealer no open invoices",
            "IDEMP-HDR-DEALER-NO-OPEN",
            Boolean.FALSE,
            null,
            null);

    PartnerSettlementResponse response = service.settleDealerInvoices(request);

    assertThat(response.totalApplied()).isEqualByComparingTo("25.00");
    assertThat(response.cashAmount()).isEqualByComparingTo("25.00");
    assertThat(response.allocations())
        .singleElement()
        .satisfies(
            allocation -> {
              assertThat(allocation.invoiceId()).isNull();
              assertThat(allocation.applicationType())
                  .isEqualTo(SettlementAllocationApplication.ON_ACCOUNT);
              assertThat(allocation.memo()).isEqualTo("Header-level on-account carry");
            });
    verify(invoiceSettlementPolicy, never()).applySettlement(any(), any(), any());
  }

  @Test
  void settleDealerInvoices_allowsExplicitFutureApplicationAllocation() {
    AccountingService service = spy(accountingService);

    Dealer dealer = new Dealer();
    dealer.setName("Dealer");
    ReflectionTestUtils.setField(dealer, "id", 1L);

    Account receivable = new Account();
    receivable.setCompany(company);
    receivable.setCode("AR");
    receivable.setType(AccountType.ASSET);
    ReflectionTestUtils.setField(receivable, "id", 10L);
    dealer.setReceivableAccount(receivable);

    Account cash = new Account();
    cash.setCompany(company);
    cash.setCode("BANK");
    cash.setType(AccountType.ASSET);
    ReflectionTestUtils.setField(cash, "id", 20L);

    Invoice invoice = new Invoice();
    invoice.setCompany(company);
    invoice.setDealer(dealer);
    invoice.setCurrency("INR");
    invoice.setOutstandingAmount(new BigDecimal("100.00"));
    invoice.setTotalAmount(new BigDecimal("100.00"));
    ReflectionTestUtils.setField(invoice, "id", 703L);

    when(dealerRepository.lockByCompanyAndId(eq(company), eq(1L))).thenReturn(Optional.of(dealer));
    when(invoiceRepository.lockByCompanyAndId(eq(company), eq(703L)))
        .thenReturn(Optional.of(invoice));
    when(companyEntityLookup.requireAccount(eq(company), eq(20L))).thenReturn(cash);
    when(companyEntityLookup.requireJournalEntry(eq(company), eq(902L)))
        .thenReturn(new JournalEntry());
    doReturn(stubEntry(902L))
        .when(journalEntryService)
        .createJournalEntry(any(JournalEntryRequest.class));

    DealerSettlementRequest request =
        new DealerSettlementRequest(
            1L,
            20L,
            null,
            null,
            null,
            null,
            new BigDecimal("125.00"),
            null,
            LocalDate.of(2024, 5, 2),
            "HDR-DEALER-FUTURE",
            "Header dealer settlement",
            "IDEMP-HDR-DEALER-FUTURE",
            Boolean.FALSE,
            List.of(
                new SettlementAllocationRequest(
                    703L,
                    null,
                    new BigDecimal("100.00"),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    SettlementAllocationApplication.DOCUMENT,
                    "Apply oldest invoice"),
                new SettlementAllocationRequest(
                    null,
                    null,
                    new BigDecimal("25.00"),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    SettlementAllocationApplication.FUTURE_APPLICATION,
                    "Keep for future invoice")),
            null);

    PartnerSettlementResponse response = service.settleDealerInvoices(request);

    assertThat(response.totalApplied()).isEqualByComparingTo("125.00");
    assertThat(response.allocations()).hasSize(2);
    assertThat(response.allocations().get(1).applicationType())
        .isEqualTo(SettlementAllocationApplication.FUTURE_APPLICATION);
    assertThat(response.allocations().get(1).invoiceId()).isNull();
    assertThat(response.allocations().get(1).memo()).isEqualTo("Keep for future invoice");
    verify(invoiceSettlementPolicy)
        .applySettlement(
            eq(invoice), eq(new BigDecimal("100.00")), eq("HDR-DEALER-FUTURE-INV-703"));
  }

  @Test
  void
      settleSupplierInvoices_defaultsHeaderLevelOldestOpenAllocationsAndCarriesOnAccountRemainder() {
    AccountingService service = spy(accountingService);

    Supplier supplier = new Supplier();
    supplier.setName("Supplier");
    supplier.setStatus(SupplierStatus.ACTIVE);
    ReflectionTestUtils.setField(supplier, "id", 1L);

    Account payable = new Account();
    payable.setCompany(company);
    payable.setCode("AP");
    payable.setType(AccountType.LIABILITY);
    ReflectionTestUtils.setField(payable, "id", 10L);
    supplier.setPayableAccount(payable);

    Account cash = new Account();
    cash.setCompany(company);
    cash.setCode("BANK");
    cash.setType(AccountType.ASSET);
    ReflectionTestUtils.setField(cash, "id", 20L);

    RawMaterialPurchase first = new RawMaterialPurchase();
    first.setCompany(company);
    first.setSupplier(supplier);
    first.setTotalAmount(new BigDecimal("100.00"));
    first.setOutstandingAmount(new BigDecimal("100.00"));
    ReflectionTestUtils.setField(first, "id", 801L);

    JournalEntry firstPosting = new JournalEntry();
    ReflectionTestUtils.setField(firstPosting, "id", 811L);
    firstPosting.setSupplier(supplier);
    firstPosting.addLine(
            journalLine(
                firstPosting, payable, "purchase 801", BigDecimal.ZERO, new BigDecimal("100.00")));
    first.setJournalEntry(firstPosting);

    RawMaterialPurchase second = new RawMaterialPurchase();
    second.setCompany(company);
    second.setSupplier(supplier);
    second.setTotalAmount(new BigDecimal("50.00"));
    second.setOutstandingAmount(new BigDecimal("50.00"));
    ReflectionTestUtils.setField(second, "id", 802L);

    JournalEntry secondPosting = new JournalEntry();
    ReflectionTestUtils.setField(secondPosting, "id", 812L);
    secondPosting.setSupplier(supplier);
    secondPosting.addLine(
            journalLine(
                secondPosting, payable, "purchase 802", BigDecimal.ZERO, new BigDecimal("50.00")));
    second.setJournalEntry(secondPosting);

    when(supplierRepository.lockByCompanyAndId(eq(company), eq(1L)))
        .thenReturn(Optional.of(supplier));
    when(rawMaterialPurchaseRepository.lockOpenPurchasesForSettlement(eq(company), eq(supplier)))
        .thenReturn(List.of(first, second));
    when(rawMaterialPurchaseRepository.lockByCompanyAndId(eq(company), eq(801L)))
        .thenReturn(Optional.of(first));
    when(rawMaterialPurchaseRepository.lockByCompanyAndId(eq(company), eq(802L)))
        .thenReturn(Optional.of(second));
    when(companyEntityLookup.requireAccount(eq(company), eq(20L))).thenReturn(cash);
    when(companyEntityLookup.requireJournalEntry(eq(company), eq(903L)))
        .thenReturn(new JournalEntry());
    doReturn(stubEntry(903L))
        .when(journalEntryService)
        .createJournalEntry(any(JournalEntryRequest.class));

    SupplierSettlementRequest request =
        new SupplierSettlementRequest(
            1L,
            20L,
            null,
            null,
            null,
            null,
            new BigDecimal("180.00"),
            SettlementAllocationApplication.ON_ACCOUNT,
            LocalDate.of(2024, 5, 3),
            "HDR-SUPPLIER-DEFAULT",
            "Header supplier settlement",
            "IDEMP-HDR-SUPPLIER-DEFAULT",
            Boolean.FALSE,
            null);

    PartnerSettlementResponse response = service.settleSupplierInvoices(request);

    assertThat(response.totalApplied()).isEqualByComparingTo("180.00");
    assertThat(response.cashAmount()).isEqualByComparingTo("180.00");
    assertThat(response.allocations()).hasSize(3);
    assertThat(response.allocations().get(0).purchaseId()).isEqualTo(801L);
    assertThat(response.allocations().get(0).applicationType())
        .isEqualTo(SettlementAllocationApplication.DOCUMENT);
    assertThat(response.allocations().get(1).purchaseId()).isEqualTo(802L);
    assertThat(response.allocations().get(1).applicationType())
        .isEqualTo(SettlementAllocationApplication.DOCUMENT);
    assertThat(response.allocations().get(2).purchaseId()).isNull();
    assertThat(response.allocations().get(2).applicationType())
        .isEqualTo(SettlementAllocationApplication.ON_ACCOUNT);
    assertThat(response.allocations().get(2).appliedAmount()).isEqualByComparingTo("30.00");
  }

  @Test
  void settleSupplierInvoices_allowsFutureApplicationWhenNoOpenPurchasesExist() {
    AccountingService service = spy(accountingService);

    Supplier supplier =
        supplier(93L, "Supplier No Open", account(9301L, "AP-9301", AccountType.LIABILITY));
    supplier.setStatus(SupplierStatus.ACTIVE);
    Account cash = account(9302L, "BANK-9302", AccountType.ASSET);

    when(supplierRepository.lockByCompanyAndId(eq(company), eq(93L)))
        .thenReturn(Optional.of(supplier));
    when(rawMaterialPurchaseRepository.lockOpenPurchasesForSettlement(eq(company), eq(supplier)))
        .thenReturn(List.of());
    when(companyEntityLookup.requireAccount(eq(company), eq(9302L))).thenReturn(cash);
    when(companyEntityLookup.requireJournalEntry(eq(company), eq(907L)))
        .thenReturn(new JournalEntry());
    doReturn(stubEntry(907L))
        .when(journalEntryService)
        .createJournalEntry(any(JournalEntryRequest.class));

    SupplierSettlementRequest request =
        new SupplierSettlementRequest(
            93L,
            9302L,
            null,
            null,
            null,
            null,
            new BigDecimal("40.00"),
            SettlementAllocationApplication.FUTURE_APPLICATION,
            LocalDate.of(2024, 5, 7),
            "HDR-SUPPLIER-NO-OPEN-OK",
            "Supplier no open purchases",
            "IDEMP-HDR-SUPPLIER-NO-OPEN-OK",
            Boolean.FALSE,
            null);

    PartnerSettlementResponse response = service.settleSupplierInvoices(request);

    assertThat(response.totalApplied()).isEqualByComparingTo("40.00");
    assertThat(response.cashAmount()).isEqualByComparingTo("40.00");
    assertThat(response.allocations())
        .singleElement()
        .satisfies(
            allocation -> {
              assertThat(allocation.purchaseId()).isNull();
              assertThat(allocation.applicationType())
                  .isEqualTo(SettlementAllocationApplication.FUTURE_APPLICATION);
              assertThat(allocation.memo()).isEqualTo("Header-level future application");
            });
  }

  @Test
  void settleSupplierInvoices_requiresUnappliedApplicationWhenHeaderAmountExceedsOutstanding() {
    Supplier supplier = new Supplier();
    supplier.setName("Supplier Overflow");
    supplier.setStatus(SupplierStatus.ACTIVE);
    ReflectionTestUtils.setField(supplier, "id", 1L);

    Account payable = new Account();
    payable.setCompany(company);
    payable.setCode("AP-OVERFLOW");
    payable.setType(AccountType.LIABILITY);
    ReflectionTestUtils.setField(payable, "id", 10L);
    supplier.setPayableAccount(payable);

    RawMaterialPurchase purchase = new RawMaterialPurchase();
    purchase.setCompany(company);
    purchase.setSupplier(supplier);
    purchase.setOutstandingAmount(new BigDecimal("60.00"));
    purchase.setTotalAmount(new BigDecimal("60.00"));
    ReflectionTestUtils.setField(purchase, "id", 803L);

    when(supplierRepository.lockByCompanyAndId(eq(company), eq(1L)))
        .thenReturn(Optional.of(supplier));
    when(rawMaterialPurchaseRepository.lockOpenPurchasesForSettlement(eq(company), eq(supplier)))
        .thenReturn(List.of(purchase));

    SupplierSettlementRequest request =
        new SupplierSettlementRequest(
            1L,
            20L,
            null,
            null,
            null,
            null,
            new BigDecimal("75.00"),
            null,
            LocalDate.of(2024, 5, 4),
            "HDR-SUPPLIER-OVERFLOW",
            "Supplier overflow",
            "IDEMP-HDR-SUPPLIER-OVERFLOW",
            Boolean.FALSE,
            null);

    assertThatThrownBy(() -> accountingService.settleSupplierInvoices(request))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("choose ON_ACCOUNT or FUTURE_APPLICATION");
  }

  @Test
  void settleSupplierInvoices_requiresAmountWhenHeaderAllocationsMissing() {
    Supplier supplier = new Supplier();
    supplier.setName("Supplier No Amount");
    supplier.setStatus(SupplierStatus.ACTIVE);
    ReflectionTestUtils.setField(supplier, "id", 1L);

    Account payable = new Account();
    payable.setCompany(company);
    payable.setCode("AP-NO-AMOUNT");
    payable.setType(AccountType.LIABILITY);
    ReflectionTestUtils.setField(payable, "id", 10L);
    supplier.setPayableAccount(payable);

    when(supplierRepository.lockByCompanyAndId(eq(company), eq(1L)))
        .thenReturn(Optional.of(supplier));

    SupplierSettlementRequest request =
        new SupplierSettlementRequest(
            1L,
            20L,
            null,
            null,
            null,
            null,
            null,
            null,
            LocalDate.of(2024, 5, 4),
            "HDR-SUPPLIER-NO-AMOUNT",
            "Supplier missing amount",
            "IDEMP-HDR-SUPPLIER-NO-AMOUNT",
            Boolean.FALSE,
            null);

    assertThatThrownBy(() -> accountingService.settleSupplierInvoices(request))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Provide allocations or an amount for supplier settlements");
  }

  @Test
  void settleSupplierInvoices_requiresUnappliedApplicationWhenNoOpenPurchasesExist() {
    Supplier supplier = new Supplier();
    supplier.setName("Supplier No Open");
    supplier.setStatus(SupplierStatus.ACTIVE);
    ReflectionTestUtils.setField(supplier, "id", 1L);

    Account payable = new Account();
    payable.setCompany(company);
    payable.setCode("AP-NO-OPEN");
    payable.setType(AccountType.LIABILITY);
    ReflectionTestUtils.setField(payable, "id", 10L);
    supplier.setPayableAccount(payable);

    when(supplierRepository.lockByCompanyAndId(eq(company), eq(1L)))
        .thenReturn(Optional.of(supplier));
    when(rawMaterialPurchaseRepository.lockOpenPurchasesForSettlement(eq(company), eq(supplier)))
        .thenReturn(List.of());

    SupplierSettlementRequest request =
        new SupplierSettlementRequest(
            1L,
            20L,
            null,
            null,
            null,
            null,
            new BigDecimal("25.00"),
            null,
            LocalDate.of(2024, 5, 4),
            "HDR-SUPPLIER-NO-OPEN",
            "Supplier no open purchases",
            "IDEMP-HDR-SUPPLIER-NO-OPEN",
            Boolean.FALSE,
            null);

    assertThatThrownBy(() -> accountingService.settleSupplierInvoices(request))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("No open purchases are available");
  }

  @Test
  void settleSupplierInvoices_rejectsDocumentAsHeaderUnappliedApplication() {
    Supplier supplier = new Supplier();
    supplier.setName("Supplier Document");
    supplier.setStatus(SupplierStatus.ACTIVE);
    ReflectionTestUtils.setField(supplier, "id", 1L);

    Account payable = new Account();
    payable.setCompany(company);
    payable.setCode("AP-DOCUMENT");
    payable.setType(AccountType.LIABILITY);
    ReflectionTestUtils.setField(payable, "id", 10L);
    supplier.setPayableAccount(payable);

    Account cash = new Account();
    cash.setCompany(company);
    cash.setCode("CASH-DOCUMENT");
    cash.setType(AccountType.ASSET);
    cash.setActive(true);
    ReflectionTestUtils.setField(cash, "id", 20L);

    RawMaterialPurchase purchase = new RawMaterialPurchase();
    purchase.setCompany(company);
    purchase.setSupplier(supplier);
    purchase.setOutstandingAmount(new BigDecimal("25.00"));
    ReflectionTestUtils.setField(purchase, "id", 803L);

    when(supplierRepository.lockByCompanyAndId(eq(company), eq(1L)))
        .thenReturn(Optional.of(supplier));

    SupplierSettlementRequest request =
        new SupplierSettlementRequest(
            1L,
            20L,
            null,
            null,
            null,
            null,
            new BigDecimal("25.00"),
            SettlementAllocationApplication.DOCUMENT,
            LocalDate.of(2024, 5, 4),
            "HDR-SUPPLIER-DOCUMENT",
            "Supplier document unapplied",
            "IDEMP-HDR-SUPPLIER-DOCUMENT",
            Boolean.FALSE,
            null);

    assertThatThrownBy(() -> accountingService.settleSupplierInvoices(request))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Unapplied amount handling must be ON_ACCOUNT or FUTURE_APPLICATION");
  }

  @Test
  void settleSupplierInvoices_rejectsOnAccountAllocationWithAdjustments() {
    AccountingService service = spy(accountingService);

    Supplier supplier = new Supplier();
    supplier.setName("Supplier");
    supplier.setStatus(SupplierStatus.ACTIVE);
    Account payable = new Account();
    payable.setCompany(company);
    payable.setCode("AP");
    payable.setType(AccountType.LIABILITY);
    ReflectionTestUtils.setField(payable, "id", 10L);
    supplier.setPayableAccount(payable);
    ReflectionTestUtils.setField(supplier, "id", 1L);

    Account cash = new Account();
    cash.setCompany(company);
    cash.setCode("CASH");
    cash.setType(AccountType.ASSET);
    ReflectionTestUtils.setField(cash, "id", 20L);

    when(supplierRepository.lockByCompanyAndId(eq(company), eq(1L)))
        .thenReturn(Optional.of(supplier));
    lenient().when(companyEntityLookup.requireAccount(eq(company), eq(20L))).thenReturn(cash);

    SettlementAllocationRequest allocation =
        new SettlementAllocationRequest(
            null,
            null,
            new BigDecimal("100.00"),
            new BigDecimal("10.00"),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            "Invalid on-account discount");

    SupplierSettlementRequest request =
        new SupplierSettlementRequest(
            1L,
            20L,
            21L,
            null,
            null,
            null,
            LocalDate.of(2024, 4, 9),
            "REF-AP-ONACC-INVALID",
            "Supplier settlement",
            "IDEMP-AP-ONACC-INVALID",
            Boolean.FALSE,
            List.of(allocation));

    assertThatThrownBy(() -> service.settleSupplierInvoices(request))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining(
            "On-account supplier settlement allocations cannot include discount/write-off/FX"
                + " adjustments");
    verify(service, never()).createJournalEntry(any(JournalEntryRequest.class));
    verify(journalReferenceMappingRepository, never())
        .reserveReferenceMapping(any(), any(), any(), any(), any());
  }

  @Test
  void settleSupplierInvoices_postsDiscountSettlementWhenAdminOverrideApproved() {
    SettlementService settlementService =
        new SettlementService(
            companyContextService,
            accountRepository,
            journalEntryRepository,
            dealerLedgerService,
            supplierLedgerService,
            payrollRunRepository,
            payrollRunLineRepository,
            accountingPeriodService,
            referenceNumberService,
            eventPublisher,
            companyClock,
            companyEntityLookup,
            settlementAllocationRepository,
            rawMaterialPurchaseRepository,
            invoiceRepository,
            rawMaterialMovementRepository,
            rawMaterialBatchRepository,
            finishedGoodBatchRepository,
            dealerRepository,
            supplierRepository,
            invoiceSettlementPolicy,
            journalReferenceResolver,
            journalReferenceMappingRepository,
            entityManager,
            systemSettingsService,
            auditService,
            accountingEventStore,
            journalEntryService,
            dealerReceiptService);
    ReflectionTestUtils.setField(settlementService, "environment", environment);
    ReflectionTestUtils.setField(accountingService, "settlementService", settlementService);

    Supplier supplier =
        supplier(
            94L, "Supplier Override Approved", account(9401L, "AP-9401", AccountType.LIABILITY));
    supplier.setStatus(SupplierStatus.ACTIVE);
    Account cash = account(9402L, "BANK-9402", AccountType.ASSET);
    Account discount = account(9403L, "DISC-9403", AccountType.REVENUE);
    Account inventory = account(9404L, "RM-9404", AccountType.ASSET);
    RawMaterialPurchase purchase =
        purchase(
            94020L,
            "PUR-94020",
            supplier,
            new BigDecimal("30.00"),
            new BigDecimal("30.00"),
            "POSTED");
    JournalEntry purchaseJournal = journalEntry(94021L, "PUR-94020-JE");
    purchaseJournal.setSupplier(supplier);
    addJournalLine(
        purchaseJournal, inventory, "Inventory received", new BigDecimal("30.00"), BigDecimal.ZERO);
    addJournalLine(
        purchaseJournal,
        supplier.getPayableAccount(),
        "Supplier payable",
        BigDecimal.ZERO,
        new BigDecimal("30.00"));
    purchase.setJournalEntry(purchaseJournal);

    when(supplierRepository.lockByCompanyAndId(eq(company), eq(94L)))
        .thenReturn(Optional.of(supplier));
    when(rawMaterialPurchaseRepository.lockByCompanyAndId(eq(company), eq(94020L)))
        .thenReturn(Optional.of(purchase));
    when(companyEntityLookup.requireAccount(eq(company), eq(9402L))).thenReturn(cash);
    when(companyEntityLookup.requireAccount(eq(company), eq(9403L))).thenReturn(discount);
    when(companyEntityLookup.requireJournalEntry(eq(company), eq(908L)))
        .thenReturn(new JournalEntry());
    doReturn(stubEntry(908L))
        .when(journalEntryService)
        .createJournalEntry(any(JournalEntryRequest.class));

    SupplierSettlementRequest request =
        new SupplierSettlementRequest(
            94L,
            9402L,
            9403L,
            null,
            null,
            null,
            new BigDecimal("30.00"),
            null,
            LocalDate.of(2024, 5, 8),
            "HDR-SUPPLIER-OVERRIDE-OK",
            "Approved supplier discount override",
            "IDEMP-HDR-SUPPLIER-OVERRIDE-OK",
            Boolean.TRUE,
            List.of(
                new SettlementAllocationRequest(
                    null,
                    94020L,
                    new BigDecimal("30.00"),
                    new BigDecimal("2.00"),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    SettlementAllocationApplication.DOCUMENT,
                    "supplier override allocation")));

    PartnerSettlementResponse response = accountingService.settleSupplierInvoices(request);

    assertThat(response.totalApplied()).isEqualByComparingTo("30.00");
    assertThat(response.totalDiscount()).isEqualByComparingTo("2.00");
    assertThat(response.allocations())
        .singleElement()
        .satisfies(
            allocation -> {
              assertThat(allocation.purchaseId()).isEqualTo(94020L);
              assertThat(allocation.discountAmount()).isEqualByComparingTo("2.00");
            });
  }

  @Test
  void settleSupplierInvoices_rejectsDuplicatePurchaseAllocationTargets() {
    Supplier supplier = new Supplier();
    supplier.setName("Supplier");
    supplier.setStatus(SupplierStatus.ACTIVE);
    Account payable = new Account();
    payable.setCompany(company);
    payable.setCode("AP");
    payable.setType(AccountType.LIABILITY);
    ReflectionTestUtils.setField(payable, "id", 10L);
    supplier.setPayableAccount(payable);
    ReflectionTestUtils.setField(supplier, "id", 1L);

    Account cash = new Account();
    cash.setCompany(company);
    cash.setCode("CASH");
    cash.setType(AccountType.ASSET);
    ReflectionTestUtils.setField(cash, "id", 20L);

    when(supplierRepository.lockByCompanyAndId(eq(company), eq(1L)))
        .thenReturn(Optional.of(supplier));
    lenient().when(companyEntityLookup.requireAccount(eq(company), eq(20L))).thenReturn(cash);

    SupplierSettlementRequest request =
        new SupplierSettlementRequest(
            1L,
            20L,
            21L,
            null,
            null,
            null,
            LocalDate.of(2024, 5, 1),
            "REF-DUP-SUPPLIER",
            "Supplier settlement",
            "IDEMP-DUP-SUPPLIER",
            Boolean.FALSE,
            List.of(
                new SettlementAllocationRequest(
                    null,
                    2L,
                    new BigDecimal("100.00"),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    "first allocation"),
                new SettlementAllocationRequest(
                    null,
                    2L,
                    new BigDecimal("25.00"),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    "duplicate allocation")));

    assertThatThrownBy(() -> accountingService.settleSupplierInvoices(request))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("duplicate purchase allocations")
        .hasMessageContaining("combine repeated purchase lines into one allocation");
    verify(journalReferenceMappingRepository, never())
        .reserveReferenceMapping(any(), any(), any(), any(), any());
  }

  @Test
  void settleSupplierInvoices_rejectsAllocationWithNegativeNetCashContribution() {
    Supplier supplier = new Supplier();
    supplier.setName("Supplier");
    supplier.setStatus(SupplierStatus.ACTIVE);
    Account payable = new Account();
    payable.setCompany(company);
    payable.setCode("AP");
    payable.setType(AccountType.LIABILITY);
    ReflectionTestUtils.setField(payable, "id", 10L);
    supplier.setPayableAccount(payable);
    ReflectionTestUtils.setField(supplier, "id", 1L);

    Account cash = new Account();
    cash.setCompany(company);
    cash.setCode("CASH");
    cash.setType(AccountType.ASSET);
    ReflectionTestUtils.setField(cash, "id", 20L);

    when(supplierRepository.lockByCompanyAndId(eq(company), eq(1L)))
        .thenReturn(Optional.of(supplier));
    lenient().when(companyEntityLookup.requireAccount(eq(company), eq(20L))).thenReturn(cash);

    SupplierSettlementRequest request =
        new SupplierSettlementRequest(
            1L,
            20L,
            21L,
            null,
            null,
            null,
            LocalDate.of(2024, 5, 1),
            "REF-NEG-CASH-SUPPLIER",
            "Supplier settlement",
            "IDEMP-NEG-CASH-SUPPLIER",
            Boolean.FALSE,
            List.of(
                new SettlementAllocationRequest(
                    null,
                    2L,
                    new BigDecimal("100.00"),
                    new BigDecimal("120.00"),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    "over-discounted allocation"),
                new SettlementAllocationRequest(
                    null,
                    3L,
                    new BigDecimal("200.00"),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    "offsetting allocation")));

    assertThatThrownBy(() -> accountingService.settleSupplierInvoices(request))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("negative net cash contribution");
    verify(journalReferenceMappingRepository, never())
        .reserveReferenceMapping(any(), any(), any(), any(), any());
  }

  @Test
  void settleSupplierInvoices_allowsToleranceBoundaryForNegativeNetCashContribution() {
    Supplier supplier = new Supplier();
    supplier.setName("Supplier");
    supplier.setStatus(SupplierStatus.ACTIVE);
    Account payable = new Account();
    payable.setCompany(company);
    payable.setCode("AP");
    payable.setType(AccountType.LIABILITY);
    ReflectionTestUtils.setField(payable, "id", 10L);
    supplier.setPayableAccount(payable);
    ReflectionTestUtils.setField(supplier, "id", 1L);

    when(supplierRepository.lockByCompanyAndId(eq(company), eq(1L)))
        .thenReturn(Optional.of(supplier));
    when(journalReferenceMappingRepository.findAllByCompanyAndLegacyReferenceIgnoreCase(
            eq(company), eq("idemp-neg-cash-supplier-tolerance")))
        .thenReturn(List.of());
    when(journalReferenceMappingRepository.reserveReferenceMapping(
            eq(company.getId()),
            eq("idemp-neg-cash-supplier-tolerance"),
            eq("REF-NEG-CASH-SUPPLIER-TOLERANCE"),
            eq("SUPPLIER_SETTLEMENT"),
            any()))
        .thenReturn(1);

    SupplierSettlementRequest request =
        new SupplierSettlementRequest(
            1L,
            20L,
            null,
            null,
            null,
            null,
            LocalDate.of(2024, 5, 1),
            "REF-NEG-CASH-SUPPLIER-TOLERANCE",
            "Supplier settlement",
            "IDEMP-NEG-CASH-SUPPLIER-TOLERANCE",
            Boolean.TRUE,
            List.of(
                new SettlementAllocationRequest(
                    null,
                    2L,
                    new BigDecimal("100.00"),
                    new BigDecimal("100.01"),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    "tolerance-boundary allocation")));

    assertThatThrownBy(() -> accountingService.settleSupplierInvoices(request))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Discount account is required when a discount is applied")
        .satisfies(ex -> assertThat(ex).hasMessageNotContaining("negative net cash contribution"));
    verify(journalReferenceMappingRepository, times(1))
        .reserveReferenceMapping(any(), any(), any(), any(), any());
  }

  @Test
  @Tag("critical")
  void settleSupplierInvoices_nonLeaderReplayAllowsInactiveCashAccountAndRepairsReferenceMapping() {
    Supplier supplier = new Supplier();
    supplier.setName("Replay Supplier");
    supplier.setStatus(SupplierStatus.ACTIVE);
    ReflectionTestUtils.setField(supplier, "id", 1L);

    Account payable = new Account();
    payable.setCompany(company);
    payable.setCode("AP-REPLAY");
    payable.setType(AccountType.LIABILITY);
    ReflectionTestUtils.setField(payable, "id", 10L);
    supplier.setPayableAccount(payable);

    Account cash = new Account();
    cash.setCompany(company);
    cash.setCode("CASH-REPLAY");
    cash.setType(AccountType.ASSET);
    cash.setActive(false);
    ReflectionTestUtils.setField(cash, "id", 20L);

    JournalEntry existingEntry = new JournalEntry();
    ReflectionTestUtils.setField(existingEntry, "id", 901L);
    existingEntry.setSupplier(supplier);
    existingEntry.setReferenceNumber("SUP-SETTLE-REPLAY-1");
    existingEntry.setEntryDate(LocalDate.of(2024, 4, 9));
    existingEntry.setMemo("Supplier settlement replay");
    existingEntry.addLine(
            journalLine(
                existingEntry,
                payable,
                "Supplier settlement replay",
                new BigDecimal("100.00"),
                BigDecimal.ZERO));
    existingEntry.addLine(
            journalLine(
                existingEntry,
                cash,
                "Supplier settlement replay",
                BigDecimal.ZERO,
                new BigDecimal("100.00")));

    com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation existingRow =
        new com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation();
    existingRow.setCompany(company);
    existingRow.setPartnerType(
        com.bigbrightpaints.erp.modules.accounting.domain.PartnerType.SUPPLIER);
    existingRow.setSupplier(supplier);
    existingRow.setJournalEntry(existingEntry);
    existingRow.setSettlementDate(LocalDate.of(2024, 4, 9));
    existingRow.setAllocationAmount(new BigDecimal("100.00"));
    existingRow.setDiscountAmount(BigDecimal.ZERO);
    existingRow.setWriteOffAmount(BigDecimal.ZERO);
    existingRow.setFxDifferenceAmount(BigDecimal.ZERO);
    existingRow.setIdempotencyKey("IDEMP-AP-REPLAY");
    existingRow.setMemo(null);

    JournalReferenceMapping mapping = new JournalReferenceMapping();
    mapping.setCompany(company);
    mapping.setLegacyReference("idemp-ap-replay");
    mapping.setCanonicalReference("SUP-SETTLE-REPLAY-1");
    mapping.setEntityType("SUPPLIER_SETTLEMENT");
    mapping.setEntityId(null);

    when(supplierRepository.lockByCompanyAndId(eq(company), eq(1L)))
        .thenReturn(Optional.of(supplier));
    when(companyEntityLookup.requireAccount(eq(company), eq(20L))).thenReturn(cash);
    when(journalReferenceMappingRepository.findAllByCompanyAndLegacyReferenceIgnoreCase(
            eq(company), eq("idemp-ap-replay")))
        .thenReturn(List.of(mapping));
    when(journalReferenceResolver.findExistingEntry(eq(company), eq("SUP-SETTLE-REPLAY-1")))
        .thenReturn(Optional.of(existingEntry));
    when(settlementAllocationRepository
            .findByCompanyAndIdempotencyKeyIgnoreCaseOrderByCreatedAtAscIdAsc(
                eq(company), eq("IDEMP-AP-REPLAY")))
        .thenReturn(List.of(existingRow));

    SettlementAllocationRequest allocation =
        new SettlementAllocationRequest(
            null,
            null,
            new BigDecimal("100.00"),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            null);

    SupplierSettlementRequest request =
        new SupplierSettlementRequest(
            1L,
            20L,
            null,
            null,
            null,
            null,
            LocalDate.of(2024, 4, 9),
            null,
            "Supplier settlement replay",
            "IDEMP-AP-REPLAY",
            Boolean.FALSE,
            List.of(allocation));

    PartnerSettlementResponse response = accountingService.settleSupplierInvoices(request);

    assertThat(response.journalEntry()).isNotNull();
    assertThat(response.journalEntry().id()).isEqualTo(901L);
    verify(journalReferenceMappingRepository).save(mapping);
    assertThat(mapping.getEntityId()).isEqualTo(901L);
    assertThat(mapping.getEntityType()).isEqualTo("SUPPLIER_SETTLEMENT");
    assertThat(mapping.getCanonicalReference()).isEqualTo("SUP-SETTLE-REPLAY-1");
  }

  @Test
  @Tag("critical")
  void settleSupplierInvoices_nonLeaderReplayAllowsLegacyOnAccountAdjustments() {
    Supplier supplier = new Supplier();
    supplier.setName("Replay Supplier");
    supplier.setStatus(SupplierStatus.ACTIVE);
    ReflectionTestUtils.setField(supplier, "id", 1L);

    Account payable = new Account();
    payable.setCompany(company);
    payable.setCode("AP-REPLAY");
    payable.setType(AccountType.LIABILITY);
    ReflectionTestUtils.setField(payable, "id", 10L);
    supplier.setPayableAccount(payable);

    Account cash = new Account();
    cash.setCompany(company);
    cash.setCode("CASH-REPLAY");
    cash.setType(AccountType.ASSET);
    ReflectionTestUtils.setField(cash, "id", 20L);

    Account discount = new Account();
    discount.setCompany(company);
    discount.setCode("DISC-REPLAY");
    discount.setType(AccountType.REVENUE);
    ReflectionTestUtils.setField(discount, "id", 30L);

    JournalEntry existingEntry = new JournalEntry();
    ReflectionTestUtils.setField(existingEntry, "id", 904L);
    existingEntry.setSupplier(supplier);
    existingEntry.setReferenceNumber("SUP-SETTLE-REPLAY-ADJ-1");
    existingEntry.setEntryDate(LocalDate.of(2024, 4, 9));
    existingEntry.setMemo("Supplier settlement replay");
    List.of(
            journalLine(
                existingEntry,
                payable,
                "Supplier settlement replay",
                new BigDecimal("100.00"),
                BigDecimal.ZERO),
            journalLine(
                existingEntry,
                cash,
                "Supplier settlement replay",
                BigDecimal.ZERO,
                new BigDecimal("90.00")),
            journalLine(
                existingEntry,
                discount,
                "Settlement discount received",
                BigDecimal.ZERO,
                new BigDecimal("10.00")))
        .forEach(existingEntry::addLine);

    com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation existingRow =
        new com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation();
    existingRow.setCompany(company);
    existingRow.setPartnerType(
        com.bigbrightpaints.erp.modules.accounting.domain.PartnerType.SUPPLIER);
    existingRow.setSupplier(supplier);
    existingRow.setJournalEntry(existingEntry);
    existingRow.setSettlementDate(LocalDate.of(2024, 4, 9));
    existingRow.setAllocationAmount(new BigDecimal("100.00"));
    existingRow.setDiscountAmount(new BigDecimal("10.00"));
    existingRow.setWriteOffAmount(BigDecimal.ZERO);
    existingRow.setFxDifferenceAmount(BigDecimal.ZERO);
    existingRow.setIdempotencyKey("IDEMP-AP-REPLAY-ADJ");
    existingRow.setMemo("Legacy on-account discount");

    JournalReferenceMapping mapping = new JournalReferenceMapping();
    mapping.setCompany(company);
    mapping.setLegacyReference("idemp-ap-replay-adj");
    mapping.setCanonicalReference("SUP-SETTLE-REPLAY-ADJ-1");
    mapping.setEntityType("SUPPLIER_SETTLEMENT");
    mapping.setEntityId(null);

    when(supplierRepository.lockByCompanyAndId(eq(company), eq(1L)))
        .thenReturn(Optional.of(supplier));
    when(companyEntityLookup.requireAccount(eq(company), eq(20L))).thenReturn(cash);
    when(companyEntityLookup.requireAccount(eq(company), eq(30L))).thenReturn(discount);
    when(journalReferenceMappingRepository.findAllByCompanyAndLegacyReferenceIgnoreCase(
            eq(company), eq("idemp-ap-replay-adj")))
        .thenReturn(List.of(mapping));
    when(journalReferenceResolver.findExistingEntry(eq(company), eq("SUP-SETTLE-REPLAY-ADJ-1")))
        .thenReturn(Optional.of(existingEntry));
    when(settlementAllocationRepository
            .findByCompanyAndIdempotencyKeyIgnoreCaseOrderByCreatedAtAscIdAsc(
                eq(company), eq("IDEMP-AP-REPLAY-ADJ")))
        .thenReturn(List.of(existingRow));

    SettlementAllocationRequest allocation =
        new SettlementAllocationRequest(
            null,
            null,
            new BigDecimal("100.00"),
            new BigDecimal("10.00"),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            "Legacy on-account discount");

    SupplierSettlementRequest request =
        new SupplierSettlementRequest(
            1L,
            20L,
            30L,
            null,
            null,
            null,
            LocalDate.of(2024, 4, 9),
            null,
            "Supplier settlement replay",
            "IDEMP-AP-REPLAY-ADJ",
            Boolean.TRUE,
            List.of(allocation));

    PartnerSettlementResponse response = accountingService.settleSupplierInvoices(request);

    assertThat(response.journalEntry()).isNotNull();
    assertThat(response.journalEntry().id()).isEqualTo(904L);
    assertThat(response.totalApplied()).isEqualByComparingTo("100.00");
    assertThat(response.totalDiscount()).isEqualByComparingTo("10.00");
    verify(journalReferenceMappingRepository).save(mapping);
    verify(journalReferenceMappingRepository, never())
        .reserveReferenceMapping(any(), any(), any(), any(), any());
    verify(journalEntryRepository, never()).save(any());
  }

  @Test
  @Tag("critical")
  void settleSupplierInvoices_replayRejectsDifferentSettlementDate() {
    Supplier supplier = new Supplier();
    supplier.setName("Replay Supplier");
    supplier.setStatus(SupplierStatus.ACTIVE);
    ReflectionTestUtils.setField(supplier, "id", 1L);

    Account payable = new Account();
    payable.setCompany(company);
    payable.setCode("AP-REPLAY");
    payable.setType(AccountType.LIABILITY);
    ReflectionTestUtils.setField(payable, "id", 10L);
    supplier.setPayableAccount(payable);

    Account cash = new Account();
    cash.setCompany(company);
    cash.setCode("CASH-REPLAY");
    cash.setType(AccountType.ASSET);
    ReflectionTestUtils.setField(cash, "id", 20L);

    LocalDate persistedSettlementDate = LocalDate.of(2024, 4, 9);
    LocalDate replaySettlementDate = persistedSettlementDate.plusDays(1);

    JournalEntry existingEntry = new JournalEntry();
    ReflectionTestUtils.setField(existingEntry, "id", 912L);
    existingEntry.setSupplier(supplier);
    existingEntry.setReferenceNumber("SUP-SETTLE-DATE-REPLAY-1");
    existingEntry.setEntryDate(persistedSettlementDate);
    existingEntry.setMemo("Supplier settlement replay");
    existingEntry.addLine(
            journalLine(
                existingEntry,
                payable,
                "Supplier settlement replay",
                new BigDecimal("100.00"),
                BigDecimal.ZERO));
    existingEntry.addLine(
            journalLine(
                existingEntry,
                cash,
                "Supplier settlement replay",
                BigDecimal.ZERO,
                new BigDecimal("100.00")));

    PartnerSettlementAllocation existingRow = new PartnerSettlementAllocation();
    existingRow.setCompany(company);
    existingRow.setPartnerType(
        com.bigbrightpaints.erp.modules.accounting.domain.PartnerType.SUPPLIER);
    existingRow.setSupplier(supplier);
    existingRow.setJournalEntry(existingEntry);
    existingRow.setSettlementDate(persistedSettlementDate);
    existingRow.setAllocationAmount(new BigDecimal("100.00"));
    existingRow.setDiscountAmount(BigDecimal.ZERO);
    existingRow.setWriteOffAmount(BigDecimal.ZERO);
    existingRow.setFxDifferenceAmount(BigDecimal.ZERO);
    existingRow.setIdempotencyKey("IDEMP-AP-SETTLE-DATE");
    existingRow.setMemo(null);

    JournalReferenceMapping mapping = new JournalReferenceMapping();
    mapping.setCompany(company);
    mapping.setLegacyReference("idemp-ap-settle-date");
    mapping.setCanonicalReference("SUP-SETTLE-DATE-REPLAY-1");
    mapping.setEntityType("SUPPLIER_SETTLEMENT");
    mapping.setEntityId(null);

    when(supplierRepository.lockByCompanyAndId(eq(company), eq(1L)))
        .thenReturn(Optional.of(supplier));
    when(companyEntityLookup.requireAccount(eq(company), eq(20L))).thenReturn(cash);
    when(journalReferenceMappingRepository.findAllByCompanyAndLegacyReferenceIgnoreCase(
            eq(company), eq("idemp-ap-settle-date")))
        .thenReturn(List.of(mapping));
    when(journalReferenceResolver.findExistingEntry(eq(company), eq("SUP-SETTLE-DATE-REPLAY-1")))
        .thenReturn(Optional.of(existingEntry));
    when(settlementAllocationRepository
            .findByCompanyAndIdempotencyKeyIgnoreCaseOrderByCreatedAtAscIdAsc(
                eq(company), eq("IDEMP-AP-SETTLE-DATE")))
        .thenReturn(List.of(existingRow));

    SupplierSettlementRequest request =
        new SupplierSettlementRequest(
            1L,
            20L,
            null,
            null,
            null,
            null,
            replaySettlementDate,
            null,
            "Supplier settlement replay",
            "IDEMP-AP-SETTLE-DATE",
            Boolean.FALSE,
            List.of(
                new SettlementAllocationRequest(
                    null,
                    null,
                    new BigDecimal("100.00"),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    null)));

    assertThatThrownBy(() -> accountingService.settleSupplierInvoices(request))
        .isInstanceOfSatisfying(
            ApplicationException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CONCURRENCY_CONFLICT))
        .hasMessageContaining("settlement date");
  }

  @Test
  void settleSupplierInvoices_replayRejectsMappingAllocationJournalMismatch() {
    Supplier supplier = new Supplier();
    supplier.setName("Replay Supplier");
    supplier.setStatus(SupplierStatus.ACTIVE);
    ReflectionTestUtils.setField(supplier, "id", 1L);

    Account payable = new Account();
    payable.setCompany(company);
    payable.setCode("AP-REPLAY");
    payable.setType(AccountType.LIABILITY);
    ReflectionTestUtils.setField(payable, "id", 10L);
    supplier.setPayableAccount(payable);

    Account cash = new Account();
    cash.setCompany(company);
    cash.setCode("CASH-REPLAY");
    cash.setType(AccountType.ASSET);
    ReflectionTestUtils.setField(cash, "id", 20L);

    JournalEntry mappingEntry = new JournalEntry();
    ReflectionTestUtils.setField(mappingEntry, "id", 901L);
    mappingEntry.setSupplier(supplier);
    mappingEntry.setReferenceNumber("SUP-SETTLE-MAP-1");

    JournalEntry allocationEntry = new JournalEntry();
    ReflectionTestUtils.setField(allocationEntry, "id", 902L);
    allocationEntry.setSupplier(supplier);
    allocationEntry.setReferenceNumber("SUP-SETTLE-ALLOC-1");

    com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation existingRow =
        new com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation();
    existingRow.setCompany(company);
    existingRow.setPartnerType(
        com.bigbrightpaints.erp.modules.accounting.domain.PartnerType.SUPPLIER);
    existingRow.setSupplier(supplier);
    existingRow.setJournalEntry(allocationEntry);
    existingRow.setSettlementDate(LocalDate.of(2024, 4, 9));
    existingRow.setAllocationAmount(new BigDecimal("100.00"));
    existingRow.setDiscountAmount(BigDecimal.ZERO);
    existingRow.setWriteOffAmount(BigDecimal.ZERO);
    existingRow.setFxDifferenceAmount(BigDecimal.ZERO);
    existingRow.setIdempotencyKey("IDEMP-AP-MISMATCH");
    existingRow.setMemo(null);

    JournalReferenceMapping mapping = new JournalReferenceMapping();
    mapping.setCompany(company);
    mapping.setLegacyReference("idemp-ap-mismatch");
    mapping.setCanonicalReference("SUP-SETTLE-MAP-1");
    mapping.setEntityType("SUPPLIER_SETTLEMENT");
    mapping.setEntityId(null);

    when(supplierRepository.lockByCompanyAndId(eq(company), eq(1L)))
        .thenReturn(Optional.of(supplier));
    when(companyEntityLookup.requireAccount(eq(company), eq(20L))).thenReturn(cash);
    when(journalReferenceMappingRepository.findAllByCompanyAndLegacyReferenceIgnoreCase(
            eq(company), eq("idemp-ap-mismatch")))
        .thenReturn(List.of(mapping));
    when(journalReferenceResolver.findExistingEntry(eq(company), eq("SUP-SETTLE-MAP-1")))
        .thenReturn(Optional.of(mappingEntry));
    when(settlementAllocationRepository
            .findByCompanyAndIdempotencyKeyIgnoreCaseOrderByCreatedAtAscIdAsc(
                eq(company), eq("IDEMP-AP-MISMATCH")))
        .thenReturn(List.of(existingRow));

    SettlementAllocationRequest allocation =
        new SettlementAllocationRequest(
            null,
            null,
            new BigDecimal("100.00"),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            null);

    SupplierSettlementRequest request =
        new SupplierSettlementRequest(
            1L,
            20L,
            null,
            null,
            null,
            null,
            LocalDate.of(2024, 4, 9),
            null,
            "Supplier settlement replay",
            "IDEMP-AP-MISMATCH",
            Boolean.FALSE,
            List.of(allocation));

    assertThatThrownBy(() -> accountingService.settleSupplierInvoices(request))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("different journal than settled allocations");
  }

  @Test
  void settleSupplierInvoices_replayRejectsMappingAllocationJournalMismatchOnLeaderFastPath() {
    Supplier supplier = new Supplier();
    supplier.setName("Replay Supplier");
    supplier.setStatus(SupplierStatus.ACTIVE);
    ReflectionTestUtils.setField(supplier, "id", 1L);

    Account payable = new Account();
    payable.setCompany(company);
    payable.setCode("AP-REPLAY");
    payable.setType(AccountType.LIABILITY);
    ReflectionTestUtils.setField(payable, "id", 10L);
    supplier.setPayableAccount(payable);

    Account cash = new Account();
    cash.setCompany(company);
    cash.setCode("CASH-REPLAY");
    cash.setType(AccountType.ASSET);
    ReflectionTestUtils.setField(cash, "id", 20L);

    JournalEntry mappingEntry = new JournalEntry();
    ReflectionTestUtils.setField(mappingEntry, "id", 1901L);
    mappingEntry.setSupplier(supplier);
    mappingEntry.setReferenceNumber("SUP-SETTLE-MAP-LEADER-1");

    JournalEntry allocationEntry = new JournalEntry();
    ReflectionTestUtils.setField(allocationEntry, "id", 1902L);
    allocationEntry.setSupplier(supplier);
    allocationEntry.setReferenceNumber("SUP-SETTLE-ALLOC-LEADER-1");

    com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation existingRow =
        new com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation();
    existingRow.setCompany(company);
    existingRow.setPartnerType(
        com.bigbrightpaints.erp.modules.accounting.domain.PartnerType.SUPPLIER);
    existingRow.setSupplier(supplier);
    existingRow.setJournalEntry(allocationEntry);
    existingRow.setSettlementDate(LocalDate.of(2024, 4, 9));
    existingRow.setAllocationAmount(new BigDecimal("100.00"));
    existingRow.setDiscountAmount(BigDecimal.ZERO);
    existingRow.setWriteOffAmount(BigDecimal.ZERO);
    existingRow.setFxDifferenceAmount(BigDecimal.ZERO);
    existingRow.setIdempotencyKey("IDEMP-AP-MISMATCH-LEADER");
    existingRow.setMemo(null);

    when(supplierRepository.lockByCompanyAndId(eq(company), eq(1L)))
        .thenReturn(Optional.of(supplier));
    lenient().when(companyEntityLookup.requireAccount(eq(company), eq(20L))).thenReturn(cash);
    when(journalReferenceResolver.findExistingEntry(eq(company), eq("SUP-SETTLE-MAP-LEADER-1")))
        .thenReturn(Optional.of(mappingEntry));
    when(settlementAllocationRepository
            .findByCompanyAndIdempotencyKeyIgnoreCaseOrderByCreatedAtAscIdAsc(
                eq(company), eq("IDEMP-AP-MISMATCH-LEADER")))
        .thenReturn(List.of(existingRow));

    SettlementAllocationRequest allocation =
        new SettlementAllocationRequest(
            null,
            null,
            new BigDecimal("100.00"),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            null);

    SupplierSettlementRequest request =
        new SupplierSettlementRequest(
            1L,
            20L,
            null,
            null,
            null,
            null,
            LocalDate.of(2024, 4, 9),
            "SUP-SETTLE-MAP-LEADER-1",
            "Supplier settlement replay",
            "IDEMP-AP-MISMATCH-LEADER",
            Boolean.FALSE,
            List.of(allocation));

    assertThatThrownBy(() -> accountingService.settleSupplierInvoices(request))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("different journal than settled allocations");
  }

  @Test
  @Tag("critical")
  void settleSupplierInvoices_nonLeaderReplayMissingAllocationsIncludesPartnerDetails() {
    Supplier supplier = new Supplier();
    supplier.setName("Supplier");
    supplier.setStatus(SupplierStatus.ACTIVE);
    ReflectionTestUtils.setField(supplier, "id", 1L);

    Account payable = new Account();
    payable.setCompany(company);
    payable.setCode("AP-RACE");
    payable.setType(AccountType.LIABILITY);
    ReflectionTestUtils.setField(payable, "id", 10L);
    supplier.setPayableAccount(payable);

    Account cash = new Account();
    cash.setCompany(company);
    cash.setCode("CASH-RACE");
    cash.setType(AccountType.ASSET);
    ReflectionTestUtils.setField(cash, "id", 20L);

    JournalEntry existingEntry = new JournalEntry();
    ReflectionTestUtils.setField(existingEntry, "id", 992L);
    existingEntry.setSupplier(supplier);
    existingEntry.setReferenceNumber("SUP-SETTLE-RACE-1");

    JournalReferenceMapping mapping = new JournalReferenceMapping();
    mapping.setCompany(company);
    mapping.setLegacyReference("idemp-ap-race-miss");
    mapping.setCanonicalReference("SUP-SETTLE-RACE-1");
    mapping.setEntityType("SUPPLIER_SETTLEMENT");
    mapping.setEntityId(null);

    when(supplierRepository.lockByCompanyAndId(eq(company), eq(1L)))
        .thenReturn(Optional.of(supplier));
    lenient().when(companyEntityLookup.requireAccount(eq(company), eq(20L))).thenReturn(cash);
    when(journalReferenceMappingRepository.findAllByCompanyAndLegacyReferenceIgnoreCase(
            eq(company), eq("idemp-ap-race-miss")))
        .thenReturn(List.of(mapping));
    when(settlementAllocationRepository.findByCompanyAndIdempotencyKey(
            eq(company), eq("IDEMP-AP-RACE-MISS")))
        .thenReturn(List.of());
    when(journalReferenceResolver.findExistingEntry(eq(company), eq("SUP-SETTLE-RACE-1")))
        .thenReturn(Optional.of(existingEntry));
    when(settlementAllocationRepository
            .findByCompanyAndIdempotencyKeyIgnoreCaseOrderByCreatedAtAscIdAsc(
                eq(company), eq("IDEMP-AP-RACE-MISS")))
        .thenReturn(List.of());

    SupplierSettlementRequest request =
        new SupplierSettlementRequest(
            1L,
            20L,
            null,
            null,
            null,
            null,
            LocalDate.of(2024, 4, 9),
            "SUP-SETTLE-RACE-1",
            "Supplier settlement replay race",
            "IDEMP-AP-RACE-MISS",
            Boolean.FALSE,
            List.of(
                new SettlementAllocationRequest(
                    null,
                    null,
                    new BigDecimal("100.00"),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    null)));

    assertThatThrownBy(() -> accountingService.settleSupplierInvoices(request))
        .isInstanceOfSatisfying(
            ApplicationException.class,
            ex -> {
              assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INTERNAL_CONCURRENCY_FAILURE);
              assertPartnerReplayDetails(ex, "IDEMP-AP-RACE-MISS", "SUPPLIER", 1L);
            })
        .hasMessageContaining(
            "Supplier settlement idempotency key is reserved but allocation not found");
  }

  @Test
  @Tag("critical")
  void settleSupplierInvoices_replayPartnerMismatchIncludesPartnerDetails() {
    Supplier supplier = new Supplier();
    supplier.setName("Supplier");
    supplier.setStatus(SupplierStatus.ACTIVE);
    ReflectionTestUtils.setField(supplier, "id", 1L);

    Supplier otherSupplier = new Supplier();
    otherSupplier.setName("Other Supplier");
    otherSupplier.setStatus(SupplierStatus.ACTIVE);
    ReflectionTestUtils.setField(otherSupplier, "id", 2L);

    Account payable = new Account();
    payable.setCompany(company);
    payable.setCode("AP-RACE");
    payable.setType(AccountType.LIABILITY);
    ReflectionTestUtils.setField(payable, "id", 10L);
    supplier.setPayableAccount(payable);

    Account cash = new Account();
    cash.setCompany(company);
    cash.setCode("CASH-RACE");
    cash.setType(AccountType.ASSET);
    ReflectionTestUtils.setField(cash, "id", 20L);

    JournalEntry existingEntry = new JournalEntry();
    ReflectionTestUtils.setField(existingEntry, "id", 994L);
    existingEntry.setSupplier(otherSupplier);
    existingEntry.setEntryDate(LocalDate.of(2024, 4, 9));
    existingEntry.setReferenceNumber("SUP-SETTLE-RACE-PARTNER-1");

    com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation existingRow =
        new com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation();
    existingRow.setCompany(company);
    existingRow.setPartnerType(
        com.bigbrightpaints.erp.modules.accounting.domain.PartnerType.SUPPLIER);
    existingRow.setSupplier(supplier);
    existingRow.setJournalEntry(existingEntry);
    existingRow.setSettlementDate(LocalDate.of(2024, 4, 9));
    existingRow.setAllocationAmount(new BigDecimal("100.00"));
    existingRow.setDiscountAmount(BigDecimal.ZERO);
    existingRow.setWriteOffAmount(BigDecimal.ZERO);
    existingRow.setFxDifferenceAmount(BigDecimal.ZERO);
    existingRow.setIdempotencyKey("IDEMP-AP-RACE-PARTNER");

    JournalReferenceMapping mapping = new JournalReferenceMapping();
    mapping.setCompany(company);
    mapping.setLegacyReference("idemp-ap-race-partner");
    mapping.setCanonicalReference("SUP-SETTLE-RACE-PARTNER-1");
    mapping.setEntityType("SUPPLIER_SETTLEMENT");
    mapping.setEntityId(null);

    when(supplierRepository.lockByCompanyAndId(eq(company), eq(1L)))
        .thenReturn(Optional.of(supplier));
    when(companyEntityLookup.requireAccount(eq(company), eq(20L))).thenReturn(cash);
    when(journalReferenceMappingRepository.findAllByCompanyAndLegacyReferenceIgnoreCase(
            eq(company), eq("idemp-ap-race-partner")))
        .thenReturn(List.of(mapping));
    when(journalReferenceResolver.findExistingEntry(eq(company), eq("SUP-SETTLE-RACE-PARTNER-1")))
        .thenReturn(Optional.of(existingEntry));
    when(settlementAllocationRepository
            .findByCompanyAndIdempotencyKeyIgnoreCaseOrderByCreatedAtAscIdAsc(
                eq(company), eq("IDEMP-AP-RACE-PARTNER")))
        .thenReturn(List.of(existingRow));

    SupplierSettlementRequest request =
        new SupplierSettlementRequest(
            1L,
            20L,
            null,
            null,
            null,
            null,
            LocalDate.of(2024, 4, 9),
            "SUP-SETTLE-RACE-PARTNER-1",
            "Supplier settlement replay partner mismatch",
            "IDEMP-AP-RACE-PARTNER",
            Boolean.FALSE,
            List.of(
                new SettlementAllocationRequest(
                    null,
                    null,
                    new BigDecimal("100.00"),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    null)));

    assertThatThrownBy(() -> accountingService.settleSupplierInvoices(request))
        .isInstanceOfSatisfying(
            ApplicationException.class,
            ex -> {
              assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CONCURRENCY_CONFLICT);
              assertPartnerReplayDetails(ex, "IDEMP-AP-RACE-PARTNER", "SUPPLIER", 1L);
            })
        .hasMessageContaining("another supplier");
  }

  @Test
  void settleSupplierInvoices_rejectsInactiveCashAccount() {
    AccountingService service = spy(accountingService);

    Supplier supplier = new Supplier();
    supplier.setName("Supplier");
    supplier.setStatus(SupplierStatus.ACTIVE);
    Account payable = new Account();
    payable.setCompany(company);
    payable.setCode("AP-SUP");
    payable.setType(AccountType.LIABILITY);
    ReflectionTestUtils.setField(payable, "id", 10L);
    supplier.setPayableAccount(payable);
    ReflectionTestUtils.setField(supplier, "id", 1L);

    Account inactiveCash = new Account();
    inactiveCash.setCompany(company);
    inactiveCash.setCode("BANK-INACTIVE");
    inactiveCash.setType(AccountType.ASSET);
    inactiveCash.setActive(false);
    ReflectionTestUtils.setField(inactiveCash, "id", 20L);

    when(supplierRepository.lockByCompanyAndId(eq(company), eq(1L)))
        .thenReturn(Optional.of(supplier));
    when(companyEntityLookup.requireAccount(eq(company), eq(20L))).thenReturn(inactiveCash);

    SettlementAllocationRequest allocation =
        new SettlementAllocationRequest(
            null, 2L, new BigDecimal("100.00"), BigDecimal.ZERO, BigDecimal.ZERO, null, null);

    SupplierSettlementRequest request =
        new SupplierSettlementRequest(
            1L,
            20L,
            null,
            null,
            null,
            null,
            LocalDate.of(2024, 4, 9),
            "REF-AP-INACTIVE",
            "Supplier settlement",
            "IDEMP-AP-INACTIVE",
            Boolean.FALSE,
            List.of(allocation));

    assertThatThrownBy(() -> service.settleSupplierInvoices(request))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("must be active");
  }

  @Test
  void settleSupplierInvoices_rejectsNonAssetCashAccount() {
    AccountingService service = spy(accountingService);

    Supplier supplier = new Supplier();
    supplier.setName("Supplier");
    supplier.setStatus(SupplierStatus.ACTIVE);
    Account payable = new Account();
    payable.setCompany(company);
    payable.setCode("AP-SUP");
    payable.setType(AccountType.LIABILITY);
    ReflectionTestUtils.setField(payable, "id", 10L);
    supplier.setPayableAccount(payable);
    ReflectionTestUtils.setField(supplier, "id", 1L);

    Account invalidCash = new Account();
    invalidCash.setCompany(company);
    invalidCash.setCode("AP-CLEARING");
    invalidCash.setType(AccountType.LIABILITY);
    ReflectionTestUtils.setField(invalidCash, "id", 20L);

    when(supplierRepository.lockByCompanyAndId(eq(company), eq(1L)))
        .thenReturn(Optional.of(supplier));
    when(companyEntityLookup.requireAccount(eq(company), eq(20L))).thenReturn(invalidCash);

    SettlementAllocationRequest allocation =
        new SettlementAllocationRequest(
            null, 2L, new BigDecimal("100.00"), BigDecimal.ZERO, BigDecimal.ZERO, null, null);

    SupplierSettlementRequest request =
        new SupplierSettlementRequest(
            1L,
            20L,
            null,
            null,
            null,
            null,
            LocalDate.of(2024, 4, 9),
            "REF-AP-2",
            "Supplier settlement",
            "IDEMP-AP-2",
            Boolean.FALSE,
            List.of(allocation));

    assertThatThrownBy(() -> service.settleSupplierInvoices(request))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("must be an ASSET account");
  }

  @Test
  void autoSettleDealer_allocatesOldestOutstandingInvoicesInFifoOrder() {
    AccountingService service = spy(accountingService);

    Dealer dealer = new Dealer();
    ReflectionTestUtils.setField(dealer, "id", 1L);
    dealer.setName("Dealer Auto");
    dealer.setReceivableAccount(account(1030L, "AR-AUTO", AccountType.ASSET));

    Invoice oldest = new Invoice();
    ReflectionTestUtils.setField(oldest, "id", 101L);
    oldest.setOutstandingAmount(new BigDecimal("100.00"));
    oldest.setIssueDate(LocalDate.of(2026, 1, 1));

    Invoice newer = new Invoice();
    ReflectionTestUtils.setField(newer, "id", 102L);
    newer.setOutstandingAmount(new BigDecimal("90.00"));
    newer.setIssueDate(LocalDate.of(2026, 1, 5));

    when(dealerRepository.lockByCompanyAndId(company, 1L)).thenReturn(Optional.of(dealer));
    when(invoiceRepository.lockOpenInvoicesForSettlement(company, dealer))
        .thenReturn(List.of(oldest, newer));

    JournalEntry persistedEntry = new JournalEntry();
    ReflectionTestUtils.setField(persistedEntry, "id", 501L);
    when(companyEntityLookup.requireJournalEntry(company, 501L)).thenReturn(persistedEntry);

    PartnerSettlementAllocation allocationOne = new PartnerSettlementAllocation();
    allocationOne.setInvoice(oldest);
    allocationOne.setAllocationAmount(new BigDecimal("100.00"));
    PartnerSettlementAllocation allocationTwo = new PartnerSettlementAllocation();
    allocationTwo.setInvoice(newer);
    allocationTwo.setAllocationAmount(new BigDecimal("80.00"));
    when(settlementAllocationRepository.findByCompanyAndJournalEntryOrderByCreatedAtAsc(
            company, persistedEntry))
        .thenReturn(List.of(allocationOne, allocationTwo));

    ArgumentCaptor<DealerReceiptRequest> requestCaptor =
        ArgumentCaptor.forClass(DealerReceiptRequest.class);
    doReturn(stubEntry(501L))
        .when(dealerReceiptService)
        .recordDealerReceiptNormalized(requestCaptor.capture());

    AutoSettlementRequest request =
        new AutoSettlementRequest(
            900L,
            new BigDecimal("180.00"),
            "AUTO-RCPT-1",
            "auto-settlement",
            "IDEMP-AUTO-DEALER-1");

    PartnerSettlementResponse response = service.autoSettleDealer(1L, request);

    DealerReceiptRequest forwarded = requestCaptor.getValue();
    assertThat(forwarded.dealerId()).isEqualTo(1L);
    assertThat(forwarded.amount()).isEqualByComparingTo("180.00");
    assertThat(forwarded.allocations()).hasSize(2);
    assertThat(forwarded.allocations().get(0).invoiceId()).isEqualTo(101L);
    assertThat(forwarded.allocations().get(0).appliedAmount()).isEqualByComparingTo("100.00");
    assertThat(forwarded.allocations().get(1).invoiceId()).isEqualTo(102L);
    assertThat(forwarded.allocations().get(1).appliedAmount()).isEqualByComparingTo("80.00");

    assertThat(response.totalApplied()).isEqualByComparingTo("180.00");
    assertThat(response.allocations()).hasSize(2);
  }

  @Test
  void autoSettleSupplier_allocatesOldestOutstandingPurchasesInFifoOrder() {
    AccountingService service = spy(accountingService);

    Supplier supplier = new Supplier();
    ReflectionTestUtils.setField(supplier, "id", 2L);
    supplier.setName("Supplier Auto");
    supplier.setStatus(SupplierStatus.ACTIVE);
    supplier.setPayableAccount(account(2030L, "AP-AUTO", AccountType.LIABILITY));

    RawMaterialPurchase oldest = new RawMaterialPurchase();
    ReflectionTestUtils.setField(oldest, "id", 201L);
    oldest.setOutstandingAmount(new BigDecimal("60.00"));
    oldest.setInvoiceDate(LocalDate.of(2026, 1, 2));

    RawMaterialPurchase newer = new RawMaterialPurchase();
    ReflectionTestUtils.setField(newer, "id", 202L);
    newer.setOutstandingAmount(new BigDecimal("75.00"));
    newer.setInvoiceDate(LocalDate.of(2026, 1, 7));

    when(supplierRepository.lockByCompanyAndId(company, 2L)).thenReturn(Optional.of(supplier));
    when(rawMaterialPurchaseRepository.lockOpenPurchasesForSettlement(company, supplier))
        .thenReturn(List.of(oldest, newer));

    JournalEntry persistedEntry = new JournalEntry();
    ReflectionTestUtils.setField(persistedEntry, "id", 601L);
    when(companyEntityLookup.requireJournalEntry(company, 601L)).thenReturn(persistedEntry);

    PartnerSettlementAllocation allocationOne = new PartnerSettlementAllocation();
    allocationOne.setPurchase(oldest);
    allocationOne.setAllocationAmount(new BigDecimal("60.00"));
    PartnerSettlementAllocation allocationTwo = new PartnerSettlementAllocation();
    allocationTwo.setPurchase(newer);
    allocationTwo.setAllocationAmount(new BigDecimal("40.00"));
    when(settlementAllocationRepository.findByCompanyAndJournalEntryOrderByCreatedAtAsc(
            company, persistedEntry))
        .thenReturn(List.of(allocationOne, allocationTwo));

    ArgumentCaptor<SupplierPaymentRequest> requestCaptor =
        ArgumentCaptor.forClass(SupplierPaymentRequest.class);
    doReturn(stubEntry(601L))
        .when(settlementService)
        .recordSupplierPaymentInternal(requestCaptor.capture());

    AutoSettlementRequest request =
        new AutoSettlementRequest(
            901L,
            new BigDecimal("100.00"),
            "AUTO-PAY-1",
            "auto-settlement supplier",
            "IDEMP-AUTO-SUPPLIER-1");

    PartnerSettlementResponse response = service.autoSettleSupplier(2L, request);

    SupplierPaymentRequest forwarded = requestCaptor.getValue();
    assertThat(forwarded.supplierId()).isEqualTo(2L);
    assertThat(forwarded.amount()).isEqualByComparingTo("100.00");
    assertThat(forwarded.allocations()).hasSize(2);
    assertThat(forwarded.allocations().get(0).purchaseId()).isEqualTo(201L);
    assertThat(forwarded.allocations().get(0).appliedAmount()).isEqualByComparingTo("60.00");
    assertThat(forwarded.allocations().get(1).purchaseId()).isEqualTo(202L);
    assertThat(forwarded.allocations().get(1).appliedAmount()).isEqualByComparingTo("40.00");

    assertThat(response.totalApplied()).isEqualByComparingTo("100.00");
    assertThat(response.allocations()).hasSize(2);
  }

  @Test
  void autoSettleSupplier_generatesDeterministicReferenceAndIdempotencyForAmountOnlyRequests() {
    AccountingService service = spy(accountingService);

    Supplier supplier = new Supplier();
    ReflectionTestUtils.setField(supplier, "id", 2L);
    supplier.setCode("SUP-02");
    supplier.setName("Supplier Auto");
    supplier.setStatus(SupplierStatus.ACTIVE);
    supplier.setPayableAccount(account(2031L, "AP-AUTO-2", AccountType.LIABILITY));

    RawMaterialPurchase oldest = new RawMaterialPurchase();
    ReflectionTestUtils.setField(oldest, "id", 201L);
    oldest.setOutstandingAmount(new BigDecimal("60.00"));
    oldest.setInvoiceDate(LocalDate.of(2026, 1, 2));

    RawMaterialPurchase newer = new RawMaterialPurchase();
    ReflectionTestUtils.setField(newer, "id", 202L);
    newer.setOutstandingAmount(new BigDecimal("75.00"));
    newer.setInvoiceDate(LocalDate.of(2026, 1, 7));

    when(supplierRepository.lockByCompanyAndId(company, 2L)).thenReturn(Optional.of(supplier));
    when(rawMaterialPurchaseRepository.lockOpenPurchasesForSettlement(company, supplier))
        .thenReturn(List.of(oldest, newer));

    JournalEntry persistedEntry = new JournalEntry();
    ReflectionTestUtils.setField(persistedEntry, "id", 601L);
    when(companyEntityLookup.requireJournalEntry(company, 601L)).thenReturn(persistedEntry);

    PartnerSettlementAllocation allocationOne = new PartnerSettlementAllocation();
    allocationOne.setPurchase(oldest);
    allocationOne.setAllocationAmount(new BigDecimal("60.00"));
    PartnerSettlementAllocation allocationTwo = new PartnerSettlementAllocation();
    allocationTwo.setPurchase(newer);
    allocationTwo.setAllocationAmount(new BigDecimal("40.00"));
    when(settlementAllocationRepository.findByCompanyAndJournalEntryOrderByCreatedAtAsc(
            company, persistedEntry))
        .thenReturn(List.of(allocationOne, allocationTwo));

    ArgumentCaptor<SupplierPaymentRequest> requestCaptor =
        ArgumentCaptor.forClass(SupplierPaymentRequest.class);
    doReturn(stubEntry(601L))
        .when(settlementService)
        .recordSupplierPaymentInternal(requestCaptor.capture());

    AutoSettlementRequest request =
        new AutoSettlementRequest(
            901L, new BigDecimal("100.00"), null, "auto-settlement supplier", null);

    service.autoSettleSupplier(2L, request);

    SupplierPaymentRequest forwarded = requestCaptor.getValue();
    assertThat(forwarded.referenceNumber()).isNotBlank();
    assertThat(forwarded.referenceNumber()).startsWith("SUP-SET-");
    assertThat(forwarded.idempotencyKey()).isEqualTo(forwarded.referenceNumber());
  }

  @Test
  void autoSettleDealer_rebuildsImplicitReplayKeyWhenOpenInvoicesChange() {
    Dealer dealer =
        dealer(91L, "Dealer Drift", account(1091L, "AR-1091", AccountType.ASSET));
    Account cash = account(2091L, "CASH-2091", AccountType.ASSET);

    Invoice firstOldest = invoice(9101L, dealer, "INV-9101", new BigDecimal("100.00"));
    firstOldest.setIssueDate(LocalDate.of(2026, 1, 1));
    Invoice firstNewer = invoice(9102L, dealer, "INV-9102", new BigDecimal("90.00"));
    firstNewer.setIssueDate(LocalDate.of(2026, 1, 5));
    Invoice secondOldest = invoice(9102L, dealer, "INV-9102", new BigDecimal("90.00"));
    secondOldest.setIssueDate(LocalDate.of(2026, 1, 5));
    Invoice secondNewer = invoice(9103L, dealer, "INV-9103", new BigDecimal("120.00"));
    secondNewer.setIssueDate(LocalDate.of(2026, 1, 8));

    when(dealerRepository.lockByCompanyAndId(company, 91L)).thenReturn(Optional.of(dealer));
    when(companyEntityLookup.requireAccount(company, 2091L)).thenReturn(cash);
    when(invoiceRepository.lockOpenInvoicesForSettlement(company, dealer))
        .thenReturn(List.of(firstOldest, firstNewer))
        .thenReturn(List.of(secondOldest, secondNewer));
    when(invoiceRepository.lockByCompanyAndId(company, 9101L)).thenReturn(Optional.of(firstOldest));
    when(invoiceRepository.lockByCompanyAndId(company, 9102L))
        .thenReturn(Optional.of(firstNewer), Optional.of(secondOldest));
    when(invoiceRepository.lockByCompanyAndId(company, 9103L)).thenReturn(Optional.of(secondNewer));

    Map<String, List<PartnerSettlementAllocation>> allocationsByIdempotency = new java.util.HashMap<>();
    Map<Long, List<PartnerSettlementAllocation>> allocationsByJournalEntryId = new java.util.HashMap<>();
    Map<Long, JournalEntry> persistedEntries = new java.util.HashMap<>();
    AtomicInteger nextEntryId = new AtomicInteger(9500);

    doAnswer(
            invocation -> {
              JournalEntryRequest payload = invocation.getArgument(0);
              long entryId = nextEntryId.incrementAndGet();
              JournalEntry entry = journalEntry(entryId, payload.referenceNumber());
              entry.setDealer(dealer);
              entry.setEntryDate(payload.entryDate());
              entry.setMemo(payload.memo());
              for (JournalEntryRequest.JournalLineRequest line : payload.lines()) {
                addJournalLine(
                    entry,
                    account(line.accountId(), "ACC-" + line.accountId(), AccountType.ASSET),
                    line.description(),
                    line.debit(),
                    line.credit());
              }
              persistedEntries.put(entryId, entry);
              return journalEntryDto(entryId, payload.referenceNumber());
            })
        .when(dealerReceiptService)
        .createJournalEntry(any(JournalEntryRequest.class));
    when(companyEntityLookup.requireJournalEntry(eq(company), any(Long.class)))
        .thenAnswer(invocation -> persistedEntries.get(invocation.getArgument(1)));
    when(settlementAllocationRepository
            .findByCompanyAndIdempotencyKeyIgnoreCaseOrderByCreatedAtAscIdAsc(eq(company), any()))
        .thenAnswer(
            invocation ->
                allocationsByIdempotency.getOrDefault(
                    ((String) invocation.getArgument(1)).trim().toUpperCase(), List.of()));
    when(settlementAllocationRepository.findByCompanyAndJournalEntryOrderByCreatedAtAsc(
            eq(company), any(JournalEntry.class)))
        .thenAnswer(
            invocation -> {
              JournalEntry entry = invocation.getArgument(1);
              if (entry == null || entry.getId() == null) {
                return List.of();
              }
              return allocationsByJournalEntryId.getOrDefault(entry.getId(), List.of());
            });
    when(settlementAllocationRepository.saveAll(any()))
        .thenAnswer(
            invocation -> {
              @SuppressWarnings("unchecked")
              List<PartnerSettlementAllocation> rows = invocation.getArgument(0);
              List<PartnerSettlementAllocation> stored = new ArrayList<>(rows);
              for (PartnerSettlementAllocation row : stored) {
                String key = row.getIdempotencyKey().trim().toUpperCase();
                allocationsByIdempotency.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row);
                Long entryId = row.getJournalEntry().getId();
                allocationsByJournalEntryId
                    .computeIfAbsent(entryId, ignored -> new ArrayList<>())
                    .add(row);
              }
              return stored;
            });

    AutoSettlementRequest request =
        new AutoSettlementRequest(
            2091L, new BigDecimal("180.00"), null, "dealer drift auto-settle", null);

    PartnerSettlementResponse first = accountingService.autoSettleDealer(91L, request);
    PartnerSettlementResponse second = accountingService.autoSettleDealer(91L, request);

    assertThat(first.allocations()).hasSize(2);
    assertThat(first.allocations().get(0).invoiceId()).isEqualTo(9101L);
    assertThat(first.allocations().get(1).invoiceId()).isEqualTo(9102L);

    assertThat(second.allocations()).hasSize(2);
    assertThat(second.allocations().get(0).invoiceId()).isEqualTo(9102L);
    assertThat(second.allocations().get(0).appliedAmount()).isEqualByComparingTo("90.00");
    assertThat(second.allocations().get(1).invoiceId()).isEqualTo(9103L);
    assertThat(second.allocations().get(1).appliedAmount()).isEqualByComparingTo("90.00");
    assertThat(second.journalEntry().id()).isNotEqualTo(first.journalEntry().id());
  }

  @Test
  void autoSettleSupplier_rebuildsImplicitReplayKeyWhenOpenPurchasesChange() {
    Supplier supplier =
        supplier(92L, "Supplier Drift", account(3092L, "AP-3092", AccountType.LIABILITY));
    supplier.setStatus(SupplierStatus.ACTIVE);
    Account cash = account(4092L, "CASH-4092", AccountType.ASSET);

    RawMaterialPurchase firstOldest =
        purchase(9201L, "PUR-9201", supplier, new BigDecimal("60.00"), new BigDecimal("60.00"), "POSTED");
    firstOldest.setInvoiceDate(LocalDate.of(2026, 1, 2));
    RawMaterialPurchase firstNewer =
        purchase(9202L, "PUR-9202", supplier, new BigDecimal("75.00"), new BigDecimal("75.00"), "POSTED");
    firstNewer.setInvoiceDate(LocalDate.of(2026, 1, 7));
    RawMaterialPurchase secondOldest =
        purchase(9202L, "PUR-9202", supplier, new BigDecimal("75.00"), new BigDecimal("75.00"), "POSTED");
    secondOldest.setInvoiceDate(LocalDate.of(2026, 1, 7));
    RawMaterialPurchase secondNewer =
        purchase(9203L, "PUR-9203", supplier, new BigDecimal("40.00"), new BigDecimal("40.00"), "POSTED");
    secondNewer.setInvoiceDate(LocalDate.of(2026, 1, 10));

    when(supplierRepository.lockByCompanyAndId(company, 92L)).thenReturn(Optional.of(supplier));
    when(companyEntityLookup.requireAccount(company, 4092L)).thenReturn(cash);
    when(rawMaterialPurchaseRepository.lockOpenPurchasesForSettlement(company, supplier))
        .thenReturn(List.of(firstOldest, firstNewer))
        .thenReturn(List.of(secondOldest, secondNewer));
    when(rawMaterialPurchaseRepository.lockByCompanyAndId(company, 9201L))
        .thenReturn(Optional.of(firstOldest));
    when(rawMaterialPurchaseRepository.lockByCompanyAndId(company, 9202L))
        .thenReturn(Optional.of(firstNewer), Optional.of(secondOldest));
    when(rawMaterialPurchaseRepository.lockByCompanyAndId(company, 9203L))
        .thenReturn(Optional.of(secondNewer));

    Map<String, List<PartnerSettlementAllocation>> allocationsByIdempotency = new java.util.HashMap<>();
    Map<Long, List<PartnerSettlementAllocation>> allocationsByJournalEntryId = new java.util.HashMap<>();
    Map<Long, JournalEntry> persistedEntries = new java.util.HashMap<>();
    AtomicInteger nextEntryId = new AtomicInteger(9600);

    doAnswer(
            invocation -> {
              JournalEntryRequest payload = invocation.getArgument(0);
              long entryId = nextEntryId.incrementAndGet();
              JournalEntry entry = journalEntry(entryId, payload.referenceNumber());
              entry.setSupplier(supplier);
              entry.setEntryDate(payload.entryDate());
              entry.setMemo(payload.memo());
              for (JournalEntryRequest.JournalLineRequest line : payload.lines()) {
                addJournalLine(
                    entry,
                    account(line.accountId(), "ACC-" + line.accountId(), AccountType.ASSET),
                    line.description(),
                    line.debit(),
                    line.credit());
              }
              persistedEntries.put(entryId, entry);
              return journalEntryDto(entryId, payload.referenceNumber());
            })
        .when(journalEntryService)
        .createJournalEntry(any(JournalEntryRequest.class));
    when(companyEntityLookup.requireJournalEntry(eq(company), any(Long.class)))
        .thenAnswer(invocation -> persistedEntries.get(invocation.getArgument(1)));
    when(settlementAllocationRepository
            .findByCompanyAndIdempotencyKeyIgnoreCaseOrderByCreatedAtAscIdAsc(eq(company), any()))
        .thenAnswer(
            invocation ->
                allocationsByIdempotency.getOrDefault(
                    ((String) invocation.getArgument(1)).trim().toUpperCase(), List.of()));
    when(settlementAllocationRepository.findByCompanyAndJournalEntryOrderByCreatedAtAsc(
            eq(company), any(JournalEntry.class)))
        .thenAnswer(
            invocation -> {
              JournalEntry entry = invocation.getArgument(1);
              if (entry == null || entry.getId() == null) {
                return List.of();
              }
              return allocationsByJournalEntryId.getOrDefault(entry.getId(), List.of());
            });
    when(settlementAllocationRepository.saveAll(any()))
        .thenAnswer(
            invocation -> {
              @SuppressWarnings("unchecked")
              List<PartnerSettlementAllocation> rows = invocation.getArgument(0);
              List<PartnerSettlementAllocation> stored = new ArrayList<>(rows);
              for (PartnerSettlementAllocation row : stored) {
                String key = row.getIdempotencyKey().trim().toUpperCase();
                allocationsByIdempotency.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row);
                Long entryId = row.getJournalEntry().getId();
                allocationsByJournalEntryId
                    .computeIfAbsent(entryId, ignored -> new ArrayList<>())
                    .add(row);
              }
              return stored;
            });

    AutoSettlementRequest request =
        new AutoSettlementRequest(
            4092L, new BigDecimal("100.00"), null, "supplier drift auto-settle", null);

    PartnerSettlementResponse first = accountingService.autoSettleSupplier(92L, request);
    PartnerSettlementResponse second = accountingService.autoSettleSupplier(92L, request);

    assertThat(first.allocations()).hasSize(2);
    assertThat(first.allocations().get(0).purchaseId()).isEqualTo(9201L);
    assertThat(first.allocations().get(1).purchaseId()).isEqualTo(9202L);

    assertThat(second.allocations()).hasSize(2);
    assertThat(second.allocations().get(0).purchaseId()).isEqualTo(9202L);
    assertThat(second.allocations().get(0).appliedAmount()).isEqualByComparingTo("75.00");
    assertThat(second.allocations().get(1).purchaseId()).isEqualTo(9203L);
    assertThat(second.allocations().get(1).appliedAmount()).isEqualByComparingTo("25.00");
    assertThat(second.journalEntry().id()).isNotEqualTo(first.journalEntry().id());
  }

  @Test
  void partnerMismatchMessage_fallbackUsesPartnerTypeWording() {
    String dealerMessage =
        ReflectionTestUtils.invokeMethod(
            accountingService,
            "partnerMismatchMessage",
            com.bigbrightpaints.erp.modules.accounting.domain.PartnerType.DEALER);
    String supplierMessage =
        ReflectionTestUtils.invokeMethod(
            accountingService,
            "partnerMismatchMessage",
            com.bigbrightpaints.erp.modules.accounting.domain.PartnerType.SUPPLIER);
    String message =
        ReflectionTestUtils.invokeMethod(
            accountingService,
            "partnerMismatchMessage",
            (com.bigbrightpaints.erp.modules.accounting.domain.PartnerType) null);

    assertThat(dealerMessage).isEqualTo("Idempotency key already used for another dealer");
    assertThat(supplierMessage).isEqualTo("Idempotency key already used for another supplier");
    assertThat(message).isEqualTo("Idempotency key already used for another partner type");
  }

  @Test
  void ensureDuplicateMatchesExisting_partnerMismatchesExposeCanonicalPartnerTypes() {
    JournalEntry existing = new JournalEntry();
    existing.setReferenceNumber("REF-DUP-1");
    existing.setEntryDate(LocalDate.of(2024, 4, 1));
    existing.setCurrency("INR");
    existing.setFxRate(BigDecimal.ONE);
    existing.setMemo("Duplicate guard");

    Dealer existingDealer = new Dealer();
    ReflectionTestUtils.setField(existingDealer, "id", 10L);
    existing.setDealer(existingDealer);
    Supplier existingSupplier = new Supplier();
    ReflectionTestUtils.setField(existingSupplier, "id", 20L);
    existing.setSupplier(existingSupplier);

    JournalEntry candidate = new JournalEntry();
    candidate.setEntryDate(LocalDate.of(2024, 4, 1));
    candidate.setCurrency("INR");
    candidate.setFxRate(BigDecimal.ONE);
    candidate.setMemo("Duplicate guard");

    Dealer candidateDealer = new Dealer();
    ReflectionTestUtils.setField(candidateDealer, "id", 11L);
    candidate.setDealer(candidateDealer);
    Supplier candidateSupplier = new Supplier();
    ReflectionTestUtils.setField(candidateSupplier, "id", 21L);
    candidate.setSupplier(candidateSupplier);

    assertThatThrownBy(
            () ->
                ReflectionTestUtils.invokeMethod(
                    accountingService,
                    "ensureDuplicateMatchesExisting",
                    existing,
                    candidate,
                    List.of()))
        .isInstanceOf(ApplicationException.class)
        .satisfies(
            ex -> {
              ApplicationException appEx = (ApplicationException) ex;
              assertThat(appEx.getErrorCode()).isEqualTo(ErrorCode.BUSINESS_DUPLICATE_ENTRY);
              assertThat(appEx.getDetails())
                  .containsEntry("partnerMismatchTypes", List.of("DEALER", "SUPPLIER"));
            });
  }

  @Test
  void accountingService_removesDeadDealerPaymentSignatureHelperScaffold() {
    assertThat(
            Arrays.stream(AccountingService.class.getDeclaredMethods())
                .map(java.lang.reflect.Method::getName)
                .toList())
        .doesNotContain("decrementSignatureCount");
    assertThat(Arrays.stream(AccountingService.class.getDeclaredClasses()).map(Class::getSimpleName))
        .doesNotContain("DealerPaymentSignature");
  }

  private void assertPartnerReplayDetails(
      ApplicationException ex, String idempotencyKey, String partnerType, Long partnerId) {
    assertThat(ex.getDetails())
        .containsEntry(IntegrationFailureMetadataSchema.KEY_IDEMPOTENCY_KEY, idempotencyKey)
        .containsEntry(IntegrationFailureMetadataSchema.KEY_PARTNER_TYPE, partnerType)
        .containsEntry(IntegrationFailureMetadataSchema.KEY_PARTNER_ID, partnerId);
  }

  private void assertAllocationReplayDiagnostics(
      ApplicationException ex,
      int existingCount,
      int requestCount,
      String existingAppliedMarker,
      String requestAppliedMarker) {
    assertThat(ex.getDetails())
        .containsEntry("existingAllocationCount", existingCount)
        .containsEntry("requestAllocationCount", requestCount)
        .containsKey("existingAllocationSignatureDigest")
        .containsKey("requestAllocationSignatureDigest");
    assertThat(String.valueOf(ex.getDetails().get("existingAllocationSignatureDigest")))
        .contains(existingAppliedMarker);
    assertThat(String.valueOf(ex.getDetails().get("requestAllocationSignatureDigest")))
        .contains(requestAppliedMarker);
  }

  @Test
  void helperMethods_coverSettlementValidationAndPostingMetadata() {
    Account debit = account(501L, "DEBIT", AccountType.EXPENSE);

    JournalLine countedA =
        journalLine(new JournalEntry(), debit, "line-a", new BigDecimal("10.004"), BigDecimal.ZERO);
    JournalLine countedB =
        journalLine(new JournalEntry(), debit, "line-b", new BigDecimal("10.00"), BigDecimal.ZERO);
    JournalLine ignored =
        journalLine(new JournalEntry(), new Account(), "ignored", BigDecimal.ONE, BigDecimal.ZERO);

    @SuppressWarnings("unchecked")
    Map<Object, Integer> counts =
        ReflectionTestUtils.invokeMethod(
            accountingService, "lineSignatureCounts", List.of(countedA, countedB, ignored));
    assertThat(counts.values()).containsExactly(2);

    Boolean sameCurrency =
        ReflectionTestUtils.invokeMethod(accountingService, "sameCurrency", "inr", "INR");
    Boolean differentCurrency =
        ReflectionTestUtils.invokeMethod(accountingService, "sameCurrency", "INR", null);
    Boolean sameFxRate =
        ReflectionTestUtils.invokeMethod(accountingService, "sameFxRate", null, BigDecimal.ONE);
    Boolean differentFxRate =
        ReflectionTestUtils.invokeMethod(
            accountingService, "sameFxRate", BigDecimal.ONE, new BigDecimal("1.01"));
    String noAttachments =
        ReflectionTestUtils.invokeMethod(
            accountingService, "joinAttachmentReferences", new Object[] {null});
    String blankOnlyAttachments =
        ReflectionTestUtils.invokeMethod(
            accountingService, "joinAttachmentReferences", List.of("   ", ""));
    String joinedAttachments =
        ReflectionTestUtils.invokeMethod(
            accountingService,
            "joinAttachmentReferences",
            List.of(" scan-1 ", "", "scan-2", "scan-1"));

    JournalEntry postingEntry = new JournalEntry();
    postingEntry.setSourceModule(" manual ");
    postingEntry.setSourceReference(" REF-1 ");
    postingEntry.setReferenceNumber("JE-1");

    String documentType =
        ReflectionTestUtils.invokeMethod(
            accountingService, "resolvePostingDocumentType", postingEntry);
    String documentReference =
        ReflectionTestUtils.invokeMethod(
            accountingService, "resolvePostingDocumentReference", postingEntry);
    JournalEntry emptyPostingEntry = new JournalEntry();
    String fallbackType =
        ReflectionTestUtils.invokeMethod(
            accountingService, "resolvePostingDocumentType", emptyPostingEntry);
    String fallbackReference =
        ReflectionTestUtils.invokeMethod(
            accountingService, "resolvePostingDocumentReference", emptyPostingEntry);
    String nullFallbackType =
        ReflectionTestUtils.invokeMethod(
            accountingService, "resolvePostingDocumentType", new Object[] {null});
    String nullReferenceFallback =
        ReflectionTestUtils.invokeMethod(
            accountingService, "resolvePostingDocumentReference", new Object[] {null});

    assertThat(sameCurrency).isTrue();
    assertThat(differentCurrency).isFalse();
    assertThat(sameFxRate).isTrue();
    assertThat(differentFxRate).isFalse();
    assertThat(noAttachments).isNull();
    assertThat(blankOnlyAttachments).isNull();
    assertThat(joinedAttachments).isEqualTo("scan-1\nscan-2");
    assertThat(documentType).isEqualTo("MANUAL");
    assertThat(documentReference).isEqualTo("REF-1");
    assertThat(fallbackType).isEqualTo("JOURNAL_ENTRY");
    assertThat(fallbackReference).isNull();
    assertThat(nullFallbackType).isEqualTo("JOURNAL_ENTRY");
    assertThat(nullReferenceFallback).isNull();

    assertThatThrownBy(
            () ->
                ReflectionTestUtils.invokeMethod(
                    accountingService,
                    "validateDealerSettlementAllocations",
                    List.of(
                        new SettlementAllocationRequest(
                            null,
                            null,
                            new BigDecimal("10.00"),
                            BigDecimal.ZERO,
                            BigDecimal.ZERO,
                            BigDecimal.ZERO,
                            SettlementAllocationApplication.ON_ACCOUNT,
                            "on account"),
                        new SettlementAllocationRequest(
                            null,
                            null,
                            new BigDecimal("5.00"),
                            BigDecimal.ZERO,
                            BigDecimal.ZERO,
                            BigDecimal.ZERO,
                            SettlementAllocationApplication.ON_ACCOUNT,
                            "duplicate on account"))))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("duplicate unapplied allocation rows");

    assertThatThrownBy(
            () ->
                ReflectionTestUtils.invokeMethod(
                    accountingService,
                    "validateSupplierSettlementAllocations",
                    List.of(
                        new SettlementAllocationRequest(
                            701L,
                            null,
                            new BigDecimal("10.00"),
                            BigDecimal.ZERO,
                            BigDecimal.ZERO,
                            BigDecimal.ZERO,
                            SettlementAllocationApplication.DOCUMENT,
                            "bad supplier allocation"))))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Supplier settlements cannot allocate to invoices");
  }

  @Test
  void helperMethods_coverSettlementOverrideAndCorrectionProvenance() {
    Object zeroTotals =
        ReflectionTestUtils.invokeMethod(accountingService, "computeSettlementTotals", List.of());
    Object overrideTotals =
        ReflectionTestUtils.invokeMethod(
            accountingService,
            "computeSettlementTotals",
            List.of(
                new SettlementAllocationRequest(
                    701L,
                    null,
                    new BigDecimal("100.00"),
                    new BigDecimal("5.00"),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    SettlementAllocationApplication.DOCUMENT,
                    "discount")));

    Boolean zeroOverride =
        ReflectionTestUtils.invokeMethod(
            accountingService, "settlementOverrideRequested", zeroTotals);
    Boolean requiredOverride =
        ReflectionTestUtils.invokeMethod(
            accountingService, "settlementOverrideRequested", overrideTotals);
    assertThat(zeroOverride).isFalse();
    assertThat(requiredOverride).isTrue();

    String trimmedReason =
        ReflectionTestUtils.invokeMethod(
            accountingService,
            "requireAdminExceptionReason",
            "Settlement override",
            Boolean.TRUE,
            "  Approved reason  ");
    assertThat(trimmedReason).isEqualTo("Approved reason");

    assertThatThrownBy(
            () ->
                ReflectionTestUtils.invokeMethod(
                    accountingService,
                    "requireAdminExceptionReason",
                    "Settlement override",
                    Boolean.FALSE,
                    "reason"))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("requires an explicit admin override");

    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "plain.user", "n/a", List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    try {
      assertThatThrownBy(
              () ->
                  ReflectionTestUtils.invokeMethod(
                      accountingService,
                      "requireAdminExceptionReason",
                      "Settlement override",
                      Boolean.TRUE,
                      "reason"))
          .isInstanceOf(ApplicationException.class)
          .hasMessageContaining("admin-only");
    } finally {
      SecurityContextHolder.getContext()
          .setAuthentication(
              new UsernamePasswordAuthenticationToken(
                  "policy.admin", "n/a", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "super.admin", "n/a", List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))));
    try {
      Boolean overrideAuthority =
          ReflectionTestUtils.invokeMethod(accountingService, "hasEntryDateOverrideAuthority");
      assertThat(overrideAuthority).isTrue();

      assertThatThrownBy(
              () ->
                  ReflectionTestUtils.invokeMethod(
                      accountingService,
                      "requireAdminExceptionReason",
                      "Settlement override",
                      Boolean.TRUE,
                      "   "))
          .isInstanceOf(ApplicationException.class)
          .hasMessageContaining("Settlement override reason is required");
    } finally {
      SecurityContextHolder.getContext()
          .setAuthentication(
              new UsernamePasswordAuthenticationToken(
                  "policy.admin", "n/a", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    JournalEntry source = new JournalEntry();
    ReflectionTestUtils.setField(source, "id", 9001L);
    source.setReferenceNumber("SRC-9001");

    JournalEntry correction = new JournalEntry();
    correction.setCorrectionReason("WRONG");
    correction.setSourceModule("legacy");
    correction.setSourceReference("OLD");

    ReflectionTestUtils.invokeMethod(
        accountingService,
        "ensureCorrectionJournalProvenance",
        null,
        source,
        "AUTO_REVERSAL",
        "ACCRUAL",
        "SRC-9001");
    ReflectionTestUtils.invokeMethod(
        accountingService,
        "ensureCorrectionJournalProvenance",
        correction,
        null,
        "AUTO_REVERSAL",
        "ACCRUAL",
        "SRC-9001");
    ReflectionTestUtils.invokeMethod(
        accountingService,
        "ensureCorrectionJournalProvenance",
        correction,
        source,
        "AUTO_REVERSAL",
        "ACCRUAL",
        "SRC-9001");

    assertThat(correction.getReversalOf()).isSameAs(source);
    assertThat(correction.getCorrectionType()).isEqualTo(JournalCorrectionType.REVERSAL);
    assertThat(correction.getCorrectionReason()).isEqualTo("AUTO_REVERSAL");
    assertThat(correction.getSourceModule()).isEqualTo("ACCRUAL");
    assertThat(correction.getSourceReference()).isEqualTo("SRC-9001");
    verify(journalEntryRepository).save(correction);

    JournalEntry alignedCorrection = new JournalEntry();
    alignedCorrection.setReversalOf(source);
    alignedCorrection.setCorrectionType(JournalCorrectionType.REVERSAL);
    alignedCorrection.setCorrectionReason("AUTO_REVERSAL");
    alignedCorrection.setSourceModule("ACCRUAL");
    alignedCorrection.setSourceReference("SRC-9001");

    ReflectionTestUtils.invokeMethod(
        accountingService,
        "ensureCorrectionJournalProvenance",
        alignedCorrection,
        source,
        "AUTO_REVERSAL",
        "ACCRUAL",
        "SRC-9001");

    verify(journalEntryRepository, never()).save(alignedCorrection);
  }

  @Test
  void helperMethods_coverSettlementSignatureVariantsAndPostingReferenceFallback() {
    @SuppressWarnings("unchecked")
    java.util.Map<String, Integer> nullRequestCounts =
        ReflectionTestUtils.invokeMethod(
            accountingService, "allocationSignatureCountsFromRequests", new Object[] {null});
    assertThat(nullRequestCounts).isEmpty();

    PartnerSettlementAllocation documentRow = new PartnerSettlementAllocation();
    Invoice invoice = new Invoice();
    ReflectionTestUtils.setField(invoice, "id", 9201L);
    documentRow.setInvoice(invoice);
    documentRow.setAllocationAmount(new BigDecimal("20.00"));
    documentRow.setMemo("  Header memo  ");

    PartnerSettlementAllocation unappliedRow = new PartnerSettlementAllocation();
    unappliedRow.setAllocationAmount(new BigDecimal("15.00"));
    unappliedRow.setMemo("[SETTLEMENT-APPLICATION:ON_ACCOUNT]  Carry forward ");

    @SuppressWarnings("unchecked")
    java.util.Map<String, Integer> rowCounts =
        ReflectionTestUtils.invokeMethod(
            accountingService,
            "allocationSignatureCountsFromRows",
            List.of(documentRow, unappliedRow));
    assertThat(rowCounts.keySet())
        .anySatisfy(
            signature -> {
              assertThat(signature).contains("|application=DOCUMENT|");
              assertThat(signature).contains("|memo=Header memo");
            });
    assertThat(rowCounts.keySet())
        .anySatisfy(
            signature -> {
              assertThat(signature).contains("|application=ON_ACCOUNT|");
              assertThat(signature).contains("|memo=Carry forward");
            });

    String defaultApplicationSignature =
        ReflectionTestUtils.invokeMethod(
            accountingService,
            "allocationSignature",
            77L,
            null,
            new BigDecimal("12.00"),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            null,
            "  Carry forward ");
    assertThat(defaultApplicationSignature)
        .contains("|application=DOCUMENT|")
        .contains("|memo=Carry forward");

    JournalEntry postingEntry = new JournalEntry();
    postingEntry.setSourceReference("   ");
    postingEntry.setReferenceNumber(" REF-123 ");
    String fallbackReference =
        ReflectionTestUtils.invokeMethod(
            accountingService, "resolvePostingDocumentReference", postingEntry);
    assertThat(fallbackReference).isEqualTo("REF-123");
  }

  @Test
  void postAccrual_returnsExistingEntryWhenReferenceAlreadyExists() {
    JournalEntry existing = new JournalEntry();
    ReflectionTestUtils.setField(existing, "id", 9901L);
    existing.setCompany(company);
    existing.setReferenceNumber("ACCRUAL-EXISTING");
    existing.setEntryDate(LocalDate.of(2024, 6, 1));
    existing.setMemo("Existing accrual");

    when(journalEntryRepository.findByCompanyAndReferenceNumber(company, "ACCRUAL-EXISTING"))
        .thenReturn(Optional.of(existing));

    JournalEntryDto result =
        accountingService.postAccrual(
            new AccrualRequest(
                501L,
                502L,
                new BigDecimal("25.00"),
                LocalDate.of(2024, 6, 1),
                "ACCRUAL-EXISTING",
                "Existing accrual",
                null,
                null,
                Boolean.FALSE));

    assertThat(result.referenceNumber()).isEqualTo("ACCRUAL-EXISTING");
    verify(journalEntryRepository, never()).save(any());
  }

  @Test
  void postAccrual_createsAutoReversalAndLinksProvenance() {
    Account debit = account(601L, "ACCRUAL-EXP", AccountType.EXPENSE);
    Account credit = account(602L, "ACCRUAL-LIAB", AccountType.LIABILITY);
    when(companyEntityLookup.requireAccount(eq(company), eq(601L))).thenReturn(debit);
    when(companyEntityLookup.requireAccount(eq(company), eq(602L))).thenReturn(credit);

    JournalEntry accrualEntry = new JournalEntry();
    ReflectionTestUtils.setField(accrualEntry, "id", 9910L);
    JournalEntry reversalEntry = new JournalEntry();
    ReflectionTestUtils.setField(reversalEntry, "id", 9911L);

    when(journalEntryRepository.findByCompanyAndReferenceNumber(company, "ACCRUAL-NEW-1"))
        .thenReturn(Optional.empty());
    when(companyEntityLookup.requireJournalEntry(eq(company), eq(9910L))).thenReturn(accrualEntry);
    when(companyEntityLookup.requireJournalEntry(eq(company), eq(9911L))).thenReturn(reversalEntry);

    ArgumentCaptor<JournalEntryRequest> requestCaptor =
        ArgumentCaptor.forClass(JournalEntryRequest.class);
    doReturn(stubEntry(9910L), stubEntry(9911L))
        .when(creditDebitNoteService)
        .createJournalEntry(requestCaptor.capture());

    JournalEntryDto result =
        accountingService.postAccrual(
            new AccrualRequest(
                601L,
                602L,
                new BigDecimal("80.00"),
                LocalDate.of(2024, 6, 2),
                "ACCRUAL-NEW-1",
                "  Month-end accrual  ",
                null,
                LocalDate.of(2024, 6, 30),
                Boolean.TRUE));

    assertThat(result.id()).isEqualTo(9910L);
    assertThat(requestCaptor.getAllValues()).hasSize(2);
    assertThat(requestCaptor.getAllValues().get(0).referenceNumber()).isEqualTo("ACCRUAL-NEW-1");
    assertThat(requestCaptor.getAllValues().get(1).referenceNumber())
        .isEqualTo("ACCRUAL-NEW-1-REV");
    assertThat(requestCaptor.getAllValues().get(1).memo()).isEqualTo("Reversal of ACCRUAL-NEW-1");
    assertThat(reversalEntry.getReversalOf()).isSameAs(accrualEntry);
    assertThat(reversalEntry.getCorrectionType()).isEqualTo(JournalCorrectionType.REVERSAL);
    assertThat(reversalEntry.getCorrectionReason()).isEqualTo("AUTO_REVERSAL");
    verify(journalEntryRepository).save(reversalEntry);
  }

  @Test
  void helperMethods_coverCreditNoteIdempotencyValidationBranches() {
    Account receivable = account(710L, "AR-710", AccountType.ASSET);
    Account revenue = account(711L, "SALES-711", AccountType.REVENUE);
    Dealer dealer = dealer(801L, "Dealer A", receivable);
    Dealer otherDealer = dealer(802L, "Dealer B", account(712L, "AR-712", AccountType.ASSET));

    Invoice invoice = new Invoice();
    invoice.setCompany(company);
    invoice.setDealer(dealer);
    invoice.setInvoiceNumber("INV-801");
    invoice.setTotalAmount(new BigDecimal("100.00"));
    invoice.addPaymentReference("CN-OK");

    JournalEntry source = journalEntry(9001L, "INV-801-JE");
    source.setDealer(dealer);
    addJournalLine(
        source, receivable, "Invoice receivable", new BigDecimal("100.00"), BigDecimal.ZERO);
    addJournalLine(source, revenue, "Invoice revenue", BigDecimal.ZERO, new BigDecimal("100.00"));

    assertThatThrownBy(
            () ->
                ReflectionTestUtils.invokeMethod(
                    accountingService,
                    "validateCreditNoteIdempotency",
                    "CN-KEY",
                    "CN-KEY",
                    invoice,
                    source,
                    null,
                    new BigDecimal("100.00"),
                    new BigDecimal("100.00")))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("credit note journal is missing");

    JournalEntry wrongDealerEntry =
        creditNoteEntry(9101L, "CN-OK", otherDealer, source, receivable, revenue, "100.00");
    assertThatThrownBy(
            () ->
                ReflectionTestUtils.invokeMethod(
                    accountingService,
                    "validateCreditNoteIdempotency",
                    "CN-OK",
                    "CN-KEY",
                    invoice,
                    source,
                    wrongDealerEntry,
                    new BigDecimal("100.00"),
                    new BigDecimal("100.00")))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("another dealer");

    JournalEntry wrongReversalEntry =
        creditNoteEntry(
            9102L,
            "CN-OK",
            dealer,
            journalEntry(9002L, "OTHER-SRC"),
            receivable,
            revenue,
            "100.00");
    assertThatThrownBy(
            () ->
                ReflectionTestUtils.invokeMethod(
                    accountingService,
                    "validateCreditNoteIdempotency",
                    "CN-OK",
                    "CN-KEY",
                    invoice,
                    source,
                    wrongReversalEntry,
                    new BigDecimal("100.00"),
                    new BigDecimal("100.00")))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("another invoice reversal");

    JournalEntry wrongInvoiceEntry =
        creditNoteEntry(9103L, "CN-WRONG", dealer, null, receivable, revenue, "100.00");
    assertThatThrownBy(
            () ->
                ReflectionTestUtils.invokeMethod(
                    accountingService,
                    "validateCreditNoteIdempotency",
                    "CN-WRONG",
                    "CN-KEY",
                    invoice,
                    source,
                    wrongInvoiceEntry,
                    new BigDecimal("100.00"),
                    new BigDecimal("100.00")))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("another invoice");

    JournalEntry wrongAmountEntry =
        creditNoteEntry(9104L, "CN-OK", dealer, source, receivable, revenue, "90.00");
    assertThatThrownBy(
            () ->
                ReflectionTestUtils.invokeMethod(
                    accountingService,
                    "validateCreditNoteIdempotency",
                    "CN-OK",
                    "CN-KEY",
                    invoice,
                    source,
                    wrongAmountEntry,
                    new BigDecimal("100.00"),
                    new BigDecimal("100.00")))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("different credit amount");

    JournalEntry wrongPayloadEntry =
        creditNoteEntry(
            9105L,
            "CN-OK",
            dealer,
            source,
            receivable,
            account(713L, "ALT-REV", AccountType.REVENUE),
            "100.00");
    assertThatThrownBy(
            () ->
                ReflectionTestUtils.invokeMethod(
                    accountingService,
                    "validateCreditNoteIdempotency",
                    "CN-OK",
                    "CN-KEY",
                    invoice,
                    source,
                    wrongPayloadEntry,
                    new BigDecimal("100.00"),
                    new BigDecimal("100.00")))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("different credit note payload");

    JournalEntry matchingEntry =
        creditNoteEntry(9106L, "CN-OK", dealer, source, receivable, revenue, "100.00");
    ReflectionTestUtils.invokeMethod(
        accountingService,
        "validateCreditNoteIdempotency",
        "CN-OK",
        "CN-KEY",
        invoice,
        source,
        matchingEntry,
        new BigDecimal("100.00"),
        new BigDecimal("100.00"));
  }

  @Test
  void findExistingEntry_normalizesInputsAndSkipsDuplicateKeyLookup() {
    JournalEntry byKey = journalEntry(9107L, "IDEMP-FIND");
    JournalEntry byReference = journalEntry(9108L, "REF-FIND");
    JournalEntry byDuplicateReference = journalEntry(9109L, "REF-DUP");

    when(journalReferenceResolver.findExistingEntry(company, "IDEMP-FIND"))
        .thenReturn(Optional.of(byKey));
    when(journalReferenceResolver.findExistingEntry(company, "REF-FIND"))
        .thenReturn(Optional.of(byReference));
    when(journalReferenceResolver.findExistingEntry(company, "REF-DUP"))
        .thenReturn(Optional.of(byDuplicateReference));

    JournalEntry keyResult =
        ReflectionTestUtils.invokeMethod(
            accountingService, "findExistingEntry", company, "   ", " IDEMP-FIND ");
    JournalEntry referenceResult =
        ReflectionTestUtils.invokeMethod(
            accountingService, "findExistingEntry", company, " REF-FIND ", "   ");
    JournalEntry duplicateResult =
        ReflectionTestUtils.invokeMethod(
            accountingService, "findExistingEntry", company, " REF-DUP ", " ref-dup ");

    assertThat(keyResult).isSameAs(byKey);
    assertThat(referenceResult).isSameAs(byReference);
    assertThat(duplicateResult).isSameAs(byDuplicateReference);
    verify(journalReferenceResolver, times(1)).findExistingEntry(company, "REF-DUP");
  }

  @Test
  void validateCreditNoteIdempotency_allowsBlankReferenceWhenEntryMatches() {
    Account receivable = account(9110L, "AR-9110", AccountType.ASSET);
    Account revenue = account(9111L, "REV-9111", AccountType.REVENUE);
    Dealer dealer = dealer(9112L, "Dealer Blank Ref", receivable);

    Invoice invoice = new Invoice();
    ReflectionTestUtils.setField(invoice, "id", 9113L);
    invoice.setCompany(company);
    invoice.setDealer(dealer);
    invoice.setInvoiceNumber("INV-9113");
    invoice.setTotalAmount(new BigDecimal("100.00"));

    JournalEntry source = journalEntry(9114L, "INV-9113-JE");
    source.setDealer(dealer);
    addJournalLine(
        source, receivable, "Invoice receivable", new BigDecimal("100.00"), BigDecimal.ZERO);
    addJournalLine(source, revenue, "Invoice revenue", BigDecimal.ZERO, new BigDecimal("100.00"));

    JournalEntry matchingEntry =
        creditNoteEntry(9115L, "CN-9115", dealer, source, receivable, revenue, "100.00");

    ReflectionTestUtils.invokeMethod(
        accountingService,
        "validateCreditNoteIdempotency",
        "   ",
        "CN-BLANK-REF",
        invoice,
        source,
        matchingEntry,
        new BigDecimal("100.00"),
        new BigDecimal("100.00"));
  }

  @Test
  void helperMethods_coverEntryDateOverrideAndAccountRoleGuards() {
    LocalDate today = LocalDate.of(2026, 3, 12);
    when(companyClock.today(company)).thenReturn(today);

    assertThatThrownBy(
            () ->
                ReflectionTestUtils.invokeMethod(
                    accountingService, "validateEntryDate", company, null, false, false))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Entry date is required");

    ReflectionTestUtils.setField(accountingService, "skipDateValidation", true);
    ReflectionTestUtils.invokeMethod(
        accountingService, "validateEntryDate", company, today.plusDays(2), false, false);

    environment.setActiveProfiles("prod");
    assertThatThrownBy(
            () ->
                ReflectionTestUtils.invokeMethod(
                    accountingService,
                    "validateEntryDate",
                    company,
                    today.plusDays(2),
                    false,
                    false))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Entry date cannot be in the future");

    assertThatThrownBy(
            () ->
                ReflectionTestUtils.invokeMethod(
                    accountingService,
                    "validateEntryDate",
                    company,
                    today.minusDays(31),
                    true,
                    false))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("mandatory reason is required");

    ReflectionTestUtils.invokeMethod(
        accountingService, "validateEntryDate", company, today.minusDays(45), true, true);

    AccountingService service = spy(accountingService);
    @SuppressWarnings("unchecked")
    ThreadLocal<Boolean> overrideState =
        (ThreadLocal<Boolean>)
            ReflectionTestUtils.getField(
                AccountingService.class.getSuperclass(), "SYSTEM_ENTRY_DATE_OVERRIDE");
    assertThat(overrideState).isNotNull();
    overrideState.remove();

    AtomicInteger calls = new AtomicInteger();
    doAnswer(
            invocation -> {
              int call = calls.incrementAndGet();
              if (call == 1) {
                assertThat(Boolean.TRUE.equals(overrideState.get())).isFalse();
              } else {
                assertThat(overrideState.get()).isTrue();
              }
              return stubEntry(8800L + call);
            })
        .when(service)
        .createJournalEntry(any());

    JournalEntryRequest payload =
        new JournalEntryRequest("REV-REF", today, "reversal", null, null, Boolean.TRUE, List.of());

    ReflectionTestUtils.invokeMethod(service, "createJournalEntryForReversal", payload, false);
    assertThat(Boolean.TRUE.equals(overrideState.get())).isFalse();

    ReflectionTestUtils.invokeMethod(service, "createJournalEntryForReversal", payload, true);
    assertThat(Boolean.TRUE.equals(overrideState.get())).isFalse();

    overrideState.set(Boolean.TRUE);
    ReflectionTestUtils.invokeMethod(service, "createJournalEntryForReversal", payload, true);
    assertThat(overrideState.get()).isTrue();
    overrideState.remove();

    Account receivable = account(720L, "AR-720", AccountType.ASSET);
    Account payable = account(721L, "AP-721", AccountType.LIABILITY);

    assertThat(
            (Account)
                ReflectionTestUtils.invokeMethod(
                    accountingService,
                    "requireDealerReceivable",
                    dealer(820L, "Dealer C", receivable)))
        .isSameAs(receivable);
    assertThatThrownBy(
            () ->
                ReflectionTestUtils.invokeMethod(
                    accountingService, "requireDealerReceivable", dealer(821L, "Dealer D", null)))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("missing a receivable account");

    assertThat(
            (Account)
                ReflectionTestUtils.invokeMethod(
                    accountingService,
                    "requireSupplierPayable",
                    supplier(830L, "Supplier A", payable)))
        .isSameAs(payable);
    assertThatThrownBy(
            () ->
                ReflectionTestUtils.invokeMethod(
                    accountingService,
                    "requireSupplierPayable",
                    supplier(831L, "Supplier B", null)))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("missing a payable account");

    assertThat(
            (Boolean)
                ReflectionTestUtils.invokeMethod(
                    accountingService, "isReceivableAccount", receivable))
        .isTrue();
    assertThat(
            (Boolean)
                ReflectionTestUtils.invokeMethod(accountingService, "isReceivableAccount", payable))
        .isFalse();
    assertThat(
            (Boolean)
                ReflectionTestUtils.invokeMethod(accountingService, "isPayableAccount", payable))
        .isTrue();
    assertThat(
            (Boolean)
                ReflectionTestUtils.invokeMethod(accountingService, "isPayableAccount", receivable))
        .isFalse();
  }

  @Test
  void helperMethods_coverCurrencyAndFxNormalization() {
    company.setBaseCurrency(" inr ");

    assertThat(
            (String)
                ReflectionTestUtils.invokeMethod(
                    accountingService, "resolveCurrency", null, company))
        .isEqualTo("INR");
    assertThat(
            (String)
                ReflectionTestUtils.invokeMethod(
                    accountingService, "resolveCurrency", " usd ", company))
        .isEqualTo("USD");

    assertThat(
            (BigDecimal)
                ReflectionTestUtils.invokeMethod(
                    accountingService, "resolveFxRate", "INR", company, null))
        .isEqualByComparingTo(BigDecimal.ONE);

    assertThatThrownBy(
            () ->
                ReflectionTestUtils.invokeMethod(
                    accountingService, "resolveFxRate", "USD", company, null))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("FX rate is required");

    assertThatThrownBy(
            () ->
                ReflectionTestUtils.invokeMethod(
                    accountingService, "resolveFxRate", "USD", company, BigDecimal.ZERO))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("FX rate must be positive");

    assertThatThrownBy(
            () ->
                ReflectionTestUtils.invokeMethod(
                    accountingService, "resolveFxRate", "USD", company, new BigDecimal("0.00001")))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("FX rate out of bounds");

    assertThat(
            (BigDecimal)
                ReflectionTestUtils.invokeMethod(
                    accountingService,
                    "resolveFxRate",
                    "USD",
                    company,
                    new BigDecimal("1.2345678")))
        .isEqualByComparingTo("1.234568");

    assertThat(
            (BigDecimal)
                ReflectionTestUtils.invokeMethod(
                    accountingService, "toBaseCurrency", null, BigDecimal.ONE))
        .isEqualByComparingTo("0");
    assertThat(
            (BigDecimal)
                ReflectionTestUtils.invokeMethod(
                    accountingService, "toBaseCurrency", new BigDecimal("10"), null))
        .isEqualByComparingTo("10.00");
    assertThat(
            (BigDecimal)
                ReflectionTestUtils.invokeMethod(
                    accountingService,
                    "toBaseCurrency",
                    new BigDecimal("10"),
                    new BigDecimal("1.25")))
        .isEqualByComparingTo("12.50");

    assertThat(
            (BigDecimal)
                ReflectionTestUtils.invokeMethod(
                    accountingService, "roundCurrency", new Object[] {null}))
        .isEqualByComparingTo("0.00");
    assertThat(
            (BigDecimal)
                ReflectionTestUtils.invokeMethod(
                    accountingService, "roundCurrency", new BigDecimal("12.345")))
        .isEqualByComparingTo("12.35");
  }

  @Test
  void helperMethods_coverJournalTypeSourceAndLineTotals() {
    assertThat(
            (BigDecimal)
                ReflectionTestUtils.invokeMethod(
                    accountingService, "totalLinesAmount", new Object[] {null}))
        .isEqualByComparingTo("0");
    assertThat(
            (BigDecimal)
                ReflectionTestUtils.invokeMethod(
                    accountingService,
                    "totalLinesAmount",
                    List.of(
                        new JournalEntryRequest.JournalLineRequest(
                            1L, "credit", BigDecimal.ZERO, new BigDecimal("8.00")),
                        new JournalEntryRequest.JournalLineRequest(
                            2L, "credit-2", BigDecimal.ZERO, new BigDecimal("2.00")))))
        .isEqualByComparingTo("10.00");
    assertThat(
            (BigDecimal)
                ReflectionTestUtils.invokeMethod(
                    accountingService,
                    "totalLinesAmount",
                    List.of(
                        new JournalEntryRequest.JournalLineRequest(
                            1L, "debit", new BigDecimal("7.00"), BigDecimal.ZERO),
                        new JournalEntryRequest.JournalLineRequest(
                            2L, "credit", BigDecimal.ZERO, new BigDecimal("2.00")))))
        .isEqualByComparingTo("7.00");

    assertThat(
            (JournalEntryType)
                ReflectionTestUtils.invokeMethod(accountingService, "resolveJournalEntryType", " "))
        .isEqualTo(JournalEntryType.AUTOMATED);
    assertThat(
            (JournalEntryType)
                ReflectionTestUtils.invokeMethod(
                    accountingService, "resolveJournalEntryType", " manual "))
        .isEqualTo(JournalEntryType.MANUAL);
    assertThatThrownBy(
            () ->
                ReflectionTestUtils.invokeMethod(
                    accountingService, "resolveJournalEntryType", "bogus"))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Unsupported journal type");

    assertThat(
            (Object)
                ReflectionTestUtils.invokeMethod(accountingService, "parseJournalTypeFilter", " "))
        .isNull();
    assertThat(
            (JournalEntryType)
                ReflectionTestUtils.invokeMethod(
                    accountingService, "parseJournalTypeFilter", "manual"))
        .isEqualTo(JournalEntryType.MANUAL);

    assertThat(
            (Object)
                ReflectionTestUtils.invokeMethod(accountingService, "normalizeSourceModule", " "))
        .isNull();
    assertThat(
            (String)
                ReflectionTestUtils.invokeMethod(
                    accountingService, "normalizeSourceModule", " sales "))
        .isEqualTo("SALES");
    assertThat(
            (Object)
                ReflectionTestUtils.invokeMethod(
                    accountingService, "normalizeSourceReference", " "))
        .isNull();
    assertThat(
            (String)
                ReflectionTestUtils.invokeMethod(
                    accountingService, "normalizeSourceReference", " ref-001 "))
        .isEqualTo("ref-001");
  }

  @Test
  @Tag("critical")
  void postDebitNote_rejectsReferenceReuseAcrossDifferentPurchaseProvenance() {
    Account payable = account(8401L, "AP-8401", AccountType.LIABILITY);
    Account inventory = account(8402L, "RM-8402", AccountType.ASSET);
    Supplier supplier = supplier(840L, "Supplier Existing", payable);
    RawMaterialPurchase purchase =
        purchase(
            9401L,
            "PUR-9401",
            supplier,
            new BigDecimal("100.00"),
            new BigDecimal("100.00"),
            "POSTED");

    JournalEntry source = journalEntry(9402L, "PUR-9401-JE");
    purchase.setJournalEntry(source);

    JournalEntry existing = journalEntry(9403L, "DN-EXIST");
    addJournalLine(
        existing, payable, "Debit note reversal - AP", BigDecimal.ZERO, new BigDecimal("40.00"));
    addJournalLine(
        existing,
        inventory,
        "Debit note reversal - Inventory",
        new BigDecimal("40.00"),
        BigDecimal.ZERO);
    existing.setCorrectionReason("legacy");
    existing.setSourceModule("legacy");
    existing.setSourceReference("OLD");
    existing.setReversalOf(journalEntry(9404L, "OTHER-PURCHASE-JE"));

    when(rawMaterialPurchaseRepository.lockByCompanyAndId(company, 9401L))
        .thenReturn(Optional.of(purchase));
    when(journalReferenceResolver.findExistingEntry(company, "DN-EXIST"))
        .thenReturn(Optional.of(existing));

    assertThatThrownBy(
            () ->
                accountingService.postDebitNote(
                    new DebitNoteRequest(
                        9401L, null, null, "DN-EXIST", "reuse existing", null, Boolean.TRUE)))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("another purchase reversal");

    assertThat(purchase.getOutstandingAmount()).isEqualByComparingTo("100.00");
    assertThat(purchase.getStatus()).isEqualTo("POSTED");
    verify(journalEntryRepository, never()).save(existing);
  }

  @Test
  @Tag("critical")
  void postDebitNote_rejectsInvalidPurchaseStatesAndAmounts() {
    Account payable = account(8501L, "AP-8501", AccountType.LIABILITY);
    Supplier supplier = supplier(850L, "Supplier Invalid", payable);

    RawMaterialPurchase missingJournal =
        purchase(
            9501L,
            "PUR-9501",
            supplier,
            new BigDecimal("100.00"),
            new BigDecimal("100.00"),
            "POSTED");
    when(rawMaterialPurchaseRepository.lockByCompanyAndId(company, 9501L))
        .thenReturn(Optional.of(missingJournal));
    assertThatThrownBy(
            () ->
                accountingService.postDebitNote(
                    new DebitNoteRequest(
                        9501L,
                        new BigDecimal("10.00"),
                        null,
                        "DN-MISSING",
                        null,
                        null,
                        Boolean.FALSE)))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("has no posted journal");

    RawMaterialPurchase voidPurchase =
        purchase(
            9502L,
            "PUR-9502",
            supplier,
            new BigDecimal("100.00"),
            new BigDecimal("100.00"),
            "VOID");
    voidPurchase.setJournalEntry(journalEntry(95020L, "PUR-9502-JE"));
    when(rawMaterialPurchaseRepository.lockByCompanyAndId(company, 9502L))
        .thenReturn(Optional.of(voidPurchase));
    when(journalReferenceResolver.findExistingEntry(company, "DN-VOID"))
        .thenReturn(Optional.empty());
    assertThatThrownBy(
            () ->
                accountingService.postDebitNote(
                    new DebitNoteRequest(
                        9502L,
                        new BigDecimal("10.00"),
                        null,
                        "DN-VOID",
                        null,
                        null,
                        Boolean.FALSE)))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("is void; cannot apply debit note");

    RawMaterialPurchase zeroTotal =
        purchase(9503L, "PUR-9503", supplier, BigDecimal.ZERO, BigDecimal.ZERO, "POSTED");
    zeroTotal.setJournalEntry(journalEntry(95030L, "PUR-9503-JE"));
    when(rawMaterialPurchaseRepository.lockByCompanyAndId(company, 9503L))
        .thenReturn(Optional.of(zeroTotal));
    when(journalReferenceResolver.findExistingEntry(company, "DN-ZERO"))
        .thenReturn(Optional.empty());
    assertThatThrownBy(
            () ->
                accountingService.postDebitNote(
                    new DebitNoteRequest(
                        9503L,
                        new BigDecimal("10.00"),
                        null,
                        "DN-ZERO",
                        null,
                        null,
                        Boolean.FALSE)))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Debit note amount must be positive");

    RawMaterialPurchase fullyDebited =
        purchase(
            9504L,
            "PUR-9504",
            supplier,
            new BigDecimal("100.00"),
            new BigDecimal("100.00"),
            "POSTED");
    JournalEntry fullSource = journalEntry(95040L, "PUR-9504-JE");
    fullyDebited.setJournalEntry(fullSource);
    JournalEntry fullDebit = journalEntry(95041L, "DN-FULL");
    addJournalLine(
        fullDebit, payable, "Debit note reversal - AP", BigDecimal.ZERO, new BigDecimal("100.00"));
    when(rawMaterialPurchaseRepository.lockByCompanyAndId(company, 9504L))
        .thenReturn(Optional.of(fullyDebited));
    when(journalReferenceResolver.findExistingEntry(company, "DN-FULL"))
        .thenReturn(Optional.empty());
    when(journalEntryRepository.findByCompanyAndReversalOfAndCorrectionReasonIgnoreCase(
            company, fullSource, "DEBIT_NOTE"))
        .thenReturn(List.of(fullDebit));
    assertThatThrownBy(
            () ->
                accountingService.postDebitNote(
                    new DebitNoteRequest(
                        9504L, new BigDecimal("5.00"), null, "DN-FULL", null, null, Boolean.FALSE)))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("already fully credited");

    RawMaterialPurchase exceeded =
        purchase(
            9505L,
            "PUR-9505",
            supplier,
            new BigDecimal("100.00"),
            new BigDecimal("100.00"),
            "POSTED");
    JournalEntry exceededSource = journalEntry(95050L, "PUR-9505-JE");
    exceeded.setJournalEntry(exceededSource);
    JournalEntry priorDebit = journalEntry(95051L, "DN-PRIOR");
    addJournalLine(
        priorDebit, payable, "Debit note reversal - AP", BigDecimal.ZERO, new BigDecimal("70.00"));
    when(rawMaterialPurchaseRepository.lockByCompanyAndId(company, 9505L))
        .thenReturn(Optional.of(exceeded));
    when(journalReferenceResolver.findExistingEntry(company, "DN-EXCEED"))
        .thenReturn(Optional.empty());
    when(journalEntryRepository.findByCompanyAndReversalOfAndCorrectionReasonIgnoreCase(
            company, exceededSource, "DEBIT_NOTE"))
        .thenReturn(List.of(priorDebit));
    assertThatThrownBy(
            () ->
                accountingService.postDebitNote(
                    new DebitNoteRequest(
                        9505L,
                        new BigDecimal("40.00"),
                        null,
                        "DN-EXCEED",
                        null,
                        null,
                        Boolean.FALSE)))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("exceeds remaining purchase amount");
  }

  @Test
  @Tag("critical")
  void postCreditNote_rejectsAmountBeyondCurrentOutstanding() {
    Account receivable = account(8451L, "AR-8451", AccountType.ASSET);
    Account revenue = account(8452L, "REV-8452", AccountType.REVENUE);
    Dealer dealer = dealer(845L, "Dealer Outstanding", receivable);

    Invoice invoice = new Invoice();
    ReflectionTestUtils.setField(invoice, "id", 8450L);
    invoice.setCompany(company);
    invoice.setDealer(dealer);
    invoice.setInvoiceNumber("INV-8450");
    invoice.setTotalAmount(new BigDecimal("100.00"));
    invoice.setOutstandingAmount(new BigDecimal("20.00"));
    invoice.setStatus("PARTIAL");

    JournalEntry source = journalEntry(8453L, "INV-8450-JE");
    addJournalLine(
        source, receivable, "Invoice receivable", new BigDecimal("100.00"), BigDecimal.ZERO);
    addJournalLine(source, revenue, "Invoice revenue", BigDecimal.ZERO, new BigDecimal("100.00"));
    invoice.setJournalEntry(source);

    when(invoiceRepository.lockByCompanyAndId(company, 8450L)).thenReturn(Optional.of(invoice));
    when(journalReferenceResolver.findExistingEntry(company, "CN-8450"))
        .thenReturn(Optional.empty());
    when(journalEntryRepository.findByCompanyAndReversalOfAndCorrectionReasonIgnoreCase(
            company, source, "CREDIT_NOTE"))
        .thenReturn(List.of());

    assertThatThrownBy(
            () ->
                accountingService.postCreditNote(
                    new CreditNoteRequest(
                        8450L,
                        new BigDecimal("30.00"),
                        null,
                        "CN-8450",
                        "credit beyond outstanding",
                        null,
                        Boolean.FALSE)))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("exceeds remaining invoice amount");

    verify(creditDebitNoteService, never()).createJournalEntry(any(JournalEntryRequest.class));
  }

  @Test
  @Tag("critical")
  void postCreditNote_replayRejectsDifferentReferenceNumberForSameIdempotencyKey() {
    Account receivable = account(8455L, "AR-8455", AccountType.ASSET);
    Account revenue = account(8456L, "REV-8456", AccountType.REVENUE);
    Dealer dealer = dealer(8457L, "Dealer Replay Ref Drift", receivable);

    Invoice invoice = new Invoice();
    ReflectionTestUtils.setField(invoice, "id", 8458L);
    invoice.setCompany(company);
    invoice.setDealer(dealer);
    invoice.setInvoiceNumber("INV-8458");
    invoice.setTotalAmount(new BigDecimal("100.00"));
    invoice.setOutstandingAmount(BigDecimal.ZERO);
    invoice.setStatus("CREDITED");

    JournalEntry source = journalEntry(8459L, "INV-8458-JE");
    addJournalLine(
        source, receivable, "Invoice receivable", new BigDecimal("100.00"), BigDecimal.ZERO);
    addJournalLine(source, revenue, "Invoice revenue", BigDecimal.ZERO, new BigDecimal("100.00"));
    invoice.setJournalEntry(source);

    JournalEntry existing = journalEntry(8460L, "CN-REPLAY-ORIGINAL");
    existing.setDealer(dealer);
    existing.setReversalOf(source);
    existing.setCorrectionType(JournalCorrectionType.REVERSAL);
    existing.setCorrectionReason("CREDIT_NOTE");
    existing.setSourceModule("CREDIT_NOTE");
    existing.setSourceReference("INV-8458");
    addJournalLine(
        existing,
        revenue,
        "Credit note reversal - Invoice revenue",
        new BigDecimal("100.00"),
        BigDecimal.ZERO);
    addJournalLine(
        existing,
        receivable,
        "Credit note reversal - Invoice receivable",
        BigDecimal.ZERO,
        new BigDecimal("100.00"));

    when(invoiceRepository.lockByCompanyAndId(company, 8458L)).thenReturn(Optional.of(invoice));
    when(journalReferenceResolver.findExistingEntry(company, "CN-REPLAY-NEW"))
        .thenReturn(Optional.empty());
    when(journalReferenceResolver.findExistingEntry(company, "IDEMP-CN-REPLAY-REF"))
        .thenReturn(Optional.of(existing));
    when(journalEntryRepository.findByCompanyAndReversalOfAndCorrectionReasonIgnoreCase(
            company, source, "CREDIT_NOTE"))
        .thenReturn(List.of(existing));

    assertThatThrownBy(
            () ->
                accountingService.postCreditNote(
                    new CreditNoteRequest(
                        8458L,
                        null,
                        null,
                        "CN-REPLAY-NEW",
                        "replay",
                        "IDEMP-CN-REPLAY-REF",
                        Boolean.TRUE)))
        .isInstanceOf(ApplicationException.class)
        .satisfies(
            ex ->
                assertThat(((ApplicationException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.CONCURRENCY_CONFLICT));

    verify(creditDebitNoteService, never()).createJournalEntry(any(JournalEntryRequest.class));
    verify(journalEntryRepository, never()).save(existing);
  }

  @Test
  void postCreditNote_nonLeaderReplayReturnsAwaitedJournalAndAppliesInvoice() {
    Account receivable = account(84561L, "AR-84561", AccountType.ASSET);
    Account revenue = account(84562L, "REV-84562", AccountType.REVENUE);
    Dealer dealer = dealer(84563L, "Dealer Awaited Replay", receivable);

    Invoice invoice = new Invoice();
    ReflectionTestUtils.setField(invoice, "id", 84564L);
    invoice.setCompany(company);
    invoice.setDealer(dealer);
    invoice.setInvoiceNumber("INV-84564");
    invoice.setTotalAmount(new BigDecimal("100.00"));
    invoice.setOutstandingAmount(new BigDecimal("100.00"));
    invoice.setStatus("OPEN");

    JournalEntry source = journalEntry(84565L, "INV-84564-JE");
    addJournalLine(
        source, receivable, "Invoice receivable", new BigDecimal("100.00"), BigDecimal.ZERO);
    addJournalLine(source, revenue, "Invoice revenue", BigDecimal.ZERO, new BigDecimal("100.00"));
    invoice.setJournalEntry(source);

    JournalEntry awaited =
        creditNoteEntry(84566L, "CN-AWAIT", dealer, source, receivable, revenue, "100.00");

    JournalReferenceMapping mapping = new JournalReferenceMapping();
    mapping.setCompany(company);
    mapping.setLegacyReference("idemp-cn-await");
    mapping.setCanonicalReference("CN-AWAIT");
    mapping.setEntityType("CREDIT_NOTE");

    when(invoiceRepository.lockByCompanyAndId(company, 84564L)).thenReturn(Optional.of(invoice));
    when(journalReferenceResolver.findExistingEntry(company, "CN-AWAIT")).thenReturn(Optional.empty());

    AtomicInteger replayLookups = new AtomicInteger(0);
    when(journalReferenceResolver.findExistingEntry(company, "IDEMP-CN-AWAIT"))
        .thenAnswer(
            invocation ->
                replayLookups.getAndIncrement() == 0 ? Optional.empty() : Optional.of(awaited));
    when(journalReferenceMappingRepository.findAllByCompanyAndLegacyReferenceIgnoreCase(
            company, "idemp-cn-await"))
        .thenReturn(List.of(), List.of(mapping));
    when(journalReferenceMappingRepository.reserveReferenceMapping(
            eq(company.getId()),
            eq("idemp-cn-await"),
            eq("CN-AWAIT"),
            eq("CREDIT_NOTE"),
            any()))
        .thenReturn(0);
    when(journalEntryRepository.findByCompanyAndReversalOfAndCorrectionReasonIgnoreCase(
            company, source, "CREDIT_NOTE"))
        .thenReturn(List.of());

    JournalEntryDto result =
        accountingService.postCreditNote(
            new CreditNoteRequest(
                84564L, null, null, "CN-AWAIT", "awaited replay", "IDEMP-CN-AWAIT", Boolean.TRUE));

    assertThat(result.id()).isEqualTo(84566L);
    assertThat(invoice.getOutstandingAmount()).isEqualByComparingTo("0.00");
    assertThat(invoice.getPaymentReferences()).contains("CN-AWAIT");
    verify(creditDebitNoteService, never()).createJournalEntry(any(JournalEntryRequest.class));
  }

  @Test
  @Tag("critical")
  void postDebitNote_rejectsAmountBeyondCurrentOutstanding() {
    Account payable = account(8461L, "AP-8461", AccountType.LIABILITY);
    Account inventory = account(8462L, "RM-8462", AccountType.ASSET);
    Supplier supplier = supplier(846L, "Supplier Outstanding", payable);
    RawMaterialPurchase purchase =
        purchase(
            8460L,
            "PUR-8460",
            supplier,
            new BigDecimal("100.00"),
            new BigDecimal("20.00"),
            "PARTIAL");
    JournalEntry source = journalEntry(8463L, "PUR-8460-JE");
    addJournalLine(source, inventory, "Inventory", new BigDecimal("100.00"), BigDecimal.ZERO);
    addJournalLine(source, payable, "Payable", BigDecimal.ZERO, new BigDecimal("100.00"));
    purchase.setJournalEntry(source);

    when(rawMaterialPurchaseRepository.lockByCompanyAndId(company, 8460L))
        .thenReturn(Optional.of(purchase));
    when(journalReferenceResolver.findExistingEntry(company, "DN-8460"))
        .thenReturn(Optional.empty());
    when(journalEntryRepository.findByCompanyAndReversalOfAndCorrectionReasonIgnoreCase(
            company, source, "DEBIT_NOTE"))
        .thenReturn(List.of());

    assertThatThrownBy(
            () ->
                accountingService.postDebitNote(
                    new DebitNoteRequest(
                        8460L,
                        new BigDecimal("30.00"),
                        null,
                        "DN-8460",
                        "debit beyond outstanding",
                        null,
                        Boolean.FALSE)))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("exceeds remaining purchase amount");

    verify(creditDebitNoteService, never()).createJournalEntry(any(JournalEntryRequest.class));
  }

  @Test
  void postDebitNote_createsScaledReversalAndVoidsPurchaseWhenFullyDebited() {
    LocalDate today = LocalDate.of(2026, 3, 15);
    when(companyClock.today(company)).thenReturn(today);

    Account payable = account(8601L, "AP-8601", AccountType.LIABILITY);
    Account inventory = account(8602L, "RM-8602", AccountType.ASSET);
    Supplier supplier = supplier(860L, "Supplier New", payable);
    RawMaterialPurchase purchase =
        purchase(
            9601L,
            "PUR-9601",
            supplier,
            new BigDecimal("100.00"),
            new BigDecimal("100.00"),
            "POSTED");

    JournalEntry source = journalEntry(9602L, "PUR-9601-JE");
    addJournalLine(
        source, inventory, "Inventory received", new BigDecimal("100.00"), BigDecimal.ZERO);
    addJournalLine(source, payable, "Supplier payable", BigDecimal.ZERO, new BigDecimal("100.00"));
    purchase.setJournalEntry(source);

    JournalEntry saved = journalEntry(9603L, "DN-NEW");
    saved.setSourceModule("DEBIT_NOTE");
    saved.setSourceReference("PUR-9601");
    addJournalLine(
        saved,
        payable,
        "Debit note reversal - Supplier payable",
        new BigDecimal("100.00"),
        BigDecimal.ZERO);
    addJournalLine(
        saved,
        inventory,
        "Debit note reversal - Inventory received",
        BigDecimal.ZERO,
        new BigDecimal("100.00"));

    when(rawMaterialPurchaseRepository.lockByCompanyAndId(company, 9601L))
        .thenReturn(Optional.of(purchase));
    when(journalReferenceResolver.findExistingEntry(company, "DN-NEW"))
        .thenReturn(Optional.empty());
    when(journalEntryRepository.findByCompanyAndReversalOfAndCorrectionReasonIgnoreCase(
            company, source, "DEBIT_NOTE"))
        .thenReturn(List.of());
    when(companyEntityLookup.requireJournalEntry(company, 9603L)).thenReturn(saved);

    ArgumentCaptor<JournalEntryRequest> requestCaptor =
        ArgumentCaptor.forClass(JournalEntryRequest.class);
    doReturn(stubEntry(9603L))
        .when(creditDebitNoteService)
        .createJournalEntry(requestCaptor.capture());

    JournalEntryDto result =
        accountingService.postDebitNote(
            new DebitNoteRequest(9601L, null, null, "DN-NEW", null, null, Boolean.TRUE));

    assertThat(result.id()).isEqualTo(9603L);
    assertThat(requestCaptor.getValue().referenceNumber()).isEqualTo("DN-NEW");
    assertThat(requestCaptor.getValue().entryDate()).isEqualTo(today);
    assertThat(requestCaptor.getValue().memo()).isEqualTo("Debit note for purchase PUR-9601");
    assertThat(requestCaptor.getValue().supplierId()).isEqualTo(860L);
    assertThat(requestCaptor.getValue().sourceModule()).isEqualTo("DEBIT_NOTE");
    assertThat(requestCaptor.getValue().sourceReference()).isEqualTo("PUR-9601");
    assertThat(requestCaptor.getValue().lines()).hasSize(2);
    assertThat(requestCaptor.getValue().lines().get(0).description())
        .startsWith("Debit note reversal - ");
    assertThat(saved.getReversalOf()).isSameAs(source);
    assertThat(saved.getCorrectionType()).isEqualTo(JournalCorrectionType.REVERSAL);
    assertThat(saved.getCorrectionReason()).isEqualTo("DEBIT_NOTE");
    assertThat(saved.getSourceModule()).isEqualTo("DEBIT_NOTE");
    assertThat(saved.getSourceReference()).isEqualTo("PUR-9601");
    assertThat(purchase.getOutstandingAmount()).isEqualByComparingTo("0.00");
    assertThat(purchase.getStatus()).isEqualTo("VOID");
    verify(journalEntryRepository).save(saved);
  }

  @Test
  void postDebitNote_replaysExistingReferenceWithoutReapplyingPurchase() {
    Account payable = account(8604L, "AP-8604", AccountType.LIABILITY);
    Account inventory = account(8605L, "RM-8605", AccountType.ASSET);
    Supplier supplier = supplier(861L, "Supplier Replay", payable);
    RawMaterialPurchase purchase =
        purchase(9604L, "PUR-9604", supplier, new BigDecimal("100.00"), BigDecimal.ZERO, "VOID");

    JournalEntry source = journalEntry(9605L, "PUR-9604-JE");
    addJournalLine(
        source, inventory, "Inventory received", new BigDecimal("100.00"), BigDecimal.ZERO);
    addJournalLine(source, payable, "Supplier payable", BigDecimal.ZERO, new BigDecimal("100.00"));
    purchase.setJournalEntry(source);

    JournalEntry existing = journalEntry(9606L, "DN-REPLAY");
    existing.setSupplier(supplier);
    existing.setReversalOf(source);
    existing.setCorrectionType(JournalCorrectionType.REVERSAL);
    existing.setCorrectionReason("DEBIT_NOTE");
    existing.setSourceModule("DEBIT_NOTE");
    existing.setSourceReference("PUR-9604");
    addJournalLine(
        existing,
        payable,
        "Debit note reversal - Supplier payable",
        new BigDecimal("100.00"),
        BigDecimal.ZERO);
    addJournalLine(
        existing,
        inventory,
        "Debit note reversal - Inventory received",
        BigDecimal.ZERO,
        new BigDecimal("100.00"));

    when(rawMaterialPurchaseRepository.lockByCompanyAndId(company, 9604L))
        .thenReturn(Optional.of(purchase));
    when(journalReferenceResolver.findExistingEntry(company, "DN-REPLAY"))
        .thenReturn(Optional.of(existing));
    when(journalEntryRepository.findByCompanyAndReversalOfAndCorrectionReasonIgnoreCase(
            company, source, "DEBIT_NOTE"))
        .thenReturn(List.of(existing));

    JournalEntryDto result =
        accountingService.postDebitNote(
            new DebitNoteRequest(9604L, null, null, "DN-REPLAY", "replay", null, Boolean.TRUE));

    assertThat(result.id()).isEqualTo(9606L);
    assertThat(purchase.getOutstandingAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(purchase.getStatus()).isEqualTo("VOID");
    verify(creditDebitNoteService, never()).createJournalEntry(any(JournalEntryRequest.class));
    verify(journalEntryRepository, never()).save(existing);
  }

  @Test
  void postDebitNote_replayRejectsDifferentReferenceNumberForSameIdempotencyKey() {
    Account payable = account(86055L, "AP-86055", AccountType.LIABILITY);
    Account inventory = account(86056L, "RM-86056", AccountType.ASSET);
    Supplier supplier = supplier(8615L, "Supplier Replay Ref Drift", payable);
    RawMaterialPurchase purchase =
        purchase(
            96045L, "PUR-96045", supplier, new BigDecimal("100.00"), BigDecimal.ZERO, "VOID");

    JournalEntry source = journalEntry(96046L, "PUR-96045-JE");
    addJournalLine(
        source, inventory, "Inventory received", new BigDecimal("100.00"), BigDecimal.ZERO);
    addJournalLine(source, payable, "Supplier payable", BigDecimal.ZERO, new BigDecimal("100.00"));
    purchase.setJournalEntry(source);

    JournalEntry existing = journalEntry(96047L, "DN-REPLAY-ORIGINAL");
    existing.setSupplier(supplier);
    existing.setReversalOf(source);
    existing.setCorrectionType(JournalCorrectionType.REVERSAL);
    existing.setCorrectionReason("DEBIT_NOTE");
    existing.setSourceModule("DEBIT_NOTE");
    existing.setSourceReference("PUR-96045");
    addJournalLine(
        existing,
        payable,
        "Debit note reversal - Supplier payable",
        new BigDecimal("100.00"),
        BigDecimal.ZERO);
    addJournalLine(
        existing,
        inventory,
        "Debit note reversal - Inventory received",
        BigDecimal.ZERO,
        new BigDecimal("100.00"));

    when(rawMaterialPurchaseRepository.lockByCompanyAndId(company, 96045L))
        .thenReturn(Optional.of(purchase));
    when(journalReferenceResolver.findExistingEntry(company, "DN-REPLAY-NEW"))
        .thenReturn(Optional.empty());
    when(journalReferenceResolver.findExistingEntry(company, "IDEMP-DN-REPLAY-REF"))
        .thenReturn(Optional.of(existing));

    assertThatThrownBy(
            () ->
                accountingService.postDebitNote(
                    new DebitNoteRequest(
                        96045L,
                        null,
                        null,
                        "DN-REPLAY-NEW",
                        "replay",
                        "IDEMP-DN-REPLAY-REF",
                        Boolean.TRUE)))
        .isInstanceOf(ApplicationException.class)
        .satisfies(
            ex ->
                assertThat(((ApplicationException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.CONCURRENCY_CONFLICT));

    verify(creditDebitNoteService, never()).createJournalEntry(any(JournalEntryRequest.class));
    verify(journalEntryRepository, never()).save(existing);
  }

  @Test
  void postDebitNote_replayRejectsProvenanceLessExistingJournal() {
    Account payable = account(8606L, "AP-8606", AccountType.LIABILITY);
    Account inventory = account(8607L, "RM-8607", AccountType.ASSET);
    Supplier supplier = supplier(862L, "Supplier Replay Conflict", payable);
    RawMaterialPurchase purchase =
        purchase(9607L, "PUR-9607", supplier, new BigDecimal("100.00"), BigDecimal.ZERO, "VOID");

    JournalEntry source = journalEntry(9608L, "PUR-9607-JE");
    addJournalLine(
        source, inventory, "Inventory received", new BigDecimal("100.00"), BigDecimal.ZERO);
    addJournalLine(source, payable, "Supplier payable", BigDecimal.ZERO, new BigDecimal("100.00"));
    purchase.setJournalEntry(source);

    JournalEntry existing = journalEntry(9609L, "DN-REPLAY-CONFLICT");
    existing.setSupplier(supplier);
    addJournalLine(
        existing,
        payable,
        "Debit note reversal - Supplier payable",
        new BigDecimal("100.00"),
        BigDecimal.ZERO);
    addJournalLine(
        existing,
        inventory,
        "Debit note reversal - Inventory received",
        BigDecimal.ZERO,
        new BigDecimal("100.00"));

    when(rawMaterialPurchaseRepository.lockByCompanyAndId(company, 9607L))
        .thenReturn(Optional.of(purchase));
    when(journalReferenceResolver.findExistingEntry(company, "DN-REPLAY-CONFLICT"))
        .thenReturn(Optional.of(existing));

    assertThatThrownBy(
            () ->
                accountingService.postDebitNote(
                    new DebitNoteRequest(
                        9607L, null, null, "DN-REPLAY-CONFLICT", "replay", null, Boolean.TRUE)))
        .isInstanceOf(ApplicationException.class)
        .satisfies(
            ex ->
                assertThat(((ApplicationException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.CONCURRENCY_CONFLICT));

    verify(creditDebitNoteService, never()).createJournalEntry(any(JournalEntryRequest.class));
    verify(journalEntryRepository, never()).save(existing);
  }

  @Test
  void postDebitNote_nonLeaderReplayRejectsOtherPurchase() {
    Account payable = account(8610L, "AP-8610", AccountType.LIABILITY);
    Account inventory = account(8611L, "RM-8611", AccountType.ASSET);
    Supplier supplier = supplier(863L, "Supplier Replay Race", payable);

    RawMaterialPurchase existingPurchase =
        purchase(
            9610L,
            "PUR-9610",
            supplier,
            new BigDecimal("100.00"),
            BigDecimal.ZERO,
            "VOID");
    JournalEntry existingSource = journalEntry(9611L, "PUR-9610-JE");
    addJournalLine(
        existingSource, inventory, "Inventory received", new BigDecimal("100.00"), BigDecimal.ZERO);
    addJournalLine(
        existingSource, payable, "Supplier payable", BigDecimal.ZERO, new BigDecimal("100.00"));
    existingPurchase.setJournalEntry(existingSource);

    RawMaterialPurchase replayedPurchase =
        purchase(
            9612L,
            "PUR-9612",
            supplier,
            new BigDecimal("100.00"),
            new BigDecimal("100.00"),
            "POSTED");
    JournalEntry replayedSource = journalEntry(9613L, "PUR-9612-JE");
    addJournalLine(
        replayedSource, inventory, "Inventory received", new BigDecimal("100.00"), BigDecimal.ZERO);
    addJournalLine(
        replayedSource, payable, "Supplier payable", BigDecimal.ZERO, new BigDecimal("100.00"));
    replayedPurchase.setJournalEntry(replayedSource);

    JournalEntry existing = journalEntry(9614L, "DN-RACE");
    existing.setSupplier(supplier);
    existing.setReversalOf(existingSource);
    existing.setCorrectionType(JournalCorrectionType.REVERSAL);
    existing.setCorrectionReason("DEBIT_NOTE");
    existing.setSourceModule("DEBIT_NOTE");
    existing.setSourceReference("PUR-9610");
    addJournalLine(
        existing,
        payable,
        "Debit note reversal - Supplier payable",
        new BigDecimal("100.00"),
        BigDecimal.ZERO);
    addJournalLine(
        existing,
        inventory,
        "Debit note reversal - Inventory received",
        BigDecimal.ZERO,
        new BigDecimal("100.00"));

    JournalReferenceMapping mapping = new JournalReferenceMapping();
    mapping.setCompany(company);
    mapping.setLegacyReference("idemp-dn-race");
    mapping.setCanonicalReference("DN-RACE");
    mapping.setEntityType("DEBIT_NOTE");

    when(rawMaterialPurchaseRepository.lockByCompanyAndId(company, 9612L))
        .thenReturn(Optional.of(replayedPurchase));
    when(journalReferenceResolver.findExistingEntry(company, "DN-RACE")).thenReturn(Optional.empty());

    AtomicInteger replayLookups = new AtomicInteger(0);
    when(journalReferenceResolver.findExistingEntry(company, "IDEMP-DN-RACE"))
        .thenAnswer(
            invocation ->
                replayLookups.getAndIncrement() == 0 ? Optional.empty() : Optional.of(existing));
    when(journalReferenceMappingRepository.findAllByCompanyAndLegacyReferenceIgnoreCase(
            company, "idemp-dn-race"))
        .thenReturn(List.of(), List.of(mapping));
    when(journalReferenceMappingRepository.reserveReferenceMapping(
            eq(company.getId()),
            eq("idemp-dn-race"),
            eq("DN-RACE"),
            eq("DEBIT_NOTE"),
            any()))
        .thenReturn(0);

    assertThatThrownBy(
            () ->
                accountingService.postDebitNote(
                    new DebitNoteRequest(
                        9612L,
                        null,
                        null,
                        "DN-RACE",
                        "race",
                        "IDEMP-DN-RACE",
                        Boolean.TRUE)))
        .isInstanceOf(ApplicationException.class)
        .satisfies(
            ex ->
                assertThat(((ApplicationException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.CONCURRENCY_CONFLICT));

    assertThat(replayedPurchase.getOutstandingAmount()).isEqualByComparingTo("100.00");
    assertThat(replayedPurchase.getStatus()).isEqualTo("POSTED");
    verify(creditDebitNoteService, never()).createJournalEntry(any(JournalEntryRequest.class));
    verify(journalEntryRepository, never()).save(existing);
  }

  @Test
  void postDebitNote_leaderReplayRejectsOtherPurchaseDuplicateReturn() {
    Account payable = account(8612L, "AP-8612", AccountType.LIABILITY);
    Account inventory = account(8613L, "RM-8613", AccountType.ASSET);
    Supplier supplier = supplier(864L, "Supplier Leader Replay Race", payable);

    RawMaterialPurchase existingPurchase =
        purchase(
            9614L,
            "PUR-9614",
            supplier,
            new BigDecimal("100.00"),
            BigDecimal.ZERO,
            "VOID");
    JournalEntry existingSource = journalEntry(9615L, "PUR-9614-JE");
    addJournalLine(
        existingSource, inventory, "Inventory received", new BigDecimal("100.00"), BigDecimal.ZERO);
    addJournalLine(
        existingSource, payable, "Supplier payable", BigDecimal.ZERO, new BigDecimal("100.00"));
    existingPurchase.setJournalEntry(existingSource);

    RawMaterialPurchase replayedPurchase =
        purchase(
            9616L,
            "PUR-9616",
            supplier,
            new BigDecimal("100.00"),
            new BigDecimal("100.00"),
            "POSTED");
    JournalEntry replayedSource = journalEntry(9617L, "PUR-9616-JE");
    addJournalLine(
        replayedSource, inventory, "Inventory received", new BigDecimal("100.00"), BigDecimal.ZERO);
    addJournalLine(
        replayedSource, payable, "Supplier payable", BigDecimal.ZERO, new BigDecimal("100.00"));
    replayedPurchase.setJournalEntry(replayedSource);

    JournalEntry existing = journalEntry(9618L, "DN-RACE-LEADER");
    existing.setSupplier(supplier);
    existing.setReversalOf(existingSource);
    existing.setCorrectionType(JournalCorrectionType.REVERSAL);
    existing.setCorrectionReason("DEBIT_NOTE");
    existing.setSourceModule("DEBIT_NOTE");
    existing.setSourceReference("PUR-9614");
    addJournalLine(
        existing,
        payable,
        "Debit note reversal - Supplier payable",
        new BigDecimal("100.00"),
        BigDecimal.ZERO);
    addJournalLine(
        existing,
        inventory,
        "Debit note reversal - Inventory received",
        BigDecimal.ZERO,
        new BigDecimal("100.00"));

    when(rawMaterialPurchaseRepository.lockByCompanyAndId(company, 9616L))
        .thenReturn(Optional.of(replayedPurchase));
    when(journalReferenceResolver.findExistingEntry(company, "DN-RACE-LEADER"))
        .thenReturn(Optional.empty());
    when(journalReferenceResolver.findExistingEntry(company, "IDEMP-DN-RACE-LEADER"))
        .thenReturn(Optional.empty());
    when(journalReferenceMappingRepository.findAllByCompanyAndLegacyReferenceIgnoreCase(
            company, "idemp-dn-race-leader"))
        .thenReturn(List.of());
    when(journalReferenceMappingRepository.reserveReferenceMapping(
            eq(company.getId()),
            eq("idemp-dn-race-leader"),
            eq("DN-RACE-LEADER"),
            eq("DEBIT_NOTE"),
            any()))
        .thenReturn(1);
    when(companyEntityLookup.requireJournalEntry(company, 9618L)).thenReturn(existing);
    doReturn(stubEntry(9618L))
        .when(creditDebitNoteService)
        .createJournalEntry(any(JournalEntryRequest.class));

    assertThatThrownBy(
            () ->
                accountingService.postDebitNote(
                    new DebitNoteRequest(
                        9616L,
                        null,
                        null,
                        "DN-RACE-LEADER",
                        "race",
                        "IDEMP-DN-RACE-LEADER",
                        Boolean.TRUE)))
        .isInstanceOf(ApplicationException.class)
        .satisfies(
            ex ->
                assertThat(((ApplicationException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.CONCURRENCY_CONFLICT));

    assertThat(replayedPurchase.getOutstandingAmount()).isEqualByComparingTo("100.00");
    assertThat(replayedPurchase.getStatus()).isEqualTo("POSTED");
    verify(journalEntryRepository, never()).save(existing);
  }

  @Test
  void postDebitNote_nonLeaderReplayReturnsAwaitedJournalAndBackfillsProvenance() {
    Account payable = account(8614L, "AP-8614", AccountType.LIABILITY);
    Account inventory = account(8615L, "RM-8615", AccountType.ASSET);
    Supplier supplier = supplier(865L, "Supplier Awaited Replay", payable);

    RawMaterialPurchase purchase =
        purchase(
            9619L,
            "PUR-9619",
            supplier,
            new BigDecimal("100.00"),
            new BigDecimal("100.00"),
            "POSTED");
    JournalEntry source = journalEntry(9620L, "PUR-9619-JE");
    addJournalLine(
        source, inventory, "Inventory received", new BigDecimal("100.00"), BigDecimal.ZERO);
    addJournalLine(source, payable, "Supplier payable", BigDecimal.ZERO, new BigDecimal("100.00"));
    purchase.setJournalEntry(source);

    JournalEntry awaited = journalEntry(9621L, "IDEMP-DN-AWAIT");
    awaited.setSupplier(supplier);
    awaited.setSourceReference("PUR-9619");
    addJournalLine(
        awaited,
        payable,
        "Debit note reversal - Supplier payable",
        new BigDecimal("100.00"),
        BigDecimal.ZERO);
    addJournalLine(
        awaited,
        inventory,
        "Debit note reversal - Inventory received",
        BigDecimal.ZERO,
        new BigDecimal("100.00"));

    JournalReferenceMapping mapping = new JournalReferenceMapping();
    mapping.setCompany(company);
    mapping.setLegacyReference("idemp-dn-await");
    mapping.setCanonicalReference("IDEMP-DN-AWAIT");
    mapping.setEntityType("DEBIT_NOTE");

    when(rawMaterialPurchaseRepository.lockByCompanyAndId(company, 9619L))
        .thenReturn(Optional.of(purchase));

    AtomicInteger replayLookups = new AtomicInteger(0);
    when(journalReferenceResolver.findExistingEntry(company, "IDEMP-DN-AWAIT"))
        .thenAnswer(
            invocation ->
                replayLookups.getAndIncrement() == 0 ? Optional.empty() : Optional.of(awaited));
    when(journalReferenceMappingRepository.findAllByCompanyAndLegacyReferenceIgnoreCase(
            company, "idemp-dn-await"))
        .thenReturn(List.of(), List.of(mapping));
    when(journalReferenceMappingRepository.reserveReferenceMapping(
            eq(company.getId()),
            eq("idemp-dn-await"),
            eq("IDEMP-DN-AWAIT"),
            eq("DEBIT_NOTE"),
            any()))
        .thenReturn(0);

    JournalEntryDto result =
        accountingService.postDebitNote(
            new DebitNoteRequest(
                9619L, null, null, "   ", "awaited replay", " IDEMP-DN-AWAIT ", Boolean.TRUE));

    assertThat(result.id()).isEqualTo(9621L);
    assertThat(awaited.getReversalOf()).isSameAs(source);
    assertThat(awaited.getCorrectionType()).isEqualTo(JournalCorrectionType.REVERSAL);
    assertThat(awaited.getCorrectionReason()).isEqualTo("DEBIT_NOTE");
    assertThat(awaited.getSourceModule()).isEqualTo("DEBIT_NOTE");
    assertThat(awaited.getSourceReference()).isEqualTo("PUR-9619");
    assertThat(purchase.getOutstandingAmount()).isEqualByComparingTo("100.00");
    assertThat(purchase.getStatus()).isEqualTo("POSTED");
    verify(journalEntryRepository).save(awaited);
    verify(creditDebitNoteService, never()).createJournalEntry(any(JournalEntryRequest.class));
  }

  @Test
  void postDebitNote_generatesReferenceWhenReferenceAndIdempotencyAreMissing() {
    Account payable = account(8616L, "AP-8616", AccountType.LIABILITY);
    Account inventory = account(8617L, "RM-8617", AccountType.ASSET);
    Supplier supplier = supplier(866L, "Supplier Generated Reference", payable);
    RawMaterialPurchase purchase =
        purchase(
            9622L,
            "PUR-9622",
            supplier,
            new BigDecimal("100.00"),
            new BigDecimal("100.00"),
            "POSTED");

    JournalEntry source = journalEntry(9623L, "PUR-9622-JE");
    addJournalLine(
        source, inventory, "Inventory received", new BigDecimal("100.00"), BigDecimal.ZERO);
    addJournalLine(source, payable, "Supplier payable", BigDecimal.ZERO, new BigDecimal("100.00"));
    purchase.setJournalEntry(source);

    JournalEntry saved = journalEntry(9624L, "DN-GEN-1");
    saved.setCorrectionReason("DEBIT_NOTE");
    saved.setSourceModule("DEBIT_NOTE");
    saved.setSourceReference("PUR-9622");
    addJournalLine(
        saved,
        payable,
        "Debit note reversal - Supplier payable",
        new BigDecimal("40.00"),
        BigDecimal.ZERO);
    addJournalLine(
        saved,
        inventory,
        "Debit note reversal - Inventory received",
        BigDecimal.ZERO,
        new BigDecimal("40.00"));

    when(rawMaterialPurchaseRepository.lockByCompanyAndId(company, 9622L))
        .thenReturn(Optional.of(purchase));
    when(referenceNumberService.nextJournalReference(company)).thenReturn("DN-GEN-1");
    when(journalReferenceResolver.findExistingEntry(company, "DN-GEN-1"))
        .thenReturn(Optional.empty());
    when(journalReferenceMappingRepository.findAllByCompanyAndLegacyReferenceIgnoreCase(
            company, "dn-gen-1"))
        .thenReturn(List.of());
    when(journalReferenceMappingRepository.reserveReferenceMapping(
            eq(company.getId()), eq("dn-gen-1"), eq("DN-GEN-1"), eq("DEBIT_NOTE"), any()))
        .thenReturn(1);
    when(journalEntryRepository.findByCompanyAndReversalOfAndCorrectionReasonIgnoreCase(
            company, source, "DEBIT_NOTE"))
        .thenReturn(List.of());
    when(companyEntityLookup.requireJournalEntry(company, 9624L)).thenReturn(saved);

    ArgumentCaptor<JournalEntryRequest> requestCaptor =
        ArgumentCaptor.forClass(JournalEntryRequest.class);
    doReturn(stubEntry(9624L))
        .when(creditDebitNoteService)
        .createJournalEntry(requestCaptor.capture());

    LocalDate requestedDate = LocalDate.of(2026, 4, 2);
    JournalEntryDto result =
        accountingService.postDebitNote(
            new DebitNoteRequest(
                9622L,
                new BigDecimal("40.00"),
                requestedDate,
                "   ",
                null,
                null,
                Boolean.FALSE));

    assertThat(result.id()).isEqualTo(9624L);
    assertThat(requestCaptor.getValue().referenceNumber()).isEqualTo("DN-GEN-1");
    assertThat(requestCaptor.getValue().entryDate()).isEqualTo(requestedDate);
    verify(referenceNumberService).nextJournalReference(company);
  }

  @Test
  void settlementHeaderHelpers_coverDealerResolutionAndUnappliedRemainders() {
    Dealer dealer = dealer(870L, "Dealer Header", account(8701L, "AR-8701", AccountType.ASSET));

    assertThatThrownBy(
            () ->
                ReflectionTestUtils.invokeMethod(
                    accountingService, "resolveDealerHeaderSettlementAmount", new Object[] {null}))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Dealer settlement request is required");

    DealerSettlementRequest mismatchRequest =
        new DealerSettlementRequest(
            870L,
            200L,
            null,
            null,
            null,
            null,
            new BigDecimal("30.00"),
            null,
            LocalDate.of(2024, 6, 10),
            "DLR-HDR-1",
            "dealer header",
            null,
            Boolean.FALSE,
            null,
            List.of(new SettlementPaymentRequest(200L, new BigDecimal("25.00"), "cash")));
    assertThatThrownBy(
            () ->
                ReflectionTestUtils.invokeMethod(
                    accountingService, "resolveDealerHeaderSettlementAmount", mismatchRequest))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("must match the total payment amount");

    DealerSettlementRequest paymentOnlyRequest =
        new DealerSettlementRequest(
            870L,
            200L,
            null,
            null,
            null,
            null,
            null,
            null,
            LocalDate.of(2024, 6, 10),
            "DLR-HDR-2",
            "dealer header",
            null,
            Boolean.FALSE,
            null,
            List.of(
                new SettlementPaymentRequest(200L, new BigDecimal("20.00"), "cash"),
                new SettlementPaymentRequest(201L, new BigDecimal("15.00"), "upi")));
    BigDecimal paymentOnlyAmount =
        ReflectionTestUtils.invokeMethod(
            accountingService, "resolveDealerHeaderSettlementAmount", paymentOnlyRequest);
    assertThat(paymentOnlyAmount).isEqualByComparingTo("35.00");

    when(invoiceRepository.lockOpenInvoicesForSettlement(company, dealer)).thenReturn(List.of());
    assertThatThrownBy(
            () ->
                ReflectionTestUtils.invokeMethod(
                    accountingService,
                    "buildDealerHeaderSettlementAllocations",
                    company,
                    dealer,
                    new BigDecimal("10.00"),
                    null))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("No open invoices are available");

    Invoice openInvoice = invoice(87010L, dealer, "INV-87010", new BigDecimal("50.00"));
    Invoice settledInvoice = invoice(87011L, dealer, "INV-87011", BigDecimal.ZERO);
    when(invoiceRepository.lockOpenInvoicesForSettlement(company, dealer))
        .thenReturn(List.of(openInvoice, settledInvoice));
    @SuppressWarnings("unchecked")
    List<SettlementAllocationRequest> allocations =
        ReflectionTestUtils.invokeMethod(
            accountingService,
            "buildDealerHeaderSettlementAllocations",
            company,
            dealer,
            new BigDecimal("80.00"),
            SettlementAllocationApplication.ON_ACCOUNT);
    assertThat(allocations).hasSize(2);
    assertThat(allocations.get(0).invoiceId()).isEqualTo(87010L);
    assertThat(allocations.get(0).appliedAmount()).isEqualByComparingTo("50.00");
    assertThat(allocations.get(1).applicationType())
        .isEqualTo(SettlementAllocationApplication.ON_ACCOUNT);
    assertThat(allocations.get(1).appliedAmount()).isEqualByComparingTo("30.00");
  }

  @Test
  void settlementHeaderHelpers_coverSupplierResolutionAndUnappliedRemainders() {
    Supplier supplier =
        supplier(880L, "Supplier Header", account(8801L, "AP-8801", AccountType.LIABILITY));

    assertThatThrownBy(
            () ->
                ReflectionTestUtils.invokeMethod(
                    accountingService,
                    "resolveSupplierHeaderSettlementAmount",
                    new Object[] {null}))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Provide allocations or an amount");

    SupplierSettlementRequest noAmountRequest =
        new SupplierSettlementRequest(
            880L,
            300L,
            null,
            null,
            null,
            null,
            null,
            null,
            LocalDate.of(2024, 6, 11),
            "SUP-HDR-1",
            "supplier header",
            null,
            Boolean.FALSE,
            null);
    assertThatThrownBy(
            () ->
                ReflectionTestUtils.invokeMethod(
                    accountingService, "resolveSupplierHeaderSettlementAmount", noAmountRequest))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Provide allocations or an amount");

    RawMaterialPurchase openPurchase =
        purchase(
            88010L,
            "PUR-88010",
            supplier,
            new BigDecimal("40.00"),
            new BigDecimal("40.00"),
            "POSTED");
    RawMaterialPurchase settledPurchase =
        purchase(88011L, "PUR-88011", supplier, new BigDecimal("25.00"), BigDecimal.ZERO, "POSTED");
    when(rawMaterialPurchaseRepository.lockOpenPurchasesForSettlement(company, supplier))
        .thenReturn(List.of(openPurchase, settledPurchase));

    assertThatThrownBy(
            () ->
                ReflectionTestUtils.invokeMethod(
                    accountingService,
                    "buildSupplierHeaderSettlementAllocations",
                    company,
                    supplier,
                    new BigDecimal("60.00"),
                    null))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("exceeds open purchase outstanding total");

    @SuppressWarnings("unchecked")
    List<SettlementAllocationRequest> allocations =
        ReflectionTestUtils.invokeMethod(
            accountingService,
            "buildSupplierHeaderSettlementAllocations",
            company,
            supplier,
            new BigDecimal("60.00"),
            SettlementAllocationApplication.FUTURE_APPLICATION);
    assertThat(allocations).hasSize(2);
    assertThat(allocations.get(0).purchaseId()).isEqualTo(88010L);
    assertThat(allocations.get(0).appliedAmount()).isEqualByComparingTo("40.00");
    assertThat(allocations.get(1).applicationType())
        .isEqualTo(SettlementAllocationApplication.FUTURE_APPLICATION);
    assertThat(allocations.get(1).appliedAmount()).isEqualByComparingTo("20.00");
  }

  @Test
  void
      settlementHeaderHelpers_replaySelection_prefersDealerIdempotencyKeyAndSupplierReferenceFallback() {
    Dealer dealer = dealer(881L, "Dealer Replay", account(8811L, "AR-8811", AccountType.ASSET));
    Invoice invoice = invoice(88101L, dealer, "INV-88101", new BigDecimal("45.00"));

    PartnerSettlementAllocation dealerReplay = new PartnerSettlementAllocation();
    dealerReplay.setCompany(company);
    dealerReplay.setInvoice(invoice);
    dealerReplay.setAllocationAmount(new BigDecimal("45.00"));
    dealerReplay.setDiscountAmount(BigDecimal.ZERO);
    dealerReplay.setWriteOffAmount(BigDecimal.ZERO);
    dealerReplay.setFxDifferenceAmount(BigDecimal.ZERO);
    dealerReplay.setMemo("  dealer replay  ");

    when(settlementAllocationRepository
            .findByCompanyAndIdempotencyKeyIgnoreCaseOrderByCreatedAtAscIdAsc(
                eq(company), eq("DEALER-IDEMP-881")))
        .thenReturn(List.of(dealerReplay));

    DealerSettlementRequest dealerRequest =
        new DealerSettlementRequest(
            881L,
            200L,
            null,
            null,
            null,
            null,
            null,
            null,
            LocalDate.of(2024, 6, 12),
            "DLR-REF-881",
            "dealer replay",
            "DEALER-IDEMP-881",
            Boolean.FALSE,
            null,
            null);

    @SuppressWarnings("unchecked")
    List<SettlementAllocationRequest> dealerAllocations =
        ReflectionTestUtils.invokeMethod(
            accountingService,
            "resolveDealerSettlementAllocations",
            company,
            dealer,
            dealerRequest);

    assertThat(dealerAllocations)
        .singleElement()
        .satisfies(
            allocation -> {
              assertThat(allocation.invoiceId()).isEqualTo(88101L);
              assertThat(allocation.memo()).isEqualTo("dealer replay");
            });
    verify(settlementAllocationRepository, never())
        .findByCompanyAndIdempotencyKeyIgnoreCaseOrderByCreatedAtAscIdAsc(company, "DLR-REF-881");

    when(settlementAllocationRepository
            .findByCompanyAndIdempotencyKeyIgnoreCaseOrderByCreatedAtAscIdAsc(
                eq(company), eq("DLR-REF-882")))
        .thenReturn(List.of(dealerReplay));

    DealerSettlementRequest dealerReferenceFallbackRequest =
        new DealerSettlementRequest(
            881L,
            200L,
            null,
            null,
            null,
            null,
            null,
            null,
            LocalDate.of(2024, 6, 12),
            "DLR-REF-882",
            "dealer replay",
            "   ",
            Boolean.FALSE,
            null,
            null);

    @SuppressWarnings("unchecked")
    List<SettlementAllocationRequest> dealerReferenceAllocations =
        ReflectionTestUtils.invokeMethod(
            accountingService,
            "resolveDealerSettlementAllocations",
            company,
            dealer,
            dealerReferenceFallbackRequest);

    assertThat(dealerReferenceAllocations)
        .singleElement()
        .satisfies(
            allocation -> {
              assertThat(allocation.invoiceId()).isEqualTo(88101L);
              assertThat(allocation.memo()).isEqualTo("dealer replay");
            });

    Supplier supplier =
        supplier(882L, "Supplier Replay", account(8821L, "AP-8821", AccountType.LIABILITY));
    RawMaterialPurchase purchase =
        purchase(
            88201L,
            "PUR-88201",
            supplier,
            new BigDecimal("35.00"),
            new BigDecimal("35.00"),
            "POSTED");

    PartnerSettlementAllocation supplierReplay = new PartnerSettlementAllocation();
    supplierReplay.setCompany(company);
    supplierReplay.setPurchase(purchase);
    supplierReplay.setAllocationAmount(new BigDecimal("35.00"));
    supplierReplay.setDiscountAmount(BigDecimal.ZERO);
    supplierReplay.setWriteOffAmount(BigDecimal.ZERO);
    supplierReplay.setFxDifferenceAmount(BigDecimal.ZERO);
    supplierReplay.setMemo("  supplier replay  ");

    when(settlementAllocationRepository
            .findByCompanyAndIdempotencyKeyIgnoreCaseOrderByCreatedAtAscIdAsc(
                eq(company), eq("SUP-REF-882")))
        .thenReturn(List.of(supplierReplay));

    SupplierSettlementRequest supplierRequest =
        new SupplierSettlementRequest(
            882L,
            300L,
            null,
            null,
            null,
            null,
            null,
            null,
            LocalDate.of(2024, 6, 12),
            "SUP-REF-882",
            "supplier replay",
            "   ",
            Boolean.FALSE,
            null);

    @SuppressWarnings("unchecked")
    List<SettlementAllocationRequest> supplierAllocations =
        ReflectionTestUtils.invokeMethod(
            accountingService,
            "resolveSupplierSettlementAllocations",
            company,
            supplier,
            supplierRequest);

    assertThat(supplierAllocations)
        .singleElement()
        .satisfies(
            allocation -> {
              assertThat(allocation.purchaseId()).isEqualTo(88201L);
              assertThat(allocation.memo()).isEqualTo("supplier replay");
            });
  }

  @Test
  void settlementHeaderHelpers_resolveProvidedDealerAndSupplierAllocationsDirectly() {
    Dealer dealer = dealer(883L, "Dealer Provided", account(8831L, "AR-8831", AccountType.ASSET));
    Supplier supplier =
        supplier(884L, "Supplier Provided", account(8841L, "AP-8841", AccountType.LIABILITY));

    List<SettlementAllocationRequest> dealerProvided =
        List.of(
            new SettlementAllocationRequest(
                88301L,
                null,
                new BigDecimal("25.00"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                SettlementAllocationApplication.DOCUMENT,
                "dealer provided"));
    DealerSettlementRequest dealerRequest =
        new DealerSettlementRequest(
            883L,
            200L,
            null,
            null,
            null,
            null,
            new BigDecimal("25.00"),
            null,
            LocalDate.of(2024, 6, 14),
            "DLR-PROVIDED-883",
            "dealer provided",
            "DEALER-PROVIDED-883",
            Boolean.FALSE,
            dealerProvided,
            null);

    @SuppressWarnings("unchecked")
    List<SettlementAllocationRequest> resolvedDealer =
        ReflectionTestUtils.invokeMethod(
            accountingService,
            "resolveDealerSettlementAllocations",
            company,
            dealer,
            dealerRequest);
    assertThat(resolvedDealer).containsExactlyElementsOf(dealerProvided);

    List<SettlementAllocationRequest> supplierProvided =
        List.of(
            new SettlementAllocationRequest(
                null,
                88401L,
                new BigDecimal("30.00"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                SettlementAllocationApplication.DOCUMENT,
                "supplier provided"));
    SupplierSettlementRequest supplierRequest =
        new SupplierSettlementRequest(
            884L,
            300L,
            null,
            null,
            null,
            null,
            new BigDecimal("30.00"),
            null,
            LocalDate.of(2024, 6, 14),
            "SUP-PROVIDED-884",
            "supplier provided",
            "   ",
            Boolean.FALSE,
            supplierProvided);

    @SuppressWarnings("unchecked")
    List<SettlementAllocationRequest> resolvedSupplier =
        ReflectionTestUtils.invokeMethod(
            accountingService,
            "resolveSupplierSettlementAllocations",
            company,
            supplier,
            supplierRequest);
    assertThat(resolvedSupplier).containsExactlyElementsOf(supplierProvided);
    verify(settlementAllocationRepository, never())
        .findByCompanyAndIdempotencyKeyIgnoreCaseOrderByCreatedAtAscIdAsc(
            company, "SUP-PROVIDED-884");
  }

  @Test
  void settlementHeaderHelpers_rejectNullSettlementRequestsDuringAllocationResolution() {
    Dealer dealer = dealer(885L, "Dealer Null", account(8851L, "AR-8851", AccountType.ASSET));
    Supplier supplier =
        supplier(886L, "Supplier Null", account(8861L, "AP-8861", AccountType.LIABILITY));

    assertThatThrownBy(
            () ->
                ReflectionTestUtils.invokeMethod(
                    accountingService,
                    "resolveDealerSettlementAllocations",
                    company,
                    dealer,
                    null,
                    null))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Dealer settlement request is required");

    assertThatThrownBy(
            () ->
                ReflectionTestUtils.invokeMethod(
                    accountingService,
                    "resolveSupplierSettlementAllocations",
                    company,
                    supplier,
                    null,
                    null))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Provide allocations or an amount for supplier settlements");
  }

  @Test
  void settlementHeaderHelpers_rejectZeroAmountHeaderAllocationsWhenNothingCanBeBuilt() {
    Dealer dealer = dealer(887L, "Dealer Zero", account(8871L, "AR-8871", AccountType.ASSET));
    Supplier supplier =
        supplier(888L, "Supplier Zero", account(8881L, "AP-8881", AccountType.LIABILITY));

    when(invoiceRepository.lockOpenInvoicesForSettlement(company, dealer)).thenReturn(List.of());
    when(rawMaterialPurchaseRepository.lockOpenPurchasesForSettlement(company, supplier))
        .thenReturn(List.of());

    assertThatThrownBy(
            () ->
                ReflectionTestUtils.invokeMethod(
                    accountingService,
                    "buildDealerHeaderSettlementAllocations",
                    company,
                    dealer,
                    BigDecimal.ZERO,
                    null))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("At least one dealer settlement allocation is required");

    assertThatThrownBy(
            () ->
                ReflectionTestUtils.invokeMethod(
                    accountingService,
                    "buildSupplierHeaderSettlementAllocations",
                    company,
                    supplier,
                    BigDecimal.ZERO,
                    null))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("At least one supplier settlement allocation is required");
  }

  @Test
  void settlementHeaderHelpers_useHeaderAllocationsWhenReplayKeysAreBlank() {
    Dealer dealer = dealer(889L, "Dealer Header", account(8891L, "AR-8891", AccountType.ASSET));
    Supplier supplier =
        supplier(890L, "Supplier Header", account(8901L, "AP-8901", AccountType.LIABILITY));

    Invoice openInvoice = invoice(88901L, dealer, "INV-88901", new BigDecimal("42.00"));
    RawMaterialPurchase openPurchase =
        purchase(
            89001L,
            "PUR-89001",
            supplier,
            new BigDecimal("36.00"),
            new BigDecimal("36.00"),
            "POSTED");

    when(invoiceRepository.lockOpenInvoicesForSettlement(company, dealer))
        .thenReturn(List.of(openInvoice));
    when(rawMaterialPurchaseRepository.lockOpenPurchasesForSettlement(company, supplier))
        .thenReturn(List.of(openPurchase));

    DealerSettlementRequest dealerRequest =
        new DealerSettlementRequest(
            889L,
            200L,
            null,
            null,
            null,
            null,
            new BigDecimal("42.00"),
            null,
            LocalDate.of(2024, 6, 15),
            "   ",
            "dealer header",
            "   ",
            Boolean.FALSE,
            null,
            null);

    SupplierSettlementRequest supplierRequest =
        new SupplierSettlementRequest(
            890L,
            300L,
            null,
            null,
            null,
            null,
            new BigDecimal("36.00"),
            null,
            LocalDate.of(2024, 6, 15),
            "   ",
            "supplier header",
            "   ",
            Boolean.FALSE,
            null);

    @SuppressWarnings("unchecked")
    List<SettlementAllocationRequest> dealerAllocations =
        ReflectionTestUtils.invokeMethod(
            accountingService,
            "resolveDealerSettlementAllocations",
            company,
            dealer,
            dealerRequest);
    @SuppressWarnings("unchecked")
    List<SettlementAllocationRequest> supplierAllocations =
        ReflectionTestUtils.invokeMethod(
            accountingService,
            "resolveSupplierSettlementAllocations",
            company,
            supplier,
            supplierRequest);

    assertThat(dealerAllocations)
        .singleElement()
        .satisfies(
            allocation -> {
              assertThat(allocation.invoiceId()).isEqualTo(88901L);
              assertThat(allocation.appliedAmount()).isEqualByComparingTo("42.00");
            });
    assertThat(supplierAllocations)
        .singleElement()
        .satisfies(
            allocation -> {
              assertThat(allocation.purchaseId()).isEqualTo(89001L);
              assertThat(allocation.appliedAmount()).isEqualByComparingTo("36.00");
            });
  }

  @Test
  void settlementHeaderHelpers_ignoreNullPaymentRowsWhenSummingDealerHeaderAmount() {
    DealerSettlementRequest request =
        new DealerSettlementRequest(
            883L,
            200L,
            null,
            null,
            null,
            null,
            null,
            null,
            LocalDate.of(2024, 6, 13),
            "DLR-HDR-NULL-PAY",
            "dealer header",
            null,
            Boolean.FALSE,
            null,
            java.util.Arrays.asList(
                null, new SettlementPaymentRequest(200L, new BigDecimal("12.50"), "cash")));

    BigDecimal resolvedAmount =
        ReflectionTestUtils.invokeMethod(
            accountingService, "resolveDealerHeaderSettlementAmount", request);

    assertThat(resolvedAmount).isEqualByComparingTo("12.50");
  }

  @Test
  void settlementHeaderHelpers_validateOptionalAmountsAndMemoCodec() {
    assertThatThrownBy(
            () ->
                ReflectionTestUtils.invokeMethod(
                    accountingService,
                    "validateOptionalHeaderSettlementAmount",
                    "dealer",
                    new BigDecimal("25.00"),
                    List.of(
                        new SettlementAllocationRequest(
                            9001L,
                            null,
                            new BigDecimal("20.00"),
                            BigDecimal.ZERO,
                            BigDecimal.ZERO,
                            BigDecimal.ZERO,
                            SettlementAllocationApplication.DOCUMENT,
                            "short"))))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("must add up to the request amount");

    ReflectionTestUtils.invokeMethod(
        accountingService,
        "validateOptionalHeaderSettlementAmount",
        "dealer",
        null,
        List.of(
            new SettlementAllocationRequest(
                9002L,
                null,
                new BigDecimal("25.00"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                SettlementAllocationApplication.DOCUMENT,
                "noop")));
    ReflectionTestUtils.invokeMethod(
        accountingService,
        "validateOptionalHeaderSettlementAmount",
        "dealer",
        new BigDecimal("25.00"),
        List.of());
    ReflectionTestUtils.invokeMethod(
        accountingService,
        "validateOptionalHeaderSettlementAmount",
        "dealer",
        new BigDecimal("25.00"),
        null);

    assertThat(
            (SettlementAllocationApplication)
                ReflectionTestUtils.invokeMethod(
                    accountingService,
                    "normalizeRequestedUnappliedApplication",
                    new Object[] {null}))
        .isNull();
    assertThat(
            (SettlementAllocationApplication)
                ReflectionTestUtils.invokeMethod(
                    accountingService,
                    "normalizeRequestedUnappliedApplication",
                    SettlementAllocationApplication.FUTURE_APPLICATION))
        .isEqualTo(SettlementAllocationApplication.FUTURE_APPLICATION);
    assertThatThrownBy(
            () ->
                ReflectionTestUtils.invokeMethod(
                    accountingService,
                    "normalizeRequestedUnappliedApplication",
                    SettlementAllocationApplication.DOCUMENT))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("must be ON_ACCOUNT or FUTURE_APPLICATION");

    String documentMemo =
        ReflectionTestUtils.invokeMethod(
            accountingService,
            "encodeSettlementAllocationMemo",
            SettlementAllocationApplication.DOCUMENT,
            "  Invoice memo  ");
    String unappliedMemo =
        ReflectionTestUtils.invokeMethod(
            accountingService,
            "encodeSettlementAllocationMemo",
            SettlementAllocationApplication.FUTURE_APPLICATION,
            "  Future memo  ");
    Object malformedDecoded =
        ReflectionTestUtils.invokeMethod(
            accountingService,
            "decodeSettlementAllocationMemo",
            "[SETTLEMENT-APPLICATION:BOGUS]  Keep visible  ");
    Object blankDecoded =
        ReflectionTestUtils.invokeMethod(
            accountingService, "decodeSettlementAllocationMemo", "   ");

    assertThat(documentMemo).isEqualTo("Invoice memo");
    assertThat(unappliedMemo).isEqualTo("[SETTLEMENT-APPLICATION:FUTURE_APPLICATION] Future memo");
    assertThat(
            (SettlementAllocationApplication)
                ReflectionTestUtils.invokeMethod(malformedDecoded, "applicationType"))
        .isEqualTo(SettlementAllocationApplication.ON_ACCOUNT);
    assertThat((String) ReflectionTestUtils.invokeMethod(malformedDecoded, "memo"))
        .isEqualTo("Keep visible");
    assertThat(
            (SettlementAllocationApplication)
                ReflectionTestUtils.invokeMethod(blankDecoded, "applicationType"))
        .isEqualTo(SettlementAllocationApplication.ON_ACCOUNT);
    assertThat((String) ReflectionTestUtils.invokeMethod(blankDecoded, "memo")).isNull();
  }

  @Test
  void helperMethods_coverReceiptReservationAndRetryGuards() {
    assertThat(
            (String)
                ReflectionTestUtils.invokeMethod(
                    accountingService, "reservedManualReference", new Object[] {null}))
        .isEqualTo("RESERVED");
    assertThat(
            (String)
                ReflectionTestUtils.invokeMethod(
                    accountingService, "reservedManualReference", "legacy-key"))
        .startsWith("RESERVED-");

    assertThat(
            (Boolean)
                ReflectionTestUtils.invokeMethod(
                    accountingService, "isReservedReference", new Object[] {null}))
        .isFalse();
    assertThat(
            (Boolean)
                ReflectionTestUtils.invokeMethod(accountingService, "isReservedReference", "   "))
        .isFalse();
    assertThat(
            (Boolean)
                ReflectionTestUtils.invokeMethod(
                    accountingService, "isReservedReference", " reserved-aBc "))
        .isTrue();

    assertThat(
            (String)
                ReflectionTestUtils.invokeMethod(
                    accountingService,
                    "resolveReceiptIdempotencyKey",
                    "  PROVIDED-KEY  ",
                    "  FALLBACK-REF  ",
                    "dealer receipt"))
        .isEqualTo("PROVIDED-KEY");
    assertThat(
            (String)
                ReflectionTestUtils.invokeMethod(
                    accountingService,
                    "resolveReceiptIdempotencyKey",
                    "   ",
                    "  FALLBACK-REF  ",
                    "dealer receipt"))
        .isEqualTo("FALLBACK-REF");
    assertThatThrownBy(
            () ->
                ReflectionTestUtils.invokeMethod(
                    accountingService,
                    "resolveReceiptIdempotencyKey",
                    "   ",
                    "  ",
                    "dealer receipt"))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Idempotency key or reference number is required for dealer receipt");

    assertThat(
            (Boolean)
                ReflectionTestUtils.invokeMethod(
                    accountingService,
                    "isRetryableManualConcurrencyFailure",
                    new RuntimeException(new DataIntegrityViolationException("duplicate"))))
        .isTrue();
    assertThat(
            (Boolean)
                ReflectionTestUtils.invokeMethod(
                    accountingService,
                    "isRetryableManualConcurrencyFailure",
                    new RuntimeException(new org.hibernate.AssertionFailure("assertion"))))
        .isTrue();
    assertThat(
            (Boolean)
                ReflectionTestUtils.invokeMethod(
                    accountingService,
                    "isRetryableManualConcurrencyFailure",
                    new RuntimeException("plain")))
        .isFalse();
  }

  @Test
  void helperMethods_coverLegacyMappingSelectionAndReplayResolution() {
    assertThat(
            (Optional<?>)
                ReflectionTestUtils.invokeMethod(
                    accountingService, "findLatestLegacyReferenceMapping", null, "legacy-map"))
        .isEmpty();
    assertThat(
            (Optional<?>)
                ReflectionTestUtils.invokeMethod(
                    accountingService, "findLatestLegacyReferenceMapping", company, "   "))
        .isEmpty();

    JournalReferenceMapping olderWithoutEntity = new JournalReferenceMapping();
    ReflectionTestUtils.setField(olderWithoutEntity, "id", 1L);
    ReflectionTestUtils.setField(
        olderWithoutEntity, "createdAt", java.time.Instant.parse("2024-06-01T00:00:00Z"));
    olderWithoutEntity.setCanonicalReference("LEGACY-OLD");

    JournalReferenceMapping olderWithEntity = new JournalReferenceMapping();
    ReflectionTestUtils.setField(olderWithEntity, "id", 2L);
    ReflectionTestUtils.setField(
        olderWithEntity, "createdAt", java.time.Instant.parse("2024-06-02T00:00:00Z"));
    olderWithEntity.setCanonicalReference("LEGACY-ENTITY-OLD");
    olderWithEntity.setEntityId(200L);

    JournalReferenceMapping newerWithEntity = new JournalReferenceMapping();
    ReflectionTestUtils.setField(newerWithEntity, "id", 3L);
    ReflectionTestUtils.setField(
        newerWithEntity, "createdAt", java.time.Instant.parse("2024-06-03T00:00:00Z"));
    newerWithEntity.setCanonicalReference("LEGACY-ENTITY-NEW");
    newerWithEntity.setEntityId(201L);

    when(journalReferenceMappingRepository.findAllByCompanyAndLegacyReferenceIgnoreCase(
            company, "legacy-map"))
        .thenReturn(List.of(olderWithoutEntity, olderWithEntity, newerWithEntity));

    @SuppressWarnings("unchecked")
    Optional<JournalReferenceMapping> selected =
        ReflectionTestUtils.invokeMethod(
            accountingService, "findLatestLegacyReferenceMapping", company, "legacy-map");
    assertThat(selected).contains(newerWithEntity);

    Supplier supplier =
        supplier(990L, "Supplier Legacy", account(9901L, "AP-9901", AccountType.LIABILITY));
    when(referenceNumberService.supplierPaymentReference(company, supplier))
        .thenReturn("SUP-FALLBACK-990");

    assertThat(
            (String)
                ReflectionTestUtils.invokeMethod(
                    accountingService,
                    "resolveSupplierPaymentReference",
                    company,
                    supplier,
                    "  SUP-PROVIDED  ",
                    "legacy-map"))
        .isEqualTo("SUP-PROVIDED");
    assertThat(
            (String)
                ReflectionTestUtils.invokeMethod(
                    accountingService,
                    "resolveSupplierPaymentReference",
                    company,
                    supplier,
                    null,
                    "legacy-map"))
        .isEqualTo("LEGACY-ENTITY-NEW");
    assertThat(
            (String)
                ReflectionTestUtils.invokeMethod(
                    accountingService,
                    "resolveSupplierPaymentReference",
                    company,
                    supplier,
                    "   ",
                    "   "))
        .isEqualTo("SUP-FALLBACK-990");

    SupplierSettlementRequest supplierSettlementRequest =
        new SupplierSettlementRequest(
            supplier.getId(),
            300L,
            null,
            null,
            null,
            null,
            null,
            null,
            LocalDate.of(2024, 6, 30),
            null,
            "supplier legacy",
            "legacy-map",
            Boolean.FALSE,
            null);
    assertThat(
            (String)
                ReflectionTestUtils.invokeMethod(
                    accountingService,
                    "resolveSupplierSettlementReference",
                    company,
                    supplier,
                    supplierSettlementRequest,
                    "legacy-map"))
        .isEqualTo("LEGACY-ENTITY-NEW");

    assertThat(
            (Boolean)
                ReflectionTestUtils.invokeMethod(
                    accountingService, "hasExistingIdempotencyMapping", company, "   "))
        .isFalse();
    assertThat(
            (Boolean)
                ReflectionTestUtils.invokeMethod(
                    accountingService, "hasExistingIdempotencyMapping", company, "legacy-map"))
        .isTrue();

    PartnerSettlementAllocation exactOnlyAllocation = new PartnerSettlementAllocation();
    when(settlementAllocationRepository
            .findByCompanyAndIdempotencyKeyIgnoreCaseOrderByCreatedAtAscIdAsc(
                company, "ALLOC-EXACT"))
        .thenReturn(List.of());
    when(settlementAllocationRepository.findByCompanyAndIdempotencyKey(company, "ALLOC-EXACT"))
        .thenReturn(List.of(exactOnlyAllocation));

    assertThat(
            (Boolean)
                ReflectionTestUtils.invokeMethod(
                    accountingService, "hasExistingSettlementAllocations", company, "ALLOC-EXACT"))
        .isTrue();
    assertThat(
            (Boolean)
                ReflectionTestUtils.invokeMethod(
                    accountingService, "hasExistingSettlementAllocations", company, "   "))
        .isFalse();

    JournalEntry mappingEntry = journalEntry(9910L, "MAP-9910");
    JournalEntry allocationEntry = journalEntry(9911L, "ALLOC-9911");
    PartnerSettlementAllocation mismatchAllocation = new PartnerSettlementAllocation();
    mismatchAllocation.setJournalEntry(allocationEntry);

    assertThatThrownBy(
            () ->
                ReflectionTestUtils.invokeMethod(
                    accountingService,
                    "resolveReplayJournalEntry",
                    "ALLOC-EXACT",
                    mappingEntry,
                    List.of(mismatchAllocation)))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining(
            "Idempotency mapping points to a different journal than settled allocations");

    PartnerSettlementAllocation matchedAllocation = new PartnerSettlementAllocation();
    matchedAllocation.setJournalEntry(mappingEntry);
    assertThat(
            (JournalEntry)
                ReflectionTestUtils.invokeMethod(
                    accountingService,
                    "resolveReplayJournalEntry",
                    "ALLOC-EXACT",
                    mappingEntry,
                    List.of(matchedAllocation)))
        .isSameAs(mappingEntry);
  }

  @Test
  void helperMethods_coverIdempotencySanitizersAndSettlementAdjustmentSignatures() {
    assertThat(
            (String)
                ReflectionTestUtils.invokeMethod(
                    accountingService, "normalizeIdempotencyMappingKey", new Object[] {null}))
        .isEqualTo("");
    assertThat(
            (String)
                ReflectionTestUtils.invokeMethod(
                    accountingService, "normalizeIdempotencyMappingKey", "  Mixed-Key  "))
        .isEqualTo("mixed-key");

    assertThat(
            (String)
                ReflectionTestUtils.invokeMethod(
                    accountingService, "sanitizeIdempotencyLogValue", "   "))
        .isEqualTo("<empty>");
    assertThat(
            (String)
                ReflectionTestUtils.invokeMethod(
                    accountingService, "sanitizeIdempotencyLogValue", "Secret-Key"))
        .isEqualTo(
            com.bigbrightpaints.erp.core.idempotency.IdempotencyUtils.sha256Hex("Secret-Key", 12));

    assertThat(
            (String)
                ReflectionTestUtils.invokeMethod(
                    accountingService, "sanitizeToken", new Object[] {null}))
        .isEqualTo("TOKEN");
    assertThat(
            (String)
                ReflectionTestUtils.invokeMethod(
                    accountingService, "sanitizeToken", " token-1234567890-ABCDEFG "))
        .isEqualTo("TOKEN1234567890A");

    Supplier supplier =
        supplier(991L, "Supplier Branches", account(9911L, "AP-9911", AccountType.LIABILITY));
    when(referenceNumberService.supplierPaymentReference(company, supplier))
        .thenReturn("SUP-FALLBACK-991");
    when(journalReferenceMappingRepository.findAllByCompanyAndLegacyReferenceIgnoreCase(
            company, "settlement-key"))
        .thenReturn(List.of());

    SupplierSettlementRequest providedReferenceRequest =
        new SupplierSettlementRequest(
            supplier.getId(),
            300L,
            null,
            null,
            null,
            null,
            null,
            null,
            LocalDate.of(2024, 7, 1),
            "  SETTLE-REF-991  ",
            "provided ref",
            "settlement-key",
            Boolean.FALSE,
            null);
    assertThat(
            (String)
                ReflectionTestUtils.invokeMethod(
                    accountingService,
                    "resolveSupplierSettlementReference",
                    company,
                    supplier,
                    providedReferenceRequest,
                    "settlement-key"))
        .isEqualTo("SETTLE-REF-991");

    SupplierSettlementRequest generatedReferenceRequest =
        new SupplierSettlementRequest(
            supplier.getId(),
            300L,
            null,
            null,
            null,
            null,
            null,
            null,
            LocalDate.of(2024, 7, 2),
            null,
            "generated ref",
            "settlement-key",
            Boolean.FALSE,
            null);
    assertThat(
            (String)
                ReflectionTestUtils.invokeMethod(
                    accountingService,
                    "resolveSupplierSettlementReference",
                    company,
                    supplier,
                    generatedReferenceRequest,
                    "settlement-key"))
        .isEqualTo("SUP-FALLBACK-991");

    PartnerSettlementAllocation discountAllocation = new PartnerSettlementAllocation();
    discountAllocation.setDiscountAmount(new BigDecimal("4.25"));
    discountAllocation.setWriteOffAmount(new BigDecimal("1.75"));
    discountAllocation.setFxDifferenceAmount(new BigDecimal("-2.50"));
    PartnerSettlementAllocation ignoredAllocation = new PartnerSettlementAllocation();
    ignoredAllocation.setDiscountAmount(BigDecimal.ZERO);
    ignoredAllocation.setWriteOffAmount(BigDecimal.ZERO);
    ignoredAllocation.setFxDifferenceAmount(new BigDecimal("3.00"));

    List<PartnerSettlementAllocation> adjustmentRows = new ArrayList<>();
    adjustmentRows.add(null);
    adjustmentRows.add(discountAllocation);
    adjustmentRows.add(ignoredAllocation);

    @SuppressWarnings("unchecked")
    List<Object> signatures =
        ReflectionTestUtils.invokeMethod(
            accountingService, "buildSettlementAdjustmentSignaturesFromRows", adjustmentRows);
    assertThat(signatures).hasSize(3);
    assertThat(
            (String) ReflectionTestUtils.invokeMethod(signatures.get(0), "normalizedDescription"))
        .isEqualTo("settlement discount");
    assertThat((BigDecimal) ReflectionTestUtils.invokeMethod(signatures.get(0), "amount"))
        .isEqualByComparingTo("4.25");
    assertThat(
            (String) ReflectionTestUtils.invokeMethod(signatures.get(1), "normalizedDescription"))
        .isEqualTo("settlement write-off");
    assertThat((BigDecimal) ReflectionTestUtils.invokeMethod(signatures.get(1), "amount"))
        .isEqualByComparingTo("1.75");
    assertThat(
            (String) ReflectionTestUtils.invokeMethod(signatures.get(2), "normalizedDescription"))
        .isEqualTo("fx loss on settlement");
    assertThat((BigDecimal) ReflectionTestUtils.invokeMethod(signatures.get(2), "amount"))
        .isEqualByComparingTo("2.50");

    DealerSettlementRequest adjustmentRequest =
        new DealerSettlementRequest(
            700L,
            701L,
            702L,
            703L,
            704L,
            705L,
            LocalDate.of(2024, 7, 3),
            "SETTLE-ADJ-1",
            "adjustment ids",
            "ADJ-IDS-1",
            Boolean.FALSE,
            List.of(),
            null);
    @SuppressWarnings("unchecked")
    Map<String, Long> adjustmentIds =
        ReflectionTestUtils.invokeMethod(
            accountingService, "requestedAdjustmentAccountIds", adjustmentRequest);
    assertThat(adjustmentIds)
        .containsEntry("settlement discount", 702L)
        .containsEntry("settlement write-off", 703L)
        .containsEntry("fx loss on settlement", 705L);
    assertThat(
            (Map<?, ?>)
                ReflectionTestUtils.invokeMethod(
                    accountingService, "requestedAdjustmentAccountIds", new Object[] {null}))
        .isEmpty();
  }

  @Test
  @Tag("critical")
  void helperMethods_coverRemainingAmountsAndSettlementAuditMetadata() {
    Account receivable = account(9921L, "AR-9921", AccountType.ASSET);
    Account revenue = account(9922L, "REV-9922", AccountType.REVENUE);
    Dealer dealer = dealer(992L, "Dealer Remaining", receivable);
    Invoice invoice = invoice(9923L, dealer, "INV-9923", new BigDecimal("20.00"));
    invoice.setTotalAmount(new BigDecimal("100.00"));
    JournalEntry invoiceSource = journalEntry(9924L, "INV-9923-JE");
    JournalEntry priorCredit =
        creditNoteEntry(9925L, "CN-9925", dealer, invoiceSource, receivable, revenue, "90.00");

    Account payable = account(9931L, "AP-9931", AccountType.LIABILITY);
    Account inventory = account(9932L, "RM-9932", AccountType.ASSET);
    Supplier supplier = supplier(993L, "Supplier Remaining", payable);
    RawMaterialPurchase purchase =
        purchase(
            9933L,
            "PUR-9933",
            supplier,
            new BigDecimal("100.00"),
            new BigDecimal("50.00"),
            "POSTED");
    JournalEntry purchaseSource = journalEntry(9934L, "PUR-9933-JE");
    JournalEntry priorDebit = journalEntry(9935L, "DN-9935");
    addJournalLine(
        priorDebit, payable, "Debit note reversal - Supplier payable", BigDecimal.ZERO, new BigDecimal("70.00"));
    addJournalLine(
        priorDebit,
        inventory,
        "Debit note reversal - Inventory received",
        new BigDecimal("70.00"),
        BigDecimal.ZERO);

    when(journalEntryRepository.findByCompanyAndReversalOfAndCorrectionReasonIgnoreCase(
            company, invoiceSource, "CREDIT_NOTE"))
        .thenReturn(List.of(priorCredit));
    when(journalEntryRepository.findByCompanyAndReversalOfAndCorrectionReasonIgnoreCase(
            company, purchaseSource, "DEBIT_NOTE"))
        .thenReturn(List.of(priorDebit));

    assertThat(
            (BigDecimal)
                ReflectionTestUtils.invokeMethod(
                    accountingService, "remainingCreditableAmount", invoice, invoiceSource, company))
        .isEqualByComparingTo("10.00");
    assertThat(
            (BigDecimal)
                ReflectionTestUtils.invokeMethod(
                    accountingService, "remainingCreditableAmount", null, invoiceSource, company))
        .isEqualByComparingTo("0.00");
    assertThat(
            (BigDecimal)
                ReflectionTestUtils.invokeMethod(
                    accountingService, "remainingDebitableAmount", purchase, purchaseSource, company))
        .isEqualByComparingTo("30.00");
    assertThat(
            (BigDecimal)
                ReflectionTestUtils.invokeMethod(
                    accountingService, "remainingDebitableAmount", purchase, null, company))
        .isEqualByComparingTo("0.00");

    @SuppressWarnings("unchecked")
    Map<String, String> auditMetadata =
        (Map<String, String>)
            ReflectionTestUtils.invokeMethod(
                accountingService,
                "buildSettlementAuditSuccessMetadata",
                com.bigbrightpaints.erp.modules.accounting.domain.PartnerType.SUPPLIER,
                993L,
                stubEntry(9936L),
                LocalDate.of(2024, 7, 4),
                "  Secret-Key  ",
                2,
                new BigDecimal("50.00"),
                new BigDecimal("45.00"),
                new BigDecimal("3.00"),
                new BigDecimal("2.00"),
                BigDecimal.ZERO,
                new BigDecimal("1.00"),
                true,
                "  override reason  ",
                "  approver  ");

    assertThat(auditMetadata)
        .containsEntry(IntegrationFailureMetadataSchema.KEY_PARTNER_TYPE, "SUPPLIER")
        .containsEntry(IntegrationFailureMetadataSchema.KEY_PARTNER_ID, "993")
        .containsEntry(IntegrationFailureMetadataSchema.KEY_SETTLEMENT_DATE, "2024-07-04")
        .containsEntry("settlementOverrideRequested", "true")
        .containsEntry("settlementOverrideReason", "override reason")
        .containsEntry("settlementOverrideActor", "approver");
    assertThat(auditMetadata.get(IntegrationFailureMetadataSchema.KEY_IDEMPOTENCY_KEY))
        .isEqualTo(com.bigbrightpaints.erp.core.idempotency.IdempotencyUtils.sha256Hex("Secret-Key", 12));

    @SuppressWarnings("unchecked")
    Map<String, String> blankMetadata =
        (Map<String, String>)
            ReflectionTestUtils.invokeMethod(
                accountingService,
                "buildSettlementAuditSuccessMetadata",
                com.bigbrightpaints.erp.modules.accounting.domain.PartnerType.DEALER,
                null,
                null,
                null,
                "   ",
                0,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                false,
                "   ",
                "   ");
    assertThat(blankMetadata)
        .doesNotContainKeys(
            IntegrationFailureMetadataSchema.KEY_IDEMPOTENCY_KEY,
            "settlementOverrideReason",
            "settlementOverrideActor");
  }

  @Test
  @Tag("critical")
  void helperMethods_coverJournalListSpecifications() {
    @SuppressWarnings("unchecked")
    Specification<JournalEntry> noRangeSpec =
        (Specification<JournalEntry>)
            ReflectionTestUtils.invokeMethod(
                accountingService, "byJournalEntryDateRange", null, null);
    @SuppressWarnings("unchecked")
    Specification<JournalEntry> betweenSpec =
        (Specification<JournalEntry>)
            ReflectionTestUtils.invokeMethod(
                accountingService,
                "byJournalEntryDateRange",
                LocalDate.of(2024, 7, 1),
                LocalDate.of(2024, 7, 31));
    @SuppressWarnings("unchecked")
    Specification<JournalEntry> fromOnlySpec =
        (Specification<JournalEntry>)
            ReflectionTestUtils.invokeMethod(
                accountingService, "byJournalEntryDateRange", LocalDate.of(2024, 7, 1), null);
    @SuppressWarnings("unchecked")
    Specification<JournalEntry> toOnlySpec =
        (Specification<JournalEntry>)
            ReflectionTestUtils.invokeMethod(
                accountingService, "byJournalEntryDateRange", null, LocalDate.of(2024, 7, 31));
    @SuppressWarnings("unchecked")
    Specification<JournalEntry> journalTypeSpec =
        (Specification<JournalEntry>)
            ReflectionTestUtils.invokeMethod(
                accountingService, "byJournalType", JournalEntryType.MANUAL);
    @SuppressWarnings("unchecked")
    Specification<JournalEntry> noJournalTypeSpec =
        (Specification<JournalEntry>)
            ReflectionTestUtils.invokeMethod(accountingService, "byJournalType", new Object[] {null});
    @SuppressWarnings("unchecked")
    Specification<JournalEntry> sourceModuleSpec =
        (Specification<JournalEntry>)
            ReflectionTestUtils.invokeMethod(accountingService, "byJournalSourceModule", "sales");
    @SuppressWarnings("unchecked")
    Specification<JournalEntry> noSourceModuleSpec =
        (Specification<JournalEntry>)
            ReflectionTestUtils.invokeMethod(
                accountingService, "byJournalSourceModule", new Object[] {null});

    Root<JournalEntry> root = mock(Root.class);
    CriteriaQuery<?> query = mock(CriteriaQuery.class);
    CriteriaBuilder cb = mock(CriteriaBuilder.class);
    @SuppressWarnings("unchecked")
    Path<LocalDate> entryDatePath = mock(Path.class);
    @SuppressWarnings("unchecked")
    Path<JournalEntryType> journalTypePath = mock(Path.class);
    @SuppressWarnings("unchecked")
    Path<String> sourceModulePath = mock(Path.class);
    @SuppressWarnings("unchecked")
    Expression<String> loweredSourceModule = mock(Expression.class);
    Predicate conjunction = mock(Predicate.class);
    Predicate betweenPredicate = mock(Predicate.class);
    Predicate fromPredicate = mock(Predicate.class);
    Predicate toPredicate = mock(Predicate.class);
    Predicate journalTypePredicate = mock(Predicate.class);
    Predicate sourceModulePredicate = mock(Predicate.class);

    when(root.get("entryDate")).thenReturn((Path) entryDatePath);
    when(root.get("journalType")).thenReturn((Path) journalTypePath);
    when(root.get("sourceModule")).thenReturn((Path) sourceModulePath);
    when(cb.conjunction()).thenReturn(conjunction);
    when(cb.between(entryDatePath, LocalDate.of(2024, 7, 1), LocalDate.of(2024, 7, 31)))
        .thenReturn(betweenPredicate);
    when(cb.greaterThanOrEqualTo(entryDatePath, LocalDate.of(2024, 7, 1))).thenReturn(fromPredicate);
    when(cb.lessThanOrEqualTo(entryDatePath, LocalDate.of(2024, 7, 31))).thenReturn(toPredicate);
    when(cb.equal(journalTypePath, JournalEntryType.MANUAL)).thenReturn(journalTypePredicate);
    when(cb.lower(sourceModulePath)).thenReturn(loweredSourceModule);
    when(cb.equal(loweredSourceModule, "sales")).thenReturn(sourceModulePredicate);

    assertThat(noRangeSpec.toPredicate(root, query, cb)).isSameAs(conjunction);
    assertThat(betweenSpec.toPredicate(root, query, cb)).isSameAs(betweenPredicate);
    assertThat(fromOnlySpec.toPredicate(root, query, cb)).isSameAs(fromPredicate);
    assertThat(toOnlySpec.toPredicate(root, query, cb)).isSameAs(toPredicate);
    assertThat(journalTypeSpec.toPredicate(root, query, cb)).isSameAs(journalTypePredicate);
    assertThat(noJournalTypeSpec.toPredicate(root, query, cb)).isSameAs(conjunction);
    assertThat(sourceModuleSpec.toPredicate(root, query, cb)).isSameAs(sourceModulePredicate);
    assertThat(noSourceModuleSpec.toPredicate(root, query, cb)).isSameAs(conjunction);
  }

  @Test
  @Tag("critical")
  void helperMethods_rejectApplyingNotesBeyondOutstandingBalances() {
    Account receivable = account(9951L, "AR-9951", AccountType.ASSET);
    Dealer dealer = dealer(995L, "Dealer Overflow", receivable);
    Invoice invoice = invoice(9952L, dealer, "INV-9952", new BigDecimal("20.00"));
    invoice.setTotalAmount(new BigDecimal("100.00"));
    LocalDate entryDate = LocalDate.of(2024, 8, 1);

    assertThatThrownBy(
            () ->
                ReflectionTestUtils.invokeMethod(
                    accountingService,
                    "applyCreditNoteToInvoice",
                    invoice,
                    new BigDecimal("25.00"),
                    BigDecimal.ZERO,
                    "CN-9952",
                    entryDate))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("exceeds remaining invoice outstanding amount");

    ReflectionTestUtils.invokeMethod(
        accountingService,
        "applyCreditNoteToInvoice",
        invoice,
        new BigDecimal("5.00"),
        new BigDecimal("5.00"),
        "CN-9952-OK",
        entryDate);

    assertThat(invoice.getOutstandingAmount()).isEqualByComparingTo("15.00");
    assertThat(invoice.getPaymentReferences()).contains("CN-9952-OK");
    verify(invoiceSettlementPolicy)
        .updateStatusFromOutstanding(invoice, new BigDecimal("15.00"));
    verify(dealerLedgerService).syncInvoiceLedger(invoice, entryDate);

    Account payable = account(9953L, "AP-9953", AccountType.LIABILITY);
    Supplier supplier = supplier(996L, "Supplier Overflow", payable);
    RawMaterialPurchase purchase =
        purchase(
            9954L,
            "PUR-9954",
            supplier,
            new BigDecimal("100.00"),
            new BigDecimal("20.00"),
            "PARTIAL");

    assertThatThrownBy(
            () ->
                ReflectionTestUtils.invokeMethod(
                    accountingService,
                    "applyDebitNoteToPurchase",
                    purchase,
                    new BigDecimal("25.00"),
                    BigDecimal.ZERO))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("exceeds remaining purchase outstanding amount");
  }

  @Test
  @Tag("critical")
  void validateDebitNoteReplay_coversConflictBranchesAndMatchingPayload() {
    Account payable = account(9941L, "AP-9941", AccountType.LIABILITY);
    Account inventory = account(9942L, "RM-9942", AccountType.ASSET);
    Supplier supplier = supplier(994L, "Supplier Validate", payable);
    Supplier otherSupplier = supplier(995L, "Supplier Other", payable);
    RawMaterialPurchase purchase =
        purchase(
            9943L,
            "PUR-9943",
            supplier,
            new BigDecimal("100.00"),
            new BigDecimal("100.00"),
            "POSTED");
    JournalEntry source = journalEntry(9944L, "PUR-9943-JE");
    addJournalLine(source, inventory, "Inventory received", new BigDecimal("100.00"), BigDecimal.ZERO);
    addJournalLine(source, payable, "Supplier payable", BigDecimal.ZERO, new BigDecimal("100.00"));

    assertThatThrownBy(
            () ->
                ReflectionTestUtils.invokeMethod(
                    accountingService,
                    "validateDebitNoteReplay",
                    "DN-9944",
                    purchase,
                    source,
                    null,
                    new BigDecimal("40.00")))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("journal entry is missing");

    JournalEntry wrongSupplier = journalEntry(9945L, "DN-9945");
    wrongSupplier.setSupplier(otherSupplier);
    assertThatThrownBy(
            () ->
                ReflectionTestUtils.invokeMethod(
                    accountingService,
                    "validateDebitNoteReplay",
                    "DN-9945",
                    purchase,
                    source,
                    wrongSupplier,
                    new BigDecimal("40.00")))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("another supplier");

    JournalEntry wrongCorrectionFlow = journalEntry(99455L, "DN-99455");
    wrongCorrectionFlow.setSupplier(supplier);
    wrongCorrectionFlow.setCorrectionReason("REVERSAL");
    wrongCorrectionFlow.setSourceReference("PUR-9943");
    assertThatThrownBy(
            () ->
                ReflectionTestUtils.invokeMethod(
                    accountingService,
                    "validateDebitNoteReplay",
                    "DN-99455",
                    purchase,
                    source,
                    wrongCorrectionFlow,
                    new BigDecimal("40.00")))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("different correction flow");

    JournalEntry wrongSourceModule = journalEntry(9946L, "DN-9946");
    wrongSourceModule.setSupplier(supplier);
    wrongSourceModule.setCorrectionReason("DEBIT_NOTE");
    wrongSourceModule.setSourceModule("CREDIT_NOTE");
    wrongSourceModule.setSourceReference("PUR-9943");
    assertThatThrownBy(
            () ->
                ReflectionTestUtils.invokeMethod(
                    accountingService,
                    "validateDebitNoteReplay",
                    "DN-9946",
                    purchase,
                    source,
                    wrongSourceModule,
                    new BigDecimal("40.00")))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("different source module");

    JournalEntry wrongSourceReference = journalEntry(9947L, "DN-9947");
    wrongSourceReference.setSupplier(supplier);
    wrongSourceReference.setCorrectionReason("DEBIT_NOTE");
    wrongSourceReference.setSourceModule("DEBIT_NOTE");
    wrongSourceReference.setSourceReference("OTHER-PURCHASE");
    assertThatThrownBy(
            () ->
                ReflectionTestUtils.invokeMethod(
                    accountingService,
                    "validateDebitNoteReplay",
                    "DN-9947",
                    purchase,
                    source,
                    wrongSourceReference,
                    new BigDecimal("40.00")))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("another purchase");

    JournalEntry wrongAmount = journalEntry(9948L, "DN-9948");
    wrongAmount.setSupplier(supplier);
    wrongAmount.setCorrectionReason("DEBIT_NOTE");
    wrongAmount.setSourceModule("DEBIT_NOTE");
    wrongAmount.setSourceReference("PUR-9943");
    addJournalLine(
        wrongAmount, payable, "Debit note reversal - Supplier payable", BigDecimal.ZERO, new BigDecimal("50.00"));
    addJournalLine(
        wrongAmount,
        inventory,
        "Debit note reversal - Inventory received",
        new BigDecimal("50.00"),
        BigDecimal.ZERO);
    assertThatThrownBy(
            () ->
                ReflectionTestUtils.invokeMethod(
                    accountingService,
                    "validateDebitNoteReplay",
                    "DN-9948",
                    purchase,
                    source,
                    wrongAmount,
                    new BigDecimal("40.00")))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("different amount");

    JournalEntry wrongPayload = journalEntry(9949L, "DN-9949");
    wrongPayload.setSupplier(supplier);
    wrongPayload.setCorrectionReason("DEBIT_NOTE");
    wrongPayload.setSourceModule("DEBIT_NOTE");
    wrongPayload.setSourceReference("PUR-9943");
    addJournalLine(
        wrongPayload, payable, "Debit note reversal - Supplier payable", BigDecimal.ZERO, new BigDecimal("40.00"));
    addJournalLine(
        wrongPayload,
        account(9950L, "MISC-9950", AccountType.EXPENSE),
        "Debit note reversal - Different",
        new BigDecimal("40.00"),
        BigDecimal.ZERO);
    assertThatThrownBy(
            () ->
                ReflectionTestUtils.invokeMethod(
                    accountingService,
                    "validateDebitNoteReplay",
                    "DN-9949",
                    purchase,
                    source,
                    wrongPayload,
                    new BigDecimal("40.00")))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("different payload");

    JournalEntry matching = journalEntry(9951L, "DN-9951");
    matching.setSupplier(supplier);
    matching.setCorrectionReason("DEBIT_NOTE");
    matching.setSourceModule("DEBIT_NOTE");
    matching.setSourceReference("PUR-9943");
    addJournalLine(
        matching, payable, "Debit note reversal - Supplier payable", new BigDecimal("40.00"), BigDecimal.ZERO);
    addJournalLine(
        matching,
        inventory,
        "Debit note reversal - Inventory received",
        BigDecimal.ZERO,
        new BigDecimal("40.00"));

    ReflectionTestUtils.invokeMethod(
        accountingService,
        "validateDebitNoteReplay",
        "DN-9951",
        purchase,
        source,
        matching,
        new BigDecimal("40.00"));
  }

  @Test
  void validateDebitNoteReplay_rejectsReferenceMismatchAndSkipsPayloadCheckWithoutPurchase() {
    Account payable = account(9952L, "AP-9952", AccountType.LIABILITY);
    Account inventory = account(9953L, "RM-9953", AccountType.ASSET);
    Supplier supplier = supplier(996L, "Supplier Ref Mismatch", payable);
    RawMaterialPurchase purchase =
        purchase(
            9954L,
            "PUR-9954",
            supplier,
            new BigDecimal("100.00"),
            new BigDecimal("100.00"),
            "POSTED");
    JournalEntry source = journalEntry(9955L, "PUR-9954-JE");
    addJournalLine(
        source, inventory, "Inventory received", new BigDecimal("100.00"), BigDecimal.ZERO);
    addJournalLine(source, payable, "Supplier payable", BigDecimal.ZERO, new BigDecimal("100.00"));

    JournalEntry mismatchedReference = journalEntry(9956L, "DN-OTHER");
    mismatchedReference.setSupplier(supplier);
    mismatchedReference.setReversalOf(source);
    mismatchedReference.setSourceReference("PUR-9954");
    addJournalLine(
        mismatchedReference,
        payable,
        "Debit note reversal - Supplier payable",
        new BigDecimal("40.00"),
        BigDecimal.ZERO);
    addJournalLine(
        mismatchedReference,
        inventory,
        "Debit note reversal - Inventory received",
        BigDecimal.ZERO,
        new BigDecimal("40.00"));

    assertThatThrownBy(
            () ->
                ReflectionTestUtils.invokeMethod(
                    accountingService,
                    "validateDebitNoteReplay",
                    "DN-EXPECTED",
                    purchase,
                    source,
                    mismatchedReference,
                    new BigDecimal("40.00")))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("different reference");

    JournalEntry noPurchaseEntry = journalEntry(9957L, "DN-NO-PURCHASE");
    ReflectionTestUtils.invokeMethod(
        accountingService,
        "validateDebitNoteReplay",
        null,
        null,
        source,
        noPurchaseEntry,
        null);
  }

  @Test
  void settlementSuccessAuditMetadata_hashesIdempotencyKeyBeforeStorage() {
    JournalEntryDto settlementJournal =
        new JournalEntryDto(
            915L,
            null,
            "SET-915",
            LocalDate.of(2024, 7, 4),
            null,
            "POSTED",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            List.of(),
            null,
            null,
            null,
            null,
            null,
            null);

    @SuppressWarnings("unchecked")
    Map<String, String> metadata =
        ReflectionTestUtils.invokeMethod(
            accountingService,
            "buildSettlementAuditSuccessMetadata",
            com.bigbrightpaints.erp.modules.accounting.domain.PartnerType.DEALER,
            81L,
            settlementJournal,
            LocalDate.of(2024, 7, 4),
            "  settle-secret-key  ",
            2,
            new BigDecimal("120.00"),
            new BigDecimal("115.00"),
            new BigDecimal("3.00"),
            new BigDecimal("2.00"),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            true,
            "  approved override  ",
            "  policy.admin  ");

    assertThat(metadata)
        .containsEntry(
            IntegrationFailureMetadataSchema.KEY_PARTNER_TYPE,
            com.bigbrightpaints.erp.modules.accounting.domain.PartnerType.DEALER.name())
        .containsEntry(IntegrationFailureMetadataSchema.KEY_PARTNER_ID, "81")
        .containsEntry(IntegrationFailureMetadataSchema.KEY_JOURNAL_ENTRY_ID, "915")
        .containsEntry(IntegrationFailureMetadataSchema.KEY_SETTLEMENT_DATE, "2024-07-04")
        .containsEntry(
            IntegrationFailureMetadataSchema.KEY_IDEMPOTENCY_KEY,
            com.bigbrightpaints.erp.core.idempotency.IdempotencyUtils.sha256Hex(
                "settle-secret-key", 12))
        .containsEntry("settlementOverrideReason", "approved override")
        .containsEntry("settlementOverrideActor", "policy.admin");
    assertThat(metadata.values()).doesNotContain("  settle-secret-key  ", "settle-secret-key");
  }

  @Test
  void helperMethods_coverReserveReferenceMappingDecisionBranches() {
    assertThatThrownBy(
            () ->
                ReflectionTestUtils.invokeMethod(
                    accountingService,
                    "reserveReferenceMapping",
                    null,
                    "legacy-key",
                    "REF-1",
                    "TYPE"))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("required to reserve journal mapping");

    JournalReferenceMapping existingSame = new JournalReferenceMapping();
    existingSame.setCanonicalReference("REF-EXISTING");
    when(journalReferenceMappingRepository.findAllByCompanyAndLegacyReferenceIgnoreCase(
            company, "existing-key"))
        .thenReturn(List.of(existingSame));

    Object sameReservation =
        ReflectionTestUtils.invokeMethod(
            accountingService,
            "reserveReferenceMapping",
            company,
            "existing-key",
            "  REF-EXISTING  ",
            "TYPE");
    assertThat((Boolean) ReflectionTestUtils.invokeMethod(sameReservation, "leader")).isFalse();
    assertThat((String) ReflectionTestUtils.invokeMethod(sameReservation, "canonicalReference"))
        .isEqualTo("REF-EXISTING");

    assertThatThrownBy(
            () ->
                ReflectionTestUtils.invokeMethod(
                    accountingService,
                    "reserveReferenceMapping",
                    company,
                    "existing-key",
                    "OTHER-REF",
                    "TYPE"))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("already used for another reference");

    when(journalReferenceMappingRepository.findAllByCompanyAndLegacyReferenceIgnoreCase(
            company, "new-key"))
        .thenReturn(List.of());
    when(journalReferenceMappingRepository.reserveReferenceMapping(
            eq(company.getId()), eq("new-key"), eq("REF-NEW"), eq("TYPE"), any()))
        .thenReturn(1);

    Object leaderReservation =
        ReflectionTestUtils.invokeMethod(
            accountingService, "reserveReferenceMapping", company, "new-key", "REF-NEW", "TYPE");
    assertThat((Boolean) ReflectionTestUtils.invokeMethod(leaderReservation, "leader")).isTrue();
    assertThat((String) ReflectionTestUtils.invokeMethod(leaderReservation, "canonicalReference"))
        .isEqualTo("REF-NEW");

    JournalReferenceMapping racedSame = new JournalReferenceMapping();
    racedSame.setCanonicalReference("REF-RACE");
    when(journalReferenceMappingRepository.findAllByCompanyAndLegacyReferenceIgnoreCase(
            company, "race-key"))
        .thenReturn(List.of(), List.of(racedSame));
    when(journalReferenceMappingRepository.reserveReferenceMapping(
            eq(company.getId()), eq("race-key"), eq("REF-RACE"), eq("TYPE"), any()))
        .thenReturn(0);

    Object racedReservation =
        ReflectionTestUtils.invokeMethod(
            accountingService, "reserveReferenceMapping", company, "race-key", "REF-RACE", "TYPE");
    assertThat((Boolean) ReflectionTestUtils.invokeMethod(racedReservation, "leader")).isFalse();
    assertThat((String) ReflectionTestUtils.invokeMethod(racedReservation, "canonicalReference"))
        .isEqualTo("REF-RACE");

    when(journalReferenceMappingRepository.findAllByCompanyAndLegacyReferenceIgnoreCase(
            company, "missing-after-reserve"))
        .thenReturn(List.of(), List.of());
    when(journalReferenceMappingRepository.reserveReferenceMapping(
            eq(company.getId()), eq("missing-after-reserve"), eq("REF-MISSING"), eq("TYPE"), any()))
        .thenReturn(0);

    assertThatThrownBy(
            () ->
                ReflectionTestUtils.invokeMethod(
                    accountingService,
                    "reserveReferenceMapping",
                    company,
                    "missing-after-reserve",
                    "REF-MISSING",
                    "TYPE"))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("already reserved but mapping not found");

    JournalReferenceMapping racedConflict = new JournalReferenceMapping();
    racedConflict.setCanonicalReference("REF-OTHER");
    when(journalReferenceMappingRepository.findAllByCompanyAndLegacyReferenceIgnoreCase(
            company, "conflict-after-reserve"))
        .thenReturn(List.of(), List.of(racedConflict));
    when(journalReferenceMappingRepository.reserveReferenceMapping(
            eq(company.getId()),
            eq("conflict-after-reserve"),
            eq("REF-CONFLICT"),
            eq("TYPE"),
            any()))
        .thenReturn(0);

    assertThatThrownBy(
            () ->
                ReflectionTestUtils.invokeMethod(
                    accountingService,
                    "reserveReferenceMapping",
                    company,
                    "conflict-after-reserve",
                    "REF-CONFLICT",
                    "TYPE"))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("already used for another reference");
  }

  @Test
  void dealerReceiptService_routesLiveReceiptFlowThroughJournalEntryService() {
    Account cash = account(20L, "CASH-20", AccountType.ASSET);
    cash.setActive(true);
    when(companyEntityLookup.requireAccount(eq(company), eq(20L))).thenReturn(cash);
    when(accountRepository.updateBalanceAtomic(any(), any(), any())).thenReturn(1);
    when(journalEntryRepository.save(any(JournalEntry.class)))
        .thenAnswer(
            invocation -> {
              JournalEntry entry = invocation.getArgument(0);
              if (entry.getId() == null) {
                ReflectionTestUtils.setField(entry, "id", 9701L);
              }
              if (entry.getEntryDate() == null) {
                entry.setEntryDate(LocalDate.of(2024, 4, 15));
              }
              return entry;
            });
    doReturn(stubEntry(9701L))
        .when(journalEntryService)
        .createJournalEntry(any(JournalEntryRequest.class));
    JournalEntry posted = journalEntry(9701L, "DR-ROUTE-1");
    posted.setEntryDate(LocalDate.of(2024, 4, 15));
    when(companyEntityLookup.requireJournalEntry(company, 9701L)).thenReturn(posted);
    when(settlementAllocationRepository.findByCompanyAndJournalEntryOrderByCreatedAtAsc(
            company, posted))
        .thenReturn(List.of());
    Dealer routedDealer = dealer(1L, "Dealer 1", account(10001L, "AR-1", AccountType.ASSET));
    Invoice invoice = invoice(9702L, routedDealer, "INV-9702", new BigDecimal("100.00"));
    when(invoiceRepository.lockByCompanyAndId(company, 9702L)).thenReturn(Optional.of(invoice));

    dealerReceiptService.recordDealerReceipt(
        new DealerReceiptRequest(
            1L,
            20L,
            new BigDecimal("100.00"),
            "DR-ROUTE-1",
            "Dealer receipt routing",
            "IDEMP-DR-ROUTE-1",
            List.of(
                new SettlementAllocationRequest(
                    9702L,
                    null,
                    new BigDecimal("100.00"),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    null))));

    ArgumentCaptor<JournalEntryRequest> payloadCaptor =
        ArgumentCaptor.forClass(JournalEntryRequest.class);
    verify(journalEntryService).createJournalEntry(payloadCaptor.capture());
    assertThat(payloadCaptor.getValue().referenceNumber()).isEqualTo("DR-ROUTE-1");
    assertThat(payloadCaptor.getValue().dealerId()).isEqualTo(1L);
    assertThat(payloadCaptor.getValue().lines()).hasSize(2);
  }

  @Test
  void creditDebitNoteService_routesLiveCreditNoteFlowThroughJournalEntryService() {
    when(accountRepository.updateBalanceAtomic(any(), any(), any())).thenReturn(1);
    when(journalEntryRepository.save(any(JournalEntry.class)))
        .thenAnswer(
            invocation -> {
              JournalEntry entry = invocation.getArgument(0);
              if (entry.getId() == null) {
                ReflectionTestUtils.setField(entry, "id", 9801L);
              }
              if (entry.getEntryDate() == null) {
                entry.setEntryDate(LocalDate.of(2024, 6, 1));
              }
              return entry;
            });

    Account receivable = account(98011L, "AR-98011", AccountType.ASSET);
    Account revenue = account(98012L, "REV-98012", AccountType.REVENUE);
    Dealer dealer = dealer(98013L, "Dealer Route", receivable);
    Invoice invoice = invoice(98014L, dealer, "INV-98014", new BigDecimal("100.00"));
    JournalEntry source = journalEntry(98015L, "INV-98014-JE");
    addJournalLine(
        source, receivable, "Invoice receivable", new BigDecimal("100.00"), BigDecimal.ZERO);
    addJournalLine(source, revenue, "Invoice revenue", BigDecimal.ZERO, new BigDecimal("100.00"));
    invoice.setJournalEntry(source);

    when(invoiceRepository.lockByCompanyAndId(company, 98014L)).thenReturn(Optional.of(invoice));
    when(journalEntryRepository.findByCompanyAndReversalOfAndCorrectionReasonIgnoreCase(
            company, source, "CREDIT_NOTE"))
        .thenReturn(List.of());
    doReturn(stubEntry(9801L))
        .when(journalEntryService)
        .createJournalEntry(any(JournalEntryRequest.class));
    JournalEntry posted =
        creditNoteEntry(9801L, "CN-ROUTE-1", dealer, source, receivable, revenue, "100.00");
    posted.setEntryDate(LocalDate.of(2024, 6, 1));
    when(companyEntityLookup.requireJournalEntry(company, 9801L)).thenReturn(posted);

    creditDebitNoteService.postCreditNote(
        new CreditNoteRequest(
            98014L,
            new BigDecimal("100.00"),
            LocalDate.of(2024, 6, 1),
            "CN-ROUTE-1",
            "Credit note routing",
            "IDEMP-CN-ROUTE-1",
            Boolean.TRUE));

    ArgumentCaptor<JournalEntryRequest> payloadCaptor =
        ArgumentCaptor.forClass(JournalEntryRequest.class);
    verify(journalEntryService).createJournalEntry(payloadCaptor.capture());
    assertThat(payloadCaptor.getValue().referenceNumber()).isEqualTo("CN-ROUTE-1");
    assertThat(payloadCaptor.getValue().dealerId()).isEqualTo(98013L);
    assertThat(payloadCaptor.getValue().lines()).hasSize(2);
  }

  @Test
  void inventoryAccountingService_routesLiveLandedCostFlowThroughJournalEntryService() {
    when(accountRepository.updateBalanceAtomic(any(), any(), any())).thenReturn(1);
    when(journalEntryRepository.save(any(JournalEntry.class)))
        .thenAnswer(
            invocation -> {
              JournalEntry entry = invocation.getArgument(0);
              if (entry.getId() == null) {
                ReflectionTestUtils.setField(entry, "id", 9901L);
              }
              if (entry.getEntryDate() == null) {
                entry.setEntryDate(LocalDate.of(2024, 7, 1));
              }
              return entry;
            });

    Account payable = account(99011L, "AP-99011", AccountType.LIABILITY);
    Supplier supplier = supplier(99012L, "Supplier Route", payable);
    RawMaterialPurchase purchase =
        purchase(
            99013L,
            "PUR-99013",
            supplier,
            new BigDecimal("250.00"),
            new BigDecimal("250.00"),
            "POSTED");
    Account inventory = account(99014L, "INV-99014", AccountType.ASSET);
    Account offset = account(99015L, "OFFSET-99015", AccountType.EXPENSE);

    when(companyEntityLookup.requireRawMaterialPurchase(company, 99013L)).thenReturn(purchase);
    when(companyEntityLookup.requireAccount(company, 99014L)).thenReturn(inventory);
    when(companyEntityLookup.requireAccount(company, 99015L)).thenReturn(offset);
    doReturn(stubEntry(9901L))
        .when(journalEntryService)
        .createJournalEntry(any(JournalEntryRequest.class));

    inventoryAccountingService.recordLandedCost(
        new LandedCostRequest(
            99013L,
            new BigDecimal("25.00"),
            99014L,
            99015L,
            LocalDate.of(2024, 7, 1),
            "Landed cost routing",
            "LC-ROUTE-1",
            "IDEMP-LC-ROUTE-1",
            Boolean.TRUE));

    ArgumentCaptor<JournalEntryRequest> payloadCaptor =
        ArgumentCaptor.forClass(JournalEntryRequest.class);
    verify(journalEntryService).createJournalEntry(payloadCaptor.capture());
    assertThat(payloadCaptor.getValue().referenceNumber()).isEqualTo("IDEMP-LC-ROUTE-1");
    assertThat(payloadCaptor.getValue().lines()).hasSize(2);
  }

  private Account account(Long id, String code, AccountType type) {
    Account account = new Account();
    ReflectionTestUtils.setField(account, "id", id);
    account.setCode(code);
    account.setName(code);
    account.setType(type);
    account.setCompany(company);
    return account;
  }

  private Dealer dealer(Long id, String name, Account receivableAccount) {
    Dealer dealer = new Dealer();
    ReflectionTestUtils.setField(dealer, "id", id);
    dealer.setCompany(company);
    dealer.setName(name);
    dealer.setReceivableAccount(receivableAccount);
    return dealer;
  }

  private Supplier supplier(Long id, String name, Account payableAccount) {
    Supplier supplier = new Supplier();
    ReflectionTestUtils.setField(supplier, "id", id);
    supplier.setCompany(company);
    supplier.setName(name);
    supplier.setPayableAccount(payableAccount);
    return supplier;
  }

  private RawMaterialPurchase purchase(
      Long id,
      String invoiceNumber,
      Supplier supplier,
      BigDecimal totalAmount,
      BigDecimal outstandingAmount,
      String status) {
    RawMaterialPurchase purchase = new RawMaterialPurchase();
    ReflectionTestUtils.setField(purchase, "id", id);
    purchase.setCompany(company);
    purchase.setSupplier(supplier);
    purchase.setInvoiceNumber(invoiceNumber);
    purchase.setInvoiceDate(LocalDate.of(2024, 6, 1));
    purchase.setTotalAmount(totalAmount);
    purchase.setOutstandingAmount(outstandingAmount);
    purchase.setStatus(status);
    return purchase;
  }

  private Invoice invoice(
      Long id, Dealer dealer, String invoiceNumber, BigDecimal outstandingAmount) {
    Invoice invoice = new Invoice();
    ReflectionTestUtils.setField(invoice, "id", id);
    invoice.setCompany(company);
    invoice.setDealer(dealer);
    invoice.setInvoiceNumber(invoiceNumber);
    invoice.setIssueDate(LocalDate.of(2024, 6, 1));
    invoice.setTotalAmount(outstandingAmount);
    invoice.setOutstandingAmount(outstandingAmount);
    return invoice;
  }

  private JournalEntry journalEntry(Long id, String referenceNumber) {
    JournalEntry entry = new JournalEntry();
    ReflectionTestUtils.setField(entry, "id", id);
    entry.setCompany(company);
    entry.setReferenceNumber(referenceNumber);
    return entry;
  }

  private JournalEntry creditNoteEntry(
      Long id,
      String referenceNumber,
      Dealer dealer,
      JournalEntry reversalOf,
      Account receivable,
      Account offset,
      String amount) {
    JournalEntry entry = journalEntry(id, referenceNumber);
    entry.setDealer(dealer);
    entry.setReversalOf(reversalOf);
    addJournalLine(
        entry,
        receivable,
        "Credit note reversal - Invoice receivable",
        BigDecimal.ZERO,
        new BigDecimal(amount));
    addJournalLine(
        entry,
        offset,
        "Credit note reversal - Invoice revenue",
        new BigDecimal(amount),
        BigDecimal.ZERO);
    return entry;
  }

  private void addJournalLine(
      JournalEntry entry,
      Account account,
      String description,
      BigDecimal debit,
      BigDecimal credit) {
    entry.addLine(journalLine(entry, account, description, debit, credit));
  }

  private void setCreateJournalEntryEventTrailStrictness(boolean strict) {
    ReflectionTestUtils.setField(accountingService, "strictAccountingEventTrail", strict);
    ReflectionTestUtils.setField(journalEntryService, "strictAccountingEventTrail", strict);
  }

  private AccountingPeriod openPeriod(LocalDate date) {
    AccountingPeriod period = new AccountingPeriod();
    period.setYear(date.getYear());
    period.setMonth(date.getMonthValue());
    period.setStatus(AccountingPeriodStatus.OPEN);
    return period;
  }

  private JournalLine journalLine(
      JournalEntry entry,
      Account account,
      String description,
      BigDecimal debit,
      BigDecimal credit) {
    JournalLine line = new JournalLine();
    line.setJournalEntry(entry);
    line.setAccount(account);
    line.setDescription(description);
    line.setDebit(debit);
    line.setCredit(credit);
    return line;
  }

  private JournalEntryDto stubEntry(long id) {
    return new JournalEntryDto(
        id,
        null,
        null,
        LocalDate.now(),
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        List.of(),
        null,
        null,
        null,
        null,
        null,
        null);
  }

  private JournalEntryDto journalEntryDto(long id, String referenceNumber) {
    return new JournalEntryDto(
        id,
        null,
        referenceNumber,
        LocalDate.now(),
        null,
        "POSTED",
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        List.of(),
        null,
        null,
        null,
        null,
        null,
        null);
  }

  private JournalEntry reversalSourceEntry(Long id, String reference, LocalDate entryDate) {
    JournalEntry entry = new JournalEntry();
    ReflectionTestUtils.setField(entry, "id", id);
    entry.setStatus("POSTED");
    entry.setReferenceNumber(reference);
    entry.setEntryDate(entryDate);

    Account account = new Account();
    ReflectionTestUtils.setField(account, "id", 700L);
    account.setCode("CASH");
    account.setName("Cash");
    account.setType(AccountType.ASSET);
    account.setCompany(company);

    JournalLine line = new JournalLine();
    line.setJournalEntry(entry);
    line.setAccount(account);
    line.setDescription("line");
    line.setDebit(new BigDecimal("10.00"));
    line.setCredit(BigDecimal.ZERO);
    entry.addLine(line);
    return entry;
  }
}
