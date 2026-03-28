package com.bigbrightpaints.erp.modules.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.modules.accounting.domain.CostingMethod;
import com.bigbrightpaints.erp.modules.accounting.service.CostingMethodService;
import com.bigbrightpaints.erp.modules.accounting.service.GstService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
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
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrder;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrderRepository;

@Tag("critical")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TS_InventoryDispatchStateRuntimeCoverageTest {

  @Mock private CompanyContextService companyContextService;
  @Mock private FinishedGoodRepository finishedGoodRepository;
  @Mock private FinishedGoodBatchRepository finishedGoodBatchRepository;
  @Mock private PackagingSlipRepository packagingSlipRepository;
  @Mock private InventoryMovementRepository inventoryMovementRepository;
  @Mock private InventoryReservationRepository inventoryReservationRepository;
  @Mock private SalesOrderRepository salesOrderRepository;
  @Mock private CostingMethodService costingMethodService;
  @Mock private GstService gstService;
  @Mock private ApplicationEventPublisher eventPublisher;
  @Mock private CompanyClock companyClock;
  @Mock private FinishedGoodsReservationEngine reservationEngine;
  @Mock private PackagingSlipService packagingSlipService;

  private FinishedGoodsWorkflowEngineService service;
  private Company company;
  private Instant fixedNow;

  @BeforeEach
  void setUp() {
    InventoryValuationService inventoryValuationService =
        new InventoryValuationService(
            finishedGoodBatchRepository, costingMethodService, companyClock);
    InventoryMovementRecorder movementRecorder =
        new InventoryMovementRecorder(inventoryMovementRepository, eventPublisher, companyClock);
    FinishedGoodsDispatchEngine dispatchEngine =
        new FinishedGoodsDispatchEngine(
            companyContextService,
            finishedGoodRepository,
            finishedGoodBatchRepository,
            packagingSlipRepository,
            inventoryMovementRepository,
            inventoryReservationRepository,
            salesOrderRepository,
            gstService,
            companyClock,
            movementRecorder,
            reservationEngine,
            packagingSlipService,
            inventoryValuationService);
    service =
        new FinishedGoodsWorkflowEngineService(
            companyContextService,
            finishedGoodRepository,
            finishedGoodBatchRepository,
            inventoryValuationService,
            reservationEngine,
            dispatchEngine,
            packagingSlipService);

    company = new Company();
    setId(company, 1L);
    company.setCode("INV-COMP");
    company.setTimezone("UTC");

    fixedNow = Instant.parse("2026-02-23T10:00:00Z");
    when(companyClock.now(company)).thenReturn(fixedNow);
    when(companyContextService.requireCurrentCompany()).thenReturn(company);
    when(costingMethodService.resolveActiveMethod(any(Company.class), any()))
        .thenReturn(CostingMethod.FIFO);

    when(finishedGoodRepository.saveAll(any()))
        .thenAnswer(invocation -> toList(invocation.getArgument(0)));
    when(finishedGoodBatchRepository.saveAll(any()))
        .thenAnswer(invocation -> toList(invocation.getArgument(0)));
    when(inventoryReservationRepository.saveAll(any()))
        .thenAnswer(invocation -> toList(invocation.getArgument(0)));
    when(inventoryMovementRepository.save(any(InventoryMovement.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(packagingSlipRepository.save(any(PackagingSlip.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(packagingSlipRepository.findActiveBackorderSlipIds(any(Company.class), anyLong()))
        .thenReturn(List.of());
    when(packagingSlipRepository.saveAndFlush(any(PackagingSlip.class)))
        .thenAnswer(
            invocation -> {
              PackagingSlip slip = invocation.getArgument(0);
              if (slip.getId() == null) {
                setId(slip, 999L);
              }
              return slip;
            });
  }

  @Test
  void markSlipDispatched_partialFulfillmentMarksPrimarySlipDispatched() {
    Fixture fixture = fixture(new BigDecimal("5"), new BigDecimal("10"), new BigDecimal("10"));
    when(inventoryReservationRepository.findByFinishedGoodCompanyAndReferenceTypeAndReferenceId(
            company, InventoryReference.SALES_ORDER, fixture.order.getId().toString()))
        .thenReturn(List.of(fixture.reservation));
    when(finishedGoodRepository.lockByCompanyAndId(eq(company), eq(fixture.finishedGood.getId())))
        .thenReturn(Optional.of(fixture.finishedGood));

    service.markSlipDispatched(fixture.order.getId(), fixture.slip);

    assertThat(fixture.slip.getStatus()).isEqualTo("DISPATCHED");
    assertThat(fixture.slip.getDispatchedAt()).isEqualTo(fixedNow);
    assertThat(fixture.reservation.getStatus()).isEqualTo("PARTIAL");
    assertThat(fixture.line.getBackorderQuantity()).isEqualByComparingTo(new BigDecimal("5"));
    verify(packagingSlipRepository).save(any(PackagingSlip.class));
  }

  @Test
  void markSlipDispatched_fullFulfillmentMarksSlipDispatched() {
    Fixture fixture = fixture(new BigDecimal("10"), new BigDecimal("10"), new BigDecimal("10"));
    when(inventoryReservationRepository.findByFinishedGoodCompanyAndReferenceTypeAndReferenceId(
            company, InventoryReference.SALES_ORDER, fixture.order.getId().toString()))
        .thenReturn(List.of(fixture.reservation));
    when(finishedGoodRepository.lockByCompanyAndId(eq(company), eq(fixture.finishedGood.getId())))
        .thenReturn(Optional.of(fixture.finishedGood));

    service.markSlipDispatched(fixture.order.getId(), fixture.slip);

    assertThat(fixture.slip.getStatus()).isEqualTo("DISPATCHED");
    assertThat(fixture.slip.getDispatchedAt()).isEqualTo(fixedNow);
    assertThat(fixture.reservation.getStatus()).isEqualTo("FULFILLED");
    assertThat(fixture.line.getBackorderQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
    verify(packagingSlipRepository, never()).saveAndFlush(any(PackagingSlip.class));
  }

  @Test
  void markSlipDispatched_zeroFulfillmentLeavesPendingStockWithoutDispatchTimestamp() {
    Fixture fixture = fixture(BigDecimal.ZERO, new BigDecimal("10"), new BigDecimal("10"));
    when(inventoryReservationRepository.findByFinishedGoodCompanyAndReferenceTypeAndReferenceId(
            company, InventoryReference.SALES_ORDER, fixture.order.getId().toString()))
        .thenReturn(List.of(fixture.reservation));
    when(finishedGoodRepository.lockByCompanyAndId(eq(company), eq(fixture.finishedGood.getId())))
        .thenReturn(Optional.of(fixture.finishedGood));

    service.markSlipDispatched(fixture.order.getId(), fixture.slip);

    assertThat(fixture.slip.getStatus()).isEqualTo("PENDING_STOCK");
    assertThat(fixture.slip.getDispatchedAt()).isNull();
    assertThat(fixture.reservation.getStatus()).isEqualTo("PARTIAL");
    assertThat(fixture.line.getBackorderQuantity()).isEqualByComparingTo(new BigDecimal("10"));
    verify(packagingSlipRepository).save(any(PackagingSlip.class));
  }

  @Test
  void markSlipDispatched_cancelledResidualReservationDoesNotKeepSlipPending() {
    Fixture fixture = fixture(new BigDecimal("10"), new BigDecimal("10"), new BigDecimal("10"));
    InventoryReservation cancelledResidual =
        cancelledResidualReservation(fixture.finishedGood, fixture.order.getId());
    when(inventoryReservationRepository.findByFinishedGoodCompanyAndReferenceTypeAndReferenceId(
            company, InventoryReference.SALES_ORDER, fixture.order.getId().toString()))
        .thenReturn(List.of(fixture.reservation, cancelledResidual));
    when(finishedGoodRepository.lockByCompanyAndId(eq(company), eq(fixture.finishedGood.getId())))
        .thenReturn(Optional.of(fixture.finishedGood));

    service.markSlipDispatched(fixture.order.getId(), fixture.slip);

    assertThat(fixture.slip.getStatus()).isEqualTo("DISPATCHED");
    assertThat(cancelledResidual.getStatus()).isEqualTo("CANCELLED");
    verify(packagingSlipRepository, never()).saveAndFlush(any(PackagingSlip.class));
  }

  @Test
  void markSlipDispatched_partialRetryKeepsExistingDispatchedTimestamp() {
    Fixture fixture = fixture(new BigDecimal("5"), new BigDecimal("10"), new BigDecimal("10"));
    Instant existingDispatchTime = Instant.parse("2026-02-22T08:15:00Z");
    fixture.slip.setDispatchedAt(existingDispatchTime);
    when(inventoryReservationRepository.findByFinishedGoodCompanyAndReferenceTypeAndReferenceId(
            company, InventoryReference.SALES_ORDER, fixture.order.getId().toString()))
        .thenReturn(List.of(fixture.reservation));
    when(finishedGoodRepository.lockByCompanyAndId(eq(company), eq(fixture.finishedGood.getId())))
        .thenReturn(Optional.of(fixture.finishedGood));

    service.markSlipDispatched(fixture.order.getId(), fixture.slip);

    assertThat(fixture.slip.getStatus()).isEqualTo("DISPATCHED");
    assertThat(fixture.slip.getDispatchedAt()).isEqualTo(existingDispatchTime);
  }

  @Test
  void markSlipDispatched_onlyCancelledReservationsLeavesStatusUnchanged() {
    Fixture fixture = fixture(new BigDecimal("10"), new BigDecimal("10"), new BigDecimal("10"));
    InventoryReservation cancelledResidual =
        cancelledResidualReservation(fixture.finishedGood, fixture.order.getId());
    String initialStatus = fixture.slip.getStatus();
    when(inventoryReservationRepository.findByFinishedGoodCompanyAndReferenceTypeAndReferenceId(
            company, InventoryReference.SALES_ORDER, fixture.order.getId().toString()))
        .thenReturn(List.of(cancelledResidual));
    when(finishedGoodRepository.lockByCompanyAndId(eq(company), eq(fixture.finishedGood.getId())))
        .thenReturn(Optional.of(fixture.finishedGood));

    service.markSlipDispatched(fixture.order.getId(), fixture.slip);

    assertThat(fixture.slip.getStatus()).isEqualTo(initialStatus);
    assertThat(fixture.slip.getDispatchedAt()).isNull();
    verify(packagingSlipRepository).save(any(PackagingSlip.class));
  }

  @Test
  void listFinishedGoods_includesAllFinishedGoodsFromRepository() {
    FinishedGood sellable = finishedGood(901L, "FG-SELL-1L", "Sellable");
    FinishedGood alternateSellable = finishedGood(902L, "FG-SELL-10L", "Sellable");

    when(finishedGoodRepository.findByCompanyOrderByProductCodeAsc(company))
        .thenReturn(List.of(alternateSellable, sellable));

    assertThat(service.listFinishedGoods()).hasSize(2);
    assertThat(service.listFinishedGoods())
        .extracting(item -> item.productCode())
        .containsExactly("FG-SELL-10L", "FG-SELL-1L");
  }

  @Test
  void getFinishedGood_returnsRecordForPersistedSku() {
    FinishedGood finishedGood = finishedGood(903L, "FG-LOOKUP-4L", "Sellable");
    when(finishedGoodRepository.findByCompanyAndId(company, 903L))
        .thenReturn(Optional.of(finishedGood));

    assertThat(service.getFinishedGood(903L).productCode()).isEqualTo("FG-LOOKUP-4L");
  }

  @Test
  void lockFinishedGoodByProductCode_returnsPersistedSkuWhenRepositoryResolvesIt() {
    FinishedGood finishedGood = finishedGood(908L, "FG-LOCK-4L", "Sellable");
    when(finishedGoodRepository.lockByCompanyAndProductCode(company, "FG-LOCK-4L"))
        .thenReturn(Optional.of(finishedGood));

    FinishedGood locked = service.lockFinishedGoodByProductCode("FG-LOCK-4L");

    assertThat(locked).isSameAs(finishedGood);
  }

  @Test
  void lockFinishedGoodByProductCode_returnsSellableSku() {
    FinishedGood sellable = finishedGood(906L, "FG-LOCK-1L", "Sellable");
    when(finishedGoodRepository.lockByCompanyAndProductCode(company, "FG-LOCK-1L"))
        .thenReturn(Optional.of(sellable));

    FinishedGood locked = service.lockFinishedGoodByProductCode("FG-LOCK-1L");

    assertThat(locked).isSameAs(sellable);
  }

  @Test
  void listBatchesForFinishedGood_returnsBatchesForSellableSku() {
    FinishedGood sellable = finishedGood(907L, "FG-BATCH-1L", "Sellable");
    FinishedGoodBatch batch = new FinishedGoodBatch();
    setId(batch, 1907L);
    batch.setFinishedGood(sellable);
    batch.setBatchCode("FG-BATCH-907");
    batch.setQuantityTotal(new BigDecimal("4"));
    batch.setQuantityAvailable(new BigDecimal("4"));
    batch.setUnitCost(new BigDecimal("8"));

    when(finishedGoodRepository.findByCompanyAndId(company, 907L)).thenReturn(Optional.of(sellable));
    when(finishedGoodBatchRepository.findByFinishedGoodOrderByManufacturedAtAsc(sellable))
        .thenReturn(List.of(batch));

    assertThat(service.listBatchesForFinishedGood(907L)).hasSize(1);
    assertThat(service.listBatchesForFinishedGood(907L).getFirst().batchCode())
        .isEqualTo("FG-BATCH-907");
  }

  @Test
  void getLowStockThreshold_returnsValueForExistingFinishedGood() {
    FinishedGood finishedGood = finishedGood(904L, "FG-LOW-4L", "Sellable");
    finishedGood.setLowStockThreshold(new BigDecimal("7"));
    when(finishedGoodRepository.findByCompanyAndId(company, 904L))
        .thenReturn(Optional.of(finishedGood));

    assertThat(service.getLowStockThreshold(904L).threshold())
        .isEqualByComparingTo(new BigDecimal("7"));
  }

  @Test
  void updateLowStockThreshold_updatesExistingFinishedGood() {
    FinishedGood finishedGood = finishedGood(905L, "FG-LOW-UPDATE-4L", "Sellable");
    when(finishedGoodRepository.lockByCompanyAndId(company, 905L))
        .thenReturn(Optional.of(finishedGood));
    when(finishedGoodRepository.save(any(FinishedGood.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    assertThat(service.updateLowStockThreshold(905L, BigDecimal.ONE).threshold())
        .isEqualByComparingTo(BigDecimal.ONE);
    verify(finishedGoodRepository).save(finishedGood);
  }

  private Fixture fixture(BigDecimal currentStock, BigDecimal reservedStock, BigDecimal quantity) {
    SalesOrder order = new SalesOrder();
    setId(order, 101L);
    order.setOrderNumber("SO-101");

    FinishedGood finishedGood = new FinishedGood();
    setId(finishedGood, 201L);
    finishedGood.setCompany(company);
    finishedGood.setProductCode("FG-201");
    finishedGood.setName("FG 201");
    finishedGood.setCostingMethod("FIFO");
    finishedGood.setCurrentStock(currentStock);
    finishedGood.setReservedStock(reservedStock);
    finishedGood.setValuationAccountId(111L);
    finishedGood.setCogsAccountId(222L);

    FinishedGoodBatch batch = new FinishedGoodBatch();
    setId(batch, 301L);
    batch.setFinishedGood(finishedGood);
    batch.setBatchCode("BATCH-301");
    batch.setQuantityTotal(quantity);
    batch.setQuantityAvailable(quantity);
    batch.setUnitCost(new BigDecimal("9.50"));

    PackagingSlip slip = new PackagingSlip();
    setId(slip, 401L);
    slip.setCompany(company);
    slip.setSalesOrder(order);
    slip.setSlipNumber("PS-401");
    slip.setStatus("RESERVED");

    PackagingSlipLine line = new PackagingSlipLine();
    setId(line, 501L);
    line.setPackagingSlip(slip);
    line.setFinishedGoodBatch(batch);
    line.setOrderedQuantity(quantity);
    line.setQuantity(quantity);
    line.setUnitCost(new BigDecimal("9.50"));
    slip.getLines().add(line);

    InventoryReservation reservation = new InventoryReservation();
    setId(reservation, 601L);
    reservation.setFinishedGood(finishedGood);
    reservation.setFinishedGoodBatch(batch);
    reservation.setReferenceType(InventoryReference.SALES_ORDER);
    reservation.setReferenceId(order.getId().toString());
    reservation.setQuantity(quantity);
    reservation.setReservedQuantity(quantity);
    reservation.setStatus("RESERVED");

    return new Fixture(order, finishedGood, slip, line, reservation);
  }

  private InventoryReservation cancelledResidualReservation(
      FinishedGood finishedGood, Long orderId) {
    FinishedGoodBatch unmatchedBatch = new FinishedGoodBatch();
    setId(unmatchedBatch, 777L);
    unmatchedBatch.setFinishedGood(finishedGood);
    unmatchedBatch.setBatchCode("BATCH-CANCELLED");
    unmatchedBatch.setQuantityTotal(BigDecimal.ONE);
    unmatchedBatch.setQuantityAvailable(BigDecimal.ONE);
    unmatchedBatch.setUnitCost(new BigDecimal("9.50"));

    InventoryReservation reservation = new InventoryReservation();
    setId(reservation, 778L);
    reservation.setFinishedGood(finishedGood);
    reservation.setFinishedGoodBatch(unmatchedBatch);
    reservation.setReferenceType(InventoryReference.SALES_ORDER);
    reservation.setReferenceId(orderId.toString());
    reservation.setQuantity(BigDecimal.ONE);
    reservation.setReservedQuantity(BigDecimal.ONE);
    reservation.setStatus("CANCELLED");
    return reservation;
  }

  private static void setId(Object target, Long id) {
    ReflectionTestUtils.setField(target, "id", id);
  }

  private FinishedGood finishedGood(Long id, String productCode, String name) {
    FinishedGood fg = new FinishedGood();
    setId(fg, id);
    fg.setCompany(company);
    fg.setProductCode(productCode);
    fg.setName(name);
    fg.setUnit("L");
    fg.setCostingMethod("FIFO");
    fg.setCurrentStock(new BigDecimal("10"));
    fg.setReservedStock(BigDecimal.ZERO);
    fg.setValuationAccountId(111L);
    fg.setCogsAccountId(222L);
    return fg;
  }

  private static <T> List<T> toList(Iterable<T> values) {
    List<T> items = new ArrayList<>();
    values.forEach(items::add);
    return items;
  }

  private record Fixture(
      SalesOrder order,
      FinishedGood finishedGood,
      PackagingSlip slip,
      PackagingSlipLine line,
      InventoryReservation reservation) {}
}
