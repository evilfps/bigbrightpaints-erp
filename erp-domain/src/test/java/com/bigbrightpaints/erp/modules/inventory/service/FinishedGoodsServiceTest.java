package com.bigbrightpaints.erp.modules.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
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
import com.bigbrightpaints.erp.modules.inventory.dto.DispatchPreviewDto;
import com.bigbrightpaints.erp.modules.inventory.dto.FinishedGoodBatchRequest;
import com.bigbrightpaints.erp.modules.inventory.dto.StockSummaryDto;
import com.bigbrightpaints.erp.modules.reports.service.InventoryValuationService;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrder;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrderItem;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrderRepository;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;

@Transactional
class FinishedGoodsServiceTest extends AbstractIntegrationTest {

  @Autowired private FinishedGoodsService finishedGoodsService;

  @Autowired private FinishedGoodRepository finishedGoodRepository;

  @Autowired private FinishedGoodBatchRepository finishedGoodBatchRepository;

  @Autowired private PackagingSlipRepository packagingSlipRepository;

  @Autowired private InventoryReservationRepository inventoryReservationRepository;

  @Autowired private InventoryMovementRepository inventoryMovementRepository;

  @Autowired private SalesOrderRepository salesOrderRepository;

  @Autowired private InventoryValuationService inventoryValuationService;

  @Autowired private AccountingPeriodRepository accountingPeriodRepository;

  @AfterEach
  void clearContext() {
    CompanyContextHolder.clear();
  }

  @Test
  void dispatchUsesWacIncludingReserved() {
    Company company = seedCompany("WAC-RES");
    FinishedGood fg =
        createFinishedGood(company, "FG-WAC", new BigDecimal("10"), new BigDecimal("5"), "WAC");
    FinishedGoodBatch batch =
        createBatch(fg, "BATCH-WAC", new BigDecimal("10"), BigDecimal.ZERO, new BigDecimal("25"));
    SalesOrder order =
        createOrder(
            company, "SO-WAC-" + UUID.randomUUID(), fg.getProductCode(), new BigDecimal("5"));
    PackagingSlip slip = createSlip(company, order, "RESERVED", batch, new BigDecimal("5"));
    createReservation(order, fg, batch, new BigDecimal("5"));

    finishedGoodsService.markSlipDispatched(order.getId(), slip);

    List<InventoryMovement> movements =
        inventoryMovementRepository.findByReferenceTypeAndReferenceIdOrderByCreatedAtAsc(
            InventoryReference.SALES_ORDER, order.getId().toString());
    InventoryMovement dispatchMovement =
        movements.stream()
            .filter(mv -> "DISPATCH".equalsIgnoreCase(mv.getMovementType()))
            .findFirst()
            .orElseThrow();
    assertThat(dispatchMovement.getUnitCost()).isEqualByComparingTo(new BigDecimal("25"));
  }

  @Test
  void dispatchInvalidatesStaleWacCacheBeforePostingCost() {
    Company company = seedCompany("WAC-DISP-CACHE");
    FinishedGood fg =
        createFinishedGood(
            company, "FG-WAC-DISP-CACHE", new BigDecimal("10"), new BigDecimal("5"), "WAC");
    FinishedGoodBatch batch =
        createBatch(
            fg,
            "BATCH-WAC-DISP-CACHE",
            new BigDecimal("10"),
            BigDecimal.ZERO,
            new BigDecimal("20"));
    SalesOrder order =
        createOrder(
            company,
            "SO-WAC-DISP-CACHE-" + UUID.randomUUID(),
            fg.getProductCode(),
            new BigDecimal("5"));
    PackagingSlip slip = createSlip(company, order, "RESERVED", batch, new BigDecimal("5"));
    createReservation(order, fg, batch, new BigDecimal("5"));

    BigDecimal primedCachedCost = finishedGoodsService.currentWeightedAverageCost(fg);
    assertThat(primedCachedCost).isEqualByComparingTo(new BigDecimal("20"));

    batch.setUnitCost(new BigDecimal("37"));
    finishedGoodBatchRepository.saveAndFlush(batch);

    finishedGoodsService.markSlipDispatched(order.getId(), slip);

    InventoryMovement dispatchMovement =
        inventoryMovementRepository
            .findByReferenceTypeAndReferenceIdOrderByCreatedAtAsc(
                InventoryReference.SALES_ORDER, order.getId().toString())
            .stream()
            .filter(mv -> "DISPATCH".equalsIgnoreCase(mv.getMovementType()))
            .findFirst()
            .orElseThrow();

    assertThat(dispatchMovement.getUnitCost()).isEqualByComparingTo(new BigDecimal("37"));
    assertThat(dispatchMovement.getUnitCost()).isNotEqualByComparingTo(primedCachedCost);
  }

  @Test
  void confirmDispatchInvalidatesStaleWacCacheBeforePostingCost() {
    Company company = seedCompany("WAC-CONFIRM-CACHE");
    FinishedGood fg =
        createFinishedGood(
            company, "FG-WAC-CONFIRM-CACHE", new BigDecimal("10"), new BigDecimal("5"), "WAC");
    FinishedGoodBatch batch =
        createBatch(
            fg,
            "BATCH-WAC-CONFIRM-CACHE",
            new BigDecimal("10"),
            BigDecimal.ZERO,
            new BigDecimal("22"));
    SalesOrder order =
        createOrder(
            company,
            "SO-WAC-CONFIRM-CACHE-" + UUID.randomUUID(),
            fg.getProductCode(),
            new BigDecimal("5"));
    PackagingSlip slip = createSlip(company, order, "RESERVED", batch, new BigDecimal("5"));
    createReservation(order, fg, batch, new BigDecimal("5"));

    BigDecimal primedCachedCost = finishedGoodsService.currentWeightedAverageCost(fg);
    assertThat(primedCachedCost).isEqualByComparingTo(new BigDecimal("22"));

    batch.setUnitCost(new BigDecimal("31"));
    finishedGoodBatchRepository.saveAndFlush(batch);

    PackagingSlipLine line = slip.getLines().getFirst();
    DispatchConfirmationRequest request =
        new DispatchConfirmationRequest(
            slip.getId(),
            List.of(
                new DispatchConfirmationRequest.LineConfirmation(
                    line.getId(), new BigDecimal("5"), null)),
            null,
            null,
            null);

    finishedGoodsService.confirmDispatch(request, "tester");

    PackagingSlip refreshedSlip =
        packagingSlipRepository.findByIdAndCompany(slip.getId(), company).orElseThrow();
    assertThat(refreshedSlip.getLines().getFirst().getUnitCost())
        .isEqualByComparingTo(new BigDecimal("31"));

    InventoryMovement dispatchMovement =
        inventoryMovementRepository
            .findByReferenceTypeAndReferenceIdOrderByCreatedAtAsc(
                InventoryReference.SALES_ORDER, order.getId().toString())
            .stream()
            .filter(mv -> "DISPATCH".equalsIgnoreCase(mv.getMovementType()))
            .findFirst()
            .orElseThrow();

    assertThat(dispatchMovement.getUnitCost()).isEqualByComparingTo(new BigDecimal("31"));
    assertThat(dispatchMovement.getUnitCost()).isNotEqualByComparingTo(primedCachedCost);
  }

  @Test
  void confirmDispatchUsesReservedBatchActualCostWhenCurrentPeriodDefaultsToFifo() {
    Company company = seedCompany("WAC-CONFIRM-BATCH-ACTUAL");
    FinishedGood fg =
        createFinishedGood(
            company,
            "FG-WAC-CONFIRM-BATCH-ACTUAL",
            new BigDecimal("20"),
            new BigDecimal("5"),
            "WAC");

    FinishedGoodBatch reservedBatch =
        createBatch(
            fg,
            "BATCH-WAC-CONFIRM-ACTUAL-A",
            new BigDecimal("10"),
            new BigDecimal("5"),
            new BigDecimal("20"));
    reservedBatch.setManufacturedAt(Instant.now().minusSeconds(7200));
    reservedBatch = finishedGoodBatchRepository.saveAndFlush(reservedBatch);

    FinishedGoodBatch otherBatch =
        createBatch(
            fg,
            "BATCH-WAC-CONFIRM-ACTUAL-B",
            new BigDecimal("10"),
            new BigDecimal("10"),
            new BigDecimal("40"));
    otherBatch.setManufacturedAt(Instant.now().minusSeconds(3600));
    finishedGoodBatchRepository.saveAndFlush(otherBatch);

    SalesOrder order =
        createOrder(
            company,
            "SO-WAC-CBA-" + UUID.randomUUID().toString().substring(0, 8),
            fg.getProductCode(),
            new BigDecimal("5"));
    PackagingSlip slip = createSlip(company, order, "RESERVED", reservedBatch, new BigDecimal("5"));
    createReservation(order, fg, reservedBatch, new BigDecimal("5"));

    PackagingSlipLine line = slip.getLines().getFirst();
    DispatchConfirmationRequest request =
        new DispatchConfirmationRequest(
            slip.getId(),
            List.of(
                new DispatchConfirmationRequest.LineConfirmation(
                    line.getId(), new BigDecimal("5"), null)),
            null,
            null,
            null);

    finishedGoodsService.confirmDispatch(request, "tester");

    PackagingSlip refreshedSlip =
        packagingSlipRepository.findByIdAndCompany(slip.getId(), company).orElseThrow();
    assertThat(refreshedSlip.getLines().getFirst().getUnitCost())
        .isEqualByComparingTo(new BigDecimal("20"));

    InventoryMovement dispatchMovement =
        inventoryMovementRepository
            .findByReferenceTypeAndReferenceIdOrderByCreatedAtAsc(
                InventoryReference.SALES_ORDER, order.getId().toString())
            .stream()
            .filter(mv -> "DISPATCH".equalsIgnoreCase(mv.getMovementType()))
            .findFirst()
            .orElseThrow();

    assertThat(dispatchMovement.getUnitCost()).isEqualByComparingTo(new BigDecimal("20"));
    assertThat(dispatchMovement.getUnitCost()).isNotEqualByComparingTo(new BigDecimal("30"));
  }

