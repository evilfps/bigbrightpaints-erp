package com.bigbrightpaints.erp.modules.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriod;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodStatus;
import com.bigbrightpaints.erp.modules.accounting.domain.CostingMethod;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovement;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryReference;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryReservation;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryReservationRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlip;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlipLine;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlipRepository;
import com.bigbrightpaints.erp.modules.inventory.dto.DispatchConfirmationRequest;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrder;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrderItem;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrderRepository;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;

@Transactional
@Tag("critical")
class FinishedGoodsReservationEngineTest extends AbstractIntegrationTest {

  @Autowired private FinishedGoodsService finishedGoodsService;

  @Autowired private FinishedGoodRepository finishedGoodRepository;

  @Autowired private FinishedGoodBatchRepository finishedGoodBatchRepository;

  @Autowired private PackagingSlipRepository packagingSlipRepository;

  @Autowired private InventoryReservationRepository inventoryReservationRepository;

  @Autowired private InventoryMovementRepository inventoryMovementRepository;

  @Autowired private SalesOrderRepository salesOrderRepository;

  @Autowired private AccountingPeriodRepository accountingPeriodRepository;

  @AfterEach
  void clearContext() {
    CompanyContextHolder.clear();
  }

  @Test
  void reserveForOrderReplayAfterDispatch_keepsFulfilledReservationsTerminal() {
    Company company = seedCompany("RES-REPLAY-FULL");
    FinishedGood finishedGood =
        createFinishedGood(company, "FG-RES-REPLAY-FULL", new BigDecimal("5"), BigDecimal.ZERO);
    FinishedGoodBatch batch =
        createBatch(
            finishedGood,
            "BATCH-RES-REPLAY-FULL",
            new BigDecimal("5"),
            new BigDecimal("5"),
            new BigDecimal("8"));
    SalesOrder order =
        createOrder(
            company,
            "SO-RES-REPLAY-FULL-" + UUID.randomUUID(),
            finishedGood.getProductCode(),
            new BigDecimal("5"));

    finishedGoodsService.reserveForOrder(order);
    PackagingSlip primarySlip = findPrimarySlip(company, order.getId());
    finishedGoodsService.markSlipDispatched(order.getId(), primarySlip);

    FinishedGoodsService.InventoryReservationResult replay =
        finishedGoodsService.reserveForOrder(order);

    List<InventoryReservation> reservations = reservationsFor(company, order.getId());
    assertThat(replay.shortages()).isEmpty();
    assertThat(reservations).hasSize(1);
    assertThat(reservations.getFirst().getStatus()).isEqualTo("FULFILLED");
    assertThat(reservations.getFirst().getFulfilledQuantity())
        .isEqualByComparingTo(new BigDecimal("5"));
    assertThat(zeroIfNull(reservations.getFirst().getReservedQuantity()))
        .isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(
            finishedGoodRepository.findById(finishedGood.getId()).orElseThrow().getReservedStock())
        .isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(
            finishedGoodBatchRepository
                .findById(batch.getId())
                .orElseThrow()
                .getQuantityAvailable())
        .isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(
            packagingSlipRepository
                .findByIdAndCompany(primarySlip.getId(), company)
                .orElseThrow()
                .getStatus())
        .isEqualTo("DISPATCHED");
  }

  @Test
  void reserveForOrderReplayAfterDispatchWithMissingReservationRows_doesNotRebuildReservedStock() {
    Company company = seedCompany("RES-REPLAY-NO-ROWS");
    FinishedGood finishedGood =
        createFinishedGood(company, "FG-RES-REPLAY-NO-ROWS", new BigDecimal("5"), BigDecimal.ZERO);
    FinishedGoodBatch batch =
        createBatch(
            finishedGood,
            "BATCH-RES-REPLAY-NO-ROWS",
            new BigDecimal("5"),
            new BigDecimal("5"),
            new BigDecimal("8"));
    SalesOrder order =
        createOrder(
            company,
            "SO-RES-REPLAY-NO-ROWS-" + UUID.randomUUID(),
            finishedGood.getProductCode(),
            new BigDecimal("5"));

    finishedGoodsService.reserveForOrder(order);
    PackagingSlip primarySlip = findPrimarySlip(company, order.getId());
    finishedGoodsService.markSlipDispatched(order.getId(), primarySlip);

    inventoryReservationRepository.deleteAll(reservationsFor(company, order.getId()));
    inventoryReservationRepository.flush();

    FinishedGoodsService.InventoryReservationResult replay =
        finishedGoodsService.reserveForOrder(order);

    assertThat(replay.shortages()).isEmpty();
    assertThat(reservationsFor(company, order.getId())).isEmpty();
    assertThat(
            finishedGoodRepository.findById(finishedGood.getId()).orElseThrow().getReservedStock())
        .isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(
            finishedGoodBatchRepository
                .findById(batch.getId())
                .orElseThrow()
                .getQuantityAvailable())
        .isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(
            packagingSlipRepository
                .findByIdAndCompany(primarySlip.getId(), company)
                .orElseThrow()
                .getStatus())
        .isEqualTo("DISPATCHED");
  }

  @Test
  void reserveForOrderReplayAfterPartialDispatch_preservesRemainingBalanceAndPartialState() {
    Company company = seedCompany("RES-REPLAY-PARTIAL");
    FinishedGood finishedGood =
        createFinishedGood(company, "FG-RES-REPLAY-PARTIAL", new BigDecimal("10"), BigDecimal.ZERO);
    FinishedGoodBatch batch =
        createBatch(
            finishedGood,
            "BATCH-RES-REPLAY-PARTIAL",
            new BigDecimal("10"),
            new BigDecimal("10"),
            new BigDecimal("9"));
    SalesOrder order =
        createOrder(
            company,
            "SO-RES-REPLAY-PARTIAL-" + UUID.randomUUID(),
            finishedGood.getProductCode(),
            new BigDecimal("10"));

    finishedGoodsService.reserveForOrder(order);
    PackagingSlip primarySlip = findPrimarySlip(company, order.getId());
    PackagingSlipLine line = primarySlip.getLines().getFirst();
    finishedGoodsService.confirmDispatch(
        new DispatchConfirmationRequest(
            primarySlip.getId(),
            List.of(
                new DispatchConfirmationRequest.LineConfirmation(
                    line.getId(), new BigDecimal("6"), null)),
            "partial shipment",
            "tester",
            null),
        "tester");

    FinishedGoodsService.InventoryReservationResult replay =
        finishedGoodsService.reserveForOrder(order);

    List<InventoryReservation> reservations = reservationsFor(company, order.getId());
    assertThat(replay.shortages()).isEmpty();
    assertThat(reservations).hasSize(1);
    assertThat(reservations.getFirst().getStatus()).isEqualTo("PARTIAL");
    assertThat(reservations.getFirst().getQuantity()).isEqualByComparingTo(new BigDecimal("10"));
    assertThat(reservations.getFirst().getFulfilledQuantity())
        .isEqualByComparingTo(new BigDecimal("6"));
    assertThat(reservations.getFirst().getReservedQuantity())
        .isEqualByComparingTo(new BigDecimal("4"));
    assertThat(
            finishedGoodRepository.findById(finishedGood.getId()).orElseThrow().getReservedStock())
        .isEqualByComparingTo(new BigDecimal("4"));
    assertThat(
            finishedGoodBatchRepository
                .findById(batch.getId())
                .orElseThrow()
                .getQuantityAvailable())
        .isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(
            packagingSlipRepository
                .findByIdAndCompany(primarySlip.getId(), company)
                .orElseThrow()
                .getStatus())
        .isEqualTo("DISPATCHED");
    assertThat(packagingSlipRepository.findAllByCompanyAndSalesOrderId(company, order.getId()))
        .anyMatch(
            existing ->
                existing.isBackorder() && "BACKORDER".equalsIgnoreCase(existing.getStatus()));
  }

  @Test
  void reserveForOrderReplay_preservesBackorderReservationState() {
    Company company = seedCompany("RES-REPLAY-BACKORDER");
    FinishedGood finishedGood =
        createFinishedGood(
            company, "FG-RES-REPLAY-BACKORDER", new BigDecimal("5"), new BigDecimal("5"));
    FinishedGoodBatch batch =
        createBatch(
            finishedGood,
            "BATCH-RES-REPLAY-BACKORDER",
            new BigDecimal("5"),
            BigDecimal.ZERO,
            new BigDecimal("7"));
    SalesOrder order =
        createOrder(
            company,
            "SO-RES-REPLAY-BACKORDER-" + UUID.randomUUID(),
            finishedGood.getProductCode(),
            new BigDecimal("5"));
    createSlip(company, order, "BACKORDER", batch, new BigDecimal("5"));
    InventoryReservation reservation =
        createReservation(order, finishedGood, batch, new BigDecimal("5"));
    reservation.setStatus("BACKORDER");
    inventoryReservationRepository.saveAndFlush(reservation);

    FinishedGoodsService.InventoryReservationResult replay =
        finishedGoodsService.reserveForOrder(order);

    List<InventoryReservation> reservations = reservationsFor(company, order.getId());
    assertThat(replay.shortages()).isEmpty();
    assertThat(reservations).hasSize(1);
    assertThat(reservations.getFirst().getStatus()).isEqualTo("BACKORDER");
    assertThat(reservations.getFirst().getReservedQuantity())
        .isEqualByComparingTo(new BigDecimal("5"));
    assertThat(zeroIfNull(reservations.getFirst().getFulfilledQuantity()))
        .isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(
            finishedGoodRepository.findById(finishedGood.getId()).orElseThrow().getReservedStock())
        .isEqualByComparingTo(new BigDecimal("5"));
    assertThat(
            finishedGoodBatchRepository
                .findById(batch.getId())
                .orElseThrow()
                .getQuantityAvailable())
        .isEqualByComparingTo(BigDecimal.ZERO);
  }

