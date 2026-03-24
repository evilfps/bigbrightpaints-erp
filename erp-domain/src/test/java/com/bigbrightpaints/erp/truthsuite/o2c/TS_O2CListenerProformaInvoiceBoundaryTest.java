package com.bigbrightpaints.erp.truthsuite.o2c;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.event.InventoryAccountingEventListener;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.dto.FinishedGoodRequest;
import com.bigbrightpaints.erp.modules.inventory.event.InventoryMovementEvent;
import com.bigbrightpaints.erp.modules.inventory.event.InventoryValuationChangedEvent;
import com.bigbrightpaints.erp.modules.inventory.service.FinishedGoodsService;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceRepository;
import com.bigbrightpaints.erp.modules.invoice.service.InvoiceService;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrand;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrandRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProduct;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProductRepository;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrder;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrderRepository;
import com.bigbrightpaints.erp.modules.sales.dto.SalesOrderItemRequest;
import com.bigbrightpaints.erp.modules.sales.dto.SalesOrderRequest;
import com.bigbrightpaints.erp.modules.sales.service.SalesService;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("critical")
@Tag("reconciliation")
@TestPropertySource(properties = {
        "erp.auto-approval.enabled=false",
        "erp.inventory.accounting.events.enabled=true"
})
class TS_O2CListenerProformaInvoiceBoundaryTest extends AbstractIntegrationTest {

    @Autowired private InventoryAccountingEventListener inventoryAccountingEventListener;
    @Autowired private CompanyRepository companyRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private JournalEntryRepository journalEntryRepository;
    @Autowired private DealerRepository dealerRepository;
    @Autowired private ProductionBrandRepository productionBrandRepository;
    @Autowired private ProductionProductRepository productionProductRepository;
    @Autowired private FinishedGoodsService finishedGoodsService;
    @Autowired private FinishedGoodRepository finishedGoodRepository;
    @Autowired private InventoryMovementRepository inventoryMovementRepository;
    @Autowired private InvoiceRepository invoiceRepository;
    @Autowired private InvoiceService invoiceService;
    @Autowired private SalesService salesService;
    @Autowired private SalesOrderRepository salesOrderRepository;

    @AfterEach
    void clearCompanyContext() {
        CompanyContextHolder.clear();
    }

    @Test
    void inventoryListenerSkipsSalesOrderAndPackagingSlipDispatchMovements() {
        Company company = bootstrapCompany("TS-LIST-" + shortId());
        Account inventory = ensureAccount(company, "INV", "Inventory", AccountType.ASSET);
        Account cogs = ensureAccount(company, "COGS", "Cost of Goods Sold", AccountType.COGS);
        int journalCountBefore = countJournalEntries(company);

        inventoryAccountingEventListener.onInventoryMovement(movementEvent(
                company,
                inventory,
                cogs,
                "SALES_ORDER-" + shortId(),
                com.bigbrightpaints.erp.modules.inventory.domain.InventoryReference.SALES_ORDER,
                501L));
        inventoryAccountingEventListener.onInventoryMovement(movementEvent(
                company,
                inventory,
                cogs,
                "PS-" + shortId(),
                "PACKAGING_SLIP",
                601L));

        assertThat(countJournalEntries(company)).isEqualTo(journalCountBefore);

        inventoryAccountingEventListener.onInventoryMovement(movementEvent(
                company,
                inventory,
                cogs,
                "INV-MOVE-CONTROL-" + shortId(),
                "MANUAL_TEST",
                701L));

        assertThat(countJournalEntries(company)).isEqualTo(journalCountBefore + 1);
    }

    @Test
    void proformaCreateAndUpdateRemainCommercialOnly() {
        CommercialFixture fixture = bootstrapCommercialFixture("TS-PROFORMA");
        activateCompany(fixture.company());
        int journalCountBefore = countJournalEntries(fixture.company());
        int movementCountBefore = countInventoryMovements(fixture.company());
        int invoiceCountBefore = countInvoices(fixture.company());

        var created = salesService.createOrder(orderRequest(
                fixture,
                new BigDecimal("5"),
                new BigDecimal("12.34"),
                "truthsuite proforma create"));

        SalesOrder createdOrder = salesOrderRepository.findById(created.id()).orElseThrow();
        assertThat(createdOrder.getStatus()).isEqualTo("PENDING_PRODUCTION");
        assertThat(countJournalEntries(fixture.company())).isEqualTo(journalCountBefore);
        assertThat(countInventoryMovements(fixture.company())).isEqualTo(movementCountBefore);
        assertThat(countInvoices(fixture.company())).isEqualTo(invoiceCountBefore);
        assertThat(invoiceRepository.findAllByCompanyAndSalesOrderId(fixture.company(), createdOrder.getId())).isEmpty();

        activateCompany(fixture.company());
        salesService.updateOrder(createdOrder.getId(), orderRequest(
                fixture,
                new BigDecimal("8"),
                new BigDecimal("12.34"),
                "truthsuite proforma update"));

        SalesOrder updatedOrder = salesOrderRepository.findById(createdOrder.getId()).orElseThrow();
        assertThat(updatedOrder.getStatus()).isEqualTo("PENDING_PRODUCTION");
        assertThat(updatedOrder.getTotalAmount()).isEqualByComparingTo(new BigDecimal("98.72"));
        assertThat(countJournalEntries(fixture.company())).isEqualTo(journalCountBefore);
        assertThat(countInventoryMovements(fixture.company())).isEqualTo(movementCountBefore);
        assertThat(countInvoices(fixture.company())).isEqualTo(invoiceCountBefore);
        assertThat(invoiceRepository.findAllByCompanyAndSalesOrderId(fixture.company(), createdOrder.getId())).isEmpty();
    }

