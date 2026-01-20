package com.bigbrightpaints.erp.regression;

import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovement;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryReference;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovement;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.service.OpeningStockImportService;
import com.bigbrightpaints.erp.modules.reports.dto.ReconciliationSummaryDto;
import com.bigbrightpaints.erp.modules.reports.service.ReportService;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Regression: Opening stock import posts GL and links movements")
class OpeningStockPostingRegressionIT extends AbstractIntegrationTest {

    private static final String COMPANY_CODE = "LF-021";

    @Autowired
    private CompanyRepository companyRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private OpeningStockImportService openingStockImportService;

    @Autowired
    private RawMaterialMovementRepository rawMaterialMovementRepository;

    @Autowired
    private InventoryMovementRepository inventoryMovementRepository;

    @Autowired
    private JournalEntryRepository journalEntryRepository;

    @Autowired
    private ReportService reportService;

    private Company company;
    private Account inventoryAccount;

    @BeforeEach
    void setUp() {
        company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE)
                .orElseGet(() -> {
                    Company created = new Company();
                    created.setCode(COMPANY_CODE);
                    created.setName("LF-021 Opening Stock");
                    created.setTimezone("UTC");
                    return companyRepository.save(created);
                });
        inventoryAccount = ensureAccount(company, "INV-LF021", "Inventory", AccountType.ASSET);
        ensureAccount(company, "COGS-LF021", "COGS", AccountType.COGS);
        ensureAccount(company, "REV-LF021", "Revenue", AccountType.REVENUE);
        ensureAccount(company, "GST-LF021", "GST Output", AccountType.LIABILITY);

        company.setDefaultInventoryAccountId(inventoryAccount.getId());
        company.setDefaultCogsAccountId(accountRepository.findByCompanyAndCodeIgnoreCase(company, "COGS-LF021").orElseThrow().getId());
        company.setDefaultRevenueAccountId(accountRepository.findByCompanyAndCodeIgnoreCase(company, "REV-LF021").orElseThrow().getId());
        company.setDefaultTaxAccountId(accountRepository.findByCompanyAndCodeIgnoreCase(company, "GST-LF021").orElseThrow().getId());
        companyRepository.save(company);

        CompanyContextHolder.setCompanyId(COMPANY_CODE);
    }

    @AfterEach
    void tearDown() {
        CompanyContextHolder.clear();
    }

    @Test
    void openingStockImportCreatesJournalAndReconciles() {
        String csv = String.join("\n",
                "type,sku,name,unit,unit_type,batch_code,quantity,unit_cost,material_type,manufactured_at",
                "RAW_MATERIAL,RM-OPEN-1,Resin,KG,KG,RM-OPEN-B1,10,5.00,PRODUCTION,",
                "FINISHED_GOOD,FG-OPEN-1,Paint 1L,L,L,FG-OPEN-B1,5,12.50,,2026-01-10"
        );
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "opening.csv",
                "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));

        openingStockImportService.importOpeningStock(file);

        List<RawMaterialMovement> rmMovements = rawMaterialMovementRepository
                .findByReferenceTypeAndReferenceId(InventoryReference.OPENING_STOCK, "RM-OPEN-B1");
        assertThat(rmMovements).hasSize(1);
        assertThat(rmMovements.get(0).getJournalEntryId()).isNotNull();

        List<InventoryMovement> fgMovements = inventoryMovementRepository
                .findByReferenceTypeAndReferenceIdOrderByCreatedAtAsc(InventoryReference.OPENING_STOCK, "FG-OPEN-B1");
        assertThat(fgMovements).hasSize(1);
        assertThat(fgMovements.get(0).getJournalEntryId()).isNotNull();
        assertThat(fgMovements.get(0).getJournalEntryId()).isEqualTo(rmMovements.get(0).getJournalEntryId());
        assertThat(journalEntryRepository.findById(fgMovements.get(0).getJournalEntryId()).orElseThrow()
                .getReferenceNumber()).startsWith("OPEN-STOCK-");

        ReconciliationSummaryDto summary = reportService.inventoryReconciliation();
        assertThat(summary.variance().abs()).isLessThanOrEqualTo(new BigDecimal("0.01"));
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