  @Test
  void reserveForOrderReplay_reusesActiveRepeatedBatchReservations() {
    Company company = seedCompany("RES-REPLAY-DUP-BATCH");
    FinishedGood finishedGood =
        createFinishedGood(
            company, "FG-RES-REPLAY-DUP-BATCH", new BigDecimal("10"), BigDecimal.ZERO);
    FinishedGoodBatch batch =
        createBatch(
            finishedGood,
            "BATCH-RES-REPLAY-DUP-BATCH",
            new BigDecimal("10"),
            new BigDecimal("10"),
            new BigDecimal("11"));
    SalesOrder order =
        createOrder(
            company,
            "SO-RES-REPLAY-DUP-BATCH-" + UUID.randomUUID(),
            finishedGood.getProductCode(),
            new BigDecimal("5"));
    createSlip(company, order, "RESERVED", batch, new BigDecimal("2"), new BigDecimal("3"));
    InventoryReservation firstReservation =
        createReservation(order, finishedGood, batch, new BigDecimal("2"));
    InventoryReservation secondReservation =
        createReservation(order, finishedGood, batch, new BigDecimal("3"));

    FinishedGoodsService.InventoryReservationResult replay =
        finishedGoodsService.reserveForOrder(order);

    List<InventoryReservation> reservations = reservationsFor(company, order.getId());
    assertThat(replay.shortages()).isEmpty();
    assertThat(reservations).hasSize(2);
    assertThat(reservations)
        .extracting(InventoryReservation::getId)
        .containsExactly(firstReservation.getId(), secondReservation.getId());
    assertThat(reservations).extracting(InventoryReservation::getStatus).containsOnly("RESERVED");
    assertThat(reservations)
        .extracting(InventoryReservation::getQuantity)
        .containsExactly(new BigDecimal("2"), new BigDecimal("3"));
    assertThat(
            finishedGoodRepository.findById(finishedGood.getId()).orElseThrow().getReservedStock())
        .isEqualByComparingTo(new BigDecimal("5"));
  }

  @Test
  void reserveForOrderReplay_reusesSameSkuMultiLineReservationsWithoutResettingShape() {
    Company company = seedCompany("RES-REPLAY-SAME-SKU");
    FinishedGood finishedGood =
        createFinishedGood(
            company, "FG-RES-REPLAY-SAME-SKU", new BigDecimal("10"), BigDecimal.ZERO);
    FinishedGoodBatch batch =
        createBatch(
            finishedGood,
            "BATCH-RES-REPLAY-SAME-SKU",
            new BigDecimal("10"),
            new BigDecimal("10"),
            new BigDecimal("11"));
    SalesOrder order =
        createOrder(
            company,
            "SO-RES-REPLAY-SAME-SKU-" + UUID.randomUUID(),
            List.of(
                new OrderLineSeed(finishedGood.getProductCode(), new BigDecimal("2")),
                new OrderLineSeed(finishedGood.getProductCode(), new BigDecimal("3"))));
    createSlip(company, order, "RESERVED", batch, new BigDecimal("2"), new BigDecimal("3"));
    InventoryReservation firstReservation =
        createReservation(order, finishedGood, batch, new BigDecimal("2"));
    InventoryReservation secondReservation =
        createReservation(order, finishedGood, batch, new BigDecimal("3"));

    FinishedGoodsService.InventoryReservationResult replay =
        finishedGoodsService.reserveForOrder(order);

    List<InventoryReservation> reservations = reservationsFor(company, order.getId());
    assertThat(replay.shortages()).isEmpty();
    assertThat(reservations).hasSize(2);
    assertThat(reservations)
        .extracting(InventoryReservation::getId)
        .containsExactly(firstReservation.getId(), secondReservation.getId());
    assertThat(reservations).extracting(InventoryReservation::getStatus).containsOnly("RESERVED");
    assertThat(reservations)
        .extracting(InventoryReservation::getQuantity)
        .containsExactly(new BigDecimal("2"), new BigDecimal("3"));
    assertThat(packagingSlipRepository.findAllByCompanyAndSalesOrderId(company, order.getId()))
        .singleElement()
        .satisfies(
            existing -> {
              assertThat(existing.getStatus()).isEqualTo("RESERVED");
              assertThat(existing.getLines())
                  .extracting(PackagingSlipLine::getOrderedQuantity)
                  .containsExactly(new BigDecimal("2"), new BigDecimal("3"));
            });
    assertThat(
            finishedGoodRepository.findById(finishedGood.getId()).orElseThrow().getReservedStock())
        .isEqualByComparingTo(new BigDecimal("5"));
  }

  @Test
  void reserveForOrder_rejectsMissingSalesOrder() {
    Company company = seedCompany("RES-MISSING-ORDER");
    SalesOrder missingOrder = new SalesOrder();
    ReflectionTestUtils.setField(missingOrder, "id", 999_999L);
    missingOrder.setCompany(company);

    assertThatThrownBy(() -> finishedGoodsService.reserveForOrder(missingOrder))
        .hasMessageContaining("Sales order not found: " + missingOrder.getId());
  }

  @Test
  void reserveForOrder_rebuildsWhenMatchingSlipCannotSynchronizeCurrentReservations() {
    Company company = seedCompany("RES-SYNC-RESET");
    FinishedGood finishedGood =
        createFinishedGood(company, "FG-RES-SYNC-RESET", new BigDecimal("4"), BigDecimal.ZERO);
    FinishedGoodBatch batch =
        createBatch(
            finishedGood,
            "BATCH-RES-SYNC-RESET",
            new BigDecimal("4"),
            new BigDecimal("4"),
            new BigDecimal("8"));
    SalesOrder order =
        createOrder(
            company,
            "SO-RES-SYNC-RESET-" + UUID.randomUUID(),
            finishedGood.getProductCode(),
            new BigDecimal("2"));

    PackagingSlip slip = createSlip(company, order, "RESERVED", batch, new BigDecimal("2"));
    InventoryReservation reservation =
        createReservation(order, finishedGood, batch, new BigDecimal("2"));
    reservation.setFinishedGoodBatch(null);
    inventoryReservationRepository.saveAndFlush(reservation);

    FinishedGoodsService.InventoryReservationResult replay =
        finishedGoodsService.reserveForOrder(order);

    PackagingSlip reloadedSlip =
        packagingSlipRepository.findByIdAndCompany(slip.getId(), company).orElseThrow();
    List<InventoryReservation> reservations = reservationsFor(company, order.getId());
    assertThat(replay.shortages()).isEmpty();
    assertThat(reloadedSlip.getStatus()).isEqualTo("RESERVED");
    assertThat(reloadedSlip.getLines()).hasSize(1);
    assertThat(reservations).hasSize(2);
    assertThat(reservations)
        .extracting(InventoryReservation::getStatus)
        .containsExactlyInAnyOrder("CANCELLED", "RESERVED");
    assertThat(
            reservations.stream()
                .filter(existing -> "RESERVED".equalsIgnoreCase(existing.getStatus()))
                .findFirst()
                .orElseThrow()
                .getFinishedGoodBatch()
                .getId())
        .isEqualTo(batch.getId());
  }

  @Test
  void reserveForOrder_rebuildsWhenExistingSlipLinesNoLongerMatchOrderShape() {
    Company company = seedCompany("RES-SHAPE-RESET");
    FinishedGood firstGood =
        createFinishedGood(company, "FG-RES-SHAPE-RESET-1", new BigDecimal("2"), BigDecimal.ZERO);
    FinishedGood secondGood =
        createFinishedGood(company, "FG-RES-SHAPE-RESET-2", new BigDecimal("2"), BigDecimal.ZERO);
    FinishedGoodBatch firstBatch =
        createBatch(
            firstGood,
            "BATCH-RES-SHAPE-RESET-1",
            new BigDecimal("2"),
            new BigDecimal("2"),
            new BigDecimal("8"));
    createBatch(
        secondGood,
        "BATCH-RES-SHAPE-RESET-2",
        new BigDecimal("2"),
        new BigDecimal("2"),
        new BigDecimal("9"));
    SalesOrder order =
        createOrder(
            company,
            "SO-RES-SHAPE-RESET-" + UUID.randomUUID(),
            List.of(
                new OrderLineSeed(firstGood.getProductCode(), BigDecimal.ONE),
                new OrderLineSeed(secondGood.getProductCode(), BigDecimal.ONE)));

    PackagingSlip staleSlip =
        createSlip(company, order, "RESERVED", firstBatch, new BigDecimal("2"));
    createReservation(order, firstGood, firstBatch, new BigDecimal("2"));

    FinishedGoodsService.InventoryReservationResult replay =
        finishedGoodsService.reserveForOrder(order);

    PackagingSlip reloadedSlip =
        packagingSlipRepository.findByIdAndCompany(staleSlip.getId(), company).orElseThrow();
    assertThat(replay.shortages()).isEmpty();
    assertThat(reloadedSlip.getStatus()).isEqualTo("RESERVED");
    assertThat(reloadedSlip.getLines()).hasSize(2);
    assertThat(reloadedSlip.getLines())
        .extracting(line -> line.getFinishedGoodBatch().getFinishedGood().getProductCode())
        .containsExactlyInAnyOrder(firstGood.getProductCode(), secondGood.getProductCode());
  }

  @Test
  void reserveForOrder_marksSlipPendingProductionWhenInventoryIsShort() {
    Company company = seedCompany("RES-SHORTAGE-PATH");
    FinishedGood finishedGood =
        createFinishedGood(company, "FG-RES-SHORTAGE-PATH", BigDecimal.ZERO, BigDecimal.ZERO);
    SalesOrder order =
        createOrder(
            company,
            "SO-RES-SHORTAGE-" + UUID.randomUUID(),
            finishedGood.getProductCode(),
            BigDecimal.ONE);

    FinishedGoodsService.InventoryReservationResult reservation =
        finishedGoodsService.reserveForOrder(order);

    assertThat(reservation.shortages())
        .singleElement()
        .satisfies(
            shortage ->
                assertThat(shortage.shortageQuantity()).isEqualByComparingTo(BigDecimal.ONE));
    assertThat(reservation.packagingSlip().status()).isEqualTo("PENDING_PRODUCTION");
  }