  @Test
  void dispatchUsesReservedBatchActualCostUnderLegacyWeightedAverageAliasUnderTurkishLocale() {
    Locale previous = Locale.getDefault();
    Locale.setDefault(Locale.forLanguageTag("tr-TR"));
    try {
      Company company = seedCompany("WAC-DISP-ALIAS");
      FinishedGood fg =
          createFinishedGood(
              company,
              "FG-WAC-ALIAS-DISP",
              new BigDecimal("20"),
              BigDecimal.ZERO,
              "weighted-average");

      FinishedGoodBatch batchA =
          createBatch(
              fg, "BATCH-WAC-A", new BigDecimal("10"), new BigDecimal("10"), new BigDecimal("20"));
      batchA.setManufacturedAt(Instant.now().minusSeconds(7200));
      batchA.setExpiryDate(LocalDate.now().plusDays(5));
      batchA = finishedGoodBatchRepository.saveAndFlush(batchA);

      FinishedGoodBatch batchB =
          createBatch(
              fg, "BATCH-WAC-B", new BigDecimal("10"), new BigDecimal("10"), new BigDecimal("40"));
      batchB.setManufacturedAt(Instant.now().minusSeconds(3600));
      batchB.setExpiryDate(LocalDate.now().plusDays(30));
      batchB = finishedGoodBatchRepository.saveAndFlush(batchB);

      SalesOrder order =
          createOrder(
              company,
              "SO-WAC-ALIAS-" + UUID.randomUUID(),
              fg.getProductCode(),
              new BigDecimal("5"));
      finishedGoodsService.reserveForOrder(order);

      PackagingSlip slip =
          packagingSlipRepository.findAllByCompanyAndSalesOrderId(company, order.getId()).stream()
              .filter(existing -> !existing.isBackorder())
              .findFirst()
              .orElseThrow();

      finishedGoodsService.markSlipDispatched(order.getId(), slip);

      List<InventoryMovement> movements =
          inventoryMovementRepository.findByReferenceTypeAndReferenceIdOrderByCreatedAtAsc(
              InventoryReference.SALES_ORDER, order.getId().toString());
      InventoryMovement dispatchMovement =
          movements.stream()
              .filter(mv -> "DISPATCH".equalsIgnoreCase(mv.getMovementType()))
              .findFirst()
              .orElseThrow();

      assertThat(dispatchMovement.getUnitCost()).isEqualByComparingTo(new BigDecimal("20"));
    } finally {
      Locale.setDefault(previous);
    }
  }

  @Test
  void reserveForOrder_usesFifoWhenCurrentPeriodDefaultsToFifo() {
    Company company = seedCompany("WAC-FEFO");
    FinishedGood fg =
        createFinishedGood(company, "FG-WAC-FEFO", new BigDecimal("6"), BigDecimal.ZERO, "WAC");

    FinishedGoodBatch olderManufacturedLaterExpiry =
        createBatch(
            fg, "BATCH-WAC-OLDER", new BigDecimal("3"), new BigDecimal("3"), new BigDecimal("9"));
    olderManufacturedLaterExpiry.setManufacturedAt(Instant.now().minusSeconds(7200));
    olderManufacturedLaterExpiry.setExpiryDate(LocalDate.now().plusDays(30));
    olderManufacturedLaterExpiry =
        finishedGoodBatchRepository.saveAndFlush(olderManufacturedLaterExpiry);

    FinishedGoodBatch newerManufacturedSoonerExpiry =
        createBatch(
            fg, "BATCH-WAC-SOON", new BigDecimal("3"), new BigDecimal("3"), new BigDecimal("10"));
    newerManufacturedSoonerExpiry.setManufacturedAt(Instant.now().minusSeconds(3600));
    newerManufacturedSoonerExpiry.setExpiryDate(LocalDate.now().plusDays(3));
    newerManufacturedSoonerExpiry =
        finishedGoodBatchRepository.saveAndFlush(newerManufacturedSoonerExpiry);

    SalesOrder order =
        createOrder(
            company, "SO-WAC-FEFO-" + UUID.randomUUID(), fg.getProductCode(), new BigDecimal("2"));

    FinishedGoodsService.InventoryReservationResult result =
        finishedGoodsService.reserveForOrder(order);

    PackagingSlip slip =
        packagingSlipRepository.findAllByCompanyAndSalesOrderId(company, order.getId()).stream()
            .filter(existing -> !existing.isBackorder())
            .findFirst()
            .orElseThrow();
    assertThat(result.shortages()).isEmpty();
    assertThat(slip.getLines()).hasSize(1);
    assertThat(slip.getLines().getFirst().getFinishedGoodBatch().getId())
        .isEqualTo(olderManufacturedLaterExpiry.getId());
  }

  @Test
  @Tag("critical")
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void reserveForOrderUsesStableBatchIdTieBreakWhenWacBatchesShareExpiryAndManufacturedAt() {
    Company company = seedCompany("WAC-FEFO-TIE");
    FinishedGood fg =
        createFinishedGood(company, "FG-WAC-FEFO-TIE", new BigDecimal("6"), BigDecimal.ZERO, "WAC");
    Instant sameManufacturedAt = Instant.now().minusSeconds(3600);
    LocalDate sameExpiry = LocalDate.now().plusDays(12);

    FinishedGoodBatch firstInserted =
        createBatch(
            fg, "BATCH-WAC-TIE-A", new BigDecimal("3"), new BigDecimal("3"), new BigDecimal("9"));
    firstInserted.setManufacturedAt(sameManufacturedAt);
    firstInserted.setExpiryDate(sameExpiry);
    firstInserted = finishedGoodBatchRepository.saveAndFlush(firstInserted);

    FinishedGoodBatch secondInserted =
        createBatch(
            fg, "BATCH-WAC-TIE-B", new BigDecimal("3"), new BigDecimal("3"), new BigDecimal("10"));
    secondInserted.setManufacturedAt(sameManufacturedAt);
    secondInserted.setExpiryDate(sameExpiry);
    secondInserted = finishedGoodBatchRepository.saveAndFlush(secondInserted);

    assertThat(firstInserted.getId()).isLessThan(secondInserted.getId());

    SalesOrder order =
        createOrder(
            company, "SO-WAC-FEFO-TIE-" + UUID.randomUUID(), fg.getProductCode(), BigDecimal.ONE);
    FinishedGoodsService.InventoryReservationResult first =
        finishedGoodsService.reserveForOrder(order);
    FinishedGoodsService.InventoryReservationResult replay =
        finishedGoodsService.reserveForOrder(order);

    assertThat(first.shortages()).isEmpty();
    assertThat(replay.shortages()).isEmpty();

    FinishedGoodBatch firstAfterReplay =
        finishedGoodBatchRepository.findById(firstInserted.getId()).orElseThrow();
    FinishedGoodBatch secondAfterReplay =
        finishedGoodBatchRepository.findById(secondInserted.getId()).orElseThrow();
    assertThat(firstAfterReplay.getQuantityAvailable()).isEqualByComparingTo(new BigDecimal("2"));
    assertThat(secondAfterReplay.getQuantityAvailable()).isEqualByComparingTo(new BigDecimal("3"));

    List<InventoryReservation> reservations =
        inventoryReservationRepository.findByFinishedGoodCompanyAndReferenceTypeAndReferenceId(
            company, InventoryReference.SALES_ORDER, order.getId().toString());
    assertThat(reservations).hasSize(1);
    assertThat(reservations.getFirst().getFinishedGoodBatch().getId())
        .isEqualTo(firstInserted.getId());
    assertThat(reservations.getFirst().getReservedQuantity()).isEqualByComparingTo(BigDecimal.ONE);
    FinishedGood refreshedFinishedGood = finishedGoodRepository.findById(fg.getId()).orElseThrow();
    assertThat(refreshedFinishedGood.getReservedStock()).isEqualByComparingTo(BigDecimal.ONE);

    List<InventoryMovement> reserveMovements =
        inventoryMovementRepository
            .findByReferenceTypeAndReferenceIdOrderByCreatedAtAsc(
                InventoryReference.SALES_ORDER, order.getId().toString())
            .stream()
            .filter(movement -> "RESERVE".equalsIgnoreCase(movement.getMovementType()))
            .toList();
    assertThat(reserveMovements).hasSize(1);
    assertThat(reserveMovements.getFirst().getQuantity()).isEqualByComparingTo(BigDecimal.ONE);
  }

  @Test
  @Tag("critical")
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void reserveForOrder_treatsLegacyWeightedAverageAliasAsFifoUnderTurkishLocale() {
    Locale previous = Locale.getDefault();
    Locale.setDefault(Locale.forLanguageTag("tr-TR"));
    try {
      Company company = seedCompany("WAC-ALIAS-TR");
      FinishedGood fg =
          createFinishedGood(
              company,
              "FG-WAC-ALIAS-" + UUID.randomUUID().toString().substring(0, 8),
              new BigDecimal("6"),
              BigDecimal.ZERO,
              "weighted-average");

      FinishedGoodBatch olderManufacturedLaterExpiry =
          createBatch(
              fg,
              "BATCH-WAC-ALIAS-OLD",
              new BigDecimal("3"),
              new BigDecimal("3"),
              new BigDecimal("9"));
      olderManufacturedLaterExpiry.setManufacturedAt(Instant.now().minusSeconds(7200));
      olderManufacturedLaterExpiry.setExpiryDate(LocalDate.now().plusDays(30));
      olderManufacturedLaterExpiry =
          finishedGoodBatchRepository.saveAndFlush(olderManufacturedLaterExpiry);

      FinishedGoodBatch newerManufacturedSoonerExpiry =
          createBatch(
              fg,
              "BATCH-WAC-ALIAS-SOON",
              new BigDecimal("3"),
              new BigDecimal("3"),
              new BigDecimal("10"));
      newerManufacturedSoonerExpiry.setManufacturedAt(Instant.now().minusSeconds(3600));
      newerManufacturedSoonerExpiry.setExpiryDate(LocalDate.now().plusDays(3));
      newerManufacturedSoonerExpiry =
          finishedGoodBatchRepository.saveAndFlush(newerManufacturedSoonerExpiry);

      SalesOrder order =
          createOrder(
              company,
              "SO-WAC-ALIAS-" + UUID.randomUUID(),
              fg.getProductCode(),
              new BigDecimal("2"));

      FinishedGoodsService.InventoryReservationResult first =
          finishedGoodsService.reserveForOrder(order);
      FinishedGoodsService.InventoryReservationResult replay =
          finishedGoodsService.reserveForOrder(order);

      assertThat(first.shortages()).isEmpty();
      assertThat(replay.shortages()).isEmpty();

      FinishedGoodBatch soonerAfterReplay =
          finishedGoodBatchRepository.findById(newerManufacturedSoonerExpiry.getId()).orElseThrow();
      FinishedGoodBatch olderAfterReplay =
          finishedGoodBatchRepository.findById(olderManufacturedLaterExpiry.getId()).orElseThrow();
      assertThat(soonerAfterReplay.getQuantityAvailable())
          .isEqualByComparingTo(new BigDecimal("3"));
      assertThat(olderAfterReplay.getQuantityAvailable()).isEqualByComparingTo(BigDecimal.ONE);

      List<InventoryReservation> reservations =
          inventoryReservationRepository.findByFinishedGoodCompanyAndReferenceTypeAndReferenceId(
              company, InventoryReference.SALES_ORDER, order.getId().toString());
      assertThat(reservations).hasSize(1);
      assertThat(reservations.getFirst().getFinishedGoodBatch().getId())
          .isEqualTo(olderManufacturedLaterExpiry.getId());
      assertThat(reservations.getFirst().getReservedQuantity())
          .isEqualByComparingTo(new BigDecimal("2"));
      FinishedGood refreshedFinishedGood =
          finishedGoodRepository.findById(fg.getId()).orElseThrow();
      assertThat(refreshedFinishedGood.getReservedStock())
          .isEqualByComparingTo(new BigDecimal("2"));

      List<InventoryMovement> reserveMovements =
          inventoryMovementRepository
              .findByReferenceTypeAndReferenceIdOrderByCreatedAtAsc(
                  InventoryReference.SALES_ORDER, order.getId().toString())
              .stream()
              .filter(movement -> "RESERVE".equalsIgnoreCase(movement.getMovementType()))
              .toList();
      assertThat(reserveMovements).hasSize(1);
      assertThat(reserveMovements.getFirst().getQuantity())
          .isEqualByComparingTo(new BigDecimal("2"));
    } finally {
      Locale.setDefault(previous);
    }
  }

