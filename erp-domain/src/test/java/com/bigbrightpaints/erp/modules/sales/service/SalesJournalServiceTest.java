package com.bigbrightpaints.erp.modules.sales.service;

import com.bigbrightpaints.erp.modules.accounting.service.AccountingFacade;
import com.bigbrightpaints.erp.modules.accounting.service.CompanyAccountingSettingsService;
import com.bigbrightpaints.erp.modules.accounting.service.CompanyDefaultAccountsService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.inventory.service.FinishedGoodsService;
import com.bigbrightpaints.erp.modules.inventory.service.FinishedGoodsService.FinishedGoodAccountingProfile;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProductRepository;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrder;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrderItem;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SalesJournalServiceTest {

    @Mock
    private FinishedGoodsService finishedGoodsService;
    @Mock
    private AccountingFacade accountingFacade;
    @Mock
    private ProductionProductRepository productionProductRepository;
    @Mock
    private CompanyDefaultAccountsService companyDefaultAccountsService;
    @Mock
    private CompanyAccountingSettingsService companyAccountingSettingsService;

    private SalesJournalService salesJournalService;

    @BeforeEach
    void setup() {
        salesJournalService = new SalesJournalService(
                finishedGoodsService,
                accountingFacade,
                productionProductRepository,
                companyDefaultAccountsService,
                companyAccountingSettingsService
        );
    }

    @Test
    void postSalesJournal_includesDiscountLinesForLineDiscounts() {
        Company company = new Company();
        company.setTimezone("UTC");
        Dealer dealer = new Dealer();
        ReflectionTestUtils.setField(dealer, "id", 42L);

        SalesOrder order = new SalesOrder();
        order.setCompany(company);
        order.setDealer(dealer);
        order.setOrderNumber("SO-1");
        order.setGstInclusive(false);

        SalesOrderItem item = new SalesOrderItem();
        item.setProductCode("SKU-1");
        item.setQuantity(new BigDecimal("2"));
        item.setUnitPrice(new BigDecimal("100.00"));
        item.setLineSubtotal(new BigDecimal("180.00"));
        item.setGstAmount(new BigDecimal("18.00"));
        order.getItems().add(item);

        FinishedGoodAccountingProfile profile = new FinishedGoodAccountingProfile(
                "SKU-1",
                null,
                null,
                10L,
                11L,
                12L
        );
        when(finishedGoodsService.accountingProfiles(List.of("SKU-1"))).thenReturn(Map.of("SKU-1", profile));
        when(companyAccountingSettingsService.requireTaxAccounts())
                .thenReturn(new CompanyAccountingSettingsService.TaxAccountConfiguration(null, 12L, null));

        ArgumentCaptor<Map<Long, BigDecimal>> revenueCaptor = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<Map<Long, BigDecimal>> taxCaptor = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<Map<Long, BigDecimal>> discountCaptor = ArgumentCaptor.forClass(Map.class);

        salesJournalService.postSalesJournal(
                order,
                new BigDecimal("198.00"),
                "INV-REF",
                LocalDate.of(2024, 4, 8),
                "Invoice INV-1");

        verify(accountingFacade).postSalesJournal(
                eq(42L),
                eq("SO-1"),
                eq(LocalDate.of(2024, 4, 8)),
                eq("Invoice INV-1"),
                revenueCaptor.capture(),
                taxCaptor.capture(),
                discountCaptor.capture(),
                eq(new BigDecimal("198.00")),
                eq("INV-REF"));

        Map<Long, BigDecimal> revenueLines = revenueCaptor.getValue();
        Map<Long, BigDecimal> taxLines = taxCaptor.getValue();
        Map<Long, BigDecimal> discountLines = discountCaptor.getValue();

        assertThat(revenueLines.get(10L)).isEqualByComparingTo("200.00");
        assertThat(taxLines.get(12L)).isEqualByComparingTo("18.00");
        assertThat(discountLines.get(11L)).isEqualByComparingTo("20.00");
    }
}
