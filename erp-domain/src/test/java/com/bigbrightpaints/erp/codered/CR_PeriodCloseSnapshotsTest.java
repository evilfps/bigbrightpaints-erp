package com.bigbrightpaints.erp.codered;

import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.core.util.CompanyTime;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriod;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountingPeriodCloseRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingPeriodService;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingService;
import com.bigbrightpaints.erp.modules.accounting.service.TemporalBalanceService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;
import com.bigbrightpaints.erp.modules.reports.dto.InventoryValuationDto;
import com.bigbrightpaints.erp.modules.reports.service.ReportService;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
import com.bigbrightpaints.erp.test.support.TestDateUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CR_PeriodCloseSnapshotsTest extends AbstractIntegrationTest {

    @Autowired private AccountingPeriodService accountingPeriodService;
    @Autowired private AccountingService accountingService;
    @Autowired private TemporalBalanceService temporalBalanceService;
    @Autowired private ReportService reportService;
    @Autowired private AccountRepository accountRepository;
    @Autowired private RawMaterialRepository rawMaterialRepository;
    @Autowired private RawMaterialBatchRepository rawMaterialBatchRepository;

    @AfterEach
    void clearCompanyContext() {
        CompanyContextHolder.clear();
    }

    @Test
    void trialBalanceAsOf_usesSnapshotForClosedPeriod() {
        String companyCode = "CR-SNAP-GL-" + System.nanoTime();
        Company company = dataSeeder.ensureCompany(companyCode, companyCode + " Ltd");
        CompanyContextHolder.setCompanyId(companyCode);
        try {
            LocalDate today = TestDateUtils.safeDate(company);
            LocalDate periodDate = today.minusMonths(1);
            AccountingPeriod period = accountingPeriodService.ensurePeriod(company, periodDate);
            Account cash = ensureAccount(company, "CASH-SNAP", "Cash", AccountType.ASSET);
            Account revenue = ensureAccount(company, "REV-SNAP", "Revenue", AccountType.REVENUE);

            postJournal(period.getEndDate().minusDays(1), List.of(
                    line(cash.getId(), new BigDecimal("100.00"), BigDecimal.ZERO),
                    line(revenue.getId(), BigDecimal.ZERO, new BigDecimal("100.00"))
            ));

            accountingPeriodService.closePeriod(period.getId(), new AccountingPeriodCloseRequest(true, "snapshot close"));

            TemporalBalanceService.TrialBalanceSnapshot before =
                    temporalBalanceService.getTrialBalanceAsOf(period.getEndDate());

            postJournal(period.getEndDate().plusDays(1), List.of(
                    line(cash.getId(), new BigDecimal("50.00"), BigDecimal.ZERO),
                    line(revenue.getId(), BigDecimal.ZERO, new BigDecimal("50.00"))
            ));

            TemporalBalanceService.TrialBalanceSnapshot after =
                    temporalBalanceService.getTrialBalanceAsOf(period.getEndDate());

            assertThat(after.totalDebits()).isEqualByComparingTo(before.totalDebits());
            assertThat(after.totalCredits()).isEqualByComparingTo(before.totalCredits());
        } finally {
            CompanyContextHolder.clear();
        }
    }

    @Test
    void inventoryValuationAsOf_usesSnapshotForClosedPeriod() {
        String companyCode = "CR-SNAP-INV-" + System.nanoTime();
        Company company = dataSeeder.ensureCompany(companyCode, companyCode + " Ltd");
        CompanyContextHolder.setCompanyId(companyCode);
        try {
            LocalDate today = TestDateUtils.safeDate(company);
            LocalDate periodDate = today.minusMonths(1);
            AccountingPeriod period = accountingPeriodService.ensurePeriod(company, periodDate);

            RawMaterial material = new RawMaterial();
            material.setCompany(company);
            material.setName("Resin");
            material.setSku("RM-SNAP-" + System.nanoTime());
            material.setUnitType("KG");
            material.setReorderLevel(new BigDecimal("5"));
            material.setCurrentStock(new BigDecimal("10"));
            material = rawMaterialRepository.save(material);

            RawMaterialBatch batch = new RawMaterialBatch();
            batch.setRawMaterial(material);
            batch.setBatchCode("BATCH-1");
            batch.setQuantity(new BigDecimal("10"));
            batch.setUnit("KG");
            batch.setCostPerUnit(new BigDecimal("5"));
            batch.setReceivedAt(CompanyTime.now(company));
            rawMaterialBatchRepository.save(batch);

            accountingPeriodService.closePeriod(period.getId(), new AccountingPeriodCloseRequest(true, "snapshot close"));

            InventoryValuationDto before = reportService.inventoryValuationAsOf(period.getEndDate());

            material.setCurrentStock(new BigDecimal("20"));
            material = rawMaterialRepository.save(material);
            RawMaterialBatch batch2 = new RawMaterialBatch();
            batch2.setRawMaterial(material);
            batch2.setBatchCode("BATCH-2");
            batch2.setQuantity(new BigDecimal("10"));
            batch2.setUnit("KG");
            batch2.setCostPerUnit(new BigDecimal("8"));
            batch2.setReceivedAt(CompanyTime.now(company));
            rawMaterialBatchRepository.save(batch2);

            InventoryValuationDto after = reportService.inventoryValuationAsOf(period.getEndDate());

            assertThat(after.totalValue()).isEqualByComparingTo(before.totalValue());
        } finally {
            CompanyContextHolder.clear();
        }
    }

    private Long postJournal(LocalDate entryDate, List<JournalEntryRequest.JournalLineRequest> lines) {
        JournalEntryRequest request = new JournalEntryRequest(
                "SNAP-TEST-" + System.nanoTime(),
                entryDate,
                "CODE-RED snapshot",
                null,
                null,
                Boolean.FALSE,
                lines
        );
        return accountingService.createJournalEntry(request).id();
    }

    private JournalEntryRequest.JournalLineRequest line(Long accountId, BigDecimal debit, BigDecimal credit) {
        return new JournalEntryRequest.JournalLineRequest(accountId, "line", debit, credit);
    }

    private Account ensureAccount(Company company, String code, String name, AccountType type) {
        return accountRepository.findByCompanyAndCodeIgnoreCase(company, code)
                .orElseGet(() -> {
                    Account account = new Account();
                    account.setCompany(company);
                    account.setCode(code);
                    account.setName(name);
                    account.setType(type);
                    return accountRepository.save(account);
                });
    }
}
