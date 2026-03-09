package com.bigbrightpaints.erp.modules.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLine;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLineRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation;
import com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocationRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.PartnerType;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountingTransactionAuditDetailDto;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountingTransactionAuditListItemDto;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
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
