package com.bigbrightpaints.erp.modules.inventory.service;

import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.CostingMethodUtils;
import com.bigbrightpaints.erp.modules.accounting.service.CostingMethodService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryReference;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryReservation;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryReservationRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlip;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlipLine;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlipRepository;
import com.bigbrightpaints.erp.modules.inventory.dto.PackagingSlipDto;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrder;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrderItem;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrderRepository;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class FinishedGoodsReservationEngine {

    private final CompanyContextService companyContextService;
    private final FinishedGoodRepository finishedGoodRepository;
    private final FinishedGoodBatchRepository finishedGoodBatchRepository;
    private final PackagingSlipRepository packagingSlipRepository;
    private final InventoryMovementRepository inventoryMovementRepository;
    private final InventoryReservationRepository inventoryReservationRepository;
    private final SalesOrderRepository salesOrderRepository;
    private final BatchNumberService batchNumberService;
    private final CostingMethodService costingMethodService;
    private final CompanyClock companyClock;
    private final InventoryMovementRecorder movementRecorder;
    private final InventoryValuationService inventoryValuationService;

    public FinishedGoodsReservationEngine(CompanyContextService companyContextService,
                                          FinishedGoodRepository finishedGoodRepository,
                                          FinishedGoodBatchRepository finishedGoodBatchRepository,
                                          PackagingSlipRepository packagingSlipRepository,
                                          InventoryMovementRepository inventoryMovementRepository,
                                          InventoryReservationRepository inventoryReservationRepository,
                                          SalesOrderRepository salesOrderRepository,
                                          BatchNumberService batchNumberService,
                                          CostingMethodService costingMethodService,
                                          CompanyClock companyClock,
                                          InventoryMovementRecorder movementRecorder,
                                          InventoryValuationService inventoryValuationService) {
        this.companyContextService = companyContextService;
        this.finishedGoodRepository = finishedGoodRepository;
        this.finishedGoodBatchRepository = finishedGoodBatchRepository;
        this.packagingSlipRepository = packagingSlipRepository;
        this.inventoryMovementRepository = inventoryMovementRepository;
        this.inventoryReservationRepository = inventoryReservationRepository;
        this.salesOrderRepository = salesOrderRepository;
        this.batchNumberService = batchNumberService;
        this.costingMethodService = costingMethodService;
        this.companyClock = companyClock;
        this.movementRecorder = movementRecorder;
        this.inventoryValuationService = inventoryValuationService;
    }

    @Transactional
    public FinishedGoodsService.InventoryReservationResult reserveForOrder(SalesOrder order) {
        Company company = companyContextService.requireCurrentCompany();
        SalesOrder managedOrder = salesOrderRepository.findWithItemsByCompanyAndIdForUpdate(company, order.getId())
                .orElseThrow(() -> com.bigbrightpaints.erp.core.validation.ValidationUtils
                        .invalidInput("Sales order not found: " + order.getId()));

        PackagingSlip slip = selectReservationSlip(company, managedOrder);

        if ("CANCELLED".equalsIgnoreCase(slip.getStatus())) {
            releaseReservationsForOrder(managedOrder.getId());
            slip.getLines().clear();
            slip.setStatus("PENDING");
            slip.setBackorder(false);
            packagingSlipRepository.save(slip);
        }

        if (slip.isBackorder() && "BACKORDER".equalsIgnoreCase(slip.getStatus())) {
            return continueBackorderReservation(managedOrder, slip);
        }

        if (!slip.getLines().isEmpty()) {
            if (slipLinesMatchOrder(slip, managedOrder)) {
                updateSlipStatusBasedOnAvailability(slip, List.of());
                return new FinishedGoodsService.InventoryReservationResult(toSlipDto(slip), List.of());
            }
            releaseReservationsForOrder(order.getId());
            slip.getLines().clear();
            slip.setStatus("PENDING");
            slip.setBackorder(false);
            packagingSlipRepository.save(slip);
        }

        List<FinishedGoodsService.InventoryShortage> shortages = new ArrayList<>();
        for (SalesOrderItem item : managedOrder.getItems()) {
            FinishedGood finishedGood = lockFinishedGood(company, item.getProductCode());
            allocateItem(managedOrder, slip, finishedGood, item, shortages);
        }

        slip.setStatus(shortages.isEmpty() ? "RESERVED" : "PENDING_PRODUCTION");
        packagingSlipRepository.save(slip);
        updateSlipStatusBasedOnAvailability(slip, shortages);
        return new FinishedGoodsService.InventoryReservationResult(toSlipDto(slip), List.copyOf(shortages));
    }

    private PackagingSlip selectReservationSlip(Company company, SalesOrder order) {
        List<PackagingSlip> slips = packagingSlipRepository.findAllByCompanyAndSalesOrderIdForUpdate(company, order.getId());
        return slips.stream()
                .filter(slip -> !slip.isBackorder())
                .findFirst()
                .orElseGet(() -> slips.stream()
                        .filter(PackagingSlip::isBackorder)
                        .filter(slip -> "BACKORDER".equalsIgnoreCase(slip.getStatus()))
                        .max(Comparator.comparing(PackagingSlip::getId, Comparator.nullsLast(Long::compareTo)))
                        .orElseGet(() -> createPrimarySlip(order)));
    }

    private FinishedGoodsService.InventoryReservationResult continueBackorderReservation(SalesOrder order, PackagingSlip slip) {
        normalizeBackorderReservations(order, slip);

        Map<String, BigDecimal> representedQuantities = new HashMap<>();
        for (PackagingSlipLine line : slip.getLines()) {
            FinishedGoodBatch batch = line.getFinishedGoodBatch();
            if (batch == null || batch.getFinishedGood() == null) {
                continue;
            }
            BigDecimal quantity = inventoryValuationService.safeQuantity(
                    line.getOrderedQuantity() != null ? line.getOrderedQuantity() : line.getQuantity());
            representedQuantities.merge(batch.getFinishedGood().getProductCode(), quantity, BigDecimal::add);
        }

        List<FinishedGoodsService.InventoryShortage> shortages = new ArrayList<>();
        for (SalesOrderItem item : order.getItems()) {
            BigDecimal alreadyRepresented = representedQuantities.getOrDefault(item.getProductCode(), BigDecimal.ZERO);
            BigDecimal required = inventoryValuationService.safeQuantity(item.getQuantity()).subtract(alreadyRepresented);
            if (required.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            FinishedGood finishedGood = lockFinishedGood(order.getCompany(), item.getProductCode());
            allocateQuantity(order, slip, finishedGood, required, shortages);
        }

        boolean fullyReserved = slipLinesMatchOrder(slip, order) && shortages.isEmpty();
        slip.setStatus(fullyReserved ? "RESERVED" : "BACKORDER");
        slip.setBackorder(!fullyReserved);
        packagingSlipRepository.save(slip);
        return new FinishedGoodsService.InventoryReservationResult(toSlipDto(slip), List.copyOf(shortages));
    }

    private void normalizeBackorderReservations(SalesOrder order, PackagingSlip slip) {
        List<InventoryReservation> reservations = inventoryReservationRepository
                .findByFinishedGoodCompanyAndReferenceTypeAndReferenceId(
                        slip.getCompany(),
                        InventoryReference.SALES_ORDER,
                        order.getId().toString());
        Map<Long, InventoryReservation> activeReservationsByBatchId = new HashMap<>();
        for (InventoryReservation reservation : reservations) {
            FinishedGoodBatch batch = reservation.getFinishedGoodBatch();
            if (batch == null || batch.getId() == null) {
                continue;
            }
            if ("CANCELLED".equalsIgnoreCase(reservation.getStatus())
                    || "FULFILLED".equalsIgnoreCase(reservation.getStatus())) {
                continue;
            }
            activeReservationsByBatchId.putIfAbsent(batch.getId(), reservation);
        }

        for (PackagingSlipLine line : slip.getLines()) {
            FinishedGoodBatch batch = line.getFinishedGoodBatch();
            if (batch == null || batch.getFinishedGood() == null || batch.getId() == null) {
                continue;
            }
            BigDecimal quantity = inventoryValuationService.safeQuantity(
                    line.getQuantity() != null ? line.getQuantity() : line.getOrderedQuantity());
            InventoryReservation existing = activeReservationsByBatchId.get(batch.getId());
            if (existing == null) {
                InventoryReservation reservation = new InventoryReservation();
                reservation.setFinishedGood(batch.getFinishedGood());
                reservation.setFinishedGoodBatch(batch);
                reservation.setReferenceType(InventoryReference.SALES_ORDER);
                reservation.setReferenceId(order.getId().toString());
                reservation.setQuantity(quantity);
                reservation.setReservedQuantity(quantity);
                reservation.setStatus("RESERVED");
                inventoryReservationRepository.save(reservation);
                continue;
            }
            if (existing.getQuantity() == null || existing.getQuantity().compareTo(BigDecimal.ZERO) <= 0) {
                existing.setQuantity(quantity);
            }
            if (existing.getReservedQuantity() == null || existing.getReservedQuantity().compareTo(BigDecimal.ZERO) <= 0) {
                existing.setReservedQuantity(quantity);
            }
            if (!"PARTIAL".equalsIgnoreCase(existing.getStatus())) {
                existing.setStatus("RESERVED");
            }
            inventoryReservationRepository.save(existing);
        }
    }

    @Transactional
    public void releaseReservationsForOrder(Long orderId) {
        Company company = companyContextService.requireCurrentCompany();
        List<InventoryReservation> reservations = inventoryReservationRepository
                .findByFinishedGoodCompanyAndReferenceTypeAndReferenceId(
                        company,
                        InventoryReference.SALES_ORDER,
                        orderId.toString());

        if (reservations.isEmpty()) {
            return;
        }

        List<Long> ids = reservations.stream()
                .map(r -> r.getFinishedGood().getId())
                .distinct()
                .sorted()
                .toList();
        Map<Long, FinishedGood> lockedGoods = new HashMap<>();
        for (Long id : ids) {
            FinishedGood fg = finishedGoodRepository.lockByCompanyAndId(company, id)
                    .orElseThrow(() -> com.bigbrightpaints.erp.core.validation.ValidationUtils
                            .invalidInput("Finished good not found: " + id));
            lockedGoods.put(id, fg);
        }

        List<FinishedGoodBatch> batchesToSave = new ArrayList<>();
        for (InventoryReservation reservation : reservations) {
            if ("CANCELLED".equalsIgnoreCase(reservation.getStatus())
                    || "FULFILLED".equalsIgnoreCase(reservation.getStatus())) {
                continue;
            }

            BigDecimal reservedQty = resolveReservedQuantity(reservation);
            if (reservedQty.compareTo(BigDecimal.ZERO) <= 0) {
                reservation.setStatus("CANCELLED");
                continue;
            }

            FinishedGood fg = lockedGoods.get(reservation.getFinishedGood().getId());
            FinishedGoodBatch batch = reservation.getFinishedGoodBatch();

            BigDecimal currentReserved = inventoryValuationService.safeQuantity(fg.getReservedStock());
            fg.setReservedStock(currentReserved.subtract(reservedQty).max(BigDecimal.ZERO));

            if (batch != null) {
                BigDecimal available = inventoryValuationService.safeQuantity(batch.getQuantityAvailable());
                batch.setQuantityAvailable(available.add(reservedQty));
                batchesToSave.add(batch);
            }

            movementRecorder.recordFinishedGoodMovement(
                    fg,
                    batch,
                    InventoryReference.SALES_ORDER,
                    orderId.toString(),
                    "RELEASE",
                    reservedQty,
                    batch != null ? batch.getUnitCost() : BigDecimal.ZERO,
                    null);

            reservation.setStatus("CANCELLED");
            reservation.setReservedQuantity(BigDecimal.ZERO);
        }

        finishedGoodRepository.saveAll(lockedGoods.values());
        lockedGoods.keySet().forEach(inventoryValuationService::invalidateWeightedAverageCost);

        if (!batchesToSave.isEmpty()) {
            finishedGoodBatchRepository.saveAll(batchesToSave);
        }

        inventoryReservationRepository.saveAll(reservations);

        List<PackagingSlip> slips = packagingSlipRepository.findAllByCompanyAndSalesOrderId(company, orderId);
        for (PackagingSlip slip : slips) {
            if (!"DISPATCHED".equalsIgnoreCase(slip.getStatus())
                    && !"CANCELLED".equalsIgnoreCase(slip.getStatus())) {
                slip.getLines().clear();
                slip.setStatus("CANCELLED");
                slip.setDispatchNotes("Order cancelled");
                packagingSlipRepository.save(slip);
            }
        }
    }

    @Transactional
    public List<InventoryReservation> rebuildReservationsFromSlip(PackagingSlip slip, Long salesOrderId) {
        if (slip.getLines() == null || slip.getLines().isEmpty()) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils
                    .invalidState("No packaging slip lines available to rebuild reservations for order " + salesOrderId);
        }
        List<InventoryReservation> rebuilt = new ArrayList<>();
        Map<Long, FinishedGood> touchedGoods = new HashMap<>();
        Map<Long, FinishedGoodBatch> touchedBatches = new HashMap<>();

        for (PackagingSlipLine line : slip.getLines()) {
            FinishedGoodBatch batch = line.getFinishedGoodBatch();
            if (batch == null || batch.getFinishedGood() == null) {
                throw com.bigbrightpaints.erp.core.validation.ValidationUtils
                        .invalidState("Cannot rebuild reservation without a finished good batch");
            }
            FinishedGood fg = batch.getFinishedGood();
            if (fg.getId() != null) {
                touchedGoods.computeIfAbsent(fg.getId(), id -> lockFinishedGood(slip.getCompany(), id));
            }
            if (batch.getId() != null) {
                touchedBatches.computeIfAbsent(batch.getId(), id ->
                        finishedGoodBatchRepository.lockById(id).orElse(batch));
            }

            InventoryReservation reservation = new InventoryReservation();
            reservation.setFinishedGood(batch.getFinishedGood());
            reservation.setFinishedGoodBatch(batch);
            reservation.setReferenceType(InventoryReference.SALES_ORDER);
            reservation.setReferenceId(salesOrderId.toString());
            BigDecimal qty = inventoryValuationService.safeQuantity(line.getQuantity());
            reservation.setQuantity(qty);
            reservation.setReservedQuantity(qty);
            reservation.setStatus("RESERVED");
            rebuilt.add(reservation);
        }

        List<InventoryReservation> saved = inventoryReservationRepository.saveAll(rebuilt);
        if (!touchedGoods.isEmpty()) {
            for (FinishedGood fg : touchedGoods.values()) {
                BigDecimal reservedTotal = inventoryReservationRepository.findByFinishedGood(fg).stream()
                        .map(this::resolveReservedQuantity)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                fg.setReservedStock(reservedTotal);
            }
            finishedGoodRepository.saveAll(touchedGoods.values());
            touchedGoods.keySet().forEach(inventoryValuationService::invalidateWeightedAverageCost);
        }
        if (!touchedBatches.isEmpty()) {
            for (FinishedGoodBatch batch : touchedBatches.values()) {
                BigDecimal reservedForBatch = inventoryReservationRepository.findByFinishedGoodBatch(batch).stream()
                        .map(this::resolveReservedQuantity)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                BigDecimal total = inventoryValuationService.safeQuantity(batch.getQuantityTotal());
                batch.setQuantityAvailable(total.subtract(reservedForBatch).max(BigDecimal.ZERO));
            }
            finishedGoodBatchRepository.saveAll(touchedBatches.values());
        }
        return saved;
    }

    public BigDecimal resolveReservedQuantity(InventoryReservation reservation) {
        String status = reservation.getStatus();
        if (status != null
                && ("CANCELLED".equalsIgnoreCase(status) || "FULFILLED".equalsIgnoreCase(status))) {
            return BigDecimal.ZERO;
        }
        BigDecimal reserved = reservation.getReservedQuantity();
        if (reserved == null || reserved.compareTo(BigDecimal.ZERO) <= 0) {
            reserved = reservation.getQuantity();
        }
        return inventoryValuationService.safeQuantity(reserved);
    }

    public boolean slipLinesMatchOrder(PackagingSlip slip, SalesOrder order) {
        if (slip.getLines() == null || slip.getLines().isEmpty()) {
            return false;
        }
        Map<String, BigDecimal> slipQuantities = new HashMap<>();
        for (PackagingSlipLine line : slip.getLines()) {
            if (line.getFinishedGoodBatch() == null || line.getFinishedGoodBatch().getFinishedGood() == null) {
                return false;
            }
            String productCode = line.getFinishedGoodBatch().getFinishedGood().getProductCode();
            BigDecimal orderedQty = inventoryValuationService.safeQuantity(
                    line.getOrderedQuantity() != null ? line.getOrderedQuantity() : line.getQuantity());
            slipQuantities.merge(productCode, orderedQty, BigDecimal::add);
        }

        for (SalesOrderItem item : order.getItems()) {
            BigDecimal slipQty = slipQuantities.get(item.getProductCode());
            if (slipQty == null
                    || slipQty.compareTo(inventoryValuationService.safeQuantity(item.getQuantity())) != 0) {
                return false;
            }
            slipQuantities.remove(item.getProductCode());
        }
        return slipQuantities.isEmpty();
    }

    private PackagingSlip createPrimarySlip(SalesOrder order) {
        Company company = companyContextService.requireCurrentCompany();
        PackagingSlip slip = new PackagingSlip();
        slip.setCompany(company);
        slip.setSalesOrder(order);
        slip.setSlipNumber(batchNumberService.nextPackagingSlipNumber(company));
        slip.setStatus("PENDING");
        slip.setBackorder(false);
        return packagingSlipRepository.saveAndFlush(slip);
    }

    private void allocateItem(SalesOrder order,
                              PackagingSlip slip,
                              FinishedGood finishedGood,
                              SalesOrderItem item,
                              List<FinishedGoodsService.InventoryShortage> shortages) {
        allocateQuantity(order, slip, finishedGood, item.getQuantity(), shortages);
    }

    private void allocateQuantity(SalesOrder order,
                                  PackagingSlip slip,
                                  FinishedGood finishedGood,
                                  BigDecimal requiredQuantity,
                                  List<FinishedGoodsService.InventoryShortage> shortages) {
        BigDecimal remaining = inventoryValuationService.safeQuantity(requiredQuantity);
        LocalDate movementDate = companyClock.today(order.getCompany());
        List<FinishedGoodBatch> batches = selectBatchesByCostingMethod(finishedGood, movementDate);
        for (FinishedGoodBatch batch : batches) {
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
                break;
            }
            BigDecimal available = batch.getQuantityAvailable();
            if (available.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            BigDecimal allocation = available.min(remaining);
            batch.setQuantityAvailable(available.subtract(allocation));
            finishedGoodBatchRepository.save(batch);

            finishedGood.setReservedStock(inventoryValuationService.safeQuantity(finishedGood.getReservedStock()).add(allocation));
            finishedGoodRepository.save(finishedGood);

            PackagingSlipLine line = new PackagingSlipLine();
            line.setPackagingSlip(slip);
            line.setFinishedGoodBatch(batch);
            line.setOrderedQuantity(allocation);
            line.setQuantity(allocation);
            line.setUnitCost(batch.getUnitCost());
            slip.getLines().add(line);

            InventoryReservation reservation = new InventoryReservation();
            reservation.setFinishedGood(finishedGood);
            reservation.setFinishedGoodBatch(batch);
            reservation.setReferenceType(InventoryReference.SALES_ORDER);
            reservation.setReferenceId(order.getId().toString());
            reservation.setQuantity(allocation);
            reservation.setReservedQuantity(allocation);
            reservation.setStatus("RESERVED");
            inventoryReservationRepository.save(reservation);

            movementRecorder.recordFinishedGoodMovement(
                    finishedGood,
                    batch,
                    InventoryReference.SALES_ORDER,
                    order.getId().toString(),
                    "RESERVE",
                    allocation,
                    batch.getUnitCost(),
                    null);
            remaining = remaining.subtract(allocation);
        }

        if (remaining.compareTo(BigDecimal.ZERO) > 0) {
            shortages.add(new FinishedGoodsService.InventoryShortage(
                    finishedGood.getProductCode(),
                    remaining,
                    finishedGood.getName()));
        }
    }

    private List<FinishedGoodBatch> selectBatchesByCostingMethod(FinishedGood finishedGood, LocalDate referenceDate) {
        CostingMethodUtils.FinishedGoodBatchSelectionMethod selectionMethod = CostingMethodUtils
                .resolveFinishedGoodBatchSelectionMethod(
                        costingMethodService.resolveActiveMethod(finishedGood.getCompany(), referenceDate).name());
        return switch (selectionMethod) {
            case WAC -> finishedGoodBatchRepository.findAllocatableBatches(finishedGood);
            case LIFO -> finishedGoodBatchRepository.findAllocatableBatchesLIFO(finishedGood);
            case FIFO -> finishedGoodBatchRepository.findAllocatableBatchesFIFO(finishedGood);
        };
    }

    private FinishedGood lockFinishedGood(Company company, Long id) {
        return finishedGoodRepository.lockByCompanyAndId(company, id)
                .orElseThrow(() -> com.bigbrightpaints.erp.core.validation.ValidationUtils
                        .invalidInput("Finished good not found: " + id));
    }

    private FinishedGood lockFinishedGood(Company company, String productCode) {
        return finishedGoodRepository.lockByCompanyAndProductCode(company, productCode)
                .orElseThrow(() -> com.bigbrightpaints.erp.core.validation.ValidationUtils
                        .invalidInput("Finished good not found for product code " + productCode));
    }


    private void updateSlipStatusBasedOnAvailability(PackagingSlip slip,
                                                      List<FinishedGoodsService.InventoryShortage> shortages) {
        if (slip == null) {
            return;
        }
        String currentStatus = slip.getStatus();
        if (currentStatus != null) {
            String normalized = currentStatus.trim().toUpperCase();
            if ("DISPATCHED".equals(normalized)
                    || "CANCELLED".equals(normalized)
                    || "BACKORDER".equals(normalized)) {
                return;
            }
        }
        boolean hasShortage = shortages != null && !shortages.isEmpty();
        slip.setStatus(hasShortage ? "PENDING_PRODUCTION" : "RESERVED");
        packagingSlipRepository.save(slip);
    }

    private PackagingSlipDto toSlipDto(PackagingSlip slip) {
        List<com.bigbrightpaints.erp.modules.inventory.dto.PackagingSlipLineDto> lines = slip.getLines().stream()
                .map(line -> {
                    FinishedGoodBatch batch = line.getFinishedGoodBatch();
                    FinishedGood fg = batch.getFinishedGood();
                    return new com.bigbrightpaints.erp.modules.inventory.dto.PackagingSlipLineDto(
                            line.getId(),
                            batch.getPublicId(),
                            batch.getBatchCode(),
                            fg.getProductCode(),
                            fg.getName(),
                            line.getOrderedQuantity(),
                            line.getShippedQuantity(),
                            line.getBackorderQuantity(),
                            line.getQuantity(),
                            null,
                            line.getNotes()
                    );
                })
                .toList();

        SalesOrder order = slip.getSalesOrder();
        String dealerName = order.getDealer() != null ? order.getDealer().getName() : null;
        return new PackagingSlipDto(
                slip.getId(),
                slip.getPublicId(),
                order.getId(),
                order.getOrderNumber(),
                dealerName,
                slip.getSlipNumber(),
                slip.getStatus(),
                slip.getCreatedAt(),
                slip.getConfirmedAt(),
                slip.getConfirmedBy(),
                slip.getDispatchedAt(),
                slip.getDispatchNotes(),
                slip.getJournalEntryId(),
                slip.getCogsJournalEntryId(),
                lines,
                slip.getTransporterName(),
                slip.getDriverName(),
                slip.getVehicleNumber(),
                slip.getChallanReference(),
                DispatchArtifactPaths.deliveryChallanNumber(slip.getSlipNumber()),
                DispatchArtifactPaths.deliveryChallanPdfPath(slip.getId())
        );
    }
}
