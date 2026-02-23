package com.bigbrightpaints.erp.truthsuite.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.modules.accounting.service.CompanyDefaultAccountsService;
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
import com.bigbrightpaints.erp.modules.inventory.service.BatchNumberService;
import com.bigbrightpaints.erp.modules.inventory.service.FinishedGoodsService;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrder;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrderRepository;
import java.util.ArrayList;
import java.math.BigDecimal;
import java.time.Instant;
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
import org.springframework.core.env.Environment;
import org.springframework.test.util.ReflectionTestUtils;

@Tag("critical")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TS_InventoryDispatchStateRuntimeCoverageTest {

    @Mock
    private CompanyContextService companyContextService;
    @Mock
    private FinishedGoodRepository finishedGoodRepository;
    @Mock
    private FinishedGoodBatchRepository finishedGoodBatchRepository;
    @Mock
    private PackagingSlipRepository packagingSlipRepository;
    @Mock
    private InventoryMovementRepository inventoryMovementRepository;
    @Mock
    private InventoryReservationRepository inventoryReservationRepository;
    @Mock
    private BatchNumberService batchNumberService;
    @Mock
    private SalesOrderRepository salesOrderRepository;
    @Mock
    private CompanyDefaultAccountsService companyDefaultAccountsService;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private CompanyClock companyClock;
    @Mock
    private Environment environment;

    private FinishedGoodsService service;
    private Company company;
    private Instant fixedNow;

    @BeforeEach
    void setUp() {
        service = new FinishedGoodsService(
                companyContextService,
                finishedGoodRepository,
                finishedGoodBatchRepository,
                packagingSlipRepository,
                inventoryMovementRepository,
                inventoryReservationRepository,
                batchNumberService,
                salesOrderRepository,
                companyDefaultAccountsService,
                eventPublisher,
                companyClock,
                environment,
                false);

        company = new Company();
        setId(company, 1L);
        company.setCode("INV-COMP");
        company.setTimezone("UTC");

        fixedNow = Instant.parse("2026-02-23T10:00:00Z");
        when(companyClock.now(company)).thenReturn(fixedNow);
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(batchNumberService.nextPackagingSlipNumber(any(Company.class))).thenReturn("PS-900");

        when(finishedGoodRepository.saveAll(any())).thenAnswer(invocation -> toList(invocation.getArgument(0)));
        when(finishedGoodBatchRepository.saveAll(any())).thenAnswer(invocation -> toList(invocation.getArgument(0)));
        when(inventoryReservationRepository.saveAll(any())).thenAnswer(invocation -> toList(invocation.getArgument(0)));
        when(inventoryMovementRepository.save(any(InventoryMovement.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(packagingSlipRepository.save(any(PackagingSlip.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(packagingSlipRepository.findAllByCompanyAndSalesOrderIdAndIsBackorderTrue(any(Company.class), anyLong()))
                .thenReturn(List.of());
        when(packagingSlipRepository.saveAndFlush(any(PackagingSlip.class))).thenAnswer(invocation -> {
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
                company,
                InventoryReference.SALES_ORDER,
                fixture.order.getId().toString())).thenReturn(List.of(fixture.reservation));
        when(finishedGoodRepository.lockByCompanyAndId(eq(company), eq(fixture.finishedGood.getId())))
                .thenReturn(Optional.of(fixture.finishedGood));

        service.markSlipDispatched(fixture.order.getId(), fixture.slip);

        assertThat(fixture.slip.getStatus()).isEqualTo("DISPATCHED");
        assertThat(fixture.slip.getDispatchedAt()).isEqualTo(fixedNow);
        assertThat(fixture.reservation.getStatus()).isEqualTo("PARTIAL");
        assertThat(fixture.line.getBackorderQuantity()).isEqualByComparingTo(new BigDecimal("5"));
        verify(packagingSlipRepository).saveAndFlush(any(PackagingSlip.class));
    }

    @Test
    void markSlipDispatched_fullFulfillmentMarksSlipDispatched() {
        Fixture fixture = fixture(new BigDecimal("10"), new BigDecimal("10"), new BigDecimal("10"));
        when(inventoryReservationRepository.findByFinishedGoodCompanyAndReferenceTypeAndReferenceId(
                company,
                InventoryReference.SALES_ORDER,
                fixture.order.getId().toString())).thenReturn(List.of(fixture.reservation));
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
                company,
                InventoryReference.SALES_ORDER,
                fixture.order.getId().toString())).thenReturn(List.of(fixture.reservation));
        when(finishedGoodRepository.lockByCompanyAndId(eq(company), eq(fixture.finishedGood.getId())))
                .thenReturn(Optional.of(fixture.finishedGood));

        service.markSlipDispatched(fixture.order.getId(), fixture.slip);

        assertThat(fixture.slip.getStatus()).isEqualTo("PENDING_STOCK");
        assertThat(fixture.slip.getDispatchedAt()).isNull();
        assertThat(fixture.reservation.getStatus()).isEqualTo("PARTIAL");
        assertThat(fixture.line.getBackorderQuantity()).isEqualByComparingTo(new BigDecimal("10"));
        verify(packagingSlipRepository).saveAndFlush(any(PackagingSlip.class));
    }

    @Test
    void markSlipDispatched_cancelledResidualReservationDoesNotKeepSlipPending() {
        Fixture fixture = fixture(new BigDecimal("10"), new BigDecimal("10"), new BigDecimal("10"));
        InventoryReservation cancelledResidual = cancelledResidualReservation(fixture.finishedGood, fixture.order.getId());
        when(inventoryReservationRepository.findByFinishedGoodCompanyAndReferenceTypeAndReferenceId(
                company,
                InventoryReference.SALES_ORDER,
                fixture.order.getId().toString())).thenReturn(List.of(fixture.reservation, cancelledResidual));
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
                company,
                InventoryReference.SALES_ORDER,
                fixture.order.getId().toString())).thenReturn(List.of(fixture.reservation));
        when(finishedGoodRepository.lockByCompanyAndId(eq(company), eq(fixture.finishedGood.getId())))
                .thenReturn(Optional.of(fixture.finishedGood));

        service.markSlipDispatched(fixture.order.getId(), fixture.slip);

        assertThat(fixture.slip.getStatus()).isEqualTo("DISPATCHED");
        assertThat(fixture.slip.getDispatchedAt()).isEqualTo(existingDispatchTime);
    }

    @Test
    void markSlipDispatched_onlyCancelledReservationsLeavesStatusUnchanged() {
        Fixture fixture = fixture(new BigDecimal("10"), new BigDecimal("10"), new BigDecimal("10"));
        InventoryReservation cancelledResidual = cancelledResidualReservation(fixture.finishedGood, fixture.order.getId());
        String initialStatus = fixture.slip.getStatus();
        when(inventoryReservationRepository.findByFinishedGoodCompanyAndReferenceTypeAndReferenceId(
                company,
                InventoryReference.SALES_ORDER,
                fixture.order.getId().toString())).thenReturn(List.of(cancelledResidual));
        when(finishedGoodRepository.lockByCompanyAndId(eq(company), eq(fixture.finishedGood.getId())))
                .thenReturn(Optional.of(fixture.finishedGood));

        service.markSlipDispatched(fixture.order.getId(), fixture.slip);

        assertThat(fixture.slip.getStatus()).isEqualTo(initialStatus);
        assertThat(fixture.slip.getDispatchedAt()).isNull();
        verify(packagingSlipRepository).saveAndFlush(any(PackagingSlip.class));
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

    private InventoryReservation cancelledResidualReservation(FinishedGood finishedGood, Long orderId) {
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
            InventoryReservation reservation) {
    }
}