  @Test
  void reserveForOrder_reusesCancelledPrimarySlipAfterReleasingExistingReservations() {
    Company company = seedCompany("RES-CANCELLED-PRIMARY");
    FinishedGood finishedGood =
        createFinishedGood(
            company, "FG-RES-CANCELLED-PRIMARY", new BigDecimal("4"), new BigDecimal("4"));
    FinishedGoodBatch batch =
        createBatch(
            finishedGood,
            "BATCH-RES-CANCELLED-PRIMARY",
            new BigDecimal("4"),
            BigDecimal.ZERO,
            new BigDecimal("8"));
    SalesOrder order =
        createOrder(
            company,
            "SO-RES-CANCELLED-PRIMARY-" + UUID.randomUUID(),
            finishedGood.getProductCode(),
            new BigDecimal("4"));

    PackagingSlip cancelledSlip =
        createSlip(company, order, "CANCELLED", batch, new BigDecimal("4"));
    createReservation(order, finishedGood, batch, new BigDecimal("4"));

    FinishedGoodsService.InventoryReservationResult replay =
        finishedGoodsService.reserveForOrder(order);

    List<InventoryReservation> reservations = reservationsFor(company, order.getId());
    PackagingSlip reloadedSlip =
        packagingSlipRepository.findByIdAndCompany(cancelledSlip.getId(), company).orElseThrow();

    assertThat(replay.shortages()).isEmpty();
    assertThat(replay.packagingSlip().id()).isEqualTo(cancelledSlip.getId());
    assertThat(reservations).hasSize(2);
    assertThat(reservations)
        .extracting(InventoryReservation::getStatus)
        .containsExactlyInAnyOrder("CANCELLED", "RESERVED");
    assertThat(
            finishedGoodRepository.findById(finishedGood.getId()).orElseThrow().getReservedStock())
        .isEqualByComparingTo(new BigDecimal("4"));
    assertThat(
            finishedGoodBatchRepository
                .findById(batch.getId())
                .orElseThrow()
                .getQuantityAvailable())
        .isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(reloadedSlip.isBackorder()).isFalse();
    assertThat(reloadedSlip.getStatus()).isEqualTo("RESERVED");
    assertThat(reloadedSlip.getLines()).hasSize(1);
    assertThat(reloadedSlip.getDispatchNotes()).isNull();
  }

  @Test
  void reserveForOrder_rejectsAmbiguousPrimarySlipSelection() {
    Company company = seedCompany("RES-AMBIG-PRIMARY");
    FinishedGood finishedGood =
        createFinishedGood(company, "FG-RES-AMBIG-PRIMARY", new BigDecimal("10"), BigDecimal.ZERO);
    FinishedGoodBatch batch =
        createBatch(
            finishedGood,
            "BATCH-RES-AMBIG-PRIMARY",
            new BigDecimal("10"),
            new BigDecimal("10"),
            new BigDecimal("11"));
    SalesOrder order =
        createOrder(
            company,
            "SO-RES-AMBIG-PRIMARY-" + UUID.randomUUID(),
            finishedGood.getProductCode(),
            new BigDecimal("5"));

    createSlip(company, order, "RESERVED", batch, new BigDecimal("2"), new BigDecimal("3"));
    createSlip(
        company,
        order,
        "PS-CANCELLED-" + UUID.randomUUID().toString().substring(0, 8),
        "CANCELLED",
        batch,
        new BigDecimal("2"),
        new BigDecimal("3"));

    assertThatThrownBy(() -> finishedGoodsService.reserveForOrder(order))
        .hasMessageContaining("Ambiguous primary packaging slip state for order " + order.getId());

    assertThat(packagingSlipRepository.findAllByCompanyAndSalesOrderId(company, order.getId()))
        .hasSize(2)
        .allMatch(existing -> !existing.isBackorder());
    assertThat(reservationsFor(company, order.getId())).isEmpty();
    assertThat(
            finishedGoodRepository.findById(finishedGood.getId()).orElseThrow().getReservedStock())
        .isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(
            finishedGoodBatchRepository
                .findById(batch.getId())
                .orElseThrow()
                .getQuantityAvailable())
        .isEqualByComparingTo(new BigDecimal("10"));
  }

  @Test
  void reserveForOrder_reusesMatchingBackorderSlipWhenPrimaryIsMissing() {
    Company company = seedCompany("RES-BACKORDER-REUSE");
    FinishedGood finishedGood =
        createFinishedGood(company, "FG-RES-BACKORDER-REUSE", new BigDecimal("5"), BigDecimal.ZERO);
    FinishedGoodBatch batch =
        createBatch(
            finishedGood,
            "BATCH-RES-BACKORDER-REUSE",
            new BigDecimal("5"),
            new BigDecimal("5"),
            new BigDecimal("8"));
    SalesOrder order =
        createOrder(
            company,
            "SO-RES-BACKORDER-REUSE-" + UUID.randomUUID(),
            finishedGood.getProductCode(),
            new BigDecimal("5"));

    PackagingSlip backorderSlip =
        createSlip(company, order, "BACKORDER", batch, new BigDecimal("5"));

    FinishedGoodsService.InventoryReservationResult replay =
        finishedGoodsService.reserveForOrder(order);

    assertThat(replay.shortages()).isEmpty();
    assertThat(replay.packagingSlip().id()).isEqualTo(backorderSlip.getId());
    assertThat(reservationsFor(company, order.getId())).hasSize(1);
  }

  @Test
  void slipLinesMatchOrder_acceptsSameSkuMultiLineOrdersByAggregatedQuantity() {
    Company company = seedCompany("RES-SHAPE-SAME-SKU");
    FinishedGood finishedGood =
        createFinishedGood(company, "FG-RES-SHAPE-SAME-SKU", new BigDecimal("10"), BigDecimal.ZERO);
    FinishedGoodBatch batch =
        createBatch(
            finishedGood,
            "BATCH-RES-SHAPE-SAME-SKU",
            new BigDecimal("10"),
            new BigDecimal("10"),
            new BigDecimal("8"));
    SalesOrder order =
        createOrder(
            company,
            "SO-RES-SHAPE-SAME-SKU-" + UUID.randomUUID(),
            List.of(
                new OrderLineSeed(finishedGood.getProductCode(), new BigDecimal("2")),
                new OrderLineSeed(finishedGood.getProductCode(), new BigDecimal("3"))));
    PackagingSlip slip =
        createSlip(company, order, "RESERVED", batch, new BigDecimal("2"), new BigDecimal("3"));

    assertThat(reservationEngine().slipLinesMatchOrder(slip, order)).isTrue();
  }

  @Test
  void slipLinesMatchOrder_returnsFalseWhenSlipAndOrderSkuSetsDiffer() {
    Company company = seedCompany("RES-SHAPE-SKU-DRIFT");
    FinishedGood firstGood =
        createFinishedGood(
            company, "FG-RES-SHAPE-SKU-DRIFT-1", new BigDecimal("10"), BigDecimal.ZERO);
    FinishedGood secondGood =
        createFinishedGood(
            company, "FG-RES-SHAPE-SKU-DRIFT-2", new BigDecimal("10"), BigDecimal.ZERO);
    FinishedGoodBatch firstBatch =
        createBatch(
            firstGood,
            "BATCH-RES-SHAPE-SKU-DRIFT-1",
            new BigDecimal("10"),
            new BigDecimal("10"),
            new BigDecimal("8"));
    SalesOrder order =
        createOrder(
            company,
            "SO-RES-SHAPE-SKU-DRIFT-" + UUID.randomUUID(),
            List.of(
                new OrderLineSeed(firstGood.getProductCode(), new BigDecimal("2")),
                new OrderLineSeed(secondGood.getProductCode(), new BigDecimal("1"))));
    PackagingSlip slip = createSlip(company, order, "RESERVED", firstBatch, new BigDecimal("2"));

    assertThat(reservationEngine().slipLinesMatchOrder(slip, order)).isFalse();
  }

  @Test
  void releaseReservationsForOrder_returnsWhenNoReservationsExist() {
    Company company = seedCompany("RES-RELEASE-EMPTY");
    FinishedGood finishedGood =
        createFinishedGood(company, "FG-RES-RELEASE-EMPTY", new BigDecimal("2"), BigDecimal.ZERO);
    SalesOrder order =
        createOrder(
            company,
            "SO-RES-RELEASE-EMPTY-" + UUID.randomUUID(),
            finishedGood.getProductCode(),
            BigDecimal.ONE);

    finishedGoodsService.releaseReservationsForOrder(order.getId());

    assertThat(reservationsFor(company, order.getId())).isEmpty();
  }

  @Test
  void releaseReservationsForOrder_cancelsActiveRowsWithoutBatch_and_preservesTerminalSlips() {
    Company company = seedCompany("RES-RELEASE-EDGE");
    FinishedGood finishedGood =
        createFinishedGood(
            company, "FG-RES-RELEASE-EDGE", new BigDecimal("5"), new BigDecimal("2"));
    FinishedGoodBatch batch =
        createBatch(
            finishedGood,
            "BATCH-RES-RELEASE-EDGE",
            new BigDecimal("5"),
            new BigDecimal("3"),
            new BigDecimal("8"));
    SalesOrder order =
        createOrder(
            company,
            "SO-RES-RELEASE-EDGE-" + UUID.randomUUID(),
            finishedGood.getProductCode(),
            new BigDecimal("2"));

    PackagingSlip activeSlip = createSlip(company, order, "RESERVED", batch, new BigDecimal("2"));
    PackagingSlip dispatchedSlip =
        createSlip(company, order, "PS-RES-RELEASE-DISP", "DISPATCHED", batch, new BigDecimal("2"));
    InventoryReservation activeReservation =
        createReservation(order, finishedGood, batch, new BigDecimal("2"));
    activeReservation.setFinishedGoodBatch(null);
    inventoryReservationRepository.saveAndFlush(activeReservation);

    InventoryReservation terminalReservation =
        createReservation(order, finishedGood, batch, BigDecimal.ONE);
    terminalReservation.setStatus("FULFILLED");
    terminalReservation.setReservedQuantity(BigDecimal.ZERO);
    inventoryReservationRepository.saveAndFlush(terminalReservation);

    finishedGoodsService.releaseReservationsForOrder(order.getId());

    PackagingSlip reloadedActiveSlip =
        packagingSlipRepository.findByIdAndCompany(activeSlip.getId(), company).orElseThrow();
    PackagingSlip reloadedDispatchedSlip =
        packagingSlipRepository.findByIdAndCompany(dispatchedSlip.getId(), company).orElseThrow();
    List<InventoryReservation> reservations = reservationsFor(company, order.getId());
    assertThat(reloadedActiveSlip.getStatus()).isEqualTo("CANCELLED");
    assertThat(reloadedActiveSlip.getLines()).isEmpty();
    assertThat(reloadedActiveSlip.getDispatchNotes()).isEqualTo("Order cancelled");
    assertThat(reloadedDispatchedSlip.getStatus()).isEqualTo("DISPATCHED");
    assertThat(
            reservations.stream()
                .filter(existing -> existing.getId().equals(activeReservation.getId()))
                .findFirst()
                .orElseThrow()
                .getStatus())
        .isEqualTo("CANCELLED");
    assertThat(
            reservations.stream()
                .filter(existing -> existing.getId().equals(activeReservation.getId()))
                .findFirst()
                .orElseThrow()
                .getReservedQuantity())
        .isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(
            reservations.stream()
                .filter(existing -> existing.getId().equals(terminalReservation.getId()))
                .findFirst()
                .orElseThrow()
                .getStatus())
        .isEqualTo("FULFILLED");
  }

