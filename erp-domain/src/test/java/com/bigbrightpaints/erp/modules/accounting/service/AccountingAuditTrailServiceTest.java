package com.bigbrightpaints.erp.modules.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLine;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLineRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation;
import com.bigbrightpaints.erp.modules.accounting.domain.PartnerType;
import com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocationRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.PartnerType;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountingTransactionAuditDetailDto;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountingTransactionAuditListItemDto;
import com.bigbrightpaints.erp.modules.accounting.dto.SettlementAllocationApplication;
import com.bigbrightpaints.erp.modules.accounting.event.AccountingEvent;
import com.bigbrightpaints.erp.modules.accounting.event.AccountingEventRepository;
import com.bigbrightpaints.erp.modules.accounting.event.AccountingEventType;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlip;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlipRepository;
import com.bigbrightpaints.erp.modules.invoice.domain.Invoice;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchase;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchaseRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.GoodsReceipt;
import com.bigbrightpaints.erp.modules.purchasing.domain.PurchaseOrder;
import com.bigbrightpaints.erp.modules.purchasing.domain.Supplier;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrder;
import com.bigbrightpaints.erp.shared.dto.LinkedBusinessReferenceDto;
import com.bigbrightpaints.erp.shared.dto.PageResponse;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@Tag("critical")
class AccountingAuditTrailServiceTest {

    @Mock
    private CompanyContextService companyContextService;
    @Mock
    private JournalEntryRepository journalEntryRepository;
    @Mock
    private JournalLineRepository journalLineRepository;
    @Mock
    private AccountingEventRepository accountingEventRepository;
    @Mock
    private PartnerSettlementAllocationRepository settlementAllocationRepository;
    @Mock
    private InvoiceRepository invoiceRepository;
    @Mock
    private RawMaterialPurchaseRepository rawMaterialPurchaseRepository;
    @Mock
    private PackagingSlipRepository packagingSlipRepository;

    private AccountingAuditTrailService service;

    @BeforeEach
    void setUp() {
        service = new AccountingAuditTrailService(
                companyContextService,
                journalEntryRepository,
                journalLineRepository,
                accountingEventRepository,
                settlementAllocationRepository,
                invoiceRepository,
                rawMaterialPurchaseRepository,
                packagingSlipRepository
        );
    }

    @Test
    void listTransactions_returnsClassifiedSalesRow() {
        Company company = new Company();
        company.setCode("BBP");
        when(companyContextService.requireCurrentCompany()).thenReturn(company);

        JournalEntry entry = new JournalEntry();
        setField(entry, "id", 11L);
        entry.setReferenceNumber("INV-202602-0001");
        entry.setEntryDate(LocalDate.of(2026, 2, 11));
        entry.setStatus("POSTED");
        entry.setMemo("Sales invoice posting");
        entry.setPostedAt(Instant.parse("2026-02-11T10:15:30Z"));

        Dealer dealer = new Dealer();
        dealer.setName("ANAS");
        entry.setDealer(dealer);

        Account ar = new Account();
        ar.setCode("AR-ANAS");
        ar.setType(AccountType.ASSET);
        JournalLine debit = new JournalLine();
        debit.setAccount(ar);
        debit.setDebit(new BigDecimal("2000.00"));
        debit.setCredit(BigDecimal.ZERO);

        Account rev = new Account();
        rev.setCode("REV");
        rev.setType(AccountType.REVENUE);
        JournalLine credit = new JournalLine();
        credit.setAccount(rev);
        credit.setDebit(BigDecimal.ZERO);
        credit.setCredit(new BigDecimal("2000.00"));

        entry.getLines().add(debit);
        entry.getLines().add(credit);

        when(journalEntryRepository.findAll(any(org.springframework.data.jpa.domain.Specification.class), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(entry)));
        when(journalLineRepository.summarizeTotalsByCompanyAndJournalEntryIds(eq(company), eq(List.of(11L))))
                .thenReturn(List.of(totals(11L, "2000.00", "2000.00")));
        when(invoiceRepository.findByCompanyAndJournalEntry_IdIn(eq(company), eq(List.of(11L)))).thenReturn(List.of());
        when(rawMaterialPurchaseRepository.findByCompanyAndJournalEntry_IdIn(eq(company), eq(List.of(11L)))).thenReturn(List.of());
        when(settlementAllocationRepository.findByCompanyAndJournalEntry_IdIn(eq(company), eq(List.of(11L)))).thenReturn(List.of());

        PageResponse<AccountingTransactionAuditListItemDto> result = service.listTransactions(
                null, null, null, null, null, 0, 50);

        assertThat(result.content()).hasSize(1);
        AccountingTransactionAuditListItemDto row = result.content().getFirst();
        assertThat(row.journalEntryId()).isEqualTo(11L);
        assertThat(row.referenceNumber()).isEqualTo("INV-202602-0001");
        assertThat(row.module()).isEqualTo("SALES");
        assertThat(row.transactionType()).isEqualTo("DEALER_JOURNAL");
        assertThat(row.totalDebit()).isEqualByComparingTo("2000.00");
        assertThat(row.totalCredit()).isEqualByComparingTo("2000.00");
        assertThat(row.consistencyStatus()).isEqualTo("OK");
    }

    @Test
    void listTransactions_shortCircuitsLookupsWhenPageIsEmpty() {
        Company company = new Company();
        company.setCode("BBP");
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(journalEntryRepository.findAll(any(org.springframework.data.jpa.domain.Specification.class), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of()));

        PageResponse<AccountingTransactionAuditListItemDto> result = service.listTransactions(
                null, null, null, null, null, 0, 50);

        assertThat(result.content()).isEmpty();
        verifyNoInteractions(journalLineRepository, invoiceRepository, rawMaterialPurchaseRepository, settlementAllocationRepository);
    }

    @Test
    void transactionDetail_flagsSettlementWithoutAllocations() {
        Company company = new Company();
        company.setCode("BBP");
        when(companyContextService.requireCurrentCompany()).thenReturn(company);

        JournalEntry entry = new JournalEntry();
        setField(entry, "id", 42L);
        entry.setReferenceNumber("SET-SKEINA-0001");
        entry.setEntryDate(LocalDate.of(2026, 2, 11));
        entry.setStatus("POSTED");
        entry.setMemo("Supplier settlement");
        // deliberately keeping postedAt null to trigger warning

        Account ap = new Account();
        ap.setCode("AP-SKEINA");
        ap.setType(AccountType.LIABILITY);
        JournalLine debit = new JournalLine();
        debit.setAccount(ap);
        debit.setDebit(new BigDecimal("4000.00"));
        debit.setCredit(BigDecimal.ZERO);

        Account cash = new Account();
        cash.setCode("CASH");
        cash.setType(AccountType.ASSET);
        JournalLine credit = new JournalLine();
        credit.setAccount(cash);
        credit.setDebit(BigDecimal.ZERO);
        credit.setCredit(new BigDecimal("4000.00"));

        entry.getLines().add(debit);
        entry.getLines().add(credit);

        AccountingEvent event = new AccountingEvent();
        event.setEventType(AccountingEventType.JOURNAL_ENTRY_POSTED);
        event.setAggregateType("JournalEntry");
        event.setSequenceNumber(1L);
        event.setEventTimestamp(Instant.parse("2026-02-11T10:20:00Z"));
        event.setEffectiveDate(LocalDate.of(2026, 2, 11));
        event.setDescription("Posted");

        when(journalEntryRepository.findByCompanyAndId(company, 42L)).thenReturn(Optional.of(entry));
        when(settlementAllocationRepository.findByCompanyAndJournalEntryOrderByCreatedAtAsc(company, entry)).thenReturn(List.of());
        when(invoiceRepository.findByCompanyAndJournalEntry(company, entry)).thenReturn(Optional.empty());
        when(rawMaterialPurchaseRepository.findByCompanyAndJournalEntry(company, entry)).thenReturn(Optional.empty());
        when(accountingEventRepository.findByJournalEntryIdOrderByEventTimestampAsc(42L)).thenReturn(List.of(event));

        AccountingTransactionAuditDetailDto detail = service.transactionDetail(42L);

        assertThat(detail.journalEntryId()).isEqualTo(42L);
        assertThat(detail.module()).isEqualTo("SETTLEMENT");
        assertThat(detail.consistencyStatus()).isEqualTo("WARNING");
        assertThat(detail.consistencyNotes()).anyMatch(note -> note.contains("Settlement-like reference"));
        assertThat(detail.eventTrail()).hasSize(1);
    }

