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
import com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocationRepository;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountingTransactionAuditDetailDto;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountingTransactionAuditListItemDto;
import com.bigbrightpaints.erp.modules.accounting.event.AccountingEvent;
import com.bigbrightpaints.erp.modules.accounting.event.AccountingEventRepository;
import com.bigbrightpaints.erp.modules.accounting.event.AccountingEventType;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchaseRepository;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.shared.dto.PageResponse;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

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
                rawMaterialPurchaseRepository
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
        when(rawMaterialPurchaseRepository.findByCompanyOrderByInvoiceDateDesc(company)).thenReturn(List.of());
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
        when(rawMaterialPurchaseRepository.findByCompanyOrderByInvoiceDateDesc(company)).thenReturn(List.of());
        when(accountingEventRepository.findByJournalEntryIdOrderByEventTimestampAsc(42L)).thenReturn(List.of(event));

        AccountingTransactionAuditDetailDto detail = service.transactionDetail(42L);

        assertThat(detail.journalEntryId()).isEqualTo(42L);
        assertThat(detail.module()).isEqualTo("SETTLEMENT");
        assertThat(detail.consistencyStatus()).isEqualTo("WARNING");
        assertThat(detail.consistencyNotes()).anyMatch(note -> note.contains("Settlement-like reference"));
        assertThat(detail.eventTrail()).hasSize(1);
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
