package com.bigbrightpaints.erp.codered.support;

import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.invoice.domain.Invoice;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlip;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlipRepository;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRun;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

public final class CoderedDbAssertions {

    private CoderedDbAssertions() {
    }

    public static void assertBalancedJournal(JournalEntryRepository journalEntryRepository, Long journalId) {
        JournalEntry entry = journalEntryRepository.findById(journalId).orElseThrow();
        BigDecimal debit = entry.getLines().stream()
                .map(line -> Optional.ofNullable(line.getDebit()).orElse(BigDecimal.ZERO))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal credit = entry.getLines().stream()
                .map(line -> Optional.ofNullable(line.getCredit()).orElse(BigDecimal.ZERO))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(debit)
                .as("Journal %s balanced", entry.getReferenceNumber())
                .isEqualByComparingTo(credit);
    }

    public static void assertOneInvoicePerSlip(PackagingSlipRepository packagingSlipRepository,
                                               InvoiceRepository invoiceRepository,
                                               Company company,
                                               Long slipId) {
        PackagingSlip slip = packagingSlipRepository.findByIdAndCompany(slipId, company).orElseThrow();
        assertThat(slip.getInvoiceId()).as("Slip invoiceId").isNotNull();
        Invoice invoice = invoiceRepository.findByCompanyAndId(company, slip.getInvoiceId()).orElseThrow();
        assertThat(invoice.getId()).isEqualTo(slip.getInvoiceId());
        if (slip.getSlipNumber() != null) {
            List<Invoice> invoicesForOrder = invoiceRepository.findAllByCompanyAndSalesOrderId(company, slip.getSalesOrder().getId());
            long matchingNotes = invoicesForOrder.stream()
                    .filter(inv -> ("Dispatch " + slip.getSlipNumber()).equals(inv.getNotes()))
                    .count();
            assertThat(matchingNotes)
                    .as("Invoices for slip %s (by notes)", slip.getSlipNumber())
                    .isEqualTo(1);
        }
    }

    public static void assertNoNegativeInventory(JdbcTemplate jdbcTemplate, Long companyId) {
        Integer fgNegative = jdbcTemplate.queryForObject(
                "select count(*) from finished_goods where company_id = ? and (current_stock < 0 or reserved_stock < 0)",
                Integer.class,
                companyId
        );
        assertThat(fgNegative).as("finished_goods negative stock").isZero();

        Integer fgBatchNegative = jdbcTemplate.queryForObject(
                """
                select count(*)
                from finished_good_batches b
                join finished_goods fg on fg.id = b.finished_good_id
                where fg.company_id = ?
                  and (b.quantity_total < 0 or b.quantity_available < 0)
                """,
                Integer.class,
                companyId
        );
        assertThat(fgBatchNegative).as("finished_good_batches negative quantity").isZero();

        Integer rmNegative = jdbcTemplate.queryForObject(
                "select count(*) from raw_materials where company_id = ? and current_stock < 0",
                Integer.class,
                companyId
        );
        assertThat(rmNegative).as("raw_materials negative stock").isZero();

        Integer rmBatchNegative = jdbcTemplate.queryForObject(
                """
                select count(*)
                from raw_material_batches b
                join raw_materials rm on rm.id = b.raw_material_id
                where rm.company_id = ?
                  and b.quantity < 0
                """,
                Integer.class,
                companyId
        );
        assertThat(rmBatchNegative).as("raw_material_batches negative quantity").isZero();
    }

    public static void assertNoOrphanJournalEntries(JdbcTemplate jdbcTemplate, Long companyId) {
        Integer orphanEntries = jdbcTemplate.queryForObject(
                """
                select count(*)
                from journal_entries je
                where je.company_id = ?
                  and not exists (select 1 from journal_lines jl where jl.journal_entry_id = je.id)
                """,
                Integer.class,
                companyId
        );
        assertThat(orphanEntries).as("journal_entries with zero lines").isZero();
    }