  @Test
  void synchronizeReservationsForSlip_returnsFalseWhenSlipHasNoLines() {
    PackagingSlip slip = new PackagingSlip();

    assertThat(synchronizeReservationsForSlip(slip, new SalesOrder())).isFalse();
  }

  @Test
  void synchronizeReservationsForSlip_returnsFalseWhenSlipLineBatchMetadataIsMissing() {
    PackagingSlip slip = new PackagingSlip();
    PackagingSlipLine line = new PackagingSlipLine();
    line.setPackagingSlip(slip);
    line.setQuantity(BigDecimal.ONE);
    slip.getLines().add(line);

    assertThat(synchronizeReservationsForSlip(slip, new SalesOrder())).isFalse();
  }

  @Test
  void synchronizeReservationsForSlip_returnsFalseWhenActiveReservationBatchShapeDrifts() {
    Company company = seedCompany("RES-SYNC-DRIFT");
    FinishedGood finishedGood =
        createFinishedGood(company, "FG-RES-SYNC-DRIFT", new BigDecimal("4"), BigDecimal.ZERO);
    FinishedGoodBatch slipBatch =
        createBatch(
            finishedGood,
            "BATCH-RES-SYNC-DRIFT-SLIP",
            new BigDecimal("2"),
            new BigDecimal("2"),
            new BigDecimal("8"));
    FinishedGoodBatch reservationBatch =
        createBatch(
            finishedGood,
            "BATCH-RES-SYNC-DRIFT-RES",
            new BigDecimal("2"),
            new BigDecimal("2"),
            new BigDecimal("9"));
    SalesOrder order =
        createOrder(
            company,
            "SO-RES-SYNC-DRIFT-" + UUID.randomUUID(),
            finishedGood.getProductCode(),
            BigDecimal.ONE);

    PackagingSlip slip = createSlip(company, order, "RESERVED", slipBatch, BigDecimal.ONE);
    createReservation(order, finishedGood, reservationBatch, BigDecimal.ONE);

    assertThat(synchronizeReservationsForSlip(slip, order)).isFalse();
  }

  @Test
  void synchronizeReservationsForSlip_returnsFalseWhenReservationBatchMetadataIsMissing() {
    Company company = seedCompany("RES-SYNC-BATCH-MISSING");
    FinishedGood finishedGood =
        createFinishedGood(
            company, "FG-RES-SYNC-BATCH-MISSING", new BigDecimal("4"), BigDecimal.ZERO);
    FinishedGoodBatch batch =
        createBatch(
            finishedGood,
            "BATCH-RES-SYNC-BATCH-MISSING",
            new BigDecimal("4"),
            new BigDecimal("4"),
            new BigDecimal("8"));
    SalesOrder order =
        createOrder(
            company,
            "SO-RES-SYNC-BATCH-" + UUID.randomUUID().toString().substring(0, 8),
            finishedGood.getProductCode(),
            BigDecimal.ONE);

    PackagingSlip slip = createSlip(company, order, "RESERVED", batch, BigDecimal.ONE);
    InventoryReservation reservation =
        createReservation(order, finishedGood, batch, BigDecimal.ONE);
    reservation.setFinishedGoodBatch(null);
    inventoryReservationRepository.saveAndFlush(reservation);

    assertThat(synchronizeReservationsForSlip(slip, order)).isFalse();
  }

  @Test
  void synchronizeReservationsForSlip_acceptsTerminalReplayWhenOnlyFulfilledReservationsRemain() {
    Company company = seedCompany("RES-SYNC-FULFILLED");
    FinishedGood finishedGood =
        createFinishedGood(company, "FG-RES-SYNC-FULFILLED", new BigDecimal("3"), BigDecimal.ZERO);
    FinishedGoodBatch batch =
        createBatch(
            finishedGood,
            "BATCH-RES-SYNC-FULFILLED",
            new BigDecimal("3"),
            BigDecimal.ZERO,
            new BigDecimal("8"));
    SalesOrder order =
        createOrder(
            company,
            "SO-RES-SYNC-FULFILLED-" + UUID.randomUUID(),
            finishedGood.getProductCode(),
            BigDecimal.ONE);

    PackagingSlip slip = createSlip(company, order, "DISPATCHED", batch, BigDecimal.ONE);
    InventoryReservation fulfilledReservation =
        createReservation(order, finishedGood, batch, BigDecimal.ONE);
    fulfilledReservation.setStatus("FULFILLED");
    fulfilledReservation.setReservedQuantity(BigDecimal.ZERO);
    inventoryReservationRepository.saveAndFlush(fulfilledReservation);

    assertThat(synchronizeReservationsForSlip(slip, order)).isTrue();
  }

  @Test
  void quantitiesMatch_returnsFalseWhenReservationTotalsDrift() {
    InventoryReservation first = new InventoryReservation();
    first.setQuantity(new BigDecimal("2"));
    InventoryReservation second = new InventoryReservation();
    second.setQuantity(new BigDecimal("3"));

    assertThat(quantitiesMatch(List.of(first, second), new BigDecimal("4"))).isFalse();
  }

  @Test
  void
      fulfilledReservationQuantitiesByBatchId_returnsEmptyMapWhenFulfilledReservationLacksBatchMetadata() {
    InventoryReservation reservation = new InventoryReservation();
    reservation.setStatus("FULFILLED");
    reservation.setQuantity(BigDecimal.ONE);

    assertThat(fulfilledReservationQuantitiesByBatchId(List.of(reservation))).isEmpty();
  }

  @Test
  void reserveForOrder_rebuildsReservationsFromSlipWhenActiveRowsAreMissing() {
    Company company = seedCompany("RES-SYNC-REBUILD");
    FinishedGood finishedGood =
        createFinishedGood(company, "FG-RES-SYNC-REBUILD", new BigDecimal("3"), BigDecimal.ZERO);
    FinishedGoodBatch batch =
        createBatch(
            finishedGood,
            "BATCH-RES-SYNC-REBUILD",
            new BigDecimal("3"),
            new BigDecimal("3"),
            new BigDecimal("8"));
    SalesOrder order =
        createOrder(
            company,
            "SO-RES-SYNC-REBUILD-" + UUID.randomUUID(),
            finishedGood.getProductCode(),
            BigDecimal.ONE);

    PackagingSlip slip = createSlip(company, order, "RESERVED", batch, BigDecimal.ONE);

    FinishedGoodsService.InventoryReservationResult replay =
        finishedGoodsService.reserveForOrder(order);

    List<InventoryReservation> reservations = reservationsFor(company, order.getId());
    assertThat(replay.shortages()).isEmpty();
    assertThat(reservations).hasSize(1);
    assertThat(reservations.getFirst().getFinishedGoodBatch().getId()).isEqualTo(batch.getId());
    assertThat(reservations.getFirst().getReservedQuantity()).isEqualByComparingTo(BigDecimal.ONE);
    assertThat(
            packagingSlipRepository
                .findByIdAndCompany(slip.getId(), company)
                .orElseThrow()
                .getStatus())
        .isEqualTo("RESERVED");
    assertThat(
            finishedGoodRepository.findById(finishedGood.getId()).orElseThrow().getReservedStock())
        .isEqualByComparingTo(BigDecimal.ONE);
  }

  @Test
  void rebuildReservationsFromSlip_rejectsMissingSlipLinesAndBatchMetadata() {
    PackagingSlip emptySlip = new PackagingSlip();
    assertThatThrownBy(() -> rebuildReservationsFromSlip(emptySlip, 7L))
        .hasMessageContaining("No packaging slip lines available");

    PackagingSlip invalidSlip = new PackagingSlip();
    PackagingSlipLine line = new PackagingSlipLine();
    line.setPackagingSlip(invalidSlip);
    line.setQuantity(BigDecimal.ONE);
    invalidSlip.getLines().add(line);

    assertThatThrownBy(() -> rebuildReservationsFromSlip(invalidSlip, 8L))
        .hasMessageContaining("Cannot rebuild reservation without a finished good batch");
  }