    @Test
    void issueInvoiceForOrderFailsClosedWithoutDispatchCreatedInvoice() {
        CommercialFixture fixture = bootstrapCommercialFixture("TS-INVOICE");
        activateCompany(fixture.company());
        SalesOrder createdOrder = salesOrderRepository.findById(salesService.createOrder(orderRequest(
                fixture,
                new BigDecimal("3"),
                new BigDecimal("25.00"),
                "truthsuite invoice boundary" )).id()).orElseThrow();
        int journalCountBefore = countJournalEntries(fixture.company());
        int invoiceCountBefore = countInvoices(fixture.company());

        activateCompany(fixture.company());
        assertThatThrownBy(() -> invoiceService.issueInvoiceForOrder(createdOrder.getId()))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Dispatch confirmation is required before issuing an invoice");

        SalesOrder reloadedOrder = salesOrderRepository.findById(createdOrder.getId()).orElseThrow();
        assertThat(reloadedOrder.getFulfillmentInvoiceId()).isNull();
        assertThat(countJournalEntries(fixture.company())).isEqualTo(journalCountBefore);
        assertThat(countInvoices(fixture.company())).isEqualTo(invoiceCountBefore);
        assertThat(invoiceRepository.findAllByCompanyAndSalesOrderId(fixture.company(), createdOrder.getId())).isEmpty();
    }

    private CommercialFixture bootstrapCommercialFixture(String prefix) {
        Company company = bootstrapCompany(prefix + "-" + shortId());
        Map<String, Account> accounts = ensureCoreAccounts(company);
        Dealer dealer = ensureDealer(company, accounts.get("AR"), prefix + "-DEALER");
        FinishedGood finishedGood = ensureFinishedGoodWithCatalog(company, accounts, prefix + "-FG-" + shortId());
        return new CommercialFixture(company, dealer, finishedGood);
    }

    private Company bootstrapCompany(String companyCode) {
        dataSeeder.ensureCompany(companyCode, companyCode + " Ltd");
        CompanyContextHolder.setCompanyId(companyCode);
        Company company = companyRepository.findByCodeIgnoreCase(companyCode).orElseThrow();
        company.setTimezone("UTC");
        company.setBaseCurrency("INR");
        return companyRepository.save(company);
    }

    private void activateCompany(Company company) {
        CompanyContextHolder.setCompanyId(company.getCode());
    }

    private Map<String, Account> ensureCoreAccounts(Company company) {
        Account ar = ensureAccount(company, "AR", "Accounts Receivable", AccountType.ASSET);
        Account inv = ensureAccount(company, "INV", "Inventory", AccountType.ASSET);
        Account cogs = ensureAccount(company, "COGS", "Cost of Goods Sold", AccountType.COGS);
        Account rev = ensureAccount(company, "REV", "Revenue", AccountType.REVENUE);
        Account disc = ensureAccount(company, "DISC", "Discounts", AccountType.EXPENSE);
        Account gstOut = ensureAccount(company, "GST-OUT", "GST Output", AccountType.LIABILITY);
        Account gstIn = ensureAccount(company, "GST-IN", "GST Input", AccountType.ASSET);

        Company refreshed = companyRepository.findById(company.getId()).orElseThrow();
        refreshed.setDefaultInventoryAccountId(inv.getId());
        refreshed.setDefaultCogsAccountId(cogs.getId());
        refreshed.setDefaultRevenueAccountId(rev.getId());
        refreshed.setDefaultDiscountAccountId(disc.getId());
        refreshed.setDefaultTaxAccountId(gstOut.getId());
        refreshed.setGstInputTaxAccountId(gstIn.getId());
        refreshed.setGstOutputTaxAccountId(gstOut.getId());
        refreshed.setGstPayableAccountId(gstOut.getId());
        companyRepository.save(refreshed);

        return Map.of(
                "AR", ar,
                "INV", inv,
                "COGS", cogs,
                "REV", rev,
                "DISC", disc,
                "GST_OUT", gstOut,
                "GST_IN", gstIn
        );
    }

    private Account ensureAccount(Company company, String code, String name, AccountType type) {
        return accountRepository.findByCompanyAndCodeIgnoreCase(company, code)
                .orElseGet(() -> {
                    Account account = new Account();
                    account.setCompany(company);
                    account.setCode(code);
                    account.setName(name);
                    account.setType(type);
                    account.setActive(true);
                    account.setBalance(BigDecimal.ZERO);
                    return accountRepository.save(account);
                });
    }

