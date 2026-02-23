package com.bigbrightpaints.erp.truthsuite.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
import com.bigbrightpaints.erp.modules.accounting.event.AccountingEventRepository;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingAuditTrailService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchase;
import com.bigbrightpaints.erp.shared.dto.PageResponse;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

@ExtendWith(MockitoExtension.class)
@Tag("critical")
@Tag("reconciliation")
class TS_RuntimeAccountingAuditTrailServiceExecutableCoverageTest {

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
    private EntityManager entityManager;
    @Mock
    private TypedQuery<RawMaterialPurchase> rawMaterialPurchaseQuery;

    private AccountingAuditTrailService service;
    private Company company;

    @BeforeEach
    void setUp() {
        service = new AccountingAuditTrailService(
                companyContextService,
                journalEntryRepository,
                journalLineRepository,
                accountingEventRepository,
                settlementAllocationRepository,
                invoiceRepository,
                entityManager
        );
        company = new Company();
        company.setCode("TRUTH");
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
    }

    @Test
    void listTransactions_classifiesSupplierJournalAndTotals() {
        JournalEntry entry = new JournalEntry();
        setField(entry, "id", 71L);
        entry.setReferenceNumber("RMP-2026-0001");
        entry.setEntryDate(LocalDate.of(2026, 2, 11));
        entry.setStatus("POSTED");
        entry.setMemo("Purchase posting");

        Account inv = new Account();
        inv.setCode("RM-INVENTORY");
        inv.setType(AccountType.ASSET);
        JournalLine debit = new JournalLine();
        debit.setAccount(inv);
        debit.setDebit(new BigDecimal("1500.00"));
        debit.setCredit(BigDecimal.ZERO);

        Account ap = new Account();
        ap.setCode("AP");
        ap.setType(AccountType.LIABILITY);
        JournalLine credit = new JournalLine();
        credit.setAccount(ap);
        credit.setDebit(BigDecimal.ZERO);
        credit.setCredit(new BigDecimal("1500.00"));

        entry.getLines().add(debit);
        entry.getLines().add(credit);

        when(journalEntryRepository.findAll(any(org.springframework.data.jpa.domain.Specification.class), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(entry)));
        when(journalLineRepository.summarizeTotalsByCompanyAndJournalEntryIds(eq(company), eq(List.of(71L))))
                .thenReturn(List.of(totals(71L, "1500.00", "1500.00")));
        when(invoiceRepository.findByCompanyAndJournalEntry_IdIn(eq(company), eq(List.of(71L)))).thenReturn(List.of());
        when(entityManager.createQuery(any(String.class), eq(RawMaterialPurchase.class))).thenReturn(rawMaterialPurchaseQuery);
        when(rawMaterialPurchaseQuery.setParameter("company", company)).thenReturn(rawMaterialPurchaseQuery);
        when(rawMaterialPurchaseQuery.setParameter("journalEntryIds", List.of(71L))).thenReturn(rawMaterialPurchaseQuery);
        when(rawMaterialPurchaseQuery.getResultList()).thenReturn(List.of());
        when(settlementAllocationRepository.findByCompanyAndJournalEntry_IdIn(eq(company), eq(List.of(71L)))).thenReturn(List.of());

        PageResponse<AccountingTransactionAuditListItemDto> result =
                service.listTransactions(null, null, null, null, null, 0, 50);

        assertThat(result.content()).hasSize(1);
        AccountingTransactionAuditListItemDto row = result.content().getFirst();
        assertThat(row.module()).isEqualTo("PURCHASING");
        assertThat(row.transactionType()).isEqualTo("GENERAL_JOURNAL");
        assertThat(row.consistencyStatus()).isEqualTo("WARNING");
    }

    @Test
    void transactionDetail_marksSettlementReferenceWithoutAllocationsAsWarning() {
        JournalEntry entry = new JournalEntry();
        setField(entry, "id", 99L);
        entry.setReferenceNumber("SET-TRUTH-0001");
        entry.setEntryDate(LocalDate.of(2026, 2, 12));
        entry.setStatus("POSTED");

        Account ap = new Account();
        ap.setCode("AP");
        ap.setType(AccountType.LIABILITY);
        JournalLine debit = new JournalLine();
        debit.setAccount(ap);
        debit.setDebit(new BigDecimal("1000.00"));
        debit.setCredit(BigDecimal.ZERO);

        Account cash = new Account();
        cash.setCode("CASH");
        cash.setType(AccountType.ASSET);
        JournalLine credit = new JournalLine();
        credit.setAccount(cash);
        credit.setDebit(BigDecimal.ZERO);
        credit.setCredit(new BigDecimal("1000.00"));

        entry.getLines().add(debit);
        entry.getLines().add(credit);

        when(journalEntryRepository.findByCompanyAndId(company, 99L)).thenReturn(Optional.of(entry));
        when(settlementAllocationRepository.findByCompanyAndJournalEntryOrderByCreatedAtAsc(company, entry)).thenReturn(List.of());
        when(invoiceRepository.findByCompanyAndJournalEntry(company, entry)).thenReturn(Optional.empty());
        when(entityManager.createQuery(any(String.class), eq(RawMaterialPurchase.class))).thenReturn(rawMaterialPurchaseQuery);
        when(rawMaterialPurchaseQuery.setParameter("company", company)).thenReturn(rawMaterialPurchaseQuery);
        when(rawMaterialPurchaseQuery.setParameter("journalEntry", entry)).thenReturn(rawMaterialPurchaseQuery);
        when(rawMaterialPurchaseQuery.setMaxResults(1)).thenReturn(rawMaterialPurchaseQuery);
        when(rawMaterialPurchaseQuery.getResultList()).thenReturn(List.of());
        when(accountingEventRepository.findByJournalEntryIdOrderByEventTimestampAsc(99L)).thenReturn(List.of());

        AccountingTransactionAuditDetailDto detail = service.transactionDetail(99L);

        assertThat(detail.module()).isEqualTo("SETTLEMENT");
        assertThat(detail.consistencyStatus()).isEqualTo("WARNING");
        assertThat(detail.consistencyNotes()).anyMatch(note -> note.contains("Settlement-like reference"));
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

    private static JournalLineRepository.JournalEntryLineTotals totals(Long id, String debit, String credit) {
        return new JournalLineRepository.JournalEntryLineTotals() {
            @Override
            public Long getJournalEntryId() {
                return id;
            }

            @Override
            public BigDecimal getTotalDebit() {
                return new BigDecimal(debit);
            }

            @Override
            public BigDecimal getTotalCredit() {
                return new BigDecimal(credit);
            }
        };
    }
}
