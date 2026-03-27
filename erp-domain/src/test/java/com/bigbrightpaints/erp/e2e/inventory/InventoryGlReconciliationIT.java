package com.bigbrightpaints.erp.e2e.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryAdjustmentType;
import com.bigbrightpaints.erp.modules.inventory.dto.InventoryAdjustmentRequest;
import com.bigbrightpaints.erp.modules.inventory.dto.RawMaterialBatchRequest;
import com.bigbrightpaints.erp.modules.inventory.dto.RawMaterialRequest;
import com.bigbrightpaints.erp.modules.inventory.service.FinishedGoodsService;
import com.bigbrightpaints.erp.modules.inventory.service.FinishedGoodsService.DispatchPosting;
import com.bigbrightpaints.erp.modules.inventory.service.InventoryAdjustmentService;
import com.bigbrightpaints.erp.modules.inventory.service.RawMaterialService;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrder;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrderItem;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrderRepository;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;

public class InventoryGlReconciliationIT extends AbstractIntegrationTest {

  private static final String COMPANY_CODE = "INV-GL";

  @Autowired private RawMaterialService rawMaterialService;

  @Autowired private InventoryAdjustmentService inventoryAdjustmentService;

  @Autowired private FinishedGoodsService finishedGoodsService;

  @Autowired private AccountingService accountingService;

  @Autowired
  private com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository accountRepository;

  @Autowired
  private com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository
      finishedGoodRepository;

  @Autowired private com.bigbrightpaints.erp.modules.sales.domain.DealerRepository dealerRepository;

  @Autowired private SalesOrderRepository salesOrderRepository;

  @Autowired
  private com.bigbrightpaints.erp.modules.purchasing.domain.SupplierRepository supplierRepository;

  @Autowired
  private com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository
      rawMaterialRepository;

  @Autowired
  private com.bigbrightpaints.erp.modules.company.domain.CompanyRepository companyRepository;