    @Test
    void transactionDetail_throwsWhenEntryNotFound() {
        Company company = new Company();
        company.setCode("BBP");
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(journalEntryRepository.findByCompanyAndId(company, 404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.transactionDetail(404L))
                .isInstanceOf(com.bigbrightpaints.erp.core.exception.ApplicationException.class)
                .hasMessageContaining("Journal entry not found");
    }

    @Test
    void transactionDetail_classifiesSupplierPrefixedReferenceAsSettlement() {
        Company company = new Company();
        company.setCode("BBP");
        when(companyContextService.requireCurrentCompany()).thenReturn(company);

        JournalEntry entry = new JournalEntry();
        setField(entry, "id", 84L);
        entry.setReferenceNumber("SUP-PAY-202602-0001");
        entry.setEntryDate(LocalDate.of(2026, 2, 12));
        entry.setStatus("POSTED");
        Supplier supplier = new Supplier();
        supplier.setName("SKEINA SUPPLY");
        entry.setSupplier(supplier);

        Account ap = new Account();
        ap.setCode("AP-SUP");
        ap.setType(AccountType.LIABILITY);
        JournalLine debit = new JournalLine();
        debit.setAccount(ap);
        debit.setDebit(new BigDecimal("4000.00"));
        debit.setCredit(BigDecimal.ZERO);

        Account cash = new Account();
        cash.setCode("CASH");
        cash.setType(AccountType.ASSET);
        JournalLine credit = new JournalLine();
        credit.setAccount(cash);
        credit.setDebit(BigDecimal.ZERO);
        credit.setCredit(new BigDecimal("4000.00"));

        entry.getLines().add(debit);
        entry.getLines().add(credit);

        when(journalEntryRepository.findByCompanyAndId(company, 84L)).thenReturn(Optional.of(entry));
        when(settlementAllocationRepository.findByCompanyAndJournalEntryOrderByCreatedAtAsc(company, entry)).thenReturn(List.of());
        when(invoiceRepository.findByCompanyAndJournalEntry(company, entry)).thenReturn(Optional.empty());
        when(rawMaterialPurchaseRepository.findByCompanyAndJournalEntry(company, entry)).thenReturn(Optional.empty());
        when(accountingEventRepository.findByJournalEntryIdOrderByEventTimestampAsc(84L)).thenReturn(List.of());

        AccountingTransactionAuditDetailDto detail = service.transactionDetail(84L);

        assertThat(detail.module()).isEqualTo("SETTLEMENT");
        assertThat(detail.transactionType()).isEqualTo("SETTLEMENT_SUPPLIER");
        assertThat(detail.consistencyStatus()).isEqualTo("WARNING");
        assertThat(detail.consistencyNotes()).anyMatch(note -> note.contains("Settlement-like reference"));
    }

    @Test
    void listTransactions_classifiesSupplierPrefixedReferenceAsSettlement() {
        Company company = new Company();
        company.setCode("BBP");
        when(companyContextService.requireCurrentCompany()).thenReturn(company);

        JournalEntry entry = new JournalEntry();
        setField(entry, "id", 85L);
        entry.setReferenceNumber("SUP-SET-202602-0007");
        entry.setEntryDate(LocalDate.of(2026, 2, 12));
        entry.setStatus("POSTED");
        Supplier supplier = new Supplier();
        supplier.setName("SKEINA SUPPLY");
        entry.setSupplier(supplier);

        Account ap = new Account();
        ap.setCode("AP-SUP");
        ap.setType(AccountType.LIABILITY);
        JournalLine debit = new JournalLine();
        debit.setAccount(ap);
        debit.setDebit(new BigDecimal("4000.00"));
        debit.setCredit(BigDecimal.ZERO);

        Account cash = new Account();
        cash.setCode("CASH");
        cash.setType(AccountType.ASSET);
        JournalLine credit = new JournalLine();
        credit.setAccount(cash);
        credit.setDebit(BigDecimal.ZERO);
        credit.setCredit(new BigDecimal("4000.00"));

        entry.getLines().add(debit);
        entry.getLines().add(credit);

        when(journalEntryRepository.findAll(any(org.springframework.data.jpa.domain.Specification.class), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(entry)));
        when(journalLineRepository.summarizeTotalsByCompanyAndJournalEntryIds(eq(company), eq(List.of(85L))))
                .thenReturn(List.of(totals(85L, "4000.00", "4000.00")));
        when(invoiceRepository.findByCompanyAndJournalEntry_IdIn(eq(company), eq(List.of(85L)))).thenReturn(List.of());
        when(rawMaterialPurchaseRepository.findByCompanyAndJournalEntry_IdIn(eq(company), eq(List.of(85L)))).thenReturn(List.of());
        when(settlementAllocationRepository.findByCompanyAndJournalEntry_IdIn(eq(company), eq(List.of(85L)))).thenReturn(List.of());

        PageResponse<AccountingTransactionAuditListItemDto> result = service.listTransactions(
                null, null, null, null, null, 0, 50);

        assertThat(result.content()).hasSize(1);
        AccountingTransactionAuditListItemDto row = result.content().getFirst();
        assertThat(row.module()).isEqualTo("SETTLEMENT");
        assertThat(row.transactionType()).isEqualTo("SETTLEMENT_SUPPLIER");
    }

    @Test
    void listTransactions_normalizesNegativePageAndOversizeSize() {
        Company company = new Company();
        company.setCode("BBP");
        when(companyContextService.requireCurrentCompany()).thenReturn(company);

        JournalEntry entry = new JournalEntry();
        setField(entry, "id", 860L);
        entry.setReferenceNumber("GEN-860");
        entry.setEntryDate(LocalDate.of(2026, 2, 12));
        entry.setStatus("POSTED");

        when(journalEntryRepository.findAll(any(org.springframework.data.jpa.domain.Specification.class), org.mockito.ArgumentMatchers.<PageRequest>argThat(pageable ->
                pageable.getPageNumber() == 0 && pageable.getPageSize() == 200)))
                .thenReturn(new PageImpl<>(List.of(entry)));
        when(journalLineRepository.summarizeTotalsByCompanyAndJournalEntryIds(eq(company), eq(List.of(860L))))
                .thenReturn(List.of(totals(860L, "0.00", "0.00")));
        when(invoiceRepository.findByCompanyAndJournalEntry_IdIn(eq(company), eq(List.of(860L)))).thenReturn(List.of());
        when(rawMaterialPurchaseRepository.findByCompanyAndJournalEntry_IdIn(eq(company), eq(List.of(860L)))).thenReturn(List.of());
        when(settlementAllocationRepository.findByCompanyAndJournalEntry_IdIn(eq(company), eq(List.of(860L)))).thenReturn(List.of());

        PageResponse<AccountingTransactionAuditListItemDto> result = service.listTransactions(
                null, null, null, null, null, -4, 500);

        assertThat(result.page()).isEqualTo(0);
        assertThat(result.size()).isEqualTo(200);
        assertThat(result.content()).hasSize(1);
    }

    @Test
    void listTransactions_filtersDuplicateJournalLinksAndDefaultsNullTotals() {
        Company company = new Company();
        company.setCode("BBP");
        when(companyContextService.requireCurrentCompany()).thenReturn(company);

        JournalEntry entry = new JournalEntry();
        setField(entry, "id", 86L);
        entry.setReferenceNumber("INV-202602-0086");
        entry.setEntryDate(LocalDate.of(2026, 2, 12));
        entry.setStatus("POSTED");
        entry.setPostedAt(Instant.parse("2026-02-12T12:00:00Z"));

        when(journalEntryRepository.findAll(any(org.springframework.data.jpa.domain.Specification.class), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(entry)));
        when(journalLineRepository.summarizeTotalsByCompanyAndJournalEntryIds(eq(company), eq(List.of(86L))))
                .thenReturn(List.of(new JournalLineRepository.JournalEntryLineTotals() {
                    @Override
                    public Long getJournalEntryId() {
                        return 86L;
                    }

                    @Override
                    public BigDecimal getTotalDebit() {
                        return null;
                    }

                    @Override
                    public BigDecimal getTotalCredit() {
                        return null;
                    }
                }));

        Invoice invoice = new Invoice();
        invoice.setJournalEntry(entry);
        Invoice duplicateInvoice = new Invoice();
        duplicateInvoice.setJournalEntry(entry);
        Invoice invoiceWithoutJournal = new Invoice();

        RawMaterialPurchase purchase = new RawMaterialPurchase();
        purchase.setJournalEntry(entry);
        RawMaterialPurchase duplicatePurchase = new RawMaterialPurchase();
        duplicatePurchase.setJournalEntry(entry);
        RawMaterialPurchase purchaseWithoutJournal = new RawMaterialPurchase();

        when(invoiceRepository.findByCompanyAndJournalEntry_IdIn(eq(company), eq(List.of(86L))))
                .thenReturn(List.of(invoice, duplicateInvoice, invoiceWithoutJournal));
        when(rawMaterialPurchaseRepository.findByCompanyAndJournalEntry_IdIn(eq(company), eq(List.of(86L))))
                .thenReturn(List.of(purchase, duplicatePurchase, purchaseWithoutJournal));
        when(settlementAllocationRepository.findByCompanyAndJournalEntry_IdIn(eq(company), eq(List.of(86L))))
                .thenReturn(List.of());

        PageResponse<AccountingTransactionAuditListItemDto> result = service.listTransactions(
                null, null, null, null, null, 0, 50);

        assertThat(result.content()).singleElement().satisfies(row -> {
            assertThat(row.journalEntryId()).isEqualTo(86L);
            assertThat(row.totalDebit()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(row.totalCredit()).isEqualByComparingTo(BigDecimal.ZERO);
        });
    }

    @Test
    void transactionDetail_exposesPurchaseWorkflowProvenanceChain() {
        Company company = new Company();
        company.setCode("BBP");
        when(companyContextService.requireCurrentCompany()).thenReturn(company);

        JournalEntry entry = new JournalEntry();
        setField(entry, "id", 91L);
        entry.setReferenceNumber("RMP-BBP-SUP-91");
        entry.setEntryDate(LocalDate.of(2026, 2, 15));
        entry.setStatus("POSTED");

        Account inv = new Account();
        inv.setCode("RM-INVENTORY");
        inv.setType(AccountType.ASSET);
        JournalLine debit = new JournalLine();
        debit.setAccount(inv);
        debit.setDebit(new BigDecimal("800.00"));
        debit.setCredit(BigDecimal.ZERO);

        Account ap = new Account();
        ap.setCode("AP-SUP");
        ap.setType(AccountType.LIABILITY);
        JournalLine credit = new JournalLine();
        credit.setAccount(ap);
        credit.setDebit(BigDecimal.ZERO);
        credit.setCredit(new BigDecimal("800.00"));
        entry.getLines().add(debit);
        entry.getLines().add(credit);

        Supplier supplier = new Supplier();
        setField(supplier, "id", 201L);
        supplier.setName("Supplier 201");

        PurchaseOrder purchaseOrder = new PurchaseOrder();
        setField(purchaseOrder, "id", 301L);
        purchaseOrder.setOrderNumber("PO-301");
        purchaseOrder.setStatus("INVOICED");

        GoodsReceipt goodsReceipt = new GoodsReceipt();
        setField(goodsReceipt, "id", 401L);
        goodsReceipt.setCompany(company);
        goodsReceipt.setReceiptNumber("GRN-401");
        goodsReceipt.setStatus("INVOICED");
        goodsReceipt.setPurchaseOrder(purchaseOrder);

        RawMaterialPurchase purchase = new RawMaterialPurchase();
        setField(purchase, "id", 501L);
        purchase.setCompany(company);
        purchase.setSupplier(supplier);
        purchase.setInvoiceNumber("PINV-501");
        purchase.setStatus("POSTED");
        purchase.setGoodsReceipt(goodsReceipt);
        purchase.setPurchaseOrder(purchaseOrder);
        purchase.setJournalEntry(entry);
        purchase.setTotalAmount(new BigDecimal("800.00"));
        purchase.setOutstandingAmount(new BigDecimal("200.00"));

        when(journalEntryRepository.findByCompanyAndId(company, 91L)).thenReturn(Optional.of(entry));
        when(settlementAllocationRepository.findByCompanyAndJournalEntryOrderByCreatedAtAsc(company, entry)).thenReturn(List.of());
        when(invoiceRepository.findByCompanyAndJournalEntry(company, entry)).thenReturn(Optional.empty());
        when(rawMaterialPurchaseRepository.findByCompanyAndJournalEntry(company, entry)).thenReturn(Optional.of(purchase));
        when(settlementAllocationRepository.findByCompanyAndPurchaseOrderByCreatedAtDesc(company, purchase)).thenReturn(List.of());
        when(accountingEventRepository.findByJournalEntryIdOrderByEventTimestampAsc(91L)).thenReturn(List.of());

        AccountingTransactionAuditDetailDto detail = service.transactionDetail(91L);

        assertThat(detail.drivingDocument().documentType()).isEqualTo("PURCHASE_INVOICE");
        assertThat(detail.drivingDocument().documentId()).isEqualTo(501L);
        assertThat(detail.drivingDocument().lifecycle().workflowStatus()).isEqualTo("POSTED");
        assertThat(detail.linkedReferenceChain())
                .extracting(LinkedBusinessReferenceDto::relationType, LinkedBusinessReferenceDto::documentType)
                .contains(
                        org.assertj.core.groups.Tuple.tuple("GOODS_RECEIPT", "GOODS_RECEIPT"),
                        org.assertj.core.groups.Tuple.tuple("PURCHASE_ORDER", "PURCHASE_ORDER"),
                        org.assertj.core.groups.Tuple.tuple("ACCOUNTING_ENTRY", "JOURNAL_ENTRY")
                );
    }

    @Test
    void transactionDetail_exposesInvoiceWorkflowProvenanceChain() {
        Company company = new Company();
        company.setCode("BBP");
        when(companyContextService.requireCurrentCompany()).thenReturn(company);

        JournalEntry entry = new JournalEntry();
        setField(entry, "id", 92L);
        entry.setReferenceNumber("INV-BBP-92");
        entry.setEntryDate(LocalDate.of(2026, 2, 16));
        entry.setStatus("POSTED");
        entry.getLines().add(line("AR", "800.00", "0.00"));
        entry.getLines().add(line("REV", "0.00", "800.00"));

        SalesOrder order = new SalesOrder();
        setField(order, "id", 302L);
        order.setOrderNumber("SO-302");
        order.setStatus("INVOICED");
        order.setSalesJournalEntryId(92L);

        Invoice invoice = new Invoice();
        setField(invoice, "id", 502L);
        invoice.setCompany(company);
        invoice.setInvoiceNumber("INV-502");
        invoice.setStatus("ISSUED");
        invoice.setSalesOrder(order);
        invoice.setJournalEntry(entry);
        invoice.setTotalAmount(new BigDecimal("800.00"));
        invoice.setOutstandingAmount(new BigDecimal("125.00"));

        PackagingSlip slip = new PackagingSlip();
        setField(slip, "id", 402L);
        slip.setSalesOrder(order);
        slip.setSlipNumber("PS-402");
        slip.setStatus("DISPATCHED");
        slip.setJournalEntryId(92L);

        PartnerSettlementAllocation allocation = new PartnerSettlementAllocation();
        setField(allocation, "id", 602L);
        allocation.setCompany(company);
        allocation.setPartnerType(PartnerType.DEALER);
        allocation.setInvoice(invoice);
        allocation.setJournalEntry(entry);
        allocation.setAllocationAmount(new BigDecimal("675.00"));
        allocation.setIdempotencyKey("settlement-602");

        when(journalEntryRepository.findByCompanyAndId(company, 92L)).thenReturn(Optional.of(entry));
        when(settlementAllocationRepository.findByCompanyAndJournalEntryOrderByCreatedAtAsc(company, entry)).thenReturn(List.of());
        when(invoiceRepository.findByCompanyAndJournalEntry(company, entry)).thenReturn(Optional.of(invoice));
        when(rawMaterialPurchaseRepository.findByCompanyAndJournalEntry(company, entry)).thenReturn(Optional.empty());
        when(packagingSlipRepository.findAllByCompanyAndSalesOrderId(company, 302L)).thenReturn(List.of(slip));
        when(settlementAllocationRepository.findByCompanyAndInvoiceOrderByCreatedAtDesc(company, invoice)).thenReturn(List.of(allocation));
        when(accountingEventRepository.findByJournalEntryIdOrderByEventTimestampAsc(92L)).thenReturn(List.of());

        AccountingTransactionAuditDetailDto detail = service.transactionDetail(92L);

        assertThat(detail.drivingDocument().documentType()).isEqualTo("INVOICE");
        assertThat(detail.drivingDocument().documentId()).isEqualTo(502L);
        assertThat(detail.linkedReferenceChain())
                .extracting(LinkedBusinessReferenceDto::relationType, LinkedBusinessReferenceDto::documentType)
                .contains(
                        org.assertj.core.groups.Tuple.tuple("DRIVING_DOCUMENT", "INVOICE"),
                        org.assertj.core.groups.Tuple.tuple("SOURCE_ORDER", "SALES_ORDER"),
                        org.assertj.core.groups.Tuple.tuple("DISPATCH", "PACKAGING_SLIP"),
                        org.assertj.core.groups.Tuple.tuple("SETTLEMENT", "SETTLEMENT_ALLOCATION"),
                        org.assertj.core.groups.Tuple.tuple("ACCOUNTING_ENTRY", "JOURNAL_ENTRY")
                );
    }

    @Test
    void transactionDetail_filtersDispatchChainToDrivingInvoice() {
        Company company = new Company();
        company.setCode("BBP");
        when(companyContextService.requireCurrentCompany()).thenReturn(company);

        JournalEntry entry = new JournalEntry();
        setField(entry, "id", 193L);
        entry.setReferenceNumber("INV-BBP-193");
        entry.setEntryDate(LocalDate.of(2026, 2, 16));
        entry.setStatus("POSTED");
        entry.getLines().add(line("AR", "500.00", "0.00"));
        entry.getLines().add(line("REV", "0.00", "500.00"));

        SalesOrder order = new SalesOrder();
        setField(order, "id", 393L);
        order.setOrderNumber("SO-393");
        order.setStatus("INVOICED");

        Invoice invoice = new Invoice();
        setField(invoice, "id", 593L);
        invoice.setCompany(company);
        invoice.setInvoiceNumber("INV-593");
        invoice.setStatus("ISSUED");
        invoice.setSalesOrder(order);
        invoice.setJournalEntry(entry);

        PackagingSlip matchingSlip = new PackagingSlip();
        setField(matchingSlip, "id", 693L);
        matchingSlip.setSalesOrder(order);
        matchingSlip.setSlipNumber("PS-693");
        matchingSlip.setStatus("DISPATCHED");
        matchingSlip.setInvoiceId(593L);

        PackagingSlip unrelatedSlip = new PackagingSlip();
        setField(unrelatedSlip, "id", 694L);
        unrelatedSlip.setSalesOrder(order);
        unrelatedSlip.setSlipNumber("PS-694");
        unrelatedSlip.setStatus("DISPATCHED");
        unrelatedSlip.setInvoiceId(594L);

        when(journalEntryRepository.findByCompanyAndId(company, 193L)).thenReturn(Optional.of(entry));
        when(settlementAllocationRepository.findByCompanyAndJournalEntryOrderByCreatedAtAsc(company, entry)).thenReturn(List.of());
        when(invoiceRepository.findByCompanyAndJournalEntry(company, entry)).thenReturn(Optional.of(invoice));
        when(rawMaterialPurchaseRepository.findByCompanyAndJournalEntry(company, entry)).thenReturn(Optional.empty());
        when(packagingSlipRepository.findAllByCompanyAndSalesOrderId(company, 393L))
                .thenReturn(List.of(matchingSlip, unrelatedSlip));
        when(settlementAllocationRepository.findByCompanyAndInvoiceOrderByCreatedAtDesc(company, invoice)).thenReturn(List.of());
        when(accountingEventRepository.findByJournalEntryIdOrderByEventTimestampAsc(193L)).thenReturn(List.of());

        AccountingTransactionAuditDetailDto detail = service.transactionDetail(193L);

        assertThat(detail.linkedReferenceChain())
                .filteredOn(reference -> "DISPATCH".equals(reference.relationType()))
                .extracting(LinkedBusinessReferenceDto::documentNumber)
                .containsExactly("PS-693");
    }

    @Test
    void transactionDetail_omitsSingleLegacyDispatchWhenOrderHasMultipleInvoices() {
        Company company = new Company();
        company.setCode("BBP");
        when(companyContextService.requireCurrentCompany()).thenReturn(company);

        JournalEntry entry = new JournalEntry();
        setField(entry, "id", 194L);
        entry.setReferenceNumber("INV-BBP-194");
        entry.setEntryDate(LocalDate.of(2026, 2, 16));
        entry.setStatus("POSTED");
        entry.getLines().add(line("AR", "500.00", "0.00"));
        entry.getLines().add(line("REV", "0.00", "500.00"));

        SalesOrder order = new SalesOrder();
        setField(order, "id", 394L);
        order.setOrderNumber("SO-394");
        order.setStatus("INVOICED");

        Invoice invoice = new Invoice();
        setField(invoice, "id", 594L);
        invoice.setCompany(company);
        invoice.setInvoiceNumber("INV-594");
        invoice.setStatus("ISSUED");
        invoice.setSalesOrder(order);
        invoice.setJournalEntry(entry);

        Invoice otherInvoice = new Invoice();
        setField(otherInvoice, "id", 595L);
        otherInvoice.setCompany(company);
        otherInvoice.setInvoiceNumber("INV-595");
        otherInvoice.setStatus("ISSUED");
        otherInvoice.setSalesOrder(order);

        PackagingSlip legacySlip = new PackagingSlip();
        setField(legacySlip, "id", 695L);
        legacySlip.setSalesOrder(order);
        legacySlip.setSlipNumber("PS-695");
        legacySlip.setStatus("DISPATCHED");

        when(journalEntryRepository.findByCompanyAndId(company, 194L)).thenReturn(Optional.of(entry));
        when(settlementAllocationRepository.findByCompanyAndJournalEntryOrderByCreatedAtAsc(company, entry)).thenReturn(List.of());
        when(invoiceRepository.findByCompanyAndJournalEntry(company, entry)).thenReturn(Optional.of(invoice));
        when(invoiceRepository.findAllByCompanyAndSalesOrderId(company, 394L))
                .thenReturn(List.of(invoice, otherInvoice));
        when(rawMaterialPurchaseRepository.findByCompanyAndJournalEntry(company, entry)).thenReturn(Optional.empty());
        when(packagingSlipRepository.findAllByCompanyAndSalesOrderId(company, 394L))
                .thenReturn(List.of(legacySlip));
        when(settlementAllocationRepository.findByCompanyAndInvoiceOrderByCreatedAtDesc(company, invoice)).thenReturn(List.of());
        when(accountingEventRepository.findByJournalEntryIdOrderByEventTimestampAsc(194L)).thenReturn(List.of());

        AccountingTransactionAuditDetailDto detail = service.transactionDetail(194L);

        assertThat(detail.linkedReferenceChain())
                .filteredOn(reference -> "DISPATCH".equals(reference.relationType()))
                .isEmpty();
    }

    @Test
    void transactionDetail_skipsInvoiceCountLookupWhenDispatchLinksAreExplicit() {
        Company company = new Company();
        company.setCode("BBP");
        when(companyContextService.requireCurrentCompany()).thenReturn(company);

        JournalEntry entry = new JournalEntry();
        setField(entry, "id", 196L);
        entry.setReferenceNumber("INV-BBP-196");
        entry.setEntryDate(LocalDate.of(2026, 2, 16));
        entry.setStatus("POSTED");
        entry.getLines().add(line("AR", "500.00", "0.00"));
        entry.getLines().add(line("REV", "0.00", "500.00"));

        SalesOrder order = new SalesOrder();
        setField(order, "id", 396L);
        order.setOrderNumber("SO-396");

        Invoice invoice = new Invoice();
        setField(invoice, "id", 597L);
        invoice.setCompany(company);
        invoice.setInvoiceNumber("INV-597");
        invoice.setStatus("ISSUED");
        invoice.setSalesOrder(order);
        invoice.setJournalEntry(entry);

        PackagingSlip explicitSlip = new PackagingSlip();
        setField(explicitSlip, "id", 697L);
        explicitSlip.setSalesOrder(order);
        explicitSlip.setSlipNumber("PS-697");
        explicitSlip.setStatus("DISPATCHED");
        explicitSlip.setInvoiceId(597L);

        when(journalEntryRepository.findByCompanyAndId(company, 196L)).thenReturn(Optional.of(entry));
        when(settlementAllocationRepository.findByCompanyAndJournalEntryOrderByCreatedAtAsc(company, entry)).thenReturn(List.of());
        when(invoiceRepository.findByCompanyAndJournalEntry(company, entry)).thenReturn(Optional.of(invoice));
        when(rawMaterialPurchaseRepository.findByCompanyAndJournalEntry(company, entry)).thenReturn(Optional.empty());
        when(packagingSlipRepository.findAllByCompanyAndSalesOrderId(company, 396L))
                .thenReturn(List.of(explicitSlip));
        when(settlementAllocationRepository.findByCompanyAndInvoiceOrderByCreatedAtDesc(company, invoice)).thenReturn(List.of());
        when(accountingEventRepository.findByJournalEntryIdOrderByEventTimestampAsc(196L)).thenReturn(List.of());

        AccountingTransactionAuditDetailDto detail = service.transactionDetail(196L);

        assertThat(detail.linkedReferenceChain())
                .filteredOn(reference -> "DISPATCH".equals(reference.relationType()))
                .extracting(LinkedBusinessReferenceDto::documentNumber)
                .containsExactly("PS-697");
        verify(invoiceRepository, org.mockito.Mockito.never()).findAllByCompanyAndSalesOrderId(any(), any());
    }

    @Test
    void transactionDetail_keepsLegacyDispatchWhenHistoricalInvoicesAreNotCurrent() {
        Company company = new Company();
        company.setCode("BBP");
        when(companyContextService.requireCurrentCompany()).thenReturn(company);

        JournalEntry entry = new JournalEntry();
        setField(entry, "id", 195L);
        entry.setReferenceNumber("INV-BBP-195");
        entry.setEntryDate(LocalDate.of(2026, 2, 16));
        entry.setStatus("POSTED");
        entry.getLines().add(line("AR", "500.00", "0.00"));
        entry.getLines().add(line("REV", "0.00", "500.00"));

        SalesOrder order = new SalesOrder();
        setField(order, "id", 395L);
        order.setOrderNumber("SO-395");
        order.setStatus("INVOICED");

        Invoice invoice = new Invoice();
        setField(invoice, "id", 596L);
        invoice.setCompany(company);
        invoice.setInvoiceNumber("INV-596");
        invoice.setStatus("ISSUED");
        invoice.setSalesOrder(order);
        invoice.setJournalEntry(entry);

        PackagingSlip legacySlip = new PackagingSlip();
        setField(legacySlip, "id", 696L);
        legacySlip.setSalesOrder(order);
        legacySlip.setSlipNumber("PS-696");
        legacySlip.setStatus("DISPATCHED");

        when(journalEntryRepository.findByCompanyAndId(company, 195L)).thenReturn(Optional.of(entry));
        when(settlementAllocationRepository.findByCompanyAndJournalEntryOrderByCreatedAtAsc(company, entry)).thenReturn(List.of());
        when(invoiceRepository.findByCompanyAndJournalEntry(company, entry)).thenReturn(Optional.of(invoice));
        when(rawMaterialPurchaseRepository.findByCompanyAndJournalEntry(company, entry)).thenReturn(Optional.empty());
        when(packagingSlipRepository.findAllByCompanyAndSalesOrderId(company, 395L))
                .thenReturn(List.of(legacySlip));
        when(settlementAllocationRepository.findByCompanyAndInvoiceOrderByCreatedAtDesc(company, invoice)).thenReturn(List.of());
        when(accountingEventRepository.findByJournalEntryIdOrderByEventTimestampAsc(195L)).thenReturn(List.of());

        for (String historicalStatus : List.of("DRAFT", "VOID", "REVERSED")) {
            Invoice historicalInvoice = new Invoice();
            historicalInvoice.setCompany(company);
            historicalInvoice.setSalesOrder(order);
            historicalInvoice.setStatus(historicalStatus);
            when(invoiceRepository.findAllByCompanyAndSalesOrderId(company, 395L))
                    .thenReturn(List.of(invoice, historicalInvoice));

            AccountingTransactionAuditDetailDto detail = service.transactionDetail(195L);

            assertThat(detail.linkedReferenceChain())
                    .filteredOn(reference -> "DISPATCH".equals(reference.relationType()))
                    .extracting(LinkedBusinessReferenceDto::documentNumber)
                    .containsExactly("PS-696");
        }
    }

    @Test
    void helperMethods_resolveCurrentSalesOrderInvoiceCount_usesCurrentStatusFallbackOnly() {
        Invoice currentInvoice = new Invoice();
        currentInvoice.setStatus("ISSUED");
        SalesOrder currentOrder = new SalesOrder();
        setField(currentOrder, "id", 901L);
        currentInvoice.setSalesOrder(currentOrder);
        Company currentCompany = new Company();
        currentInvoice.setCompany(currentCompany);
        when(invoiceRepository.findAllByCompanyAndSalesOrderId(currentCompany, 901L)).thenReturn(null);

        Invoice historicalInvoice = new Invoice();
        historicalInvoice.setStatus("VOID");
        SalesOrder historicalOrder = new SalesOrder();
        setField(historicalOrder, "id", 902L);
        historicalInvoice.setSalesOrder(historicalOrder);
        Company historicalCompany = new Company();
        historicalInvoice.setCompany(historicalCompany);
        when(invoiceRepository.findAllByCompanyAndSalesOrderId(historicalCompany, 902L)).thenReturn(null);

        assertThat((Integer) ReflectionTestUtils.invokeMethod(service, "resolveCurrentSalesOrderInvoiceCount", currentInvoice))
                .isEqualTo(1);
        assertThat((Integer) ReflectionTestUtils.invokeMethod(service, "resolveCurrentSalesOrderInvoiceCount", historicalInvoice))
                .isZero();
        assertThat((Integer) ReflectionTestUtils.invokeMethod(service, "resolveCurrentSalesOrderInvoiceCount", new Object[]{null}))
                .isZero();
    }

    @Test
    void transactionDetail_settlementDrivingInvoiceUsesSourceInvoiceJournalEntryId() {
        Company company = new Company();
        company.setCode("BBP");
        when(companyContextService.requireCurrentCompany()).thenReturn(company);

        JournalEntry settlementEntry = new JournalEntry();
        setField(settlementEntry, "id", 110L);
        settlementEntry.setReferenceNumber("SET-110");
        settlementEntry.setEntryDate(LocalDate.of(2026, 2, 18));
        settlementEntry.setStatus("POSTED");
        settlementEntry.getLines().add(line("CASH", "0.00", "100.00"));
        settlementEntry.getLines().add(line("AR", "100.00", "0.00"));

        JournalEntry invoiceJournal = new JournalEntry();
        setField(invoiceJournal, "id", 210L);
        invoiceJournal.setReferenceNumber("INV-210");
        invoiceJournal.setStatus("POSTED");

        Invoice invoice = new Invoice();
        setField(invoice, "id", 310L);
        invoice.setCompany(company);
        invoice.setInvoiceNumber("INV-310");
        invoice.setStatus("ISSUED");
        invoice.setJournalEntry(invoiceJournal);
        invoice.setTotalAmount(new BigDecimal("100.00"));
        invoice.setOutstandingAmount(new BigDecimal("40.00"));

        PartnerSettlementAllocation allocation = new PartnerSettlementAllocation();
        setField(allocation, "id", 410L);
        allocation.setCompany(company);
        allocation.setPartnerType(PartnerType.DEALER);
        allocation.setInvoice(invoice);
        allocation.setJournalEntry(settlementEntry);
        allocation.setAllocationAmount(new BigDecimal("60.00"));
        allocation.setIdempotencyKey("settlement-410");

        when(journalEntryRepository.findByCompanyAndId(company, 110L)).thenReturn(Optional.of(settlementEntry));
        when(settlementAllocationRepository.findByCompanyAndJournalEntryOrderByCreatedAtAsc(company, settlementEntry))
                .thenReturn(List.of(allocation));
        when(invoiceRepository.findByCompanyAndJournalEntry(company, settlementEntry)).thenReturn(Optional.empty());
        when(rawMaterialPurchaseRepository.findByCompanyAndJournalEntry(company, settlementEntry)).thenReturn(Optional.empty());
        when(accountingEventRepository.findByJournalEntryIdOrderByEventTimestampAsc(110L)).thenReturn(List.of());

        AccountingTransactionAuditDetailDto detail = service.transactionDetail(110L);

        assertThat(detail.drivingDocument().documentType()).isEqualTo("INVOICE");
        assertThat(detail.drivingDocument().documentId()).isEqualTo(310L);
        assertThat(detail.drivingDocument().journalEntryId()).isEqualTo(210L);
    }

    @Test
    void transactionDetail_settlementDrivingPurchaseUsesSourcePurchaseJournalEntryId() {
        Company company = new Company();
        company.setCode("BBP");
        when(companyContextService.requireCurrentCompany()).thenReturn(company);

        JournalEntry settlementEntry = new JournalEntry();
        setField(settlementEntry, "id", 111L);
        settlementEntry.setReferenceNumber("SUP-SET-111");
        settlementEntry.setEntryDate(LocalDate.of(2026, 2, 18));
        settlementEntry.setStatus("POSTED");
        settlementEntry.getLines().add(line("CASH", "100.00", "0.00"));
        settlementEntry.getLines().add(line("AP", "0.00", "100.00"));

        JournalEntry purchaseJournal = new JournalEntry();
        setField(purchaseJournal, "id", 211L);
        purchaseJournal.setReferenceNumber("RMP-211");
        purchaseJournal.setStatus("POSTED");

        RawMaterialPurchase purchase = new RawMaterialPurchase();
        setField(purchase, "id", 311L);
        purchase.setCompany(company);
        purchase.setInvoiceNumber("PINV-311");
        purchase.setStatus("POSTED");
        purchase.setJournalEntry(purchaseJournal);
        purchase.setTotalAmount(new BigDecimal("100.00"));
        purchase.setOutstandingAmount(new BigDecimal("25.00"));

        PartnerSettlementAllocation allocation = new PartnerSettlementAllocation();
        setField(allocation, "id", 411L);
        allocation.setCompany(company);
        allocation.setPartnerType(PartnerType.SUPPLIER);
        allocation.setPurchase(purchase);
        allocation.setJournalEntry(settlementEntry);
        allocation.setAllocationAmount(new BigDecimal("75.00"));
        allocation.setIdempotencyKey("settlement-411");

        when(journalEntryRepository.findByCompanyAndId(company, 111L)).thenReturn(Optional.of(settlementEntry));
        when(settlementAllocationRepository.findByCompanyAndJournalEntryOrderByCreatedAtAsc(company, settlementEntry))
                .thenReturn(List.of(allocation));
        when(invoiceRepository.findByCompanyAndJournalEntry(company, settlementEntry)).thenReturn(Optional.empty());
        when(rawMaterialPurchaseRepository.findByCompanyAndJournalEntry(company, settlementEntry)).thenReturn(Optional.empty());
        when(accountingEventRepository.findByJournalEntryIdOrderByEventTimestampAsc(111L)).thenReturn(List.of());

        AccountingTransactionAuditDetailDto detail = service.transactionDetail(111L);

        assertThat(detail.drivingDocument().documentType()).isEqualTo("PURCHASE_INVOICE");
        assertThat(detail.drivingDocument().documentId()).isEqualTo(311L);
        assertThat(detail.drivingDocument().journalEntryId()).isEqualTo(211L);
    }

    @Test
    void transactionDetail_onAccountSettlementMemoDecodesApplicationTokenAndVisibleMemo() {
        Company company = new Company();
        company.setCode("BBP");
        when(companyContextService.requireCurrentCompany()).thenReturn(company);

        JournalEntry settlementEntry = new JournalEntry();
        setField(settlementEntry, "id", 112L);
        settlementEntry.setReferenceNumber("SUP-SET-112");
        settlementEntry.setEntryDate(LocalDate.of(2026, 2, 18));
        settlementEntry.setStatus("POSTED");
        settlementEntry.getLines().add(line("CASH", "75.00", "0.00"));
        settlementEntry.getLines().add(line("AP", "0.00", "75.00"));

        PartnerSettlementAllocation allocation = new PartnerSettlementAllocation();
        setField(allocation, "id", 412L);
        allocation.setCompany(company);
        allocation.setPartnerType(PartnerType.SUPPLIER);
        allocation.setJournalEntry(settlementEntry);
        allocation.setAllocationAmount(new BigDecimal("75.00"));
        allocation.setMemo(" [SETTLEMENT-APPLICATION:ON_ACCOUNT]   future order hold  ");
        allocation.setIdempotencyKey("settlement-412");

        when(journalEntryRepository.findByCompanyAndId(company, 112L)).thenReturn(Optional.of(settlementEntry));
        when(settlementAllocationRepository.findByCompanyAndJournalEntryOrderByCreatedAtAsc(company, settlementEntry))
                .thenReturn(List.of(allocation));
        when(invoiceRepository.findByCompanyAndJournalEntry(company, settlementEntry)).thenReturn(Optional.empty());
        when(rawMaterialPurchaseRepository.findByCompanyAndJournalEntry(company, settlementEntry)).thenReturn(Optional.empty());
        when(accountingEventRepository.findByJournalEntryIdOrderByEventTimestampAsc(112L)).thenReturn(List.of());

        AccountingTransactionAuditDetailDto detail = service.transactionDetail(112L);

        assertThat(detail.settlementAllocations()).hasSize(1);
        AccountingTransactionAuditDetailDto.SettlementAllocation row = detail.settlementAllocations().getFirst();
        assertThat(row.applicationType()).isEqualTo(SettlementAllocationApplication.ON_ACCOUNT.name());
        assertThat(row.memo()).isEqualTo("future order hold");
    }

    @Test
    void transactionDetail_invalidSettlementApplicationTokenFallsBackToOnAccount() {
        Company company = new Company();
        company.setCode("BBP");
        when(companyContextService.requireCurrentCompany()).thenReturn(company);

        JournalEntry settlementEntry = new JournalEntry();
        setField(settlementEntry, "id", 113L);
        settlementEntry.setReferenceNumber("SUP-SET-113");
        settlementEntry.setEntryDate(LocalDate.of(2026, 2, 19));
        settlementEntry.setStatus("POSTED");
        settlementEntry.getLines().add(line("CASH", "40.00", "0.00"));
        settlementEntry.getLines().add(line("AP", "0.00", "40.00"));

        PartnerSettlementAllocation allocation = new PartnerSettlementAllocation();
        setField(allocation, "id", 413L);
        allocation.setCompany(company);
        allocation.setPartnerType(PartnerType.SUPPLIER);
        allocation.setJournalEntry(settlementEntry);
        allocation.setAllocationAmount(new BigDecimal("40.00"));
        allocation.setMemo("[SETTLEMENT-APPLICATION:BOGUS]  keep on account  ");
        allocation.setIdempotencyKey("settlement-413");

        when(journalEntryRepository.findByCompanyAndId(company, 113L)).thenReturn(Optional.of(settlementEntry));
        when(settlementAllocationRepository.findByCompanyAndJournalEntryOrderByCreatedAtAsc(company, settlementEntry))
                .thenReturn(List.of(allocation));
        when(invoiceRepository.findByCompanyAndJournalEntry(company, settlementEntry)).thenReturn(Optional.empty());
        when(rawMaterialPurchaseRepository.findByCompanyAndJournalEntry(company, settlementEntry)).thenReturn(Optional.empty());
        when(accountingEventRepository.findByJournalEntryIdOrderByEventTimestampAsc(113L)).thenReturn(List.of());

        AccountingTransactionAuditDetailDto detail = service.transactionDetail(113L);

        assertThat(detail.settlementAllocations()).hasSize(1);
        AccountingTransactionAuditDetailDto.SettlementAllocation row = detail.settlementAllocations().getFirst();
        assertThat(row.applicationType()).isEqualTo(SettlementAllocationApplication.ON_ACCOUNT.name());
        assertThat(row.memo()).isEqualTo("keep on account");
    }

    @Test
    void transactionDetail_settlementApplicationTokenWithoutClosingBracketFallsBackToOnAccount() {
        Company company = new Company();
        company.setCode("BBP");
        when(companyContextService.requireCurrentCompany()).thenReturn(company);

        JournalEntry settlementEntry = new JournalEntry();
        setField(settlementEntry, "id", 115L);
        settlementEntry.setReferenceNumber("SUP-SET-115");
        settlementEntry.setEntryDate(LocalDate.of(2026, 2, 21));
        settlementEntry.setStatus("POSTED");
        settlementEntry.getLines().add(line("CASH", "35.00", "0.00"));
        settlementEntry.getLines().add(line("AP", "0.00", "35.00"));

        PartnerSettlementAllocation allocation = new PartnerSettlementAllocation();
        setField(allocation, "id", 415L);
        allocation.setCompany(company);
        allocation.setPartnerType(PartnerType.SUPPLIER);
        allocation.setJournalEntry(settlementEntry);
        allocation.setAllocationAmount(new BigDecimal("35.00"));
        allocation.setMemo(" [SETTLEMENT-APPLICATION:FUTURE_APPLICATION keep unapplied ");
        allocation.setIdempotencyKey("settlement-415");

        when(journalEntryRepository.findByCompanyAndId(company, 115L)).thenReturn(Optional.of(settlementEntry));
        when(settlementAllocationRepository.findByCompanyAndJournalEntryOrderByCreatedAtAsc(company, settlementEntry))
                .thenReturn(List.of(allocation));
        when(invoiceRepository.findByCompanyAndJournalEntry(company, settlementEntry)).thenReturn(Optional.empty());
        when(rawMaterialPurchaseRepository.findByCompanyAndJournalEntry(company, settlementEntry)).thenReturn(Optional.empty());
        when(accountingEventRepository.findByJournalEntryIdOrderByEventTimestampAsc(115L)).thenReturn(List.of());

        AccountingTransactionAuditDetailDto detail = service.transactionDetail(115L);

        assertThat(detail.settlementAllocations()).hasSize(1);
        AccountingTransactionAuditDetailDto.SettlementAllocation row = detail.settlementAllocations().getFirst();
        assertThat(row.applicationType()).isEqualTo(SettlementAllocationApplication.ON_ACCOUNT.name());
        assertThat(row.memo()).isEqualTo("[SETTLEMENT-APPLICATION:FUTURE_APPLICATION keep unapplied");
    }

    @Test
    void helperMethods_decodeSettlementAuditMemo_defaultsDocumentForNullAllocation() {
        Object decoded = ReflectionTestUtils.invokeMethod(service, "decodeSettlementAuditMemo", new Object[]{null});

        assertThat((SettlementAllocationApplication) ReflectionTestUtils.invokeMethod(decoded, "applicationType"))
                .isEqualTo(SettlementAllocationApplication.DOCUMENT);
        assertThat((String) ReflectionTestUtils.invokeMethod(decoded, "memo")).isNull();
    }

    @Test
    void helperMethods_decodeSettlementAuditMemo_trimsBlankUnappliedMemoToNull() {
        PartnerSettlementAllocation allocation = new PartnerSettlementAllocation();
        allocation.setMemo("   ");

        Object decoded = ReflectionTestUtils.invokeMethod(service, "decodeSettlementAuditMemo", allocation);

        assertThat((SettlementAllocationApplication) ReflectionTestUtils.invokeMethod(decoded, "applicationType"))
                .isEqualTo(SettlementAllocationApplication.ON_ACCOUNT);
        assertThat((String) ReflectionTestUtils.invokeMethod(decoded, "memo")).isNull();
    }

    @Test
    void helperMethods_decodeSettlementAuditMemo_keepsDocumentTypeForPurchaseLinkedAllocations() {
        PartnerSettlementAllocation allocation = new PartnerSettlementAllocation();
        allocation.setMemo("  purchase linked memo  ");
        allocation.setPurchase(new RawMaterialPurchase());

        Object decoded = ReflectionTestUtils.invokeMethod(service, "decodeSettlementAuditMemo", allocation);

        assertThat((SettlementAllocationApplication) ReflectionTestUtils.invokeMethod(decoded, "applicationType"))
                .isEqualTo(SettlementAllocationApplication.DOCUMENT);
        assertThat((String) ReflectionTestUtils.invokeMethod(decoded, "memo")).isEqualTo("purchase linked memo");
    }

    @Test
    void helperMethods_decodeSettlementAuditMemo_fallsBackToOnAccountForMalformedPrefix() {
        PartnerSettlementAllocation allocation = new PartnerSettlementAllocation();
        allocation.setMemo("[SETTLEMENT-APPLICATION:ON_ACCOUNT carry forward");

        Object decoded = ReflectionTestUtils.invokeMethod(service, "decodeSettlementAuditMemo", allocation);

        assertThat((SettlementAllocationApplication) ReflectionTestUtils.invokeMethod(decoded, "applicationType"))
                .isEqualTo(SettlementAllocationApplication.ON_ACCOUNT);
        assertThat((String) ReflectionTestUtils.invokeMethod(decoded, "memo"))
                .isEqualTo("[SETTLEMENT-APPLICATION:ON_ACCOUNT carry forward");
    }

    @Test
    void helperMethods_decodeSettlementAuditMemo_treatsPlainMemoAsOnAccount() {
        PartnerSettlementAllocation allocation = new PartnerSettlementAllocation();
        allocation.setMemo("  carry forward plain memo  ");

        Object decoded = ReflectionTestUtils.invokeMethod(service, "decodeSettlementAuditMemo", allocation);

        assertThat((SettlementAllocationApplication) ReflectionTestUtils.invokeMethod(decoded, "applicationType"))
                .isEqualTo(SettlementAllocationApplication.ON_ACCOUNT);
        assertThat((String) ReflectionTestUtils.invokeMethod(decoded, "memo")).isEqualTo("carry forward plain memo");
    }

    @Test
    void transactionDetail_mapsSettlementRowsWithNullPartnerContext() {
        Company company = new Company();
        company.setCode("BBP");
        when(companyContextService.requireCurrentCompany()).thenReturn(company);

        JournalEntry settlementEntry = new JournalEntry();
        setField(settlementEntry, "id", 116L);
        settlementEntry.setReferenceNumber("SET-NULL-PARTNER");
        settlementEntry.setEntryDate(LocalDate.of(2026, 2, 21));
        settlementEntry.setStatus("POSTED");
        settlementEntry.getLines().add(line("CASH", "10.00", "0.00"));
        settlementEntry.getLines().add(line("AP", "0.00", "10.00"));

        PartnerSettlementAllocation allocation = new PartnerSettlementAllocation();
        setField(allocation, "id", 416L);
        allocation.setCompany(company);
        allocation.setJournalEntry(settlementEntry);
        allocation.setAllocationAmount(new BigDecimal("10.00"));
        allocation.setMemo("plain memo");

        when(journalEntryRepository.findByCompanyAndId(company, 116L)).thenReturn(Optional.of(settlementEntry));
        when(settlementAllocationRepository.findByCompanyAndJournalEntryOrderByCreatedAtAsc(company, settlementEntry))
                .thenReturn(List.of(allocation));
        when(invoiceRepository.findByCompanyAndJournalEntry(company, settlementEntry)).thenReturn(Optional.empty());
        when(rawMaterialPurchaseRepository.findByCompanyAndJournalEntry(company, settlementEntry)).thenReturn(Optional.empty());
        when(accountingEventRepository.findByJournalEntryIdOrderByEventTimestampAsc(116L)).thenReturn(List.of());

        AccountingTransactionAuditDetailDto detail = service.transactionDetail(116L);

        assertThat(detail.settlementAllocations()).singleElement().satisfies(row -> {
            assertThat(row.partnerType()).isNull();
            assertThat(row.dealerId()).isNull();
            assertThat(row.supplierId()).isNull();
            assertThat(row.memo()).isEqualTo("plain memo");
        });
    }

    @Test
    void transactionDetail_documentSettlementRowsStayDocumentTyped() {
        Company company = new Company();
        company.setCode("BBP");
        when(companyContextService.requireCurrentCompany()).thenReturn(company);

        JournalEntry settlementEntry = new JournalEntry();
        setField(settlementEntry, "id", 114L);
        settlementEntry.setReferenceNumber("SUP-SET-114");
        settlementEntry.setEntryDate(LocalDate.of(2026, 2, 20));
        settlementEntry.setStatus("POSTED");
        settlementEntry.getLines().add(line("CASH", "60.00", "0.00"));
        settlementEntry.getLines().add(line("AP", "0.00", "60.00"));

        RawMaterialPurchase purchase = new RawMaterialPurchase();
        setField(purchase, "id", 314L);
        purchase.setCompany(company);

        PartnerSettlementAllocation allocation = new PartnerSettlementAllocation();
        setField(allocation, "id", 414L);
        allocation.setCompany(company);
        allocation.setPartnerType(PartnerType.SUPPLIER);
        allocation.setJournalEntry(settlementEntry);
        allocation.setPurchase(purchase);
        allocation.setAllocationAmount(new BigDecimal("60.00"));
        allocation.setMemo("  settle oldest purchase  ");
        allocation.setIdempotencyKey("settlement-414");

        when(journalEntryRepository.findByCompanyAndId(company, 114L)).thenReturn(Optional.of(settlementEntry));
        when(settlementAllocationRepository.findByCompanyAndJournalEntryOrderByCreatedAtAsc(company, settlementEntry))
                .thenReturn(List.of(allocation));
        when(invoiceRepository.findByCompanyAndJournalEntry(company, settlementEntry)).thenReturn(Optional.empty());
        when(rawMaterialPurchaseRepository.findByCompanyAndJournalEntry(company, settlementEntry)).thenReturn(Optional.empty());
        when(accountingEventRepository.findByJournalEntryIdOrderByEventTimestampAsc(114L)).thenReturn(List.of());

        AccountingTransactionAuditDetailDto detail = service.transactionDetail(114L);

        assertThat(detail.settlementAllocations()).hasSize(1);
        AccountingTransactionAuditDetailDto.SettlementAllocation row = detail.settlementAllocations().getFirst();
        assertThat(row.applicationType()).isEqualTo(SettlementAllocationApplication.DOCUMENT.name());
        assertThat(row.memo()).isEqualTo("settle oldest purchase");
        assertThat(row.purchaseId()).isEqualTo(314L);
    }

    @Test
    void helperMethods_coverModuleClassificationConsistencyAndNullGuards() {
        JournalEntry entry = new JournalEntry();
        setField(entry, "id", 777L);
        entry.setReferenceNumber("MISC-777");
        entry.setStatus("POSTED");

        Object consistency = ReflectionTestUtils.invokeMethod(
                service,
                "assessConsistency",
                entry,
                List.of(),
                new BigDecimal("10.00"),
                new BigDecimal("5.00"));

        assertThat((String) ReflectionTestUtils.invokeMethod(consistency, "status")).isEqualTo("ERROR");
        assertThat((String) ReflectionTestUtils.invokeMethod(service, "deriveModule", "GENERAL_JOURNAL", "MISC-777"))
                .isEqualTo("ACCOUNTING");
        assertThat((Object) ReflectionTestUtils.invokeMethod(service, "resolveDrivingDocument", null, null, List.of()))
                .isNull();

        when(rawMaterialPurchaseRepository.findByCompanyAndJournalEntry_IdIn(any(), eq(List.of()))).thenReturn(List.of());
        @SuppressWarnings("unchecked")
        Map<Long, RawMaterialPurchase> purchasesByJournal = ReflectionTestUtils.invokeMethod(
                service,
                "findPurchasesByJournalEntryIds",
                new Company(),
                List.of());
        assertThat(purchasesByJournal).isEmpty();
    }

    @Test
    void appendSettlementReferences_usesInvoiceAndPurchaseQueries() {
        Company company = new Company();
        company.setCode("BBP");

        Invoice invoice = new Invoice();
        setField(invoice, "id", 888L);
        invoice.setCompany(company);
        invoice.setInvoiceNumber("INV-888");
        invoice.setStatus("ISSUED");

        RawMaterialPurchase purchase = new RawMaterialPurchase();
        setField(purchase, "id", 889L);
        purchase.setCompany(company);
        purchase.setInvoiceNumber("PINV-889");
        purchase.setStatus("POSTED");

        PartnerSettlementAllocation allocation = new PartnerSettlementAllocation();
        setField(allocation, "id", 890L);
        allocation.setCompany(company);
        allocation.setInvoice(invoice);
        allocation.setPurchase(purchase);
        allocation.setIdempotencyKey("settlement-890");

        when(settlementAllocationRepository.findByCompanyAndInvoiceOrderByCreatedAtDesc(company, invoice)).thenReturn(List.of(allocation));
        when(settlementAllocationRepository.findByCompanyAndPurchaseOrderByCreatedAtDesc(company, purchase)).thenReturn(List.of(allocation));

        List<LinkedBusinessReferenceDto> invoiceChain = new java.util.ArrayList<>();
        ReflectionTestUtils.invokeMethod(service, "appendSettlementReferences", invoiceChain, company, invoice, null);
        List<LinkedBusinessReferenceDto> purchaseChain = new java.util.ArrayList<>();
        ReflectionTestUtils.invokeMethod(service, "appendSettlementReferences", purchaseChain, company, null, purchase);

        assertThat(invoiceChain).extracting(LinkedBusinessReferenceDto::relationType).contains("SETTLEMENT");
        assertThat(purchaseChain).extracting(LinkedBusinessReferenceDto::relationType).contains("SETTLEMENT");
    }

    @Test
    void helperMethods_coverSettlementAppendGuardsAndReferenceChainFiltering() {
        Invoice invoice = new Invoice();
        List<LinkedBusinessReferenceDto> chain = new java.util.ArrayList<>();

        ReflectionTestUtils.invokeMethod(service, "appendSettlementReferences", chain, null, invoice, null);
        assertThat(chain).isEmpty();

        Company company = new Company();
        company.setCode("BBP");
        when(settlementAllocationRepository.findByCompanyAndInvoiceOrderByCreatedAtDesc(company, invoice)).thenReturn(null);

        ReflectionTestUtils.invokeMethod(service, "appendSettlementReferences", chain, company, invoice, null);
        assertThat(chain).isEmpty();

        JournalEntry entry = new JournalEntry();
        setField(entry, "id", 891L);
        entry.setReferenceNumber("GEN-891");
        entry.setStatus("POSTED");

        SalesOrder orderWithoutId = new SalesOrder();
        orderWithoutId.setCompany(company);
        orderWithoutId.setOrderNumber("SO-NO-ID");

        Invoice filteredInvoice = new Invoice();
        filteredInvoice.setCompany(company);
        filteredInvoice.setInvoiceNumber("INV-FILTERED");
        filteredInvoice.setStatus("ISSUED");
        filteredInvoice.setSalesOrder(orderWithoutId);

        when(packagingSlipRepository.findAllByCompanyAndSalesOrderId(company, null)).thenReturn(List.of());

        @SuppressWarnings("unchecked")
        List<LinkedBusinessReferenceDto> filteredChain = ReflectionTestUtils.invokeMethod(
                service,
                "buildReferenceChain",
                entry,
                filteredInvoice,
                null,
                List.of(),
                null);

        assertThat(filteredChain).extracting(LinkedBusinessReferenceDto::relationType)
                .containsExactly("ACCOUNTING_ENTRY");
    }

    @Test
    void deriveTransactionTypeAndModulePrefixes_coverFallbackBranches() {
        JournalEntry reversal = new JournalEntry();
        reversal.setReferenceNumber("REV-1");
        reversal.setReversalOf(new JournalEntry());
        JournalEntry reversedOriginal = new JournalEntry();
        reversedOriginal.setReferenceNumber("VOID-1");
        reversedOriginal.setReversalEntry(new JournalEntry());
        JournalEntry payroll = new JournalEntry();
        payroll.setReferenceNumber("PAY-1");
        JournalEntry inventory = new JournalEntry();
        inventory.setReferenceNumber("REVAL-1");
        JournalEntry supplierSettlement = new JournalEntry();
        supplierSettlement.setReferenceNumber("SUP-SET-1");
        JournalEntry general = new JournalEntry();
        general.setReferenceNumber("GEN-1");

        assertThat((String) ReflectionTestUtils.invokeMethod(service, "deriveTransactionType", reversal, null, null, List.of()))
                .isEqualTo("REVERSAL_ENTRY");
        assertThat((String) ReflectionTestUtils.invokeMethod(service, "deriveTransactionType", reversedOriginal, null, null, List.of()))
                .isEqualTo("REVERSED_ORIGINAL");
        assertThat((String) ReflectionTestUtils.invokeMethod(service, "deriveTransactionType", payroll, null, null, List.of()))
                .isEqualTo("PAYROLL_ENTRY");
        assertThat((String) ReflectionTestUtils.invokeMethod(service, "deriveTransactionType", inventory, null, null, List.of()))
                .isEqualTo("INVENTORY_ADJUSTMENT");
        assertThat((String) ReflectionTestUtils.invokeMethod(service, "deriveTransactionType", supplierSettlement, null, null, List.of()))
                .isEqualTo("SETTLEMENT_SUPPLIER");
        assertThat((String) ReflectionTestUtils.invokeMethod(service, "deriveTransactionType", general, null, null, List.of()))
                .isEqualTo("GENERAL_JOURNAL");

        @SuppressWarnings("unchecked")
        List<String> inventoryPrefixes = ReflectionTestUtils.invokeMethod(service, "moduleReferencePrefixes", "INVENTORY");
        @SuppressWarnings("unchecked")
        List<String> unknownPrefixes = ReflectionTestUtils.invokeMethod(service, "moduleReferencePrefixes", "UNKNOWN");

        assertThat(inventoryPrefixes).contains("REVAL", "WIP");
        assertThat(unknownPrefixes).isEmpty();
        assertThat((String) ReflectionTestUtils.invokeMethod(service, "deriveModule", "REVERSAL_ENTRY", "REV-1"))
                .isEqualTo("ADJUSTMENT");
    }

    @Test
    void deriveTransactionTypeAndConsistency_coverSettlementWarningsAndPartnerBranches() {
        JournalEntry dealerJournal = new JournalEntry();
        dealerJournal.setReferenceNumber("GEN-DEALER");
        dealerJournal.setDealer(new Dealer());
        JournalEntry supplierJournal = new JournalEntry();
        supplierJournal.setReferenceNumber("GEN-SUPPLIER");
        supplierJournal.setSupplier(new Supplier());
        JournalEntry mixedSettlement = new JournalEntry();
        mixedSettlement.setReferenceNumber("SET-900");
        mixedSettlement.setStatus("POSTED");

        PartnerSettlementAllocation dealerAllocation = new PartnerSettlementAllocation();
        dealerAllocation.setPartnerType(PartnerType.DEALER);
        PartnerSettlementAllocation supplierAllocation = new PartnerSettlementAllocation();
        supplierAllocation.setPartnerType(PartnerType.SUPPLIER);

        assertThat((String) ReflectionTestUtils.invokeMethod(service, "deriveTransactionType", dealerJournal, null, null, List.of()))
                .isEqualTo("DEALER_JOURNAL");
        assertThat((String) ReflectionTestUtils.invokeMethod(service, "deriveTransactionType", supplierJournal, null, null, List.of()))
                .isEqualTo("SUPPLIER_JOURNAL");
        assertThat((String) ReflectionTestUtils.invokeMethod(service, "deriveTransactionType", mixedSettlement, null, null, List.of(dealerAllocation, supplierAllocation)))
                .isEqualTo("SETTLEMENT_MIXED");
        assertThat((String) ReflectionTestUtils.invokeMethod(service, "deriveModule", "PAYROLL_ENTRY", "PAY-1"))
                .isEqualTo("PAYROLL");
        assertThat((String) ReflectionTestUtils.invokeMethod(service, "deriveModule", "INVENTORY_ADJUSTMENT", "REVAL-1"))
                .isEqualTo("INVENTORY");

        Object warning = ReflectionTestUtils.invokeMethod(service, "assessConsistency", mixedSettlement, List.of(), BigDecimal.TEN, BigDecimal.TEN);
        assertThat((String) ReflectionTestUtils.invokeMethod(warning, "status")).isEqualTo("WARNING");
    }

    @Test
    void helperMethods_coverModulePrefixAndClassificationBranches() {
        @SuppressWarnings("unchecked")
        List<String> salesPrefixes = ReflectionTestUtils.invokeMethod(service, "moduleReferencePrefixes", "SALES");
        @SuppressWarnings("unchecked")
        List<String> purchasingPrefixes = ReflectionTestUtils.invokeMethod(service, "moduleReferencePrefixes", "PURCHASING");
        @SuppressWarnings("unchecked")
        List<String> settlementPrefixes = ReflectionTestUtils.invokeMethod(service, "moduleReferencePrefixes", "SETTLEMENT");
        @SuppressWarnings("unchecked")
        List<String> payrollPrefixes = ReflectionTestUtils.invokeMethod(service, "moduleReferencePrefixes", "PAYROLL");
        @SuppressWarnings("unchecked")
        List<String> reversalPrefixes = ReflectionTestUtils.invokeMethod(service, "moduleReferencePrefixes", "REVERSAL");

        assertThat(salesPrefixes).contains("INV", "CRN", "SR");
        assertThat(purchasingPrefixes).contains("RMP", "DBN", "PUR", "GRN");
        assertThat(settlementPrefixes).contains("SET", "RCPT", "SUP-", "DEALER-SETTLEMENT");
        assertThat(payrollPrefixes).contains("PAY", "PRL", "SAL");
        assertThat(reversalPrefixes).contains("REV", "VOID");

        JournalEntry reversal = new JournalEntry();
        reversal.setReferenceNumber("REV-1");
        reversal.setReversalOf(new JournalEntry());
        JournalEntry reversedOriginal = new JournalEntry();
        reversedOriginal.setReferenceNumber("VOID-1");
        reversedOriginal.setReversalEntry(new JournalEntry());
        JournalEntry supplierPrefixed = new JournalEntry();
        supplierPrefixed.setReferenceNumber("SUP-SET-1");
        JournalEntry dealerPrefixed = new JournalEntry();
        dealerPrefixed.setReferenceNumber("SET-1");
        JournalEntry payroll = new JournalEntry();
        payroll.setReferenceNumber("PAY-1");

        assertThat((String) ReflectionTestUtils.invokeMethod(service, "deriveTransactionType", reversal, null, null, List.of()))
                .isEqualTo("REVERSAL_ENTRY");
        assertThat((String) ReflectionTestUtils.invokeMethod(service, "deriveTransactionType", reversedOriginal, null, null, List.of()))
                .isEqualTo("REVERSED_ORIGINAL");
        assertThat((String) ReflectionTestUtils.invokeMethod(service, "deriveTransactionType", supplierPrefixed, null, null, List.of()))
                .isEqualTo("SETTLEMENT_SUPPLIER");
        assertThat((String) ReflectionTestUtils.invokeMethod(service, "deriveTransactionType", dealerPrefixed, null, null, List.of()))
                .isEqualTo("SETTLEMENT_DEALER");
        assertThat((String) ReflectionTestUtils.invokeMethod(service, "deriveTransactionType", payroll, null, null, List.of()))
                .isEqualTo("PAYROLL_ENTRY");

        assertThat((String) ReflectionTestUtils.invokeMethod(service, "deriveModule", null, "INV-1")).isEqualTo("SALES");
        assertThat((String) ReflectionTestUtils.invokeMethod(service, "deriveModule", null, "RMP-1")).isEqualTo("PURCHASING");
        assertThat((String) ReflectionTestUtils.invokeMethod(service, "deriveModule", null, "SUP-1")).isEqualTo("SETTLEMENT");
        assertThat((String) ReflectionTestUtils.invokeMethod(service, "deriveModule", null, "SETTLEMENT-1")).isEqualTo("SETTLEMENT");
        assertThat((String) ReflectionTestUtils.invokeMethod(service, "deriveModule", null, "GEN-1")).isEqualTo("ACCOUNTING");
    }

    @Test
    void helperMethods_coverDrivingDocumentReferenceChainAndConsistencyErrors() {
        Company scopedCompany = new Company();
        scopedCompany.setCode("BBP");

        JournalEntry entry = new JournalEntry();
        setField(entry, "id", 999L);
        entry.setReferenceNumber("SETTLEMENT-999");
        entry.setStatus("VOIDED");

        SalesOrder order = new SalesOrder();
        ReflectionTestUtils.setField(order, "id", 1001L);
        order.setCompany(scopedCompany);
        order.setOrderNumber("SO-1001");

        PackagingSlip slip = new PackagingSlip();
        ReflectionTestUtils.setField(slip, "id", 1002L);
        slip.setSalesOrder(order);
        slip.setSlipNumber("PS-1002");
        slip.setStatus("DISPATCHED");
        when(packagingSlipRepository.findAllByCompanyAndSalesOrderId(scopedCompany, 1001L)).thenReturn(List.of(slip));

        Invoice invoice = new Invoice();
        ReflectionTestUtils.setField(invoice, "id", 1003L);
        invoice.setCompany(scopedCompany);
        invoice.setSalesOrder(order);
        invoice.setInvoiceNumber("INV-1003");
        invoice.setStatus("ISSUED");

        RawMaterialPurchase purchase = new RawMaterialPurchase();
        ReflectionTestUtils.setField(purchase, "id", 1004L);
        purchase.setCompany(scopedCompany);
        purchase.setInvoiceNumber("PINV-1004");
        purchase.setStatus("POSTED");

        PartnerSettlementAllocation invoiceAllocation = new PartnerSettlementAllocation();
        ReflectionTestUtils.setField(invoiceAllocation, "id", 1005L);
        invoiceAllocation.setInvoice(invoice);
        invoiceAllocation.setIdempotencyKey("settlement-invoice");

        PartnerSettlementAllocation purchaseAllocation = new PartnerSettlementAllocation();
        ReflectionTestUtils.setField(purchaseAllocation, "id", 1006L);
        purchaseAllocation.setPurchase(purchase);
        purchaseAllocation.setIdempotencyKey("settlement-purchase");

        when(settlementAllocationRepository.findByCompanyAndInvoiceOrderByCreatedAtDesc(scopedCompany, invoice))
                .thenReturn(List.of(invoiceAllocation));
        when(settlementAllocationRepository.findByCompanyAndPurchaseOrderByCreatedAtDesc(scopedCompany, purchase))
                .thenReturn(List.of(purchaseAllocation));

        LinkedBusinessReferenceDto invoiceDrivingDocument = ReflectionTestUtils.invokeMethod(service, "resolveDrivingDocument", invoice, null, List.of());
        LinkedBusinessReferenceDto purchaseDrivingDocument = ReflectionTestUtils.invokeMethod(service, "resolveDrivingDocument", null, purchase, List.of());
        LinkedBusinessReferenceDto allocationDrivingDocument = ReflectionTestUtils.invokeMethod(service, "resolveDrivingDocument", null, null, List.of(invoiceAllocation));
        LinkedBusinessReferenceDto purchaseAllocationDrivingDocument = ReflectionTestUtils.invokeMethod(service, "resolveDrivingDocument", null, null, List.of(purchaseAllocation));

        assertThat(invoiceDrivingDocument.documentType()).isEqualTo("INVOICE");
        assertThat(purchaseDrivingDocument.documentType()).isEqualTo("PURCHASE_INVOICE");
        assertThat(allocationDrivingDocument.documentType()).isEqualTo("INVOICE");
        assertThat(purchaseAllocationDrivingDocument.documentType()).isEqualTo("PURCHASE_INVOICE");

        @SuppressWarnings("unchecked")
        List<LinkedBusinessReferenceDto> invoiceChain = ReflectionTestUtils.invokeMethod(
                service,
                "buildReferenceChain",
                entry,
                invoice,
                null,
                List.of(invoiceAllocation),
                invoiceDrivingDocument);
        @SuppressWarnings("unchecked")
        List<LinkedBusinessReferenceDto> purchaseChain = ReflectionTestUtils.invokeMethod(
                service,
                "buildReferenceChain",
                entry,
                null,
                purchase,
                List.of(purchaseAllocation),
                purchaseDrivingDocument);

        assertThat(invoiceChain).extracting(LinkedBusinessReferenceDto::relationType)
                .contains("DRIVING_DOCUMENT", "SOURCE_ORDER", "DISPATCH", "SETTLEMENT", "ACCOUNTING_ENTRY");
        assertThat(purchaseChain).extracting(LinkedBusinessReferenceDto::relationType)
                .contains("DRIVING_DOCUMENT", "SETTLEMENT", "ACCOUNTING_ENTRY");

        Object reversedError = ReflectionTestUtils.invokeMethod(service, "assessConsistency", entry, List.of(), BigDecimal.ONE, BigDecimal.ONE);
        assertThat((String) ReflectionTestUtils.invokeMethod(reversedError, "status")).isEqualTo("ERROR");

        entry.setStatus("REVERSED");
        Object stillError = ReflectionTestUtils.invokeMethod(service, "assessConsistency", entry, List.of(), BigDecimal.TEN, BigDecimal.ONE);
        assertThat((String) ReflectionTestUtils.invokeMethod(stillError, "status")).isEqualTo("ERROR");
    }

    @Test
    void specificationHelpers_coverBlankReferenceAndModulePrefixBranches() {
        Class<?> coreClass = service.getClass().getSuperclass();
        @SuppressWarnings("rawtypes")
        jakarta.persistence.criteria.Root root = org.mockito.Mockito.mock(jakarta.persistence.criteria.Root.class);
        @SuppressWarnings("rawtypes")
        jakarta.persistence.criteria.CriteriaQuery query = org.mockito.Mockito.mock(jakarta.persistence.criteria.CriteriaQuery.class);
        jakarta.persistence.criteria.CriteriaBuilder cb = org.mockito.Mockito.mock(jakarta.persistence.criteria.CriteriaBuilder.class);
        @SuppressWarnings("rawtypes")
        jakarta.persistence.criteria.Path path = org.mockito.Mockito.mock(jakarta.persistence.criteria.Path.class);
        @SuppressWarnings("rawtypes")
        jakarta.persistence.criteria.Expression upper = org.mockito.Mockito.mock(jakarta.persistence.criteria.Expression.class);
        jakarta.persistence.criteria.Predicate predicate = org.mockito.Mockito.mock(jakarta.persistence.criteria.Predicate.class);

        when(root.get(any(String.class))).thenReturn((jakarta.persistence.criteria.Path) path);
        when(cb.upper(path)).thenReturn(upper);
        org.mockito.Mockito.lenient().when(cb.equal(any(), any())).thenReturn(predicate);
        org.mockito.Mockito.lenient().when(cb.like(org.mockito.ArgumentMatchers.<jakarta.persistence.criteria.Expression<String>>any(), any(String.class))).thenReturn(predicate);
        when(cb.conjunction()).thenReturn(predicate);
        when(cb.or(any(jakarta.persistence.criteria.Predicate[].class))).thenReturn(predicate);

        try {
            java.lang.reflect.Method byStatusMethod = coreClass.getDeclaredMethod("byStatus", String.class);
            java.lang.reflect.Method byReferenceMethod = coreClass.getDeclaredMethod("byReference", String.class);
            java.lang.reflect.Method byModuleMethod = coreClass.getDeclaredMethod("byModule", String.class);
            byStatusMethod.setAccessible(true);
            byReferenceMethod.setAccessible(true);
            byModuleMethod.setAccessible(true);

            @SuppressWarnings("unchecked")
            Specification<JournalEntry> blankStatus = (Specification<JournalEntry>) byStatusMethod.invoke(service, " ");
            @SuppressWarnings("unchecked")
            Specification<JournalEntry> populatedStatus = (Specification<JournalEntry>) byStatusMethod.invoke(service, " posted ");
            @SuppressWarnings("unchecked")
            Specification<JournalEntry> blankReference = (Specification<JournalEntry>) byReferenceMethod.invoke(service, new Object[]{null});
            @SuppressWarnings("unchecked")
            Specification<JournalEntry> populatedReference = (Specification<JournalEntry>) byReferenceMethod.invoke(service, "inv");
            @SuppressWarnings("unchecked")
            Specification<JournalEntry> blankModule = (Specification<JournalEntry>) byModuleMethod.invoke(service, "  ");
            @SuppressWarnings("unchecked")
            Specification<JournalEntry> unknownModule = (Specification<JournalEntry>) byModuleMethod.invoke(service, "unknown");
            @SuppressWarnings("unchecked")
            Specification<JournalEntry> salesModule = (Specification<JournalEntry>) byModuleMethod.invoke(service, "sales");

            blankStatus.toPredicate(root, query, cb);
            populatedStatus.toPredicate(root, query, cb);
            blankReference.toPredicate(root, query, cb);
            populatedReference.toPredicate(root, query, cb);
            blankModule.toPredicate(root, query, cb);
            unknownModule.toPredicate(root, query, cb);
            salesModule.toPredicate(root, query, cb);
            org.mockito.Mockito.verify(cb, org.mockito.Mockito.atLeast(3)).conjunction();
            org.mockito.Mockito.verify(cb, org.mockito.Mockito.atLeastOnce())
                    .like(org.mockito.ArgumentMatchers.<jakarta.persistence.criteria.Expression<String>>any(), any(String.class));
            org.mockito.Mockito.verify(cb, org.mockito.Mockito.atLeastOnce()).or(any(jakarta.persistence.criteria.Predicate[].class));
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException(ex);
        }
    }

    private static JournalLine line(String accountCode, String debitAmount, String creditAmount) {
        Account account = new Account();
        account.setCode(accountCode);

        JournalLine line = new JournalLine();
        line.setAccount(account);
        line.setDebit(new BigDecimal(debitAmount));
        line.setCredit(new BigDecimal(creditAmount));
        return line;
    }

    private static void setField(Object target, String fieldName, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException(ex);
        }
    }

    private static JournalLineRepository.JournalEntryLineTotals totals(Long journalEntryId, String totalDebit, String totalCredit) {
        return new JournalLineRepository.JournalEntryLineTotals() {
            @Override
            public Long getJournalEntryId() {
                return journalEntryId;
            }

            @Override
            public BigDecimal getTotalDebit() {
                return new BigDecimal(totalDebit);
            }

            @Override
            public BigDecimal getTotalCredit() {
                return new BigDecimal(totalCredit);
            }
        };
    }
}
