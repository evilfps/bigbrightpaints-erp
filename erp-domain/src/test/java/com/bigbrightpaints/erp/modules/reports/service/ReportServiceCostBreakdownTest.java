package com.bigbrightpaints.erp.modules.reports.service;

import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodSnapshotRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodTrialBalanceLineRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.DealerLedgerRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLineRepository;
import com.bigbrightpaints.erp.modules.accounting.service.DealerLedgerService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionLog;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionLogRepository;
import com.bigbrightpaints.erp.modules.factory.dto.CostBreakdownDto;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProduct;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReportServiceCostBreakdownTest {

    @Mock
    private CompanyContextService companyContextService;
    @Mock
    private AccountRepository accountRepository;
    @Mock
    private AccountingPeriodRepository accountingPeriodRepository;
    @Mock
    private AccountingPeriodSnapshotRepository snapshotRepository;
    @Mock
    private AccountingPeriodTrialBalanceLineRepository snapshotLineRepository;
    @Mock
    private DealerRepository dealerRepository;
    @Mock
    private DealerLedgerService dealerLedgerService;
    @Mock
    private DealerLedgerRepository dealerLedgerRepository;
    @Mock
    private JournalEntryRepository journalEntryRepository;
    @Mock
    private JournalLineRepository journalLineRepository;
    @Mock
    private InvoiceRepository invoiceRepository;
    @Mock
    private ProductionLogRepository productionLogRepository;
    @Mock
    private CompanyEntityLookup companyEntityLookup;
    @Mock
    private CompanyClock companyClock;
    @Mock
    private InventoryValuationService inventoryValuationService;

    private ReportService reportService;
    private Company company;

    @BeforeEach
    void setUp() {
        reportService = new ReportService(
                companyContextService,
                accountRepository,
                accountingPeriodRepository,
                snapshotRepository,
                snapshotLineRepository,
                dealerRepository,
                dealerLedgerService,
                dealerLedgerRepository,
                journalEntryRepository,
                journalLineRepository,
                invoiceRepository,
                productionLogRepository,
                companyEntityLookup,
                companyClock,
                inventoryValuationService
        );
        company = new Company();
        ReflectionTestUtils.setField(company, "id", 700L);
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
    }

    @Test
    void costBreakdown_sumsMaterialLaborAndOverheadCosts() {
        ProductionLog log = new ProductionLog();
        ReflectionTestUtils.setField(log, "id", 88L);
        log.setProductionCode("PROD-88");
        log.setBatchColour("BLUE");
        log.setMixedQuantity(new BigDecimal("10"));
        log.setMaterialCostTotal(new BigDecimal("120.00"));
        log.setLaborCostTotal(new BigDecimal("30.00"));
        log.setOverheadCostTotal(new BigDecimal("25.00"));
        log.setUnitCost(new BigDecimal("17.50"));
        log.setProducedAt(Instant.parse("2026-02-13T00:00:00Z"));

        ProductionProduct product = new ProductionProduct();
        product.setProductName("Primer Blue");
        log.setProduct(product);

        when(companyEntityLookup.requireProductionLog(company, 88L)).thenReturn(log);

        CostBreakdownDto dto = reportService.costBreakdown(88L);

        assertThat(dto.productionLogId()).isEqualTo(88L);
        assertThat(dto.productionCode()).isEqualTo("PROD-88");
        assertThat(dto.productName()).isEqualTo("Primer Blue");
        assertThat(dto.materialCostTotal()).isEqualByComparingTo("120.00");
        assertThat(dto.laborCostTotal()).isEqualByComparingTo("30.00");
        assertThat(dto.overheadCostTotal()).isEqualByComparingTo("25.00");
        assertThat(dto.totalCost()).isEqualByComparingTo("175.00");
        assertThat(dto.unitCost()).isEqualByComparingTo("17.50");
    }

    @Test
    void costBreakdown_handlesNullComponentsAndMissingProductName() {
        ProductionLog log = new ProductionLog();
        ReflectionTestUtils.setField(log, "id", 99L);
        log.setProductionCode("PROD-99");
        log.setBatchColour("WHITE");
        log.setMixedQuantity(new BigDecimal("5"));
        log.setMaterialCostTotal(null);
        log.setLaborCostTotal(new BigDecimal("7.00"));
        log.setOverheadCostTotal(null);
        log.setUnitCost(new BigDecimal("1.40"));
        log.setProducedAt(Instant.parse("2026-02-13T01:00:00Z"));
        log.setProduct(null);

        when(companyEntityLookup.requireProductionLog(company, 99L)).thenReturn(log);

        CostBreakdownDto dto = reportService.costBreakdown(99L);

        assertThat(dto.productName()).isEqualTo("Unknown");
        assertThat(dto.materialCostTotal()).isNull();
        assertThat(dto.laborCostTotal()).isEqualByComparingTo("7.00");
        assertThat(dto.overheadCostTotal()).isNull();
        assertThat(dto.totalCost()).isEqualByComparingTo("7.00");
        assertThat(dto.unitCost()).isEqualByComparingTo("1.40");
    }
}