  @Test
  void applySynchronizedAllocation_transitionsFulfilledPartialAndBackorderStates() {
    PackagingSlip fallbackSlip = new PackagingSlip();
    fallbackSlip.setStatus("RESERVED");

    InventoryReservation fulfilled = new InventoryReservation();
    fulfilled.setQuantity(new BigDecimal("5"));
    fulfilled.setReservedQuantity(new BigDecimal("5"));
    fulfilled.setFulfilledQuantity(new BigDecimal("5"));
    fulfilled.setStatus("RESERVED");

    applySynchronizedAllocation(fulfilled, new BigDecimal("5"), fallbackSlip);

    assertThat(fulfilled.getStatus()).isEqualTo("FULFILLED");
    assertThat(zeroIfNull(fulfilled.getReservedQuantity())).isEqualByComparingTo(BigDecimal.ZERO);

    InventoryReservation partial = new InventoryReservation();
    partial.setQuantity(new BigDecimal("5"));
    partial.setReservedQuantity(new BigDecimal("5"));
    partial.setFulfilledQuantity(new BigDecimal("2"));
    partial.setStatus("RESERVED");

    applySynchronizedAllocation(partial, new BigDecimal("5"), fallbackSlip);

    assertThat(partial.getStatus()).isEqualTo("PARTIAL");
    assertThat(partial.getReservedQuantity()).isEqualByComparingTo(new BigDecimal("3"));

    PackagingSlip backorderSlip = new PackagingSlip();
    backorderSlip.setStatus("BACKORDER");
    backorderSlip.setBackorder(true);
    InventoryReservation backorder = new InventoryReservation();
    backorder.setQuantity(new BigDecimal("5"));
    backorder.setReservedQuantity(new BigDecimal("5"));
    backorder.setFulfilledQuantity(BigDecimal.ZERO);
    backorder.setStatus("RESERVED");

    applySynchronizedAllocation(backorder, new BigDecimal("5"), backorderSlip);

    assertThat(backorder.getStatus()).isEqualTo("BACKORDER");
    assertThat(backorder.getReservedQuantity()).isEqualByComparingTo(new BigDecimal("5"));

    InventoryReservation alreadyBackordered = new InventoryReservation();
    alreadyBackordered.setQuantity(new BigDecimal("5"));
    alreadyBackordered.setReservedQuantity(new BigDecimal("5"));
    alreadyBackordered.setFulfilledQuantity(BigDecimal.ZERO);
    alreadyBackordered.setStatus("BACKORDER");

    applySynchronizedAllocation(alreadyBackordered, new BigDecimal("5"), fallbackSlip);

    assertThat(alreadyBackordered.getStatus()).isEqualTo("BACKORDER");
    assertThat(alreadyBackordered.getReservedQuantity()).isEqualByComparingTo(new BigDecimal("5"));

    InventoryReservation zeroAllocation = new InventoryReservation();
    zeroAllocation.setQuantity(new BigDecimal("5"));
    zeroAllocation.setReservedQuantity(new BigDecimal("5"));
    zeroAllocation.setFulfilledQuantity(BigDecimal.ZERO);
    zeroAllocation.setStatus("RESERVED");

    applySynchronizedAllocation(zeroAllocation, BigDecimal.ZERO, fallbackSlip);

    assertThat(zeroAllocation.getStatus()).isEqualTo("FULFILLED");
    assertThat(zeroIfNull(zeroAllocation.getReservedQuantity()))
        .isEqualByComparingTo(BigDecimal.ZERO);
  }

  @Test
  void applySynchronizedAllocation_coversExplicitReservedAndSlipStatusBackorderBranches() {
    PackagingSlip reservedSlip = new PackagingSlip();
    reservedSlip.setStatus("RESERVED");

    InventoryReservation reserved = new InventoryReservation();
    reserved.setQuantity(new BigDecimal("3"));
    reserved.setReservedQuantity(new BigDecimal("3"));
    reserved.setFulfilledQuantity(BigDecimal.ZERO);
    reserved.setStatus("RESERVED");
    applySynchronizedAllocation(reserved, new BigDecimal("3"), reservedSlip);
    assertThat(reserved.getStatus()).isEqualTo("RESERVED");

    PackagingSlip backorderStatusSlip = new PackagingSlip();
    backorderStatusSlip.setStatus("BACKORDER");

    InventoryReservation reservation = new InventoryReservation();
    reservation.setQuantity(new BigDecimal("3"));
    reservation.setReservedQuantity(new BigDecimal("3"));
    reservation.setFulfilledQuantity(BigDecimal.ZERO);
    reservation.setStatus("RESERVED");
    applySynchronizedAllocation(reservation, new BigDecimal("3"), backorderStatusSlip);
    assertThat(reservation.getStatus()).isEqualTo("BACKORDER");
  }

  @Test
  void terminalReplayAndActiveReservationHelpersFailClosedForNonTerminalInputs() {
    Company company = seedCompany("RES-TERMINAL-HELPERS");
    FinishedGood finishedGood =
        createFinishedGood(
            company, "FG-RES-TERMINAL-HELPERS", new BigDecimal("2"), BigDecimal.ZERO);
    FinishedGoodBatch batch =
        createBatch(
            finishedGood,
            "BATCH-RES-TERMINAL-HELPERS",
            new BigDecimal("2"),
            new BigDecimal("2"),
            new BigDecimal("7"));

    PackagingSlip dispatchedSlip = new PackagingSlip();
    dispatchedSlip.setStatus("DISPATCHED");

    InventoryReservation partialReservation = new InventoryReservation();
    partialReservation.setFinishedGoodBatch(batch);
    partialReservation.setStatus("PARTIAL");
    partialReservation.setQuantity(BigDecimal.ONE);

    assertThat(
            isTerminalDispatchReplay(
                dispatchedSlip, List.of(partialReservation), Map.of(batch.getId(), BigDecimal.ONE)))
        .isFalse();
    assertThat(isActiveReservation(null)).isFalse();

    InventoryReservation fulfilledReservation = new InventoryReservation();
    fulfilledReservation.setStatus("FULFILLED");
    assertThat(isActiveReservation(fulfilledReservation)).isFalse();

    InventoryReservation reservedReservation = new InventoryReservation();
    reservedReservation.setStatus("RESERVED");
    assertThat(isActiveReservation(reservedReservation)).isTrue();

    InventoryReservation nullStatusReservation = new InventoryReservation();
    assertThat(isActiveReservation(nullStatusReservation)).isTrue();
  }

  @Test
  void terminalReplayHelpers_coverFulfilledReplayBranches() {
    Company company = seedCompany("RES-TERMINAL-FULFILLED");
    FinishedGood finishedGood =
        createFinishedGood(
            company, "FG-RES-TERMINAL-FULFILLED", new BigDecimal("3"), BigDecimal.ZERO);
    FinishedGoodBatch batch =
        createBatch(
            finishedGood,
            "BATCH-RES-TERMINAL-FULFILLED",
            new BigDecimal("3"),
            new BigDecimal("3"),
            new BigDecimal("7"));

    PackagingSlip dispatchedSlip = new PackagingSlip();
    dispatchedSlip.setStatus("DISPATCHED");

    InventoryReservation fulfilledReservation = new InventoryReservation();
    fulfilledReservation.setFinishedGoodBatch(batch);
    fulfilledReservation.setStatus("FULFILLED");
    fulfilledReservation.setQuantity(new BigDecimal("2"));

    InventoryReservation reservedReservation = new InventoryReservation();
    reservedReservation.setFinishedGoodBatch(batch);
    reservedReservation.setStatus("RESERVED");
    reservedReservation.setQuantity(BigDecimal.ONE);

    List<InventoryReservation> mixedReservations = new ArrayList<>();
    mixedReservations.add(null);
    mixedReservations.add(reservedReservation);
    mixedReservations.add(fulfilledReservation);

    assertThat(fulfilledReservationQuantitiesByBatchId(mixedReservations))
        .containsEntry(batch.getId(), new BigDecimal("2"));
    assertThat(
            isTerminalDispatchReplay(
                dispatchedSlip,
                List.of(fulfilledReservation),
                Map.of(batch.getId(), new BigDecimal("2"))))
        .isTrue();
    assertThat(
            isTerminalDispatchReplay(
                dispatchedSlip,
                List.of(fulfilledReservation),
                Map.of(batch.getId(), BigDecimal.ONE)))
        .isFalse();
  }

  @Test
  void dispatchedMovementQuantitiesByBatchId_fallsBackToSalesOrderDispatchRows() {
    Company company = seedCompany("RES-DISPATCH-FALLBACK");
    FinishedGood finishedGood =
        createFinishedGood(
            company, "FG-RES-DISPATCH-FALLBACK", new BigDecimal("3"), BigDecimal.ZERO);
    FinishedGoodBatch batch =
        createBatch(
            finishedGood,
            "BATCH-RES-DISPATCH-FALLBACK",
            new BigDecimal("3"),
            BigDecimal.ZERO,
            new BigDecimal("7"));
    SalesOrder order =
        createOrder(
            company,
            "SO-RES-DISPATCH-FALLBACK-" + UUID.randomUUID(),
            finishedGood.getProductCode(),
            new BigDecimal("3"));
    PackagingSlip slip = createSlip(company, order, "DISPATCHED", batch, new BigDecimal("3"));

    InventoryMovement movement = new InventoryMovement();
    movement.setFinishedGood(finishedGood);
    movement.setFinishedGoodBatch(batch);
    movement.setReferenceType(InventoryReference.SALES_ORDER);
    movement.setReferenceId(order.getId().toString());
    movement.setMovementType("DISPATCH");
    movement.setQuantity(new BigDecimal("3"));
    movement.setUnitCost(batch.getUnitCost());
    inventoryMovementRepository.saveAndFlush(movement);

    assertThat(dispatchedMovementQuantitiesByBatchId(slip))
        .containsEntry(batch.getId(), new BigDecimal("3"));
  }

  @Test
  void dispatchedMovementQuantitiesByBatchId_prefersPackingSlipDispatchRows() {
    Company company = seedCompany("RES-DISPATCH-DIRECT");
    FinishedGood finishedGood =
        createFinishedGood(company, "FG-RES-DISPATCH-DIRECT", new BigDecimal("3"), BigDecimal.ZERO);
    FinishedGoodBatch batch =
        createBatch(
            finishedGood,
            "BATCH-RES-DISPATCH-DIRECT",
            new BigDecimal("3"),
            BigDecimal.ZERO,
            new BigDecimal("7"));
    SalesOrder order =
        createOrder(
            company,
            "SO-RES-DISP-DIR-" + UUID.randomUUID().toString().substring(0, 8),
            finishedGood.getProductCode(),
            new BigDecimal("3"));
    PackagingSlip slip = createSlip(company, order, "DISPATCHED", batch, new BigDecimal("3"));

    InventoryMovement movement = new InventoryMovement();
    movement.setFinishedGood(finishedGood);
    movement.setFinishedGoodBatch(batch);
    movement.setReferenceType(InventoryReference.SALES_ORDER);
    movement.setReferenceId(order.getId().toString());
    movement.setPackingSlipId(slip.getId());
    movement.setMovementType("DISPATCH");
    movement.setQuantity(new BigDecimal("3"));
    movement.setUnitCost(batch.getUnitCost());
    inventoryMovementRepository.saveAndFlush(movement);

    assertThat(dispatchedMovementQuantitiesByBatchId(slip))
        .containsEntry(batch.getId(), new BigDecimal("3"));
  }

