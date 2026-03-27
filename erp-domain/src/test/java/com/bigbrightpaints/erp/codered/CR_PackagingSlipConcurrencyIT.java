package com.bigbrightpaints.erp.codered;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.bigbrightpaints.erp.codered.support.CoderedConcurrencyHarness;
import com.bigbrightpaints.erp.codered.support.CoderedRetry;
import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlip;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlipRepository;
import com.bigbrightpaints.erp.modules.inventory.service.FinishedGoodsService;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrder;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrderItem;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrderRepository;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;

@Tag("critical")
@Tag("concurrency")
class CR_PackagingSlipConcurrencyIT extends AbstractIntegrationTest {

  @Autowired private FinishedGoodsService finishedGoodsService;
  @Autowired private FinishedGoodRepository finishedGoodRepository;
  @Autowired private FinishedGoodBatchRepository finishedGoodBatchRepository;
  @Autowired private PackagingSlipRepository packagingSlipRepository;
  @Autowired private SalesOrderRepository salesOrderRepository;

  @AfterEach
  void clearContext() {
    CompanyContextHolder.clear();
  }

  @Test
  void reserveForOrder_concurrentCalls_createSinglePrimarySlip() {
    Company company =
        dataSeeder.ensureCompany("CR-PS-CONC-" + shortId(), "CR Packaging Slip Concurrency");
    String companyCode = company.getCode();
    FinishedGood finishedGood = createFinishedGood(company, "FG-CONC-" + shortId());
    createBatch(finishedGood, "BATCH-CONC-" + shortId(), new BigDecimal("10"));
    SalesOrder order =
        createOrder(
            company, "SO-CONC-" + shortId(), finishedGood.getProductCode(), new BigDecimal("10"));

    CoderedConcurrencyHarness.RunResult<FinishedGoodsService.InventoryReservationResult> result =
        CoderedConcurrencyHarness.run(
            2,
            3,
            Duration.ofSeconds(30),
            index ->
                () -> {
                  CompanyContextHolder.setCompanyCode(companyCode);
                  try {
                    return finishedGoodsService.reserveForOrder(order);
                  } finally {
                    CompanyContextHolder.clear();
                  }
                },
            CoderedRetry::isRetryable);

    assertThat(result.outcomes())
        .allMatch(outcome -> outcome instanceof CoderedConcurrencyHarness.Outcome.Success<?>);

    List<PackagingSlip> primarySlips =
        packagingSlipRepository.findPrimarySlipsByOrderId(company, order.getId());
    assertThat(primarySlips).hasSize(1);
  }

  private FinishedGood createFinishedGood(Company company, String productCode) {
    FinishedGood fg = new FinishedGood();
    fg.setCompany(company);
    fg.setProductCode(productCode);
    fg.setName(productCode);
    fg.setUnit("UNIT");
    fg.setCostingMethod("FIFO");
    fg.setCurrentStock(new BigDecimal("10"));
    fg.setReservedStock(BigDecimal.ZERO);
    fg.setValuationAccountId(100L);
    fg.setCogsAccountId(200L);
    fg.setRevenueAccountId(300L);
    fg.setTaxAccountId(400L);
    return finishedGoodRepository.saveAndFlush(fg);
  }

  private void createBatch(FinishedGood fg, String batchCode, BigDecimal quantity) {
    FinishedGoodBatch batch = new FinishedGoodBatch();
    batch.setFinishedGood(fg);
    batch.setBatchCode(batchCode);
    batch.setQuantityTotal(quantity);
    batch.setQuantityAvailable(quantity);
    batch.setUnitCost(new BigDecimal("7.50"));
    batch.setManufacturedAt(Instant.now());
    finishedGoodBatchRepository.saveAndFlush(batch);
  }

  private SalesOrder createOrder(
      Company company, String orderNumber, String productCode, BigDecimal quantity) {
    SalesOrder order = new SalesOrder();
    order.setCompany(company);
    order.setOrderNumber(orderNumber);
    order.setStatus("PENDING");
    order.setTotalAmount(BigDecimal.ZERO);
    order.setCurrency("INR");

    SalesOrderItem item = new SalesOrderItem();
    item.setSalesOrder(order);
    item.setProductCode(productCode);
    item.setQuantity(quantity);
    item.setUnitPrice(BigDecimal.ONE);
    item.setLineSubtotal(BigDecimal.ZERO);
    item.setLineTotal(BigDecimal.ZERO);
    order.getItems().add(item);

    return salesOrderRepository.saveAndFlush(order);
  }

  private static String shortId() {
    return UUID.randomUUID().toString().substring(0, 8);
  }
}
