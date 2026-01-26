package com.bigbrightpaints.erp.modules.inventory.service;

import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
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
import com.bigbrightpaints.erp.modules.inventory.dto.DispatchPreviewDto;
import com.bigbrightpaints.erp.modules.inventory.dto.FinishedGoodBatchRequest;
import com.bigbrightpaints.erp.modules.inventory.dto.DispatchConfirmationRequest;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrder;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrderItem;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrderRepository;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Transactional
class FinishedGoodsServiceTest extends AbstractIntegrationTest {

    @Autowired
    private FinishedGoodsService finishedGoodsService;

    @Autowired
    private FinishedGoodRepository finishedGoodRepository;

    @Autowired
    private FinishedGoodBatchRepository finishedGoodBatchRepository;

    @Autowired
    private PackagingSlipRepository packagingSlipRepository;

    @Autowired
    private InventoryReservationRepository inventoryReservationRepository;

    @Autowired
    private InventoryMovementRepository inventoryMovementRepository;

    @Autowired
    private SalesOrderRepository salesOrderRepository;

    @AfterEach
    void clearContext() {
        CompanyContextHolder.clear();
    }

    @Test
    void dispatchUsesWacIncludingReserved() {
        Company company = seedCompany("WAC-RES");
        FinishedGood fg = createFinishedGood(company, "FG-WAC", new BigDecimal("10"), new BigDecimal("5"), "WAC");
        FinishedGoodBatch batch = createBatch(fg, "BATCH-WAC", new BigDecimal("10"), BigDecimal.ZERO, new BigDecimal("25"));
        SalesOrder order = createOrder(company, "SO-WAC-" + UUID.randomUUID(), fg.getProductCode(), new BigDecimal("5"));
        PackagingSlip slip = createSlip(company, order, "RESERVED", batch, new BigDecimal("5"));
        createReservation(order, fg, batch, new BigDecimal("5"));

        finishedGoodsService.markSlipDispatched(order.getId(), slip);

        List<InventoryMovement> movements = inventoryMovementRepository
                .findByReferenceTypeAndReferenceIdOrderByCreatedAtAsc(InventoryReference.SALES_ORDER, order.getId().toString());
        InventoryMovement dispatchMovement = movements.stream()
                .filter(mv -> "DISPATCH".equalsIgnoreCase(mv.getMovementType()))
                .findFirst()
                .orElseThrow();
        assertThat(dispatchMovement.getUnitCost()).isEqualByComparingTo(new BigDecimal("25"));
    }

    @Test
    void dispatchRejectsZeroCostWhenOnHandExists() {
        Company company = seedCompany("WAC-ZERO");
        FinishedGood fg = createFinishedGood(company, "FG-ZERO", new BigDecimal("5"), new BigDecimal("5"), "FIFO");
        FinishedGoodBatch batch = createBatch(fg, "BATCH-ZERO", new BigDecimal("5"), BigDecimal.ZERO, BigDecimal.ZERO);
        SalesOrder order = createOrder(company, "SO-ZERO-" + UUID.randomUUID(), fg.getProductCode(), new BigDecimal("5"));
        PackagingSlip slip = createSlip(company, order, "RESERVED", batch, new BigDecimal("5"));
        createReservation(order, fg, batch, new BigDecimal("5"));

        assertThatThrownBy(() -> finishedGoodsService.markSlipDispatched(order.getId(), slip))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Dispatch cost is zero");
    }