  @Test
  void isTerminalDispatchReplay_usesDispatchMovementFallbackWhenRowsMatchExpected() {
    Company company = seedCompany("RES-TERMINAL-MOVEMENT");
    FinishedGood finishedGood =
        createFinishedGood(
            company, "FG-RES-TERMINAL-MOVEMENT", new BigDecimal("2"), BigDecimal.ZERO);
    FinishedGoodBatch batch =
        createBatch(
            finishedGood,
            "BATCH-RES-TERMINAL-MOVEMENT",
            new BigDecimal("2"),
            BigDecimal.ZERO,
            new BigDecimal("7"));
    SalesOrder order =
        createOrder(
            company,
            "SO-RES-TERMINAL-MOVEMENT-" + UUID.randomUUID(),
            finishedGood.getProductCode(),
            BigDecimal.ONE);
    PackagingSlip slip = createSlip(company, order, "DISPATCHED", batch, BigDecimal.ONE);

    InventoryMovement movement = new InventoryMovement();
    movement.setFinishedGood(finishedGood);
    movement.setFinishedGoodBatch(batch);
    movement.setReferenceType(InventoryReference.SALES_ORDER);
    movement.setReferenceId(order.getId().toString());
    movement.setPackingSlipId(slip.getId());
    movement.setMovementType("DISPATCH");
    movement.setQuantity(BigDecimal.ONE);
    movement.setUnitCost(batch.getUnitCost());
    inventoryMovementRepository.saveAndFlush(movement);

    assertThat(isTerminalDispatchReplay(slip, List.of(), Map.of(batch.getId(), BigDecimal.ONE)))
        .isTrue();
    assertThat(isTerminalDispatchReplay(null, List.of(), Map.of())).isFalse();
  }

  @Test
  void
      dispatchedMovementQuantitiesByBatchId_returnsEmptyMapWhenFallbackMovementLacksBatchMetadata() {
    Company company = seedCompany("RES-DISPATCH-FALLBACK-INVALID");
    FinishedGood finishedGood =
        createFinishedGood(
            company, "FG-RES-DISPATCH-FALLBACK-INVALID", new BigDecimal("3"), BigDecimal.ZERO);
    FinishedGoodBatch batch =
        createBatch(
            finishedGood,
            "BATCH-RES-DISPATCH-FALLBACK-INVALID",
            new BigDecimal("3"),
            BigDecimal.ZERO,
            new BigDecimal("7"));
    SalesOrder order =
        createOrder(
            company,
            "SO-RES-DISP-INV-" + UUID.randomUUID().toString().substring(0, 8),
            finishedGood.getProductCode(),
            new BigDecimal("3"));
    PackagingSlip slip = createSlip(company, order, "DISPATCHED", batch, new BigDecimal("3"));

    InventoryMovement movement = new InventoryMovement();
    movement.setFinishedGood(finishedGood);
    movement.setReferenceType(InventoryReference.SALES_ORDER);
    movement.setReferenceId(order.getId().toString());
    movement.setMovementType("DISPATCH");
    movement.setQuantity(new BigDecimal("3"));
    movement.setUnitCost(batch.getUnitCost());
    inventoryMovementRepository.saveAndFlush(movement);

    assertThat(dispatchedMovementQuantitiesByBatchId(slip)).isEmpty();
  }

  @Test
  void dispatchedMovementQuantitiesByBatchId_returnsEmptyWhenSlipCannotFallbackToSalesOrder() {
    PackagingSlip slip = new PackagingSlip();
    slip.setStatus("DISPATCHED");

    assertThat(dispatchedMovementQuantitiesByBatchId(slip)).isEmpty();
  }

  @Test
  void dispatchedMovementQuantitiesByBatchId_returnsEmptyWhenDirectMovementLacksBatchMetadata() {
    Company company = seedCompany("RES-DISPATCH-DIRECT-INVALID");
    FinishedGood finishedGood =
        createFinishedGood(
            company, "FG-RES-DISPATCH-DIRECT-INVALID", new BigDecimal("3"), BigDecimal.ZERO);
    FinishedGoodBatch batch =
        createBatch(
            finishedGood,
            "BATCH-RES-DISPATCH-DIRECT-INVALID",
            new BigDecimal("3"),
            BigDecimal.ZERO,
            new BigDecimal("7"));
    SalesOrder order =
        createOrder(
            company,
            "SO-RES-DISP-DIR-INV-" + UUID.randomUUID().toString().substring(0, 8),
            finishedGood.getProductCode(),
            new BigDecimal("3"));
    PackagingSlip slip = createSlip(company, order, "DISPATCHED", batch, new BigDecimal("3"));

    InventoryMovement movement = new InventoryMovement();
    movement.setFinishedGood(finishedGood);
    movement.setReferenceType(InventoryReference.SALES_ORDER);
    movement.setReferenceId(order.getId().toString());
    movement.setPackingSlipId(slip.getId());
    movement.setMovementType("DISPATCH");
    movement.setQuantity(new BigDecimal("3"));
    movement.setUnitCost(batch.getUnitCost());
    inventoryMovementRepository.saveAndFlush(movement);

    assertThat(dispatchedMovementQuantitiesByBatchId(slip)).isEmpty();
  }

  @Test
  void lockTouchedReservationHelpers_skipRowsMissingEntityIds() {
    Company company = seedCompany("RES-LOCK-HELPERS");
    FinishedGood finishedGood =
        createFinishedGood(company, "FG-RES-LOCK-HELPERS", new BigDecimal("2"), BigDecimal.ZERO);
    FinishedGoodBatch batch =
        createBatch(
            finishedGood,
            "BATCH-RES-LOCK-HELPERS",
            new BigDecimal("2"),
            new BigDecimal("2"),
            new BigDecimal("7"));
    SalesOrder order =
        createOrder(
            company,
            "SO-RES-LOCK-HELPERS-" + UUID.randomUUID(),
            finishedGood.getProductCode(),
            BigDecimal.ONE);

    InventoryReservation valid = createReservation(order, finishedGood, batch, BigDecimal.ONE);
    InventoryReservation missingFinishedGood = new InventoryReservation();
    missingFinishedGood.setFinishedGoodBatch(batch);
    missingFinishedGood.setFinishedGood(new FinishedGood());
    InventoryReservation missingBatch = new InventoryReservation();
    missingBatch.setFinishedGood(finishedGood);
    missingBatch.setFinishedGoodBatch(new FinishedGoodBatch());

    assertThat(lockTouchedGoods(company, List.of(valid, missingFinishedGood)))
        .containsOnlyKeys(finishedGood.getId());
    assertThat(lockTouchedBatches(List.of(valid, missingBatch))).containsOnlyKeys(batch.getId());
  }

  @Test
  void resolveReservedQuantity_andSlipStatusHelpers_coverFallbackBranches() {
    Company company = seedCompany("RES-STATUS-HELPERS");
    FinishedGood finishedGood =
        createFinishedGood(company, "FG-RES-STATUS-HELPERS", new BigDecimal("2"), BigDecimal.ZERO);
    FinishedGoodBatch batch =
        createBatch(
            finishedGood,
            "BATCH-RES-STATUS-HELPERS",
            new BigDecimal("2"),
            new BigDecimal("2"),
            new BigDecimal("7"));
    SalesOrder order =
        createOrder(
            company,
            "SO-RES-STATUS-" + UUID.randomUUID().toString().substring(0, 8),
            finishedGood.getProductCode(),
            BigDecimal.ONE);

    InventoryReservation reservedFallback = new InventoryReservation();
    reservedFallback.setStatus("RESERVED");
    reservedFallback.setQuantity(new BigDecimal("4"));
    reservedFallback.setReservedQuantity(BigDecimal.ZERO);
    assertThat(resolveReservedQuantity(reservedFallback)).isEqualByComparingTo(new BigDecimal("4"));

    InventoryReservation cancelled = new InventoryReservation();
    cancelled.setStatus("CANCELLED");
    cancelled.setQuantity(new BigDecimal("4"));
    assertThat(resolveReservedQuantity(cancelled)).isEqualByComparingTo(BigDecimal.ZERO);

    InventoryReservation fulfilled = new InventoryReservation();
    fulfilled.setStatus("FULFILLED");
    fulfilled.setQuantity(new BigDecimal("4"));
    assertThat(resolveReservedQuantity(fulfilled)).isEqualByComparingTo(BigDecimal.ZERO);

    PackagingSlip pendingSlip = createSlip(company, order, "PENDING", batch, BigDecimal.ONE);
    updateSlipStatusBasedOnAvailability(pendingSlip, List.of());
    assertThat(pendingSlip.getStatus()).isEqualTo("RESERVED");

    SalesOrder shortageOrder =
        createOrder(
            company,
            "SO-RES-STATUS-SHORT-" + UUID.randomUUID().toString().substring(0, 8),
            finishedGood.getProductCode(),
            BigDecimal.ONE);
    PackagingSlip shortageSlip =
        createSlip(company, shortageOrder, "PS-RES-STATUS-SHORT", "PENDING", batch, BigDecimal.ONE);
    updateSlipStatusBasedOnAvailability(
        shortageSlip,
        List.of(new FinishedGoodsService.InventoryShortage("FG", BigDecimal.ONE, "FG")));
    assertThat(shortageSlip.getStatus()).isEqualTo("PENDING_PRODUCTION");

    SalesOrder dispatchedOrder =
        createOrder(
            company,
            "SO-RES-STATUS-DISP-" + UUID.randomUUID().toString().substring(0, 8),
            finishedGood.getProductCode(),
            BigDecimal.ONE);
    PackagingSlip dispatchedSlip =
        createSlip(
            company, dispatchedOrder, "PS-RES-STATUS-DISP", "DISPATCHED", batch, BigDecimal.ONE);
    updateSlipStatusBasedOnAvailability(dispatchedSlip, List.of());
    assertThat(dispatchedSlip.getStatus()).isEqualTo("DISPATCHED");

    PackagingSlip cancelledSlip =
        createSlip(
            company,
            createOrder(
                company,
                "SO-RES-STATUS-CANCEL-" + UUID.randomUUID().toString().substring(0, 8),
                finishedGood.getProductCode(),
                BigDecimal.ONE),
            "PS-RES-STATUS-CANCEL",
            "CANCELLED",
            batch,
            BigDecimal.ONE);
    updateSlipStatusBasedOnAvailability(cancelledSlip, new ArrayList<>());
    assertThat(cancelledSlip.getStatus()).isEqualTo("CANCELLED");

    PackagingSlip backorderSlip =
        createSlip(
            company,
            createOrder(
                company,
                "SO-RES-STATUS-BO-" + UUID.randomUUID().toString().substring(0, 8),
                finishedGood.getProductCode(),
                BigDecimal.ONE),
            "PS-RES-STATUS-BO",
            "BACKORDER",
            batch,
            BigDecimal.ONE);
    updateSlipStatusBasedOnAvailability(backorderSlip, List.of());
    assertThat(backorderSlip.getStatus()).isEqualTo("BACKORDER");

    updateSlipStatusBasedOnAvailability(null, List.of());
  }