    public static void assertSinglePayrollRun(JdbcTemplate jdbcTemplate,
                                              Long companyId,
                                              PayrollRun.RunType runType,
                                              java.time.LocalDate periodStart,
                                              java.time.LocalDate periodEnd) {
        Integer count = jdbcTemplate.queryForObject(
                """
                select count(*)
                from payroll_runs
                where company_id = ?
                  and run_type = ?
                  and period_start = ?
                  and period_end = ?
                """,
                Integer.class,
                companyId,
                runType.name(),
                periodStart,
                periodEnd
        );
        assertThat(count).as("single payroll run for %s %s..%s", runType, periodStart, periodEnd).isEqualTo(1);
    }

    public static void assertAuditLogRecordedForJournal(JdbcTemplate jdbcTemplate, Long journalEntryId) {
        boolean found = awaitCondition(Duration.ofSeconds(5), () -> {
            Integer count = jdbcTemplate.queryForObject(
                    """
                    select count(*)
                    from accounting_events ae
                    where ae.event_type = 'JOURNAL_ENTRY_POSTED'
                      and ae.journal_entry_id = ?
                    """,
                    Integer.class,
                    journalEntryId
            );
            return count != null && count > 0;
        });
        assertThat(found)
                .as("accounting event entry for journal %s", journalEntryId)
                .isTrue();
    }

    public static void assertRawMaterialMovementsLinkedToJournal(JdbcTemplate jdbcTemplate,
                                                                 Long companyId,
                                                                 String referenceType,
                                                                 String referenceId,
                                                                 Long journalEntryId) {
        Integer total = jdbcTemplate.queryForObject(
                """
                select count(*)
                from raw_material_movements rmm
                join raw_materials rm on rm.id = rmm.raw_material_id
                where rm.company_id = ?
                  and rmm.reference_type = ?
                  and rmm.reference_id = ?
                """,
                Integer.class,
                companyId,
                referenceType,
                referenceId
        );
        assertThat(total).as("raw material movements for %s/%s", referenceType, referenceId)
                .isNotNull()
                .isGreaterThan(0);

        Integer unlinked = jdbcTemplate.queryForObject(
                """
                select count(*)
                from raw_material_movements rmm
                join raw_materials rm on rm.id = rmm.raw_material_id
                where rm.company_id = ?
                  and rmm.reference_type = ?
                  and rmm.reference_id = ?
                  and (rmm.journal_entry_id is null or rmm.journal_entry_id <> ?)
                """,
                Integer.class,
                companyId,
                referenceType,
                referenceId,
                journalEntryId
        );
        assertThat(unlinked).as("raw material movements linked to journal %s", journalEntryId).isZero();
    }

    public static void assertDealerLedgerEntriesLinkedToJournal(JdbcTemplate jdbcTemplate,
                                                                Long companyId,
                                                                Long journalEntryId) {
        Integer count = jdbcTemplate.queryForObject(
                """
                select count(*)
                from dealer_ledger_entries dle
                where dle.company_id = ?
                  and dle.journal_entry_id = ?
                """,
                Integer.class,
                companyId,
                journalEntryId
        );
        assertThat(count).as("dealer ledger entries for journal %s", journalEntryId)
                .isNotNull()
                .isGreaterThan(0);
    }

    public static void assertSupplierLedgerEntriesLinkedToJournal(JdbcTemplate jdbcTemplate,
                                                                  Long companyId,
                                                                  Long journalEntryId) {
        Integer count = jdbcTemplate.queryForObject(
                """
                select count(*)
                from supplier_ledger_entries sle
                where sle.company_id = ?
                  and sle.journal_entry_id = ?
                """,
                Integer.class,
                companyId,
                journalEntryId
        );
        assertThat(count).as("supplier ledger entries for journal %s", journalEntryId)
                .isNotNull()
                .isGreaterThan(0);
    }

    private static boolean awaitCondition(Duration timeout, Supplier<Boolean> condition) {
        long deadline = System.nanoTime() + Math.max(1, timeout.toNanos());
        int spins = 0;
        while (System.nanoTime() < deadline) {
            if (Boolean.TRUE.equals(condition.get())) {
                return true;
            }
            if ((++spins & 0x3FF) == 0) {
                Thread.yield();
            } else {
                Thread.onSpinWait();
            }
        }
        return Boolean.TRUE.equals(condition.get());
    }
}