    @Test
    void confirmDispatchRejectsWhenReservedStockDriftsNegative() {
        Company company = seedCompany("DISPATCH-RES-NEG");
        FinishedGood fg = createFinishedGood(company, "FG-NEG", new BigDecimal("5"), new BigDecimal("1"), "FIFO");
        FinishedGoodBatch batch = createBatch(fg, "BATCH-NEG", new BigDecimal("5"), BigDecimal.ZERO, new BigDecimal("10"));
        SalesOrder order = createOrder(company, "SO-NEG-" + UUID.randomUUID(), fg.getProductCode(), new BigDecimal("5"));
        PackagingSlip slip = createSlip(company, order, "RESERVED", batch, new BigDecimal("5"));
        createReservation(order, fg, batch, new BigDecimal("5"));

        PackagingSlipLine line = slip.getLines().getFirst();
        DispatchConfirmationRequest request = new DispatchConfirmationRequest(
                slip.getId(),
                List.of(new DispatchConfirmationRequest.LineConfirmation(line.getId(), new BigDecimal("5"), null)),
                null,
                null,
                null);

        assertThatThrownBy(() -> finishedGoodsService.confirmDispatch(request, "tester"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Reserved stock insufficient");
    }

    @Test
    void confirmDispatchRejectsWhenBatchStockInsufficient() {
        Company company = seedCompany("DISPATCH-BATCH-NEG");
        FinishedGood fg = createFinishedGood(company, "FG-BATCH", new BigDecimal("5"), new BigDecimal("5"), "FIFO");
        FinishedGoodBatch batch = createBatch(fg, "BATCH-LOW", new BigDecimal("2"), BigDecimal.ZERO, new BigDecimal("11"));
        SalesOrder order = createOrder(company, "SO-BATCH-" + UUID.randomUUID(), fg.getProductCode(), new BigDecimal("5"));
        PackagingSlip slip = createSlip(company, order, "RESERVED", batch, new BigDecimal("5"));
        createReservation(order, fg, batch, new BigDecimal("5"));

        PackagingSlipLine line = slip.getLines().getFirst();
        DispatchConfirmationRequest request = new DispatchConfirmationRequest(
                slip.getId(),
                List.of(new DispatchConfirmationRequest.LineConfirmation(line.getId(), new BigDecimal("5"), null)),
                null,
                null,
                null);

        assertThatThrownBy(() -> finishedGoodsService.confirmDispatch(request, "tester"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Batch stock insufficient");
    }

    @Test
    void reserveDoesNotOverrideTerminalStatus() {
        Company company = seedCompany("SLIP-TERM");
        FinishedGood fg = createFinishedGood(company, "FG-TERM", new BigDecimal("5"), BigDecimal.ZERO, "FIFO");
        FinishedGoodBatch batch = createBatch(fg, "BATCH-TERM", new BigDecimal("5"), new BigDecimal("5"), new BigDecimal("10"));
        SalesOrder order = createOrder(company, "SO-TERM-" + UUID.randomUUID(), fg.getProductCode(), new BigDecimal("5"));
        PackagingSlip slip = createSlip(company, order, "DISPATCHED", batch, new BigDecimal("5"));

        finishedGoodsService.reserveForOrder(order);

        PackagingSlip refreshed = packagingSlipRepository.findByIdAndCompany(slip.getId(), company).orElseThrow();
        assertThat(refreshed.getStatus()).isEqualTo("DISPATCHED");
    }

    @Test
    void cancelBackorderClearsReservation() {
        Company company = seedCompany("BACKORDER-CLR");
        FinishedGood fg = createFinishedGood(company, "FG-BO", new BigDecimal("5"), new BigDecimal("5"), "FIFO");
        FinishedGoodBatch batch = createBatch(fg, "BATCH-BO", new BigDecimal("5"), BigDecimal.ZERO, new BigDecimal("12"));
        SalesOrder order = createOrder(company, "SO-BO-" + UUID.randomUUID(), fg.getProductCode(), new BigDecimal("5"));
        PackagingSlip slip = createSlip(company, order, "BACKORDER", batch, new BigDecimal("5"));
        createReservation(order, fg, batch, new BigDecimal("5"));

        finishedGoodsService.cancelBackorderSlip(slip.getId(), "tester", "cancel backorder");

        List<InventoryReservation> reservations = inventoryReservationRepository
                .findByFinishedGoodCompanyAndReferenceTypeAndReferenceId(
                        company, InventoryReference.SALES_ORDER, order.getId().toString());
        assertThat(reservations).isNotEmpty();
        assertThat(reservations).allMatch(r -> "CANCELLED".equalsIgnoreCase(r.getStatus()));
        assertThat(reservations).allSatisfy(r -> assertThat(r.getReservedQuantity()).isEqualByComparingTo(BigDecimal.ZERO));
    }

    @Test
    void reserveForOrderKeepsBackorderSlip() {
        Company company = seedCompany("BACKORDER-KEEP");
        FinishedGood fg = createFinishedGood(company, "FG-KEEP", new BigDecimal("5"), new BigDecimal("5"), "FIFO");
        FinishedGoodBatch batch = createBatch(fg, "BATCH-KEEP", new BigDecimal("5"), BigDecimal.ZERO, new BigDecimal("7"));
        SalesOrder order = createOrder(company, "SO-KEEP-" + UUID.randomUUID(), fg.getProductCode(), new BigDecimal("10"));
        PackagingSlip slip = createSlip(company, order, "BACKORDER", batch, new BigDecimal("5"));
        createReservation(order, fg, batch, new BigDecimal("5"));

        finishedGoodsService.reserveForOrder(order);

        PackagingSlip refreshed = packagingSlipRepository.findByIdAndCompany(slip.getId(), company).orElseThrow();
        assertThat(refreshed.getStatus()).isEqualTo("BACKORDER");
        assertThat(refreshed.getLines()).hasSize(1);
        List<InventoryReservation> reservations = inventoryReservationRepository
                .findByFinishedGoodCompanyAndReferenceTypeAndReferenceId(
                        company, InventoryReference.SALES_ORDER, order.getId().toString());
        assertThat(reservations).isNotEmpty();
        assertThat(reservations).allMatch(r -> !"CANCELLED".equalsIgnoreCase(r.getStatus()));
        assertThat(reservations).allSatisfy(r -> assertThat(r.getReservedQuantity()).isEqualByComparingTo(new BigDecimal("5")));
    }

    @Test
    void reserveForOrderAllocatesShortagesWhenBackorderSlipExists() {
        Company company = seedCompany("BACKORDER-ALLOC");
        FinishedGood fg = createFinishedGood(company, "FG-ALLOC", new BigDecimal("10"), new BigDecimal("5"), "FIFO");
        FinishedGoodBatch reservedBatch = createBatch(fg, "BATCH-ALLOC-RES", new BigDecimal("5"), BigDecimal.ZERO, new BigDecimal("8"));
        FinishedGoodBatch availableBatch = createBatch(fg, "BATCH-ALLOC-AVAIL", new BigDecimal("5"), new BigDecimal("5"), new BigDecimal("9"));
        SalesOrder order = createOrder(company, "SO-ALLOC-" + UUID.randomUUID(), fg.getProductCode(), new BigDecimal("10"));
        PackagingSlip slip = createSlip(company, order, "BACKORDER", reservedBatch, new BigDecimal("5"));
        createReservation(order, fg, reservedBatch, new BigDecimal("5"));

        FinishedGoodsService.InventoryReservationResult result = finishedGoodsService.reserveForOrder(order);
        FinishedGoodsService.InventoryReservationResult second = finishedGoodsService.reserveForOrder(order);

        assertThat(result.shortages()).isEmpty();
        assertThat(second.shortages()).isEmpty();
        PackagingSlip refreshed = packagingSlipRepository.findByIdAndCompany(slip.getId(), company).orElseThrow();
        assertThat(refreshed.getStatus()).isEqualTo("RESERVED");
        assertThat(refreshed.getLines()).hasSize(2);
        List<InventoryReservation> reservations = inventoryReservationRepository
                .findByFinishedGoodCompanyAndReferenceTypeAndReferenceId(
                        company, InventoryReference.SALES_ORDER, order.getId().toString());
        BigDecimal totalReserved = reservations.stream()
                .filter(r -> !"CANCELLED".equalsIgnoreCase(r.getStatus()))
                .map(r -> r.getReservedQuantity() != null ? r.getReservedQuantity() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(totalReserved).isEqualByComparingTo(new BigDecimal("10"));
        assertThat(reservations)
                .anyMatch(r -> r.getFinishedGoodBatch() != null &&
                        r.getFinishedGoodBatch().getId().equals(availableBatch.getId()));
    }

    @Test
    void reserveForOrderBackorderWithNullReservedQuantityIsNotDuplicated() {
        Company company = seedCompany("BACKORDER-NULL");
        FinishedGood fg = createFinishedGood(company, "FG-NULL", new BigDecimal("5"), new BigDecimal("5"), "FIFO");
        FinishedGoodBatch batch = createBatch(fg, "BATCH-NULL", new BigDecimal("5"), new BigDecimal("5"), new BigDecimal("6"));
        SalesOrder order = createOrder(company, "SO-NULL-" + UUID.randomUUID(), fg.getProductCode(), new BigDecimal("5"));
        PackagingSlip slip = createSlip(company, order, "BACKORDER", batch, new BigDecimal("5"));

        InventoryReservation reservation = new InventoryReservation();
        reservation.setFinishedGood(fg);
        reservation.setFinishedGoodBatch(batch);
        reservation.setReferenceType(InventoryReference.SALES_ORDER);
        reservation.setReferenceId(order.getId().toString());
        reservation.setQuantity(new BigDecimal("5"));
        reservation.setReservedQuantity(null);
        reservation.setStatus("RESERVED");
        inventoryReservationRepository.save(reservation);

        FinishedGoodsService.InventoryReservationResult result = finishedGoodsService.reserveForOrder(order);

        assertThat(result.shortages()).isEmpty();
        List<InventoryReservation> reservations = inventoryReservationRepository
                .findByFinishedGoodCompanyAndReferenceTypeAndReferenceId(
                        company, InventoryReference.SALES_ORDER, order.getId().toString());
        assertThat(reservations).hasSize(1);
    }

    @Test
    void reserveForOrderBackorderRebuildsMissingReservations() {
        Company company = seedCompany("BACKORDER-REB");
        FinishedGood fg = createFinishedGood(company, "FG-REB", new BigDecimal("5"), new BigDecimal("5"), "FIFO");
        FinishedGoodBatch batch = createBatch(fg, "BATCH-REB", new BigDecimal("5"), new BigDecimal("5"), new BigDecimal("6"));
        SalesOrder order = createOrder(company, "SO-REB-" + UUID.randomUUID(), fg.getProductCode(), new BigDecimal("5"));
        createSlip(company, order, "BACKORDER", batch, new BigDecimal("5"));

        FinishedGoodsService.InventoryReservationResult result = finishedGoodsService.reserveForOrder(order);

        assertThat(result.shortages()).isEmpty();
        List<InventoryReservation> reservations = inventoryReservationRepository
                .findByFinishedGoodCompanyAndReferenceTypeAndReferenceId(
                        company, InventoryReference.SALES_ORDER, order.getId().toString());
        assertThat(reservations).hasSize(1);
        assertThat(reservations.getFirst().getReservedQuantity()).isEqualByComparingTo(new BigDecimal("5"));
    }

    @Test
    void registerBatchRejectsNegativeQuantity() {
        Company company = seedCompany("BATCH-NEG-QTY");
        FinishedGood fg = createFinishedGood(company, "FG-NEG-QTY", new BigDecimal("0"), BigDecimal.ZERO, "FIFO");

        assertThatThrownBy(() -> finishedGoodsService.registerBatch(new FinishedGoodBatchRequest(
                fg.getId(),
                "BATCH-NEG-QTY",
                new BigDecimal("-1"),
                new BigDecimal("10.00"),
                Instant.now(),
                null
        ))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Batch quantity");
    }

    @Test
    void registerBatchRejectsNegativeUnitCost() {
        Company company = seedCompany("BATCH-NEG-COST");
        FinishedGood fg = createFinishedGood(company, "FG-NEG-COST", new BigDecimal("0"), BigDecimal.ZERO, "FIFO");

        assertThatThrownBy(() -> finishedGoodsService.registerBatch(new FinishedGoodBatchRequest(
                fg.getId(),
                "BATCH-NEG-COST",
                new BigDecimal("1"),
                new BigDecimal("-5.00"),
                Instant.now(),
                null
        ))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unit cost");
    }

    @Test
    void markSlipDispatched_createsBackorderSlipForPartialShipment() {
        Company company = seedCompany("DISPATCH-BO");
        FinishedGood fg = createFinishedGood(company, "FG-BO", new BigDecimal("5"), new BigDecimal("10"), "FIFO");
        FinishedGoodBatch batch = createBatch(fg, "BATCH-BO", new BigDecimal("10"), BigDecimal.ZERO, new BigDecimal("7"));
        SalesOrder order = createOrder(company, "SO-BO-" + UUID.randomUUID(), fg.getProductCode(), new BigDecimal("10"));
        PackagingSlip slip = createSlip(company, order, "RESERVED", batch, new BigDecimal("10"));
        createReservation(order, fg, batch, new BigDecimal("10"));

        finishedGoodsService.markSlipDispatched(order.getId(), slip);

        List<PackagingSlip> slips = packagingSlipRepository.findAllByCompanyAndSalesOrderId(company, order.getId());
        PackagingSlip backorderSlip = slips.stream()
                .filter(existing -> "BACKORDER".equalsIgnoreCase(existing.getStatus()))
                .findFirst()
                .orElseThrow();
        assertThat(backorderSlip.getLines()).hasSize(1);
        PackagingSlipLine boLine = backorderSlip.getLines().getFirst();
        assertThat(boLine.getQuantity()).isEqualByComparingTo(new BigDecimal("5"));
    }

    @Test
    void previewUsesReservedForOrder() {
        Company company = seedCompany("PREVIEW-RES");
        FinishedGood fg = createFinishedGood(company, "FG-PREV", new BigDecimal("5"), new BigDecimal("5"), "FIFO");
        FinishedGoodBatch batch = createBatch(fg, "BATCH-PREV", new BigDecimal("5"), BigDecimal.ZERO, new BigDecimal("9"));
        SalesOrder order = createOrder(company, "SO-PREV-" + UUID.randomUUID(), fg.getProductCode(), new BigDecimal("5"));
        PackagingSlip slip = createSlip(company, order, "RESERVED", batch, new BigDecimal("5"));
        createReservation(order, fg, batch, new BigDecimal("5"));

        DispatchPreviewDto preview = finishedGoodsService.getDispatchPreview(slip.getId());
        DispatchPreviewDto.LinePreview line = preview.lines().getFirst();
        assertThat(line.availableQuantity()).isEqualByComparingTo(new BigDecimal("5"));
        assertThat(line.suggestedShipQuantity()).isEqualByComparingTo(new BigDecimal("5"));
        assertThat(line.hasShortage()).isFalse();
    }

    private Company seedCompany(String code) {
        Company company = dataSeeder.ensureCompany(code, code + " Ltd");
        CompanyContextHolder.setCompanyId(company.getCode());
        return company;
    }

    private SalesOrder createOrder(Company company, String orderNumber, String productCode, BigDecimal quantity) {
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

    private FinishedGood createFinishedGood(Company company,
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

    private FinishedGoodBatch createBatch(FinishedGood fg,
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

    private PackagingSlip createSlip(Company company,
                                     SalesOrder order,
                                     String status,
                                     FinishedGoodBatch batch,
                                     BigDecimal quantity) {
        PackagingSlip slip = new PackagingSlip();
        slip.setCompany(company);
        slip.setSalesOrder(order);
        slip.setSlipNumber(order.getOrderNumber() + "-PS");
        slip.setStatus(status);

        PackagingSlipLine line = new PackagingSlipLine();
        line.setPackagingSlip(slip);
        line.setFinishedGoodBatch(batch);
        line.setOrderedQuantity(quantity);
        line.setQuantity(quantity);
        line.setUnitCost(batch.getUnitCost());
        slip.getLines().add(line);
        return packagingSlipRepository.saveAndFlush(slip);
    }

    private void createReservation(SalesOrder order,
                                   FinishedGood fg,
                                   FinishedGoodBatch batch,
                                   BigDecimal quantity) {
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
}