  @Test
  void slipLinesMatchOrder_usesQuantityFallbackAndRejectsQuantityDrift() {
    Company company = seedCompany("RES-SHAPE-QTY-FALLBACK");
    FinishedGood finishedGood =
        createFinishedGood(
            company, "FG-RES-SHAPE-QTY-FALLBACK", new BigDecimal("4"), BigDecimal.ZERO);
    FinishedGoodBatch batch =
        createBatch(
            finishedGood,
            "BATCH-RES-SHAPE-QTY-FALLBACK",
            new BigDecimal("4"),
            new BigDecimal("4"),
            new BigDecimal("8"));
    SalesOrder order =
        createOrder(
            company,
            "SO-RES-SHAPE-QTY-" + UUID.randomUUID(),
            finishedGood.getProductCode(),
            new BigDecimal("2"));

    PackagingSlip matchingSlip = new PackagingSlip();
    PackagingSlipLine matchingLine = new PackagingSlipLine();
    matchingLine.setPackagingSlip(matchingSlip);
    matchingLine.setFinishedGoodBatch(batch);
    matchingLine.setQuantity(new BigDecimal("2"));
    matchingSlip.getLines().add(matchingLine);

    PackagingSlip mismatchedSlip = new PackagingSlip();
    PackagingSlipLine mismatchedLine = new PackagingSlipLine();
    mismatchedLine.setPackagingSlip(mismatchedSlip);
    mismatchedLine.setFinishedGoodBatch(batch);
    mismatchedLine.setQuantity(BigDecimal.ONE);
    mismatchedSlip.getLines().add(mismatchedLine);

    assertThat(reservationEngine().slipLinesMatchOrder(matchingSlip, order)).isTrue();
    assertThat(reservationEngine().slipLinesMatchOrder(mismatchedSlip, order)).isFalse();
  }

  @Test
  void selectBatchesByCostingMethod_coversWeightedAverageAndLifoBranches() {
    Company company = seedCompany("RES-COSTING-BRANCH");
    FinishedGood finishedGood =
        createFinishedGood(company, "FG-RES-COSTING-BRANCH", new BigDecimal("6"), BigDecimal.ZERO);
    FinishedGoodBatch olderBatch =
        createBatch(
            finishedGood,
            "BATCH-RES-COSTING-OLD",
            new BigDecimal("3"),
            new BigDecimal("3"),
            new BigDecimal("8"));
    FinishedGoodBatch newerBatch =
        createBatch(
            finishedGood,
            "BATCH-RES-COSTING-NEW",
            new BigDecimal("3"),
            new BigDecimal("3"),
            new BigDecimal("9"));
    olderBatch.setManufacturedAt(Instant.now().minusSeconds(3600));
    newerBatch.setManufacturedAt(Instant.now());
    finishedGoodBatchRepository.saveAndFlush(olderBatch);
    finishedGoodBatchRepository.saveAndFlush(newerBatch);

    upsertCurrentPeriodCostingMethod(company, CostingMethod.WEIGHTED_AVERAGE);
    assertThat(selectBatchesByCostingMethod(finishedGood, LocalDate.now())).isNotEmpty();

    upsertCurrentPeriodCostingMethod(company, CostingMethod.LIFO);
    assertThat(selectBatchesByCostingMethod(finishedGood, LocalDate.now())).isNotEmpty();
  }

  @Test
  void slipLinesMatchOrder_returnsFalseWhenSlipLineBatchMetadataIsMissing() {
    SalesOrder order = new SalesOrder();
    SalesOrderItem item = new SalesOrderItem();
    item.setProductCode("FG-TEST");
    item.setQuantity(BigDecimal.ONE);
    order.getItems().add(item);

    PackagingSlip slip = new PackagingSlip();
    PackagingSlipLine line = new PackagingSlipLine();
    line.setPackagingSlip(slip);
    line.setOrderedQuantity(BigDecimal.ONE);
    slip.getLines().add(line);

    assertThat(reservationEngine().slipLinesMatchOrder(slip, order)).isFalse();
  }

  @Test
  void slipLinesMatchOrder_returnsFalseWhenSlipHasNoLines() {
    assertThat(reservationEngine().slipLinesMatchOrder(new PackagingSlip(), new SalesOrder()))
        .isFalse();
  }

  @Test
  void allocateItem_skipsEmptyBatches_and_recordsShortages() {
    Company company = seedCompany("RES-ALLOC-SHORT");
    FinishedGood finishedGood =
        createFinishedGood(company, "FG-RES-ALLOC-SHORT", BigDecimal.ZERO, BigDecimal.ZERO);
    FinishedGoodBatch emptyBatch =
        createBatch(
            finishedGood,
            "BATCH-RES-ALLOC-EMPTY",
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            new BigDecimal("8"));
    FinishedGoodBatch partialBatch =
        createBatch(
            finishedGood,
            "BATCH-RES-ALLOC-PARTIAL",
            BigDecimal.ONE,
            BigDecimal.ONE,
            new BigDecimal("9"));
    FinishedGoodBatch extraBatch =
        createBatch(
            finishedGood,
            "BATCH-RES-ALLOC-EXTRA",
            BigDecimal.ONE,
            BigDecimal.ONE,
            new BigDecimal("10"));
    emptyBatch.setManufacturedAt(Instant.now().minusSeconds(7200));
    partialBatch.setManufacturedAt(Instant.now().minusSeconds(3600));
    extraBatch.setManufacturedAt(Instant.now());
    finishedGoodBatchRepository.saveAndFlush(emptyBatch);
    finishedGoodBatchRepository.saveAndFlush(partialBatch);
    finishedGoodBatchRepository.saveAndFlush(extraBatch);
    upsertCurrentPeriodCostingMethod(company, CostingMethod.FIFO);

    SalesOrder order =
        createOrder(
            company,
            "SO-RES-ALLOC-SHORT-" + UUID.randomUUID(),
            finishedGood.getProductCode(),
            new BigDecimal("3"));
    PackagingSlip slip = new PackagingSlip();
    slip.setCompany(company);
    slip.setSalesOrder(order);
    slip.setSlipNumber("PS-RES-ALLOC-SHORT");
    slip.setStatus("PENDING");
    List<FinishedGoodsService.InventoryShortage> shortages = new ArrayList<>();

    allocateItem(order, slip, finishedGood, order.getItems().getFirst(), shortages);

    assertThat(slip.getLines()).hasSize(2);
    assertThat(slip.getLines())
        .extracting(line -> line.getFinishedGoodBatch().getId())
        .containsExactlyInAnyOrder(partialBatch.getId(), extraBatch.getId());
    assertThat(shortages)
        .singleElement()
        .satisfies(
            shortage -> {
              assertThat(shortage.productCode()).isEqualTo(finishedGood.getProductCode());
              assertThat(shortage.shortageQuantity()).isEqualByComparingTo(BigDecimal.ONE);
            });
    assertThat(
            finishedGoodBatchRepository
                .findById(emptyBatch.getId())
                .orElseThrow()
                .getQuantityAvailable())
        .isEqualByComparingTo(BigDecimal.ZERO);
  }

  @Test
  void lockFinishedGood_helpers_failClosed_whenFinishedGoodCannotBeFound() {
    Company company = seedCompany("RES-LOCK-MISSING");

    assertThatThrownBy(() -> lockFinishedGoodById(company, 999_999L))
        .hasMessageContaining("Finished good not found: 999999");
    assertThatThrownBy(() -> lockFinishedGoodByProductCode(company, "FG-MISSING"))
        .hasMessageContaining("Finished good not found for product code FG-MISSING");
  }

  private Company seedCompany(String code) {
    Company company = dataSeeder.ensureCompany(code, code + " Ltd");
    CompanyContextHolder.setCompanyCode(company.getCode());
    return company;
  }

  private SalesOrder createOrder(
      Company company, String orderNumber, String productCode, BigDecimal quantity) {
    return createOrder(company, orderNumber, List.of(new OrderLineSeed(productCode, quantity)));
  }

  private SalesOrder createOrder(Company company, String orderNumber, List<OrderLineSeed> items) {
    SalesOrder order = new SalesOrder();
    order.setCompany(company);
    order.setOrderNumber(orderNumber);
    order.setStatus("PENDING");
    order.setTotalAmount(BigDecimal.ZERO);
    order.setCurrency("INR");

    for (OrderLineSeed seed : items) {
      SalesOrderItem item = new SalesOrderItem();
      item.setSalesOrder(order);
      item.setProductCode(seed.productCode());
      item.setQuantity(seed.quantity());
      item.setUnitPrice(BigDecimal.ONE);
      item.setLineSubtotal(BigDecimal.ZERO);
      item.setLineTotal(BigDecimal.ZERO);
      order.getItems().add(item);
    }
    return salesOrderRepository.saveAndFlush(order);
  }

  private record OrderLineSeed(String productCode, BigDecimal quantity) {}