    private Dealer ensureDealer(Company company, Account arAccount, String dealerCode) {
        return dealerRepository.findByCompanyAndCodeIgnoreCase(company, dealerCode)
                .orElseGet(() -> {
                    Dealer dealer = new Dealer();
                    dealer.setCompany(company);
                    dealer.setCode(dealerCode);
                    dealer.setName("Truthsuite Dealer " + dealerCode);
                    dealer.setStatus("ACTIVE");
                    dealer.setReceivableAccount(arAccount);
                    return dealerRepository.save(dealer);
                });
    }

    private FinishedGood ensureFinishedGoodWithCatalog(Company company,
                                                       Map<String, Account> accounts,
                                                       String sku) {
        activateCompany(company);
        FinishedGoodRequest request = new FinishedGoodRequest(
                sku,
                sku + " Name",
                "UNIT",
                "FIFO",
                accounts.get("INV").getId(),
                accounts.get("COGS").getId(),
                accounts.get("REV").getId(),
                accounts.get("DISC").getId(),
                accounts.get("GST_OUT").getId()
        );
        FinishedGood finishedGood = finishedGoodRepository.findByCompanyAndProductCode(company, sku)
                .orElseGet(() -> {
                    var dto = finishedGoodsService.createFinishedGood(request);
                    return finishedGoodRepository.findById(dto.id()).orElseThrow();
                });
        ensureCatalogProduct(company, finishedGood);
        return finishedGood;
    }

    private void ensureCatalogProduct(Company company, FinishedGood finishedGood) {
        ProductionBrand brand = productionBrandRepository.findByCompanyAndCodeIgnoreCase(company, "TS-BRAND")
                .orElseGet(() -> {
                    ProductionBrand created = new ProductionBrand();
                    created.setCompany(company);
                    created.setCode("TS-BRAND");
                    created.setName("Truthsuite Brand");
                    return productionBrandRepository.save(created);
                });

        productionProductRepository.findByCompanyAndSkuCode(company, finishedGood.getProductCode())
                .orElseGet(() -> {
                    ProductionProduct product = new ProductionProduct();
                    product.setCompany(company);
                    product.setBrand(brand);
                    product.setSkuCode(finishedGood.getProductCode());
                    product.setProductName(finishedGood.getName());
                    product.setBasePrice(new BigDecimal("10.00"));
                    product.setCategory("GENERAL");
                    product.setSizeLabel("STD");
                    product.setDefaultColour("NA");
                    product.setMinDiscountPercent(BigDecimal.ZERO);
                    product.setMinSellingPrice(BigDecimal.ZERO);
                    product.setMetadata(new HashMap<>());
                    product.setGstRate(BigDecimal.ZERO);
                    product.setUnitOfMeasure("UNIT");
                    product.setActive(true);
                    return productionProductRepository.save(product);
                });
    }

    private SalesOrderRequest orderRequest(CommercialFixture fixture,
                                           BigDecimal quantity,
                                           BigDecimal unitPrice,
                                           String notes) {
        BigDecimal totalAmount = quantity.multiply(unitPrice).setScale(2, RoundingMode.HALF_UP);
        return new SalesOrderRequest(
                fixture.dealer().getId(),
                totalAmount,
                "INR",
                notes,
                List.of(new SalesOrderItemRequest(
                        fixture.finishedGood().getProductCode(),
                        "Truthsuite Boundary Item",
                        quantity,
                        unitPrice,
                        BigDecimal.ZERO
                )),
                "EXCLUSIVE",
                null,
                null,
                UUID.randomUUID().toString()
        );
    }

    private InventoryMovementEvent movementEvent(Company company,
                                                 Account inventory,
                                                 Account cogs,
                                                 String referenceNumber,
                                                 String relatedEntityType,
                                                 Long relatedEntityId) {
        return InventoryMovementEvent.builder()
                .companyId(company.getId())
                .movementType(InventoryMovementEvent.MovementType.ISSUE)
                .inventoryType(InventoryValuationChangedEvent.InventoryType.FINISHED_GOOD)
                .itemId(1L)
                .itemCode("FG-BOUNDARY")
                .itemName("Boundary Finished Good")
                .quantity(new BigDecimal("2"))
                .unitCost(new BigDecimal("5.00"))
                .totalCost(new BigDecimal("10.00"))
                .sourceAccountId(inventory.getId())
                .destinationAccountId(cogs.getId())
                .referenceNumber(referenceNumber)
                .movementDate(LocalDate.now().minusDays(1))
                .memo("truthsuite listener boundary")
                .relatedEntityId(relatedEntityId)
                .relatedEntityType(relatedEntityType)
                .build();
    }

    private int countJournalEntries(Company company) {
        return journalEntryRepository.findByCompanyOrderByEntryDateDesc(company).size();
    }

    private int countInventoryMovements(Company company) {
        return inventoryMovementRepository.findByCompanyCreatedAtOnOrAfter(company, Instant.EPOCH).size();
    }

    private int countInvoices(Company company) {
        return invoiceRepository.findByCompanyOrderByIssueDateDesc(company).size();
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private record CommercialFixture(Company company, Dealer dealer, FinishedGood finishedGood) {
    }
}