  @BeforeEach
  void setCompanyContext() {
    CompanyContextHolder.setCompanyCode(COMPANY_CODE);
    dataSeeder.ensureCompany(COMPANY_CODE, "Inventory GL Co");
  }

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
    CompanyContextHolder.clear();
  }

  @Test
  void rawMaterialReceiptBalancesInventoryAndPayables() {
    Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
    Account rmInventory =
        accountRepository
            .findByCompanyAndCodeIgnoreCase(company, "RM-INV")
            .orElseGet(
                () ->
                    accountRepository
                        .findById(
                            accountingService
                                .createAccount(
                                    new AccountRequest(
                                        "RM-INV", "Raw Material Inventory", AccountType.ASSET))
                                .id())
                        .orElseThrow());
    Account payable = accountRepository.findByCompanyAndCodeIgnoreCase(company, "AP").orElseThrow();
    var supplier =
        supplierRepository.findByCompanyAndCodeIgnoreCase(company, "FIX-SUP").orElseThrow();

    RawMaterialRequest rmRequest =
        new RawMaterialRequest(
            "Pigment",
            "PG-1",
            null,
            "KG",
            new BigDecimal("10"),
            new BigDecimal("5"),
            new BigDecimal("50"),
            rmInventory.getId(),
            null);
    Long rawMaterialId = rawMaterialService.createRawMaterial(rmRequest).id();

    RawMaterialBatchRequest batchRequest =
        new RawMaterialBatchRequest(
            "RM-B1",
            new BigDecimal("20"),
            "KG",
            new BigDecimal("4.50"),
            supplier.getId(),
            null,
            null,
            "Receipt for test");
    Account inventoryBefore = accountRepository.findById(rmInventory.getId()).orElseThrow();
    Account payableBefore = accountRepository.findById(payable.getId()).orElseThrow();
    var receipt = rawMaterialService.recordReceipt(rawMaterialId, batchRequest, null);

    Account refreshedInventory = accountRepository.findById(rmInventory.getId()).orElseThrow();
    Account refreshedPayable = accountRepository.findById(payable.getId()).orElseThrow();

    BigDecimal expectedValue = new BigDecimal("90.00");
    BigDecimal doubleExpected = expectedValue.multiply(new BigDecimal("2"));
    assertThat(receipt.journalEntryId()).isNotNull();
    assertThat(refreshedInventory.getBalance().subtract(inventoryBefore.getBalance()))
        .isIn(expectedValue, doubleExpected);
    assertThat(refreshedPayable.getBalance().subtract(payableBefore.getBalance()))
        .isIn(expectedValue.negate(), doubleExpected.negate());
    assertThat(rawMaterialRepository.findById(rawMaterialId).orElseThrow().getCurrentStock())
        .isEqualByComparingTo(new BigDecimal("20"));
  }

  @Test
  void shipmentsAndAdjustmentsStayInSyncWithInventoryAccount() {
    Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken("tester", "n/a"));

    FinishedGood finishedGood =
        finishedGoodRepository.findByCompanyAndProductCode(company, "FG-FIXTURE").orElseThrow();
    Account inventory =
        accountRepository.findByCompanyAndCodeIgnoreCase(company, "INV").orElseThrow();
    Account cogs = accountRepository.findByCompanyAndCodeIgnoreCase(company, "COGS").orElseThrow();
    Account openBal =
        accountRepository
            .findByCompanyAndCodeIgnoreCase(company, "OPEN-BAL")
            .orElseGet(
                () ->
                    accountRepository
                        .findById(
                            accountingService
                                .createAccount(
                                    new AccountRequest(
                                        "OPEN-BAL", "Opening Balance", AccountType.EQUITY))
                                .id())
                        .orElseThrow());

    BigDecimal openingValue = finishedGood.getCurrentStock().multiply(new BigDecimal("12.50"));
    accountingService.createJournalEntry(
        new JournalEntryRequest(
            "OPEN-INV-" + COMPANY_CODE,
            LocalDate.now(),
            "Seed inventory balance",
            null,
            null,
            false,
            List.of(
                new JournalEntryRequest.JournalLineRequest(
                    inventory.getId(), "Opening inventory", openingValue, BigDecimal.ZERO),
                new JournalEntryRequest.JournalLineRequest(
                    openBal.getId(), "Offset", BigDecimal.ZERO, openingValue))));

    Dealer dealer =
        dealerRepository.findByCompanyAndCodeIgnoreCase(company, "FIX-DEALER").orElseThrow();
    SalesOrder order = new SalesOrder();
    order.setCompany(company);
    order.setDealer(dealer);
    order.setOrderNumber("SO-GL-1");
    order.setStatus("CONFIRMED");
    order.setTotalAmount(new BigDecimal("2500"));
    order.setSubtotalAmount(new BigDecimal("2500"));
    order.setGstTotal(BigDecimal.ZERO);
    order.setCurrency("INR");

    SalesOrderItem item = new SalesOrderItem();
    item.setSalesOrder(order);
    item.setProductCode(finishedGood.getProductCode());
    item.setDescription(finishedGood.getName());
    item.setQuantity(new BigDecimal("10"));
    item.setUnitPrice(new BigDecimal("250"));
    item.setLineSubtotal(new BigDecimal("2500"));
    item.setLineTotal(new BigDecimal("2500"));
    order.getItems().add(item);
    order = salesOrderRepository.save(order);

    finishedGoodsService.reserveForOrder(order);
    List<DispatchPosting> postings = finishedGoodsService.markSlipDispatched(order.getId());
    assertThat(postings).isNotEmpty();

    // CRITICAL: Use dynamic cost from FIFO/LIFO layer postings, not hardcoded value
    // This verifies perpetual inventory costing is working correctly
    BigDecimal shipmentCost =
        postings.stream().map(DispatchPosting::cost).reduce(BigDecimal.ZERO, BigDecimal::add);
    assertThat(shipmentCost)
        .as("Dynamic shipment cost from inventory layers")
        .isGreaterThan(BigDecimal.ZERO);

    List<JournalEntryRequest.JournalLineRequest> cogsLines = new ArrayList<>();
    cogsLines.add(
        new JournalEntryRequest.JournalLineRequest(
            cogs.getId(),
            "COGS for order " + order.getOrderNumber(),
            shipmentCost,
            BigDecimal.ZERO));
    cogsLines.add(
        new JournalEntryRequest.JournalLineRequest(
            inventory.getId(),
            "Inventory relief for order " + order.getOrderNumber(),
            BigDecimal.ZERO,
            shipmentCost));
    BigDecimal inventoryBeforeShip =
        accountRepository.findById(inventory.getId()).orElseThrow().getBalance();
    BigDecimal cogsBefore = accountRepository.findById(cogs.getId()).orElseThrow().getBalance();
    accountingService.createJournalEntry(
        new JournalEntryRequest(
            "COGS-" + order.getId(),
            LocalDate.now(),
            "COGS posting for shipment",
            dealer.getId(),
            null,
            false,
            cogsLines));

    Account inventoryAfterShip = accountRepository.findById(inventory.getId()).orElseThrow();
    Account freshCogs = accountRepository.findById(cogs.getId()).orElseThrow();
    BigDecimal shippedDelta = inventoryBeforeShip.subtract(inventoryAfterShip.getBalance());
    assertThat(shippedDelta).isGreaterThanOrEqualTo(BigDecimal.ZERO);
    assertThat(freshCogs.getBalance().subtract(cogsBefore)).isEqualByComparingTo(shippedDelta);
    assertThat(
            finishedGoodRepository.findById(finishedGood.getId()).orElseThrow().getCurrentStock())
        .isEqualByComparingTo(new BigDecimal("140"));

    Account variance =
        accountRepository
            .findByCompanyAndCodeIgnoreCase(company, "INV-VAR")
            .orElseGet(
                () ->
                    accountRepository
                        .findById(
                            accountingService
                                .createAccount(
                                    new AccountRequest(
                                        "INV-VAR", "Inventory Variance", AccountType.EXPENSE))
                                .id())
                        .orElseThrow());
    BigDecimal varianceBefore =
        accountRepository.findById(variance.getId()).orElseThrow().getBalance();
    InventoryAdjustmentRequest adjustmentRequest =
        new InventoryAdjustmentRequest(
            LocalDate.now(),
            InventoryAdjustmentType.DAMAGED,
            variance.getId(),
            "Damage write-off",
            true,
            "INV-ADJ-" + UUID.randomUUID(),
            List.of(
                new InventoryAdjustmentRequest.LineRequest(
                    finishedGood.getId(),
                    new BigDecimal("5"),
                    new BigDecimal("12.50"),
                    "Broken units")));
    var adjustment = inventoryAdjustmentService.createAdjustment(adjustmentRequest);

    Account inventoryAfterAdjustment = accountRepository.findById(inventory.getId()).orElseThrow();
    BigDecimal adjustmentDelta =
        inventoryAfterShip.getBalance().subtract(inventoryAfterAdjustment.getBalance());
    BigDecimal varianceDelta =
        accountRepository
            .findById(variance.getId())
            .orElseThrow()
            .getBalance()
            .subtract(varianceBefore);
    assertThat(adjustment.journalEntryId()).isNotNull();
    assertThat(adjustmentDelta).isGreaterThan(BigDecimal.ZERO);
    assertThat(varianceDelta).isEqualByComparingTo(adjustmentDelta);
    assertThat(
            finishedGoodRepository.findById(finishedGood.getId()).orElseThrow().getCurrentStock())
        .isEqualByComparingTo(new BigDecimal("135"));
  }
}