  private FinishedGood createFinishedGood(
      Company company, String productCode, BigDecimal currentStock, BigDecimal reservedStock) {
    FinishedGood finishedGood = new FinishedGood();
    finishedGood.setCompany(company);
    finishedGood.setProductCode(productCode);
    finishedGood.setName(productCode);
    finishedGood.setUnit("UNIT");
    finishedGood.setCostingMethod("FIFO");
    finishedGood.setCurrentStock(currentStock);
    finishedGood.setReservedStock(reservedStock);
    finishedGood.setValuationAccountId(100L);
    finishedGood.setCogsAccountId(200L);
    finishedGood.setRevenueAccountId(300L);
    finishedGood.setTaxAccountId(400L);
    return finishedGoodRepository.saveAndFlush(finishedGood);
  }

  private FinishedGoodBatch createBatch(
      FinishedGood finishedGood,
      String batchCode,
      BigDecimal quantityTotal,
      BigDecimal quantityAvailable,
      BigDecimal unitCost) {
    FinishedGoodBatch batch = new FinishedGoodBatch();
    batch.setFinishedGood(finishedGood);
    batch.setBatchCode(batchCode);
    batch.setQuantityTotal(quantityTotal);
    batch.setQuantityAvailable(quantityAvailable);
    batch.setUnitCost(unitCost);
    batch.setManufacturedAt(Instant.now());
    return finishedGoodBatchRepository.saveAndFlush(batch);
  }

  private PackagingSlip createSlip(
      Company company,
      SalesOrder order,
      String status,
      FinishedGoodBatch batch,
      BigDecimal quantity) {
    return createSlip(
        company, order, order.getOrderNumber() + "-PS", status, batch, new BigDecimal[] {quantity});
  }

  private PackagingSlip createSlip(
      Company company,
      SalesOrder order,
      String status,
      FinishedGoodBatch batch,
      BigDecimal... quantities) {
    return createSlip(company, order, order.getOrderNumber() + "-PS", status, batch, quantities);
  }

  private PackagingSlip createSlip(
      Company company,
      SalesOrder order,
      String slipNumber,
      String status,
      FinishedGoodBatch batch,
      BigDecimal... quantities) {
    PackagingSlip slip = new PackagingSlip();
    slip.setCompany(company);
    slip.setSalesOrder(order);
    slip.setSlipNumber(slipNumber);
    slip.setStatus(status);
    slip.setBackorder("BACKORDER".equalsIgnoreCase(status));

    for (BigDecimal quantity : quantities) {
      PackagingSlipLine line = new PackagingSlipLine();
      line.setPackagingSlip(slip);
      line.setFinishedGoodBatch(batch);
      line.setOrderedQuantity(quantity);
      line.setQuantity(quantity);
      line.setUnitCost(batch.getUnitCost());
      slip.getLines().add(line);
    }
    return packagingSlipRepository.saveAndFlush(slip);
  }

  private InventoryReservation createReservation(
      SalesOrder order, FinishedGood finishedGood, FinishedGoodBatch batch, BigDecimal quantity) {
    InventoryReservation reservation = new InventoryReservation();
    reservation.setFinishedGood(finishedGood);
    reservation.setFinishedGoodBatch(batch);
    reservation.setReferenceType(InventoryReference.SALES_ORDER);
    reservation.setReferenceId(order.getId().toString());
    reservation.setQuantity(quantity);
    reservation.setReservedQuantity(quantity);
    reservation.setStatus("RESERVED");
    return inventoryReservationRepository.saveAndFlush(reservation);
  }

  private PackagingSlip findPrimarySlip(Company company, Long orderId) {
    return packagingSlipRepository.findAllByCompanyAndSalesOrderId(company, orderId).stream()
        .filter(existing -> !existing.isBackorder())
        .findFirst()
        .orElseThrow();
  }

  private List<InventoryReservation> reservationsFor(Company company, Long orderId) {
    return inventoryReservationRepository.findByFinishedGoodCompanyAndReferenceTypeAndReferenceId(
        company, InventoryReference.SALES_ORDER, orderId.toString());
  }

  private BigDecimal zeroIfNull(BigDecimal value) {
    return value != null ? value : BigDecimal.ZERO;
  }

  private boolean synchronizeReservationsForSlip(PackagingSlip slip, SalesOrder order) {
    return Boolean.TRUE.equals(
        ReflectionTestUtils.invokeMethod(
            reservationEngine(), "synchronizeReservationsForSlip", slip, order));
  }

  private boolean quantitiesMatch(
      List<InventoryReservation> reservations, BigDecimal expectedQuantity) {
    return Boolean.TRUE.equals(
        ReflectionTestUtils.invokeMethod(
            reservationEngine(), "quantitiesMatch", reservations, expectedQuantity));
  }

  @SuppressWarnings("unchecked")
  private Map<Long, BigDecimal> fulfilledReservationQuantitiesByBatchId(
      List<InventoryReservation> reservations) {
    return (Map<Long, BigDecimal>)
        ReflectionTestUtils.invokeMethod(
            reservationEngine(), "fulfilledReservationQuantitiesByBatchId", reservations);
  }

  private void applySynchronizedAllocation(
      InventoryReservation reservation, BigDecimal allocation, PackagingSlip slip) {
    ReflectionTestUtils.invokeMethod(
        reservationEngine(), "applySynchronizedAllocation", reservation, allocation, slip);
  }

  private boolean isTerminalDispatchReplay(
      PackagingSlip slip,
      List<InventoryReservation> reservations,
      Map<Long, BigDecimal> expectedByBatchId) {
    return Boolean.TRUE.equals(
        ReflectionTestUtils.invokeMethod(
            reservationEngine(),
            "isTerminalDispatchReplay",
            slip,
            reservations,
            expectedByBatchId));
  }

  private boolean isActiveReservation(InventoryReservation reservation) {
    return Boolean.TRUE.equals(
        ReflectionTestUtils.invokeMethod(reservationEngine(), "isActiveReservation", reservation));
  }

  @SuppressWarnings("unchecked")
  private Map<Long, BigDecimal> dispatchedMovementQuantitiesByBatchId(PackagingSlip slip) {
    return (Map<Long, BigDecimal>)
        ReflectionTestUtils.invokeMethod(
            reservationEngine(), "dispatchedMovementQuantitiesByBatchId", slip);
  }

  @SuppressWarnings("unchecked")
  private Map<Long, FinishedGood> lockTouchedGoods(
      Company company, List<InventoryReservation> reservations) {
    return (Map<Long, FinishedGood>)
        ReflectionTestUtils.invokeMethod(
            reservationEngine(), "lockTouchedGoods", company, reservations);
  }

  @SuppressWarnings("unchecked")
  private Map<Long, FinishedGoodBatch> lockTouchedBatches(List<InventoryReservation> reservations) {
    return (Map<Long, FinishedGoodBatch>)
        ReflectionTestUtils.invokeMethod(reservationEngine(), "lockTouchedBatches", reservations);
  }

  private List<InventoryReservation> rebuildReservationsFromSlip(
      PackagingSlip slip, Long salesOrderId) {
    @SuppressWarnings("unchecked")
    List<InventoryReservation> reservations =
        (List<InventoryReservation>)
            ReflectionTestUtils.invokeMethod(
                reservationEngine(), "rebuildReservationsFromSlip", slip, salesOrderId);
    return reservations;
  }

  private BigDecimal resolveReservedQuantity(InventoryReservation reservation) {
    return (BigDecimal)
        ReflectionTestUtils.invokeMethod(
            reservationEngine(), "resolveReservedQuantity", reservation);
  }

  private void updateSlipStatusBasedOnAvailability(
      PackagingSlip slip, List<FinishedGoodsService.InventoryShortage> shortages) {
    ReflectionTestUtils.invokeMethod(
        reservationEngine(), "updateSlipStatusBasedOnAvailability", slip, shortages);
  }

  private void allocateItem(
      SalesOrder order,
      PackagingSlip slip,
      FinishedGood finishedGood,
      SalesOrderItem item,
      List<FinishedGoodsService.InventoryShortage> shortages) {
    ReflectionTestUtils.invokeMethod(
        reservationEngine(), "allocateItem", order, slip, finishedGood, item, shortages);
  }

  @SuppressWarnings("unchecked")
  private List<FinishedGoodBatch> selectBatchesByCostingMethod(
      FinishedGood finishedGood, LocalDate referenceDate) {
    return (List<FinishedGoodBatch>)
        ReflectionTestUtils.invokeMethod(
            reservationEngine(), "selectBatchesByCostingMethod", finishedGood, referenceDate);
  }

  private FinishedGood lockFinishedGoodById(Company company, Long id) {
    return (FinishedGood)
        ReflectionTestUtils.invokeMethod(reservationEngine(), "lockFinishedGood", company, id);
  }

  private FinishedGood lockFinishedGoodByProductCode(Company company, String productCode) {
    return (FinishedGood)
        ReflectionTestUtils.invokeMethod(
            reservationEngine(), "lockFinishedGood", company, productCode);
  }

  private void upsertCurrentPeriodCostingMethod(Company company, CostingMethod costingMethod) {
    LocalDate today = LocalDate.now();
    AccountingPeriod period =
        accountingPeriodRepository
            .findByCompanyAndYearAndMonth(company, today.getYear(), today.getMonthValue())
            .orElseGet(
                () -> {
                  AccountingPeriod created = new AccountingPeriod();
                  created.setCompany(company);
                  created.setYear(today.getYear());
                  created.setMonth(today.getMonthValue());
                  created.setStartDate(today.withDayOfMonth(1));
                  created.setEndDate(today.withDayOfMonth(1).plusMonths(1).minusDays(1));
                  created.setStatus(AccountingPeriodStatus.OPEN);
                  return created;
                });
    period.setCostingMethod(costingMethod);
    accountingPeriodRepository.saveAndFlush(period);
  }

  private FinishedGoodsReservationEngine reservationEngine() {
    Object workflowEngine = ReflectionTestUtils.getField(finishedGoodsService, "workflowEngine");
    return (FinishedGoodsReservationEngine)
        ReflectionTestUtils.getField(workflowEngine, "reservationEngine");
  }
}