  @Test
  @Tag("critical")
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void reserveForOrderWacShortageReplayKeepsStableReservationsAndDoesNotDoubleDeplete() {
    Company company = seedCompany("WAC-REPLAY-SHORT");
    FinishedGood fg =
        createFinishedGood(
            company, "FG-WAC-REPLAY-SHORT", new BigDecimal("4"), BigDecimal.ZERO, "WAC");

    FinishedGoodBatch soonerExpiry =
        createBatch(
            fg,
            "BATCH-WAC-REPLAY-SHORT-SOON",
            new BigDecimal("2"),
            new BigDecimal("2"),
            new BigDecimal("11"));
    soonerExpiry.setManufacturedAt(Instant.now().minusSeconds(3600));
    soonerExpiry.setExpiryDate(LocalDate.now().plusDays(3));
    soonerExpiry = finishedGoodBatchRepository.saveAndFlush(soonerExpiry);

    FinishedGoodBatch laterExpiry =
        createBatch(
            fg,
            "BATCH-WAC-REPLAY-SHORT-LATER",
            new BigDecimal("2"),
            new BigDecimal("2"),
            new BigDecimal("10"));
    laterExpiry.setManufacturedAt(Instant.now().minusSeconds(7200));
    laterExpiry.setExpiryDate(LocalDate.now().plusDays(30));
    laterExpiry = finishedGoodBatchRepository.saveAndFlush(laterExpiry);

    SalesOrder order =
        createOrder(
            company,
            "SO-WAC-REPLAY-SHORT-" + UUID.randomUUID(),
            fg.getProductCode(),
            new BigDecimal("6"));

    FinishedGoodsService.InventoryReservationResult first =
        finishedGoodsService.reserveForOrder(order);
    FinishedGoodBatch soonerAfterFirst =
        finishedGoodBatchRepository.findById(soonerExpiry.getId()).orElseThrow();
    FinishedGoodBatch laterAfterFirst =
        finishedGoodBatchRepository.findById(laterExpiry.getId()).orElseThrow();
    FinishedGood finishedGoodAfterFirst = finishedGoodRepository.findById(fg.getId()).orElseThrow();
    List<InventoryReservation> activeReservationsAfterFirst =
        inventoryReservationRepository
            .findByFinishedGoodCompanyAndReferenceTypeAndReferenceId(
                company, InventoryReference.SALES_ORDER, order.getId().toString())
            .stream()
            .filter(reservation -> !"CANCELLED".equalsIgnoreCase(reservation.getStatus()))
            .toList();

    FinishedGoodsService.InventoryReservationResult replay =
        finishedGoodsService.reserveForOrder(order);
    FinishedGoodBatch soonerAfterReplay =
        finishedGoodBatchRepository.findById(soonerExpiry.getId()).orElseThrow();
    FinishedGoodBatch laterAfterReplay =
        finishedGoodBatchRepository.findById(laterExpiry.getId()).orElseThrow();
    FinishedGood finishedGoodAfterReplay =
        finishedGoodRepository.findById(fg.getId()).orElseThrow();

    assertThat(first.shortages()).hasSize(1);
    assertThat(first.shortages().getFirst().shortageQuantity())
        .isEqualByComparingTo(new BigDecimal("2"));
    assertThat(replay.shortages()).hasSize(1);
    assertThat(replay.shortages().getFirst().shortageQuantity())
        .isEqualByComparingTo(new BigDecimal("2"));

    assertThat(soonerAfterFirst.getQuantityAvailable()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(laterAfterFirst.getQuantityAvailable()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(soonerAfterReplay.getQuantityAvailable()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(laterAfterReplay.getQuantityAvailable()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(finishedGoodAfterFirst.getReservedStock()).isEqualByComparingTo(new BigDecimal("4"));
    assertThat(finishedGoodAfterReplay.getReservedStock())
        .isEqualByComparingTo(new BigDecimal("4"));
    assertThat(activeReservationsAfterFirst).hasSize(2);
    assertThat(activeReservationsAfterFirst)
        .extracting(reservation -> reservation.getFinishedGoodBatch().getId())
        .containsExactlyInAnyOrder(soonerExpiry.getId(), laterExpiry.getId());
    assertThat(activeReservationsAfterFirst)
        .allSatisfy(
            reservation ->
                assertThat(reservation.getReservedQuantity())
                    .isEqualByComparingTo(new BigDecimal("2")));

    List<InventoryReservation> activeReservations =
        inventoryReservationRepository
            .findByFinishedGoodCompanyAndReferenceTypeAndReferenceId(
                company, InventoryReference.SALES_ORDER, order.getId().toString())
            .stream()
            .filter(reservation -> !"CANCELLED".equalsIgnoreCase(reservation.getStatus()))
            .toList();
    assertThat(activeReservations).hasSize(2);
    assertThat(activeReservations)
        .extracting(reservation -> reservation.getFinishedGoodBatch().getId())
        .containsExactlyInAnyOrder(soonerExpiry.getId(), laterExpiry.getId());
    assertThat(activeReservations)
        .allSatisfy(
            reservation ->
                assertThat(reservation.getReservedQuantity())
                    .isEqualByComparingTo(new BigDecimal("2")));
    BigDecimal reservedTotal =
        activeReservations.stream()
            .map(
                reservation ->
                    reservation.getReservedQuantity() != null
                        ? reservation.getReservedQuantity()
                        : BigDecimal.ZERO)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    assertThat(reservedTotal).isEqualByComparingTo(new BigDecimal("4"));
  }

  @Test
  void stockSummaryWeightedAverageAlignsWithInventoryValuationForWacGoods() {
    Company company = seedCompany("WAC-PARITY");
    BigDecimal baselineValue = inventoryValuationService.currentSnapshot(company).totalValue();
    FinishedGood fg =
        createFinishedGood(company, "FG-WAC-PARITY", new BigDecimal("10"), BigDecimal.ZERO, "WAC");
    createBatch(fg, "WAC-PARITY-1", new BigDecimal("4"), new BigDecimal("4"), new BigDecimal("8"));
    createBatch(fg, "WAC-PARITY-2", new BigDecimal("6"), new BigDecimal("6"), new BigDecimal("12"));

    StockSummaryDto summary =
        finishedGoodsService.getStockSummary().stream()
            .filter(item -> "FG-WAC-PARITY".equals(item.code()))
            .findFirst()
            .orElseThrow();
    InventoryValuationService.InventorySnapshot snapshot =
        inventoryValuationService.currentSnapshot(company);

    assertThat(summary.weightedAverageCost()).isEqualByComparingTo(new BigDecimal("10.4"));
    assertThat(summary.currentStock()).isEqualByComparingTo(new BigDecimal("10"));
    BigDecimal expectedDelta = new BigDecimal("104.00");
    assertThat(snapshot.totalValue().subtract(baselineValue)).isEqualByComparingTo(expectedDelta);
  }

  @Test
  void stockSummaryUsesBatchValuationUnitCostForFifoGoods() {
    Company company = seedCompany("FIFO-PARITY");
    FinishedGood fg =
        createFinishedGood(company, "FG-FIFO-PARITY", new BigDecimal("5"), BigDecimal.ZERO, "FIFO");
    FinishedGoodBatch older =
        createBatch(
            fg, "FIFO-PARITY-OLD", new BigDecimal("2"), new BigDecimal("2"), new BigDecimal("5"));
    older.setManufacturedAt(Instant.now().minusSeconds(7200));
    finishedGoodBatchRepository.saveAndFlush(older);
    FinishedGoodBatch newer =
        createBatch(
            fg,
            "FIFO-PARITY-NEW",
            new BigDecimal("10"),
            new BigDecimal("10"),
            new BigDecimal("20"));
    newer.setManufacturedAt(Instant.now().minusSeconds(3600));
    finishedGoodBatchRepository.saveAndFlush(newer);

    StockSummaryDto summary =
        finishedGoodsService.getStockSummary().stream()
            .filter(item -> "FG-FIFO-PARITY".equals(item.code()))
            .findFirst()
            .orElseThrow();

    assertThat(summary.weightedAverageCost()).isEqualByComparingTo(new BigDecimal("14"));
    assertThat(summary.weightedAverageCost()).isNotEqualByComparingTo(new BigDecimal("17.5"));
  }

  @Test
  void stockSummaryUsesFifoValuationWhenFinishedGoodSettingIsLifo() {
    Company company = seedCompany("LIFO-PARITY");
    FinishedGood fg =
        createFinishedGood(company, "FG-LIFO-PARITY", new BigDecimal("5"), BigDecimal.ZERO, "LIFO");
    FinishedGoodBatch older =
        createBatch(
            fg, "LIFO-PARITY-OLD", new BigDecimal("2"), new BigDecimal("2"), new BigDecimal("5"));
    older.setManufacturedAt(Instant.now().minusSeconds(7200));
    finishedGoodBatchRepository.saveAndFlush(older);
    FinishedGoodBatch newer =
        createBatch(
            fg,
            "LIFO-PARITY-NEW",
            new BigDecimal("10"),
            new BigDecimal("10"),
            new BigDecimal("20"));
    newer.setManufacturedAt(Instant.now().minusSeconds(3600));
    finishedGoodBatchRepository.saveAndFlush(newer);

    StockSummaryDto summary =
        finishedGoodsService.getStockSummary().stream()
            .filter(item -> "FG-LIFO-PARITY".equals(item.code()))
            .findFirst()
            .orElseThrow();

    assertThat(summary.weightedAverageCost()).isEqualByComparingTo(new BigDecimal("14"));
    assertThat(summary.weightedAverageCost()).isNotEqualByComparingTo(new BigDecimal("17.5"));
  }

  @Test
  void stockSummaryNonWacValuationUsesAvailableBatchQuantity() {
    Company company = seedCompany("FIFO-RESERVED-PARITY");
    FinishedGood fg =
        createFinishedGood(
            company, "FG-FIFO-RESERVED", new BigDecimal("5"), new BigDecimal("2"), "FIFO");
    FinishedGoodBatch reserved =
        createBatch(
            fg, "FIFO-RESERVED-OLD", new BigDecimal("2"), BigDecimal.ZERO, new BigDecimal("5"));
    reserved.setManufacturedAt(Instant.now().minusSeconds(7200));
    finishedGoodBatchRepository.saveAndFlush(reserved);
    FinishedGoodBatch available =
        createBatch(
            fg,
            "FIFO-RESERVED-NEW",
            new BigDecimal("3"),
            new BigDecimal("3"),
            new BigDecimal("20"));
    available.setManufacturedAt(Instant.now().minusSeconds(3600));
    finishedGoodBatchRepository.saveAndFlush(available);

    StockSummaryDto summary =
        finishedGoodsService.getStockSummary().stream()
            .filter(item -> "FG-FIFO-RESERVED".equals(item.code()))
            .findFirst()
            .orElseThrow();

    assertThat(summary.weightedAverageCost()).isEqualByComparingTo(new BigDecimal("12"));
    assertThat(summary.weightedAverageCost()).isNotEqualByComparingTo(new BigDecimal("14"));
  }

  @Test
  void linkDispatchMovementsToJournal_backfillsLegacyNullPackingSlipIdForSingleSlipOrder() {
    Company company = seedCompany("DISPATCH-LEGACY-LINK");
    FinishedGood fg =
        createFinishedGood(company, "FG-LINK", new BigDecimal("10"), BigDecimal.ZERO, "FIFO");
    FinishedGoodBatch batch =
        createBatch(fg, "BATCH-LINK", new BigDecimal("10"), BigDecimal.ZERO, new BigDecimal("9"));
    SalesOrder order =
        createOrder(
            company, "SO-LINK-" + UUID.randomUUID(), fg.getProductCode(), new BigDecimal("2"));
    PackagingSlip slip = createSlip(company, order, "DISPATCHED", batch, new BigDecimal("2"));

    InventoryMovement legacy = new InventoryMovement();
    legacy.setFinishedGood(fg);
    legacy.setFinishedGoodBatch(batch);
    legacy.setReferenceType(InventoryReference.SALES_ORDER);
    legacy.setReferenceId(order.getId().toString());
    legacy.setMovementType("DISPATCH");
    legacy.setQuantity(new BigDecimal("2"));
    legacy.setUnitCost(new BigDecimal("9"));
    InventoryMovement savedLegacy = inventoryMovementRepository.saveAndFlush(legacy);

    finishedGoodsService.linkDispatchMovementsToJournal(slip.getId(), 777L);

    InventoryMovement refreshed =
        inventoryMovementRepository.findById(savedLegacy.getId()).orElseThrow();
    assertThat(refreshed.getPackingSlipId()).isEqualTo(slip.getId());
    assertThat(refreshed.getJournalEntryId()).isEqualTo(777L);
  }

  @Test
  void dispatchRejectsZeroCostWhenOnHandExists() {
    Company company = seedCompany("WAC-ZERO");
    FinishedGood fg =
        createFinishedGood(company, "FG-ZERO", new BigDecimal("5"), new BigDecimal("5"), "FIFO");
    FinishedGoodBatch batch =
        createBatch(fg, "BATCH-ZERO", new BigDecimal("5"), BigDecimal.ZERO, BigDecimal.ZERO);
    SalesOrder order =
        createOrder(
            company, "SO-ZERO-" + UUID.randomUUID(), fg.getProductCode(), new BigDecimal("5"));
    PackagingSlip slip = createSlip(company, order, "RESERVED", batch, new BigDecimal("5"));
    createReservation(order, fg, batch, new BigDecimal("5"));

    assertThatThrownBy(() -> finishedGoodsService.markSlipDispatched(order.getId(), slip))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Dispatch cost is zero");
  }

  @Test
  void confirmDispatchRejectsWhenReservedStockDriftsNegative() {
    Company company = seedCompany("DISPATCH-RES-NEG");
    FinishedGood fg =
        createFinishedGood(company, "FG-NEG", new BigDecimal("5"), new BigDecimal("1"), "FIFO");
    FinishedGoodBatch batch =
        createBatch(fg, "BATCH-NEG", new BigDecimal("5"), BigDecimal.ZERO, new BigDecimal("10"));
    SalesOrder order =
        createOrder(
            company, "SO-NEG-" + UUID.randomUUID(), fg.getProductCode(), new BigDecimal("5"));
    PackagingSlip slip = createSlip(company, order, "RESERVED", batch, new BigDecimal("5"));
    createReservation(order, fg, batch, new BigDecimal("5"));

    PackagingSlipLine line = slip.getLines().getFirst();
    DispatchConfirmationRequest request =
        new DispatchConfirmationRequest(
            slip.getId(),
            List.of(
                new DispatchConfirmationRequest.LineConfirmation(
                    line.getId(), new BigDecimal("5"), null)),
            null,
            null,
            null);

    assertThatThrownBy(() -> finishedGoodsService.confirmDispatch(request, "tester"))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Reserved stock insufficient");
  }

  @Test
  void confirmDispatchRejectsWhenBatchStockInsufficient() {
    Company company = seedCompany("DISPATCH-BATCH-NEG");
    FinishedGood fg =
        createFinishedGood(company, "FG-BATCH", new BigDecimal("5"), new BigDecimal("5"), "FIFO");
    FinishedGoodBatch batch =
        createBatch(fg, "BATCH-LOW", new BigDecimal("2"), BigDecimal.ZERO, new BigDecimal("11"));
    SalesOrder order =
        createOrder(
            company, "SO-BATCH-" + UUID.randomUUID(), fg.getProductCode(), new BigDecimal("5"));
    PackagingSlip slip = createSlip(company, order, "RESERVED", batch, new BigDecimal("5"));
    createReservation(order, fg, batch, new BigDecimal("5"));

    PackagingSlipLine line = slip.getLines().getFirst();
    DispatchConfirmationRequest request =
        new DispatchConfirmationRequest(
            slip.getId(),
            List.of(
                new DispatchConfirmationRequest.LineConfirmation(
                    line.getId(), new BigDecimal("5"), null)),
            null,
            null,
            null);

    assertThatThrownBy(() -> finishedGoodsService.confirmDispatch(request, "tester"))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Batch stock insufficient");
  }

  @Test
  void reserveDoesNotOverrideTerminalStatus() {
    Company company = seedCompany("SLIP-TERM");
    FinishedGood fg =
        createFinishedGood(company, "FG-TERM", new BigDecimal("5"), BigDecimal.ZERO, "FIFO");
    FinishedGoodBatch batch =
        createBatch(
            fg, "BATCH-TERM", new BigDecimal("5"), new BigDecimal("5"), new BigDecimal("10"));
    SalesOrder order =
        createOrder(
            company, "SO-TERM-" + UUID.randomUUID(), fg.getProductCode(), new BigDecimal("5"));
    PackagingSlip slip = createSlip(company, order, "DISPATCHED", batch, new BigDecimal("5"));

    finishedGoodsService.reserveForOrder(order);

    PackagingSlip refreshed =
        packagingSlipRepository.findByIdAndCompany(slip.getId(), company).orElseThrow();
    assertThat(refreshed.getStatus()).isEqualTo("DISPATCHED");
  }

  @Test
  void cancelBackorderClearsReservation() {
    Company company = seedCompany("BACKORDER-CLR");
    FinishedGood fg =
        createFinishedGood(company, "FG-BO", new BigDecimal("5"), new BigDecimal("5"), "FIFO");
    FinishedGoodBatch batch =
        createBatch(fg, "BATCH-BO", new BigDecimal("5"), BigDecimal.ZERO, new BigDecimal("12"));
    SalesOrder order =
        createOrder(
            company, "SO-BO-" + UUID.randomUUID(), fg.getProductCode(), new BigDecimal("5"));
    PackagingSlip slip = createSlip(company, order, "BACKORDER", batch, new BigDecimal("5"));
    createReservation(order, fg, batch, new BigDecimal("5"));

    finishedGoodsService.cancelBackorderSlip(slip.getId(), "tester", "cancel backorder");

    List<InventoryReservation> reservations =
        inventoryReservationRepository.findByFinishedGoodCompanyAndReferenceTypeAndReferenceId(
            company, InventoryReference.SALES_ORDER, order.getId().toString());
    assertThat(reservations).isNotEmpty();
    assertThat(reservations).allMatch(r -> "CANCELLED".equalsIgnoreCase(r.getStatus()));
    assertThat(reservations)
        .allSatisfy(r -> assertThat(r.getReservedQuantity()).isEqualByComparingTo(BigDecimal.ZERO));
  }

  @Test
  void cancelBackorderReconcilesOrderStatusToReadyToShipWhenOnlyDispatchedSlipRemains() {
    Company company = seedCompany("BACKORDER-STATUS");
    FinishedGood fg =
        createFinishedGood(
            company, "FG-BO-STATUS", new BigDecimal("10"), new BigDecimal("5"), "FIFO");
    FinishedGoodBatch dispatchedBatch =
        createBatch(
            fg, "BATCH-BO-DISP", new BigDecimal("5"), BigDecimal.ZERO, new BigDecimal("10"));
    FinishedGoodBatch backorderBatch =
        createBatch(
            fg, "BATCH-BO-OPEN", new BigDecimal("5"), BigDecimal.ZERO, new BigDecimal("11"));
    SalesOrder order =
        createOrder(
            company,
            "SO-BO-STATUS-" + UUID.randomUUID(),
            fg.getProductCode(),
            new BigDecimal("10"));
    order.setStatus("PENDING_PRODUCTION");
    order = salesOrderRepository.saveAndFlush(order);

    PackagingSlip dispatchedSlip = new PackagingSlip();
    dispatchedSlip.setCompany(company);
    dispatchedSlip.setSalesOrder(order);
    dispatchedSlip.setSlipNumber("PS-DISP-" + UUID.randomUUID().toString().substring(0, 8));
    dispatchedSlip.setStatus("DISPATCHED");
    dispatchedSlip.setBackorder(false);
    PackagingSlipLine dispatchedLine = new PackagingSlipLine();
    dispatchedLine.setPackagingSlip(dispatchedSlip);
    dispatchedLine.setFinishedGoodBatch(dispatchedBatch);
    dispatchedLine.setOrderedQuantity(new BigDecimal("5"));
    dispatchedLine.setQuantity(new BigDecimal("5"));
    dispatchedLine.setUnitCost(dispatchedBatch.getUnitCost());
    dispatchedSlip.getLines().add(dispatchedLine);
    packagingSlipRepository.saveAndFlush(dispatchedSlip);

    PackagingSlip backorderSlip =
        createSlip(company, order, "BACKORDER", backorderBatch, new BigDecimal("5"));
    createReservation(order, fg, backorderBatch, new BigDecimal("5"));

    finishedGoodsService.cancelBackorderSlip(backorderSlip.getId(), "tester", "cancel backorder");

    SalesOrder refreshedOrder =
        salesOrderRepository.findByCompanyAndId(company, order.getId()).orElseThrow();
    assertThat(refreshedOrder.getStatus()).isEqualTo("READY_TO_SHIP");
  }

  @Test
  void cancelBackorderDoesNotMutateOrderStatusWhenAccountingAlreadyPosted() {
    Company company = seedCompany("BACKORDER-POSTED");
    FinishedGood fg =
        createFinishedGood(
            company, "FG-BO-POSTED", new BigDecimal("10"), new BigDecimal("5"), "FIFO");
    FinishedGoodBatch dispatchedBatch =
        createBatch(
            fg, "BATCH-BO-POSTED-DISP", new BigDecimal("5"), BigDecimal.ZERO, new BigDecimal("10"));
    FinishedGoodBatch backorderBatch =
        createBatch(
            fg, "BATCH-BO-POSTED-OPEN", new BigDecimal("5"), BigDecimal.ZERO, new BigDecimal("11"));
    SalesOrder order =
        createOrder(
            company,
            "SO-BO-POSTED-" + UUID.randomUUID(),
            fg.getProductCode(),
            new BigDecimal("10"));
    order.setStatus("PENDING_PRODUCTION");
    order.setSalesJournalEntryId(991L);
    order = salesOrderRepository.saveAndFlush(order);

    PackagingSlip dispatchedSlip = new PackagingSlip();
    dispatchedSlip.setCompany(company);
    dispatchedSlip.setSalesOrder(order);
    dispatchedSlip.setSlipNumber("PS-DISP-" + UUID.randomUUID().toString().substring(0, 8));
    dispatchedSlip.setStatus("DISPATCHED");
    dispatchedSlip.setBackorder(false);
    PackagingSlipLine dispatchedLine = new PackagingSlipLine();
    dispatchedLine.setPackagingSlip(dispatchedSlip);
    dispatchedLine.setFinishedGoodBatch(dispatchedBatch);
    dispatchedLine.setOrderedQuantity(new BigDecimal("5"));
    dispatchedLine.setQuantity(new BigDecimal("5"));
    dispatchedLine.setUnitCost(dispatchedBatch.getUnitCost());
    dispatchedSlip.getLines().add(dispatchedLine);
    packagingSlipRepository.saveAndFlush(dispatchedSlip);

    PackagingSlip backorderSlip =
        createSlip(company, order, "BACKORDER", backorderBatch, new BigDecimal("5"));
    createReservation(order, fg, backorderBatch, new BigDecimal("5"));

    finishedGoodsService.cancelBackorderSlip(backorderSlip.getId(), "tester", "cancel backorder");

    SalesOrder refreshedOrder =
        salesOrderRepository.findByCompanyAndId(company, order.getId()).orElseThrow();
    assertThat(refreshedOrder.getStatus()).isEqualTo("PENDING_PRODUCTION");
  }

  @Test
  void cancelBackorderDoesNotMutateOrderStatusWhenInvoiceAlreadyIssued() {
    Company company = seedCompany("BACKORDER-INVOICE");
    FinishedGood fg =
        createFinishedGood(
            company, "FG-BO-INVOICE", new BigDecimal("10"), new BigDecimal("5"), "FIFO");
    FinishedGoodBatch dispatchedBatch =
        createBatch(
            fg,
            "BATCH-BO-INVOICE-DISP",
            new BigDecimal("5"),
            BigDecimal.ZERO,
            new BigDecimal("10"));
    FinishedGoodBatch backorderBatch =
        createBatch(
            fg,
            "BATCH-BO-INVOICE-OPEN",
            new BigDecimal("5"),
            BigDecimal.ZERO,
            new BigDecimal("11"));
    SalesOrder order =
        createOrder(
            company,
            "SO-BO-INVOICE-" + UUID.randomUUID(),
            fg.getProductCode(),
            new BigDecimal("10"));
    order.setStatus("PENDING_PRODUCTION");
    order.setFulfillmentInvoiceId(992L);
    order = salesOrderRepository.saveAndFlush(order);

    PackagingSlip dispatchedSlip = new PackagingSlip();
    dispatchedSlip.setCompany(company);
    dispatchedSlip.setSalesOrder(order);
    dispatchedSlip.setSlipNumber("PS-DISP-" + UUID.randomUUID().toString().substring(0, 8));
    dispatchedSlip.setStatus("DISPATCHED");
    dispatchedSlip.setBackorder(false);
    PackagingSlipLine dispatchedLine = new PackagingSlipLine();
    dispatchedLine.setPackagingSlip(dispatchedSlip);
    dispatchedLine.setFinishedGoodBatch(dispatchedBatch);
    dispatchedLine.setOrderedQuantity(new BigDecimal("5"));
    dispatchedLine.setQuantity(new BigDecimal("5"));
    dispatchedLine.setUnitCost(dispatchedBatch.getUnitCost());
    dispatchedSlip.getLines().add(dispatchedLine);
    packagingSlipRepository.saveAndFlush(dispatchedSlip);

    PackagingSlip backorderSlip =
        createSlip(company, order, "BACKORDER", backorderBatch, new BigDecimal("5"));
    createReservation(order, fg, backorderBatch, new BigDecimal("5"));

    finishedGoodsService.cancelBackorderSlip(backorderSlip.getId(), "tester", "cancel backorder");

    SalesOrder refreshedOrder =
        salesOrderRepository.findByCompanyAndId(company, order.getId()).orElseThrow();
    assertThat(refreshedOrder.getStatus()).isEqualTo("PENDING_PRODUCTION");
  }

  @Test
  void cancelBackorderDoesNotMutateOrderStatusWhenCogsJournalAlreadyPosted() {
    Company company = seedCompany("BACKORDER-COGS");
    FinishedGood fg =
        createFinishedGood(
            company, "FG-BO-COGS", new BigDecimal("10"), new BigDecimal("5"), "FIFO");
    FinishedGoodBatch dispatchedBatch =
        createBatch(
            fg, "BATCH-BO-COGS-DISP", new BigDecimal("5"), BigDecimal.ZERO, new BigDecimal("10"));
    FinishedGoodBatch backorderBatch =
        createBatch(
            fg, "BATCH-BO-COGS-OPEN", new BigDecimal("5"), BigDecimal.ZERO, new BigDecimal("11"));
    SalesOrder order =
        createOrder(
            company, "SO-BO-COGS-" + UUID.randomUUID(), fg.getProductCode(), new BigDecimal("10"));
    order.setStatus("PENDING_PRODUCTION");
    order.setCogsJournalEntryId(993L);
    order = salesOrderRepository.saveAndFlush(order);

    PackagingSlip dispatchedSlip = new PackagingSlip();
    dispatchedSlip.setCompany(company);
    dispatchedSlip.setSalesOrder(order);
    dispatchedSlip.setSlipNumber("PS-DISP-" + UUID.randomUUID().toString().substring(0, 8));
    dispatchedSlip.setStatus("DISPATCHED");
    dispatchedSlip.setBackorder(false);
    PackagingSlipLine dispatchedLine = new PackagingSlipLine();
    dispatchedLine.setPackagingSlip(dispatchedSlip);
    dispatchedLine.setFinishedGoodBatch(dispatchedBatch);
    dispatchedLine.setOrderedQuantity(new BigDecimal("5"));
    dispatchedLine.setQuantity(new BigDecimal("5"));
    dispatchedLine.setUnitCost(dispatchedBatch.getUnitCost());
    dispatchedSlip.getLines().add(dispatchedLine);
    packagingSlipRepository.saveAndFlush(dispatchedSlip);

    PackagingSlip backorderSlip =
        createSlip(company, order, "BACKORDER", backorderBatch, new BigDecimal("5"));
    createReservation(order, fg, backorderBatch, new BigDecimal("5"));

    finishedGoodsService.cancelBackorderSlip(backorderSlip.getId(), "tester", "cancel backorder");

    SalesOrder refreshedOrder =
        salesOrderRepository.findByCompanyAndId(company, order.getId()).orElseThrow();
    assertThat(refreshedOrder.getStatus()).isEqualTo("PENDING_PRODUCTION");
  }

  @Test
  void cancelBackorderDoesNotMutateOrderStatusWhenOrderAlreadyTerminal() {
    Company company = seedCompany("BACKORDER-TERMINAL");
    FinishedGood fg =
        createFinishedGood(
            company, "FG-BO-TERMINAL", new BigDecimal("10"), new BigDecimal("5"), "FIFO");
    FinishedGoodBatch dispatchedBatch =
        createBatch(
            fg,
            "BATCH-BO-TERMINAL-DISP",
            new BigDecimal("5"),
            BigDecimal.ZERO,
            new BigDecimal("10"));
    FinishedGoodBatch backorderBatch =
        createBatch(
            fg,
            "BATCH-BO-TERMINAL-OPEN",
            new BigDecimal("5"),
            BigDecimal.ZERO,
            new BigDecimal("11"));
    SalesOrder order =
        createOrder(
            company,
            "SO-BO-TERMINAL-" + UUID.randomUUID(),
            fg.getProductCode(),
            new BigDecimal("10"));
    order.setStatus("SHIPPED");
    order = salesOrderRepository.saveAndFlush(order);

    PackagingSlip dispatchedSlip = new PackagingSlip();
    dispatchedSlip.setCompany(company);
    dispatchedSlip.setSalesOrder(order);
    dispatchedSlip.setSlipNumber("PS-DISP-" + UUID.randomUUID().toString().substring(0, 8));
    dispatchedSlip.setStatus("DISPATCHED");
    dispatchedSlip.setBackorder(false);
    PackagingSlipLine dispatchedLine = new PackagingSlipLine();
    dispatchedLine.setPackagingSlip(dispatchedSlip);
    dispatchedLine.setFinishedGoodBatch(dispatchedBatch);
    dispatchedLine.setOrderedQuantity(new BigDecimal("5"));
    dispatchedLine.setQuantity(new BigDecimal("5"));
    dispatchedLine.setUnitCost(dispatchedBatch.getUnitCost());
    dispatchedSlip.getLines().add(dispatchedLine);
    packagingSlipRepository.saveAndFlush(dispatchedSlip);

    PackagingSlip backorderSlip =
        createSlip(company, order, "BACKORDER", backorderBatch, new BigDecimal("5"));
    createReservation(order, fg, backorderBatch, new BigDecimal("5"));

    finishedGoodsService.cancelBackorderSlip(backorderSlip.getId(), "tester", "cancel backorder");

    SalesOrder refreshedOrder =
        salesOrderRepository.findByCompanyAndId(company, order.getId()).orElseThrow();
    assertThat(refreshedOrder.getStatus()).isEqualTo("SHIPPED");
  }

  @Test
  void cancelBackorderDoesNotChangeStatusWhenNoActiveSlipRemains() {
    Company company = seedCompany("BACKORDER-NO-ACTIVE");
    FinishedGood fg =
        createFinishedGood(
            company, "FG-BO-NO-ACTIVE", new BigDecimal("10"), new BigDecimal("5"), "FIFO");
    FinishedGoodBatch backorderBatch =
        createBatch(
            fg, "BATCH-BO-NO-ACTIVE", new BigDecimal("5"), BigDecimal.ZERO, new BigDecimal("11"));
    SalesOrder order =
        createOrder(
            company,
            "SO-BO-NO-ACTIVE-" + UUID.randomUUID(),
            fg.getProductCode(),
            new BigDecimal("5"));
    order.setStatus("PENDING_PRODUCTION");
    order = salesOrderRepository.saveAndFlush(order);

    PackagingSlip backorderSlip =
        createSlip(company, order, "BACKORDER", backorderBatch, new BigDecimal("5"));
    createReservation(order, fg, backorderBatch, new BigDecimal("5"));

    finishedGoodsService.cancelBackorderSlip(backorderSlip.getId(), "tester", "cancel backorder");

    SalesOrder refreshedOrder =
        salesOrderRepository.findByCompanyAndId(company, order.getId()).orElseThrow();
    assertThat(refreshedOrder.getStatus()).isEqualTo("PENDING_PRODUCTION");
  }

  @Test
  void cancelBackorderDoesNotPromoteStatusWithoutAnyDispatchedActiveSlip() {
    Company company = seedCompany("BACKORDER-NO-DISPATCH");
    FinishedGood fg =
        createFinishedGood(
            company, "FG-BO-NO-DISPATCH", new BigDecimal("10"), new BigDecimal("5"), "FIFO");
    FinishedGoodBatch reservedBatch =
        createBatch(
            fg, "BATCH-BO-RESERVED", new BigDecimal("5"), BigDecimal.ZERO, new BigDecimal("10"));
    FinishedGoodBatch backorderBatch =
        createBatch(
            fg, "BATCH-BO-PENDING", new BigDecimal("5"), BigDecimal.ZERO, new BigDecimal("11"));
    SalesOrder order =
        createOrder(
            company,
            "SO-BO-NO-DISPATCH-" + UUID.randomUUID(),
            fg.getProductCode(),
            new BigDecimal("10"));
    order.setStatus("PENDING_PRODUCTION");
    order = salesOrderRepository.saveAndFlush(order);

    PackagingSlip reservedSlip = new PackagingSlip();
    reservedSlip.setCompany(company);
    reservedSlip.setSalesOrder(order);
    reservedSlip.setSlipNumber("PS-RES-" + UUID.randomUUID().toString().substring(0, 8));
    reservedSlip.setStatus("RESERVED");
    reservedSlip.setBackorder(false);
    PackagingSlipLine reservedLine = new PackagingSlipLine();
    reservedLine.setPackagingSlip(reservedSlip);
    reservedLine.setFinishedGoodBatch(reservedBatch);
    reservedLine.setOrderedQuantity(new BigDecimal("5"));
    reservedLine.setQuantity(new BigDecimal("5"));
    reservedLine.setUnitCost(reservedBatch.getUnitCost());
    reservedSlip.getLines().add(reservedLine);
    packagingSlipRepository.saveAndFlush(reservedSlip);

    PackagingSlip backorderSlip =
        createSlip(company, order, "BACKORDER", backorderBatch, new BigDecimal("5"));
    createReservation(order, fg, backorderBatch, new BigDecimal("5"));

    finishedGoodsService.cancelBackorderSlip(backorderSlip.getId(), "tester", "cancel backorder");

    SalesOrder refreshedOrder =
        salesOrderRepository.findByCompanyAndId(company, order.getId()).orElseThrow();
    assertThat(refreshedOrder.getStatus()).isEqualTo("PENDING_PRODUCTION");
  }

  @Test
  void reserveForOrderKeepsBackorderSlip() {
    Company company = seedCompany("BACKORDER-KEEP");
    FinishedGood fg =
        createFinishedGood(company, "FG-KEEP", new BigDecimal("5"), new BigDecimal("5"), "FIFO");
    FinishedGoodBatch batch =
        createBatch(fg, "BATCH-KEEP", new BigDecimal("5"), BigDecimal.ZERO, new BigDecimal("7"));
    SalesOrder order =
        createOrder(
            company, "SO-KEEP-" + UUID.randomUUID(), fg.getProductCode(), new BigDecimal("10"));
    PackagingSlip slip = createSlip(company, order, "BACKORDER", batch, new BigDecimal("5"));
    createReservation(order, fg, batch, new BigDecimal("5"));

    finishedGoodsService.reserveForOrder(order);

    PackagingSlip refreshed =
        packagingSlipRepository.findByIdAndCompany(slip.getId(), company).orElseThrow();
    assertThat(refreshed.getStatus()).isEqualTo("BACKORDER");
    assertThat(refreshed.getLines()).hasSize(1);
    List<InventoryReservation> reservations =
        inventoryReservationRepository.findByFinishedGoodCompanyAndReferenceTypeAndReferenceId(
            company, InventoryReference.SALES_ORDER, order.getId().toString());
    assertThat(reservations).isNotEmpty();
    assertThat(reservations).allMatch(r -> !"CANCELLED".equalsIgnoreCase(r.getStatus()));
    assertThat(reservations)
        .allSatisfy(
            r -> assertThat(r.getReservedQuantity()).isEqualByComparingTo(new BigDecimal("5")));
  }

  @Test
  void reserveForOrderAllocatesShortagesWhenBackorderSlipExists() {
    Company company = seedCompany("BACKORDER-ALLOC");
    FinishedGood fg =
        createFinishedGood(company, "FG-ALLOC", new BigDecimal("10"), new BigDecimal("5"), "FIFO");
    FinishedGoodBatch reservedBatch =
        createBatch(
            fg, "BATCH-ALLOC-RES", new BigDecimal("5"), BigDecimal.ZERO, new BigDecimal("8"));
    FinishedGoodBatch availableBatch =
        createBatch(
            fg, "BATCH-ALLOC-AVAIL", new BigDecimal("5"), new BigDecimal("5"), new BigDecimal("9"));
    SalesOrder order =
        createOrder(
            company, "SO-ALLOC-" + UUID.randomUUID(), fg.getProductCode(), new BigDecimal("10"));
    PackagingSlip slip =
        createSlip(company, order, "BACKORDER", reservedBatch, new BigDecimal("5"));
    createReservation(order, fg, reservedBatch, new BigDecimal("5"));

    FinishedGoodsService.InventoryReservationResult result =
        finishedGoodsService.reserveForOrder(order);
    FinishedGoodsService.InventoryReservationResult second =
        finishedGoodsService.reserveForOrder(order);

    assertThat(result.shortages()).hasSizeLessThanOrEqualTo(1);
    if (!result.shortages().isEmpty()) {
      assertThat(result.shortages().getFirst().shortageQuantity())
          .isEqualByComparingTo(new BigDecimal("5"));
    }
    assertThat(second.shortages()).hasSizeLessThanOrEqualTo(1);
    if (!second.shortages().isEmpty()) {
      assertThat(second.shortages().getFirst().shortageQuantity())
          .isEqualByComparingTo(new BigDecimal("5"));
    }
    PackagingSlip refreshed =
        packagingSlipRepository.findByIdAndCompany(slip.getId(), company).orElseThrow();
    assertThat(refreshed.getStatus()).isIn("BACKORDER", "RESERVED", "CANCELLED");
    List<InventoryReservation> reservations =
        inventoryReservationRepository.findByFinishedGoodCompanyAndReferenceTypeAndReferenceId(
            company, InventoryReference.SALES_ORDER, order.getId().toString());
    BigDecimal totalReserved =
        reservations.stream()
            .filter(r -> !"CANCELLED".equalsIgnoreCase(r.getStatus()))
            .map(r -> r.getReservedQuantity() != null ? r.getReservedQuantity() : BigDecimal.ZERO)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    assertThat(totalReserved).isGreaterThan(BigDecimal.ZERO);
    assertThat(totalReserved).isLessThanOrEqualTo(new BigDecimal("10"));
  }

  @Test
  void reserveForOrderBackorderWithNullReservedQuantityIsNotDuplicated() {
    Company company = seedCompany("BACKORDER-NULL");
    FinishedGood fg =
        createFinishedGood(company, "FG-NULL", new BigDecimal("5"), new BigDecimal("5"), "FIFO");
    FinishedGoodBatch batch =
        createBatch(
            fg, "BATCH-NULL", new BigDecimal("5"), new BigDecimal("5"), new BigDecimal("6"));
    SalesOrder order =
        createOrder(
            company, "SO-NULL-" + UUID.randomUUID(), fg.getProductCode(), new BigDecimal("5"));
    PackagingSlip slip = createSlip(company, order, "BACKORDER", batch, new BigDecimal("5"));

    InventoryReservation reservation = new InventoryReservation();
    reservation.setFinishedGood(fg);
    reservation.setFinishedGoodBatch(batch);
    reservation.setReferenceType(InventoryReference.SALES_ORDER);
    reservation.setReferenceId(order.getId().toString());
    reservation.setQuantity(new BigDecimal("5"));
    reservation.setReservedQuantity(null);
    reservation.setStatus("RESERVED");
    InventoryReservation savedReservation =
        inventoryReservationRepository.saveAndFlush(reservation);

    FinishedGoodsService.InventoryReservationResult result =
        finishedGoodsService.reserveForOrder(order);

    assertThat(result.shortages()).isEmpty();
    List<InventoryReservation> reservations =
        inventoryReservationRepository.findByFinishedGoodCompanyAndReferenceTypeAndReferenceId(
            company, InventoryReference.SALES_ORDER, order.getId().toString());
    assertThat(reservations).hasSize(1);
    InventoryReservation refreshedReservation = reservations.getFirst();
    assertThat(refreshedReservation.getId()).isEqualTo(savedReservation.getId());
    assertThat(refreshedReservation.getReservedQuantity())
        .isEqualByComparingTo(new BigDecimal("5"));
  }

  @Test
  void reserveForOrderBackorderRebuildsMissingReservations() {
    Company company = seedCompany("BACKORDER-REB");
    FinishedGood fg =
        createFinishedGood(company, "FG-REB", new BigDecimal("5"), new BigDecimal("5"), "FIFO");
    FinishedGoodBatch batch =
        createBatch(fg, "BATCH-REB", new BigDecimal("5"), new BigDecimal("5"), new BigDecimal("6"));
    SalesOrder order =
        createOrder(
            company, "SO-REB-" + UUID.randomUUID(), fg.getProductCode(), new BigDecimal("5"));
    createSlip(company, order, "BACKORDER", batch, new BigDecimal("5"));

    FinishedGoodsService.InventoryReservationResult result =
        finishedGoodsService.reserveForOrder(order);

    assertThat(result.shortages()).isEmpty();
    List<InventoryReservation> reservations =
        inventoryReservationRepository.findByFinishedGoodCompanyAndReferenceTypeAndReferenceId(
            company, InventoryReference.SALES_ORDER, order.getId().toString());
    assertThat(reservations).hasSize(1);
    assertThat(reservations.getFirst().getReservedQuantity())
        .isEqualByComparingTo(new BigDecimal("5"));
  }

  @Test
  void updateSlipStatusRejectsInvalidStateTransition() {
    Company company = seedCompany("SLIP-STATE");
    FinishedGood fg =
        createFinishedGood(company, "FG-STATE", new BigDecimal("5"), new BigDecimal("5"), "FIFO");
    FinishedGoodBatch batch =
        createBatch(
            fg, "BATCH-STATE", new BigDecimal("5"), new BigDecimal("5"), new BigDecimal("6"));
    SalesOrder order =
        createOrder(
            company, "SO-STATE-" + UUID.randomUUID(), fg.getProductCode(), new BigDecimal("5"));
    PackagingSlip slip =
        createSlip(company, order, "PENDING_PRODUCTION", batch, new BigDecimal("5"));

    assertThatThrownBy(() -> finishedGoodsService.updateSlipStatus(slip.getId(), "PENDING_STOCK"))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Invalid slip status transition");
  }

  @Test
  void updateSlipStatusRejectsBackorderSlip() {
    Company company = seedCompany("SLIP-STATE-BO");
    FinishedGood fg =
        createFinishedGood(
            company, "FG-STATE-BO", new BigDecimal("5"), new BigDecimal("5"), "FIFO");
    FinishedGoodBatch batch =
        createBatch(
            fg, "BATCH-STATE-BO", new BigDecimal("5"), new BigDecimal("5"), new BigDecimal("6"));
    SalesOrder order =
        createOrder(
            company, "SO-STATE-BO-" + UUID.randomUUID(), fg.getProductCode(), new BigDecimal("5"));
    PackagingSlip slip = createSlip(company, order, "BACKORDER", batch, new BigDecimal("5"));

    assertThatThrownBy(() -> finishedGoodsService.updateSlipStatus(slip.getId(), "RESERVED"))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Backorder slips can only be changed via backorder workflows");
  }

  @Test
  void registerBatchRejectsNegativeQuantity() {
    Company company = seedCompany("BATCH-NEG-QTY");
    FinishedGood fg =
        createFinishedGood(company, "FG-NEG-QTY", new BigDecimal("0"), BigDecimal.ZERO, "FIFO");

    assertThatThrownBy(
            () ->
                finishedGoodsService.registerBatch(
                    new FinishedGoodBatchRequest(
                        fg.getId(),
                        "BATCH-NEG-QTY",
                        new BigDecimal("-1"),
                        new BigDecimal("10.00"),
                        Instant.now(),
                        null)))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Batch quantity");
  }

  @Test
  void registerBatchRejectsNegativeUnitCost() {
    Company company = seedCompany("BATCH-NEG-COST");
    FinishedGood fg =
        createFinishedGood(company, "FG-NEG-COST", new BigDecimal("0"), BigDecimal.ZERO, "FIFO");

    assertThatThrownBy(
            () ->
                finishedGoodsService.registerBatch(
                    new FinishedGoodBatchRequest(
                        fg.getId(),
                        "BATCH-NEG-COST",
                        new BigDecimal("1"),
                        new BigDecimal("-5.00"),
                        Instant.now(),
                        null)))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("unit cost");
  }

  @Test
  void markSlipDispatched_createsBackorderSlipForPartialShipment() {
    Company company = seedCompany("DISPATCH-BO");
    FinishedGood fg =
        createFinishedGood(company, "FG-BO", new BigDecimal("5"), new BigDecimal("10"), "FIFO");
    FinishedGoodBatch batch =
        createBatch(fg, "BATCH-BO", new BigDecimal("10"), BigDecimal.ZERO, new BigDecimal("7"));
    SalesOrder order =
        createOrder(
            company, "SO-BO-" + UUID.randomUUID(), fg.getProductCode(), new BigDecimal("10"));
    PackagingSlip slip = createSlip(company, order, "RESERVED", batch, new BigDecimal("10"));
    createReservation(order, fg, batch, new BigDecimal("10"));

    finishedGoodsService.markSlipDispatched(order.getId(), slip);

    List<PackagingSlip> slips =
        packagingSlipRepository.findAllByCompanyAndSalesOrderId(company, order.getId());
    PackagingSlip primarySlip =
        slips.stream().filter(existing -> !existing.isBackorder()).findFirst().orElseThrow();
    assertThat(primarySlip.getStatus()).isEqualTo("DISPATCHED");
    assertThat(primarySlip.getDispatchedAt()).isNotNull();
    PackagingSlip backorderSlip =
        slips.stream()
            .filter(existing -> "BACKORDER".equalsIgnoreCase(existing.getStatus()))
            .findFirst()
            .orElseThrow();
    assertThat(backorderSlip.getLines()).hasSize(1);
    PackagingSlipLine boLine = backorderSlip.getLines().getFirst();
    assertThat(boLine.getQuantity()).isEqualByComparingTo(new BigDecimal("5"));
  }

  @Test
  void markSlipDispatched_fullShipmentMarksSlipDispatched() {
    Company company = seedCompany("DISPATCH-FULL");
    FinishedGood fg =
        createFinishedGood(company, "FG-FULL", new BigDecimal("10"), new BigDecimal("10"), "FIFO");
    FinishedGoodBatch batch =
        createBatch(fg, "BATCH-FULL", new BigDecimal("10"), BigDecimal.ZERO, new BigDecimal("8"));
    SalesOrder order =
        createOrder(
            company, "SO-FULL-" + UUID.randomUUID(), fg.getProductCode(), new BigDecimal("10"));
    PackagingSlip slip = createSlip(company, order, "RESERVED", batch, new BigDecimal("10"));
    createReservation(order, fg, batch, new BigDecimal("10"));

    finishedGoodsService.markSlipDispatched(order.getId(), slip);

    PackagingSlip refreshed =
        packagingSlipRepository.findByIdAndCompany(slip.getId(), company).orElseThrow();
    assertThat(refreshed.getStatus()).isEqualTo("DISPATCHED");
    assertThat(refreshed.getDispatchedAt()).isNotNull();
  }

  @Test
  void confirmDispatch_partialBackorderDispatchCreatesFollowupBackorderSlip() {
    Company company = seedCompany("DISPATCH-BO-CASCADE");
    FinishedGood fg =
        createFinishedGood(
            company, "FG-BO-CASCADE", new BigDecimal("10"), new BigDecimal("10"), "FIFO");
    FinishedGoodBatch batch =
        createBatch(
            fg, "BATCH-BO-CASCADE", new BigDecimal("10"), BigDecimal.ZERO, new BigDecimal("7"));
    SalesOrder order =
        createOrder(
            company,
            "SO-BO-CASCADE-" + UUID.randomUUID(),
            fg.getProductCode(),
            new BigDecimal("10"));
    PackagingSlip primarySlip = createSlip(company, order, "RESERVED", batch, new BigDecimal("10"));
    createReservation(order, fg, batch, new BigDecimal("10"));

    PackagingSlipLine primaryLine = primarySlip.getLines().getFirst();
    DispatchConfirmationRequest firstConfirm =
        new DispatchConfirmationRequest(
            primarySlip.getId(),
            List.of(
                new DispatchConfirmationRequest.LineConfirmation(
                    primaryLine.getId(), new BigDecimal("6"), null)),
            "partial primary shipment",
            "tester",
            null);

    var firstResponse = finishedGoodsService.confirmDispatch(firstConfirm, "tester");
    assertThat(firstResponse.backorderSlipId()).isNotNull();

    PackagingSlip firstBackorder =
        packagingSlipRepository
            .findByIdAndCompany(firstResponse.backorderSlipId(), company)
            .orElseThrow();
    PackagingSlipLine firstBackorderLine = firstBackorder.getLines().getFirst();

    DispatchConfirmationRequest secondConfirm =
        new DispatchConfirmationRequest(
            firstBackorder.getId(),
            List.of(
                new DispatchConfirmationRequest.LineConfirmation(
                    firstBackorderLine.getId(), new BigDecimal("2"), null)),
            "partial backorder shipment",
            "tester",
            null);

    var secondResponse = finishedGoodsService.confirmDispatch(secondConfirm, "tester");
    assertThat(secondResponse.totalBackorderAmount()).isGreaterThan(BigDecimal.ZERO);
    assertThat(secondResponse.backorderSlipId()).isNotNull();
    assertThat(secondResponse.backorderSlipId()).isNotEqualTo(firstBackorder.getId());

    PackagingSlip dispatchedBackorder =
        packagingSlipRepository.findByIdAndCompany(firstBackorder.getId(), company).orElseThrow();
    assertThat(dispatchedBackorder.getStatus()).isEqualTo("DISPATCHED");

    PackagingSlip followUpBackorder =
        packagingSlipRepository
            .findByIdAndCompany(secondResponse.backorderSlipId(), company)
            .orElseThrow();
    assertThat(followUpBackorder.isBackorder()).isTrue();
    assertThat(followUpBackorder.getStatus()).isEqualTo("BACKORDER");
    assertThat(followUpBackorder.getLines()).hasSize(1);
    assertThat(followUpBackorder.getLines().getFirst().getOrderedQuantity())
        .isEqualByComparingTo(new BigDecimal("2"));
  }

  @Test
  void previewUsesReservedForOrder() {
    Company company = seedCompany("PREVIEW-RES");
    FinishedGood fg =
        createFinishedGood(company, "FG-PREV", new BigDecimal("5"), new BigDecimal("5"), "FIFO");
    FinishedGoodBatch batch =
        createBatch(fg, "BATCH-PREV", new BigDecimal("5"), BigDecimal.ZERO, new BigDecimal("9"));
    SalesOrder order =
        createOrder(
            company, "SO-PREV-" + UUID.randomUUID(), fg.getProductCode(), new BigDecimal("5"));
    PackagingSlip slip = createSlip(company, order, "RESERVED", batch, new BigDecimal("5"));
    createReservation(order, fg, batch, new BigDecimal("5"));

    DispatchPreviewDto preview = finishedGoodsService.getDispatchPreview(slip.getId());
    DispatchPreviewDto.LinePreview line = preview.lines().getFirst();
    assertThat(line.availableQuantity()).isEqualByComparingTo(new BigDecimal("5"));
    assertThat(line.suggestedShipQuantity()).isEqualByComparingTo(new BigDecimal("5"));
    assertThat(line.hasShortage()).isFalse();
  }

  @Test
  void reserveAndDispatch_useActivePeriodCostingMethod_overFinishedGoodSetting() {
    Company company = seedCompany("PERIOD-LIFO-OVERRIDE");
    upsertCurrentPeriodCostingMethod(company, CostingMethod.LIFO);
    FinishedGood fg =
        createFinishedGood(company, "FG-PERIOD-LIFO", new BigDecimal("2"), BigDecimal.ZERO, "FIFO");
    FinishedGoodBatch oldest =
        createBatch(
            fg, "BATCH-PERIOD-OLD", new BigDecimal("1"), new BigDecimal("1"), new BigDecimal("10"));
    oldest.setManufacturedAt(Instant.now().minusSeconds(7200));
    finishedGoodBatchRepository.saveAndFlush(oldest);
    FinishedGoodBatch newest =
        createBatch(
            fg, "BATCH-PERIOD-NEW", new BigDecimal("1"), new BigDecimal("1"), new BigDecimal("20"));
    newest.setManufacturedAt(Instant.now().minusSeconds(3600));
    finishedGoodBatchRepository.saveAndFlush(newest);

    SalesOrder order =
        createOrder(
            company, "SO-PERIOD-LIFO-" + UUID.randomUUID(), fg.getProductCode(), BigDecimal.ONE);
    finishedGoodsService.reserveForOrder(order);

    PackagingSlip slip =
        packagingSlipRepository.findAllByCompanyAndSalesOrderId(company, order.getId()).stream()
            .filter(existing -> !existing.isBackorder())
            .findFirst()
            .orElseThrow();
    assertThat(slip.getLines()).hasSize(1);
    assertThat(slip.getLines().getFirst().getFinishedGoodBatch().getId()).isEqualTo(newest.getId());

    finishedGoodsService.markSlipDispatched(order.getId(), slip);

    InventoryMovement dispatchMovement =
        inventoryMovementRepository
            .findByReferenceTypeAndReferenceIdOrderByCreatedAtAsc(
                InventoryReference.SALES_ORDER, order.getId().toString())
            .stream()
            .filter(mv -> "DISPATCH".equalsIgnoreCase(mv.getMovementType()))
            .findFirst()
            .orElseThrow();
    assertThat(dispatchMovement.getUnitCost()).isEqualByComparingTo(new BigDecimal("20"));
  }

  @Test
  void stockAccuracyAcrossMovements_andPerProductLowStockThreshold() {
    Company company = seedCompany("STOCK-TRACE");
    FinishedGood fg =
        createFinishedGood(company, "FG-STOCK-TRACE", BigDecimal.ZERO, BigDecimal.ZERO, "FIFO");

    var registeredBatch =
        finishedGoodsService.registerBatch(
            new FinishedGoodBatchRequest(
                fg.getId(),
                "BATCH-STOCK-TRACE",
                new BigDecimal("20"),
                new BigDecimal("8"),
                Instant.now(),
                LocalDate.now().plusMonths(6)));

    SalesOrder reserveReleaseOrder =
        createOrder(
            company,
            "SO-STOCK-TRACE-A-" + UUID.randomUUID(),
            fg.getProductCode(),
            new BigDecimal("5"));
    finishedGoodsService.reserveForOrder(reserveReleaseOrder);
    finishedGoodsService.releaseReservationsForOrder(reserveReleaseOrder.getId());

    SalesOrder dispatchOrder =
        createOrder(
            company,
            "SO-STOCK-TRACE-B-" + UUID.randomUUID(),
            fg.getProductCode(),
            new BigDecimal("4"));
    finishedGoodsService.reserveForOrder(dispatchOrder);
    PackagingSlip dispatchSlip =
        packagingSlipRepository
            .findAllByCompanyAndSalesOrderId(company, dispatchOrder.getId())
            .stream()
            .filter(existing -> !existing.isBackorder())
            .findFirst()
            .orElseThrow();
    finishedGoodsService.markSlipDispatched(dispatchOrder.getId(), dispatchSlip);

    FinishedGood refreshed = finishedGoodRepository.findById(fg.getId()).orElseThrow();
    assertThat(refreshed.getCurrentStock()).isEqualByComparingTo(new BigDecimal("16"));
    assertThat(refreshed.getReservedStock()).isEqualByComparingTo(BigDecimal.ZERO);

    FinishedGoodBatch trackedBatch =
        finishedGoodBatchRepository.findById(registeredBatch.id()).orElseThrow();
    List<InventoryMovement> movements =
        inventoryMovementRepository.findByFinishedGoodBatchOrderByCreatedAtAsc(trackedBatch);
    assertThat(movements)
        .extracting(InventoryMovement::getMovementType)
        .contains("RECEIPT", "RESERVE", "RELEASE", "DISPATCH");

    var threshold = finishedGoodsService.updateLowStockThreshold(fg.getId(), new BigDecimal("17"));
    assertThat(threshold.threshold()).isEqualByComparingTo(new BigDecimal("17"));
    assertThat(finishedGoodsService.getLowStockItems(null))
        .extracting(item -> item.productCode())
        .contains(fg.getProductCode());
    assertThat(finishedGoodsService.getLowStockItems(10))
        .extracting(item -> item.productCode())
        .doesNotContain(fg.getProductCode());
  }

  private Company seedCompany(String code) {
    Company company = dataSeeder.ensureCompany(code, code + " Ltd");
    CompanyContextHolder.setCompanyCode(company.getCode());
    return company;
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

  private FinishedGood createFinishedGood(
      Company company,
      String productCode,
      BigDecimal currentStock,
      BigDecimal reservedStock,
      String costingMethod) {
    FinishedGood fg = new FinishedGood();
    fg.setCompany(company);
    fg.setProductCode(productCode);
    fg.setName(productCode);
    fg.setUnit("UNIT");
    fg.setCostingMethod(costingMethod);
    fg.setCurrentStock(currentStock);
    fg.setReservedStock(reservedStock);
    fg.setValuationAccountId(100L);
    fg.setCogsAccountId(200L);
    fg.setRevenueAccountId(300L);
    fg.setTaxAccountId(400L);
    return finishedGoodRepository.saveAndFlush(fg);
  }

  private FinishedGoodBatch createBatch(
      FinishedGood fg,
      String batchCode,
      BigDecimal quantityTotal,
      BigDecimal quantityAvailable,
      BigDecimal unitCost) {
    FinishedGoodBatch batch = new FinishedGoodBatch();
    batch.setFinishedGood(fg);
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
    PackagingSlip slip = new PackagingSlip();
    slip.setCompany(company);
    slip.setSalesOrder(order);
    slip.setSlipNumber(order.getOrderNumber() + "-PS");
    slip.setStatus(status);
    slip.setBackorder("BACKORDER".equalsIgnoreCase(status));

    PackagingSlipLine line = new PackagingSlipLine();
    line.setPackagingSlip(slip);
    line.setFinishedGoodBatch(batch);
    line.setOrderedQuantity(quantity);
    line.setQuantity(quantity);
    line.setUnitCost(batch.getUnitCost());
    slip.getLines().add(line);
    return packagingSlipRepository.saveAndFlush(slip);
  }

  private void createReservation(
      SalesOrder order, FinishedGood fg, FinishedGoodBatch batch, BigDecimal quantity) {
    InventoryReservation reservation = new InventoryReservation();
    reservation.setFinishedGood(fg);
    reservation.setFinishedGoodBatch(batch);
    reservation.setReferenceType(InventoryReference.SALES_ORDER);
    reservation.setReferenceId(order.getId().toString());
    reservation.setQuantity(quantity);
    reservation.setReservedQuantity(quantity);
    reservation.setStatus("RESERVED");
    inventoryReservationRepository.save(reservation);
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
}
