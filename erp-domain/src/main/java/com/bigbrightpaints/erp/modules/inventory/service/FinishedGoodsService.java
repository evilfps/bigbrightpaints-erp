package com.bigbrightpaints.erp.modules.inventory.service;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.CompanyTime;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.CostingMethodUtils;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.inventory.domain.*;
import com.bigbrightpaints.erp.modules.inventory.dto.*;
import com.bigbrightpaints.erp.modules.inventory.event.InventoryMovementEvent;
import com.bigbrightpaints.erp.modules.inventory.event.InventoryValuationChangedEvent;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrder;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrderItem;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrderRepository;
import com.bigbrightpaints.erp.modules.accounting.service.CompanyDefaultAccountsService;
import com.bigbrightpaints.erp.modules.factory.event.PackagingSlipEvent;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class FinishedGoodsService {

    private static final Logger log = LoggerFactory.getLogger(FinishedGoodsService.class);

    private final CompanyContextService companyContextService;
    private final FinishedGoodRepository finishedGoodRepository;
    private final FinishedGoodBatchRepository finishedGoodBatchRepository;
    private final PackagingSlipRepository packagingSlipRepository;
    private final InventoryMovementRepository inventoryMovementRepository;
    private final InventoryReservationRepository inventoryReservationRepository;
    private final BatchNumberService batchNumberService;
    private final SalesOrderRepository salesOrderRepository;
    private final CompanyDefaultAccountsService companyDefaultAccountsService;
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;
    private final CompanyClock companyClock;
    private final Environment environment;
    private final boolean manualBatchEnabled;
    private final Map<Long, CachedWac> wacCache = new ConcurrentHashMap<>();
    private static final long WAC_CACHE_MILLIS = 5 * 60 * 1000; // 5 minutes TTL

    public FinishedGoodsService(CompanyContextService companyContextService,
                                FinishedGoodRepository finishedGoodRepository,
                                FinishedGoodBatchRepository finishedGoodBatchRepository,
                                PackagingSlipRepository packagingSlipRepository,
                                InventoryMovementRepository inventoryMovementRepository,
                                InventoryReservationRepository inventoryReservationRepository,
                                BatchNumberService batchNumberService,
                                SalesOrderRepository salesOrderRepository,
                                CompanyDefaultAccountsService companyDefaultAccountsService,
                                org.springframework.context.ApplicationEventPublisher eventPublisher,
                                CompanyClock companyClock,
                                Environment environment,
                                @Value("${erp.inventory.finished-goods.batch.enabled:false}") boolean manualBatchEnabled) {
        this.companyContextService = companyContextService;
        this.finishedGoodRepository = finishedGoodRepository;
        this.finishedGoodBatchRepository = finishedGoodBatchRepository;
        this.packagingSlipRepository = packagingSlipRepository;
        this.inventoryMovementRepository = inventoryMovementRepository;
        this.inventoryReservationRepository = inventoryReservationRepository;
        this.batchNumberService = batchNumberService;
        this.salesOrderRepository = salesOrderRepository;
        this.companyDefaultAccountsService = companyDefaultAccountsService;
        this.eventPublisher = eventPublisher;
        this.companyClock = companyClock;
        this.environment = environment;
        this.manualBatchEnabled = manualBatchEnabled;
    }

    public List<FinishedGoodDto> listFinishedGoods() {
        Company company = companyContextService.requireCurrentCompany();
        return finishedGoodRepository.findByCompanyOrderByProductCodeAsc(company)
                .stream()
                .map(this::toDto)
                .toList();
    }

    public FinishedGoodDto getFinishedGood(Long id) {
        Company company = companyContextService.requireCurrentCompany();
        FinishedGood fg = finishedGoodRepository.findByCompanyAndId(company, id)
                .orElseThrow(() -> new IllegalArgumentException("Finished good not found"));
        return toDto(fg);
    }

    public FinishedGood lockFinishedGoodByProductCode(String productCode) {
        Company company = companyContextService.requireCurrentCompany();
        return lockFinishedGood(company, productCode);
    }

    public BigDecimal currentWeightedAverageCost(FinishedGood fg) {
        return weightedAverageCost(fg);
    }

    @Transactional
    public FinishedGoodDto updateFinishedGood(Long id, FinishedGoodRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        FinishedGood fg = finishedGoodRepository.lockByCompanyAndId(company, id)
                .orElseThrow(() -> new IllegalArgumentException("Finished good not found"));
        if (request.name() != null) {
            fg.setName(request.name());
        }
        if (request.unit() != null) {
            fg.setUnit(request.unit());
        }
        if (request.costingMethod() != null) {
            fg.setCostingMethod(normalizeCostingMethod(request.costingMethod()));
        }
        if (request.valuationAccountId() != null) {
            fg.setValuationAccountId(request.valuationAccountId());
        }
        if (request.cogsAccountId() != null) {
            fg.setCogsAccountId(request.cogsAccountId());
        }
        if (request.revenueAccountId() != null) {
            fg.setRevenueAccountId(request.revenueAccountId());
        }
        if (request.discountAccountId() != null) {
            fg.setDiscountAccountId(request.discountAccountId());
        }
        if (request.taxAccountId() != null) {
            fg.setTaxAccountId(request.taxAccountId());
        }
        applyDefaultAccountsIfMissing(fg);
        return toDto(finishedGoodRepository.save(fg));
    }

    public List<FinishedGoodBatchDto> listBatchesForFinishedGood(Long finishedGoodId) {
        Company company = companyContextService.requireCurrentCompany();
        FinishedGood fg = finishedGoodRepository.findByCompanyAndId(company, finishedGoodId)
                .orElseThrow(() -> new IllegalArgumentException("Finished good not found"));
        return finishedGoodBatchRepository.findByFinishedGoodOrderByManufacturedAtAsc(fg)
                .stream()
                .map(this::toBatchDto)
                .toList();
    }

    public List<StockSummaryDto> getStockSummary() {
        Company company = companyContextService.requireCurrentCompany();
        return finishedGoodRepository.findByCompanyOrderByProductCodeAsc(company)
                .stream()
                .map(fg -> new StockSummaryDto(
                        fg.getId(),
                        fg.getPublicId(),
                        fg.getProductCode(),
                        fg.getName(),
                        safeQuantity(fg.getCurrentStock()),
                        safeQuantity(fg.getReservedStock()),
                        safeQuantity(fg.getCurrentStock()).subtract(safeQuantity(fg.getReservedStock())),
                        stockSummaryUnitCost(fg),
                        null,
                        null,
                        null,
                        null
                ))
                .toList();
    }

    public List<FinishedGoodDto> getLowStockItems(int threshold) {
        Company company = companyContextService.requireCurrentCompany();
        BigDecimal thresholdQty = BigDecimal.valueOf(threshold);
        return finishedGoodRepository.findByCompanyOrderByProductCodeAsc(company)
                .stream()
                .filter(fg -> safeQuantity(fg.getCurrentStock()).subtract(safeQuantity(fg.getReservedStock()))
                        .compareTo(thresholdQty) < 0)
                .map(this::toDto)
                .toList();
    }

    @Transactional
    public FinishedGoodDto createFinishedGood(FinishedGoodRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        FinishedGood finishedGood = new FinishedGood();
        finishedGood.setCompany(company);
        finishedGood.setProductCode(request.productCode());
        finishedGood.setName(request.name());
        finishedGood.setUnit(request.unit() == null ? "UNIT" : request.unit());
        finishedGood.setCostingMethod(normalizeCostingMethod(request.costingMethod()));
        finishedGood.setCurrentStock(BigDecimal.ZERO);
        finishedGood.setReservedStock(BigDecimal.ZERO);
        finishedGood.setValuationAccountId(request.valuationAccountId());
        finishedGood.setCogsAccountId(request.cogsAccountId());
        finishedGood.setRevenueAccountId(request.revenueAccountId());
        finishedGood.setDiscountAccountId(request.discountAccountId());
        finishedGood.setTaxAccountId(request.taxAccountId());
        applyDefaultAccountsIfMissing(finishedGood);
        return toDto(finishedGoodRepository.save(finishedGood));
    }

    @Transactional
    public FinishedGoodBatchDto registerBatch(FinishedGoodBatchRequest request) {
        assertManualBatchAllowed();
        FinishedGood finishedGood = lockFinishedGood(request.finishedGoodId());
        BigDecimal quantity = safeQuantity(request.quantity());
        if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Batch quantity must be greater than zero");
        }
        BigDecimal unitCost = request.unitCost() != null ? request.unitCost() : BigDecimal.ZERO;
        if (unitCost.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Batch unit cost cannot be negative");
        }
        FinishedGoodBatch batch = new FinishedGoodBatch();
        batch.setFinishedGood(finishedGood);
        batch.setBatchCode(resolveBatchCode(finishedGood, request.batchCode(), request.manufacturedAt()));
        batch.setQuantityTotal(quantity);
        batch.setQuantityAvailable(quantity);
        batch.setUnitCost(unitCost);
        batch.setManufacturedAt(request.manufacturedAt() == null
                ? CompanyTime.now(finishedGood.getCompany())
                : request.manufacturedAt());
        batch.setExpiryDate(request.expiryDate());
        FinishedGoodBatch savedBatch = finishedGoodBatchRepository.save(batch);

        finishedGood.setCurrentStock(finishedGood.getCurrentStock().add(quantity));
        finishedGoodRepository.save(finishedGood);
        wacCache.remove(finishedGood.getId());

        recordMovement(finishedGood, savedBatch, InventoryReference.MANUFACTURING_ORDER, savedBatch.getPublicId().toString(),
                "RECEIPT", quantity, unitCost, null);

        return toBatchDto(savedBatch);
    }

    private void assertManualBatchAllowed() {
        if (isProdProfile() && !manualBatchEnabled) {
            throw new ApplicationException(ErrorCode.BUSINESS_CONSTRAINT_VIOLATION,
                    "Manual finished good batch registration is disabled; use production logs and packing.")
                    .withDetail("endpoint", "/api/v1/finished-goods/{id}/batches")
                    .withDetail("canonicalPath", "/api/v1/factory/production/logs")
                    .withDetail("setting", "erp.inventory.finished-goods.batch.enabled");
        }
    }

    private boolean isProdProfile() {
        return environment != null && environment.acceptsProfiles(Profiles.of("prod"));
    }

    public List<PackagingSlipDto> listPackagingSlips() {
        Company company = companyContextService.requireCurrentCompany();
        return packagingSlipRepository.findByCompanyOrderByCreatedAtDesc(company)
                .stream()
                .map(this::toSlipDto)
                .toList();
    }

    @Transactional
    public InventoryReservationResult reserveForOrder(SalesOrder order) {
        Company company = companyContextService.requireCurrentCompany();
        SalesOrder managedOrder = salesOrderRepository.findWithItemsByCompanyAndIdForUpdate(company, order.getId())
                .orElseThrow(() -> new IllegalArgumentException("Sales order not found: " + order.getId()));
        Optional<PackagingSlip> primarySlip = packagingSlipRepository.findAndLockPrimaryBySalesOrderId(order.getId(), company);
        if (primarySlip.isEmpty()) {
            List<PackagingSlip> backorderSlips = packagingSlipRepository
                    .findAllByCompanyAndSalesOrderIdAndIsBackorderTrue(company, order.getId()).stream()
                    .filter(existing -> !"CANCELLED".equalsIgnoreCase(existing.getStatus()))
                    .toList();
            if (backorderSlips.size() > 1) {
                throw new ApplicationException(ErrorCode.CONCURRENCY_CONFLICT,
                        "Multiple backorder slips found for order " + order.getId() + "; provide packingSlipId");
            }
            if (!backorderSlips.isEmpty()) {
                PackagingSlip backorderSlip = packagingSlipRepository
                        .findAndLockByIdAndCompany(backorderSlips.getFirst().getId(), company)
                        .orElseThrow(() -> new IllegalArgumentException("Backorder slip not found"));
                return reserveForBackorder(managedOrder, backorderSlip);
            }
        }
        PackagingSlip slip = primarySlip.orElseGet(() -> createPrimarySlip(managedOrder));

        if ("CANCELLED".equalsIgnoreCase(slip.getStatus())) {
            releaseReservationsForOrder(managedOrder.getId());
            slip.getLines().clear();
            slip.setStatus("PENDING");
            slip.setBackorder(false);
            packagingSlipRepository.save(slip);
        }

        if (!slip.getLines().isEmpty()) {
            if (slipLinesMatchOrder(slip, managedOrder)) {
                updateSlipStatusBasedOnAvailability(slip, List.of());
                return new InventoryReservationResult(toSlipDto(slip), List.of());
            } else {
                // Rebuild slip to match current order items and reset reservations
                releaseReservationsForOrder(order.getId());
                slip.getLines().clear();
                slip.setStatus("PENDING");
                slip.setBackorder(false);
                packagingSlipRepository.save(slip);
            }
        }

        List<InventoryShortage> shortages = new ArrayList<>();
        for (SalesOrderItem item : managedOrder.getItems()) {
            FinishedGood finishedGood = lockFinishedGood(managedOrder.getCompany(), item.getProductCode());
            allocateItem(managedOrder, slip, finishedGood, item, shortages);
        }

        if (shortages.isEmpty()) {
            slip.setStatus("RESERVED");
        } else {
            slip.setStatus("PENDING_PRODUCTION");
        }
        packagingSlipRepository.save(slip);
        // Soft reservation only: do not throw on shortages; caller can choose to dispatch partial/backorder
        updateSlipStatusBasedOnAvailability(slip, shortages);
        return new InventoryReservationResult(toSlipDto(slip), List.copyOf(shortages));
    }

    private InventoryReservationResult reserveForBackorder(SalesOrder order, PackagingSlip slip) {
        Company company = companyContextService.requireCurrentCompany();
        List<InventoryReservation> reservations = inventoryReservationRepository
                .findByFinishedGoodCompanyAndReferenceTypeAndReferenceId(
                        company,
                        InventoryReference.SALES_ORDER,
                        order.getId().toString());
        if (reservations.isEmpty() && slip.getLines() != null && !slip.getLines().isEmpty()) {
            reservations = rebuildReservationsFromSlip(slip, order.getId());
        }

        Map<String, BigDecimal> coveredByProduct = new HashMap<>();
        for (InventoryReservation reservation : reservations) {
            if ("CANCELLED".equalsIgnoreCase(reservation.getStatus())) {
                continue;
            }
            FinishedGood fg = reservation.getFinishedGood();
            if (fg == null || fg.getProductCode() == null) {
                continue;
            }
            BigDecimal covered = resolveReservedQuantity(reservation)
                    .add(safeQuantity(reservation.getFulfilledQuantity()));
            if (covered.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            coveredByProduct.merge(fg.getProductCode(), covered, BigDecimal::add);
        }

        List<InventoryShortage> shortages = new ArrayList<>();
        for (SalesOrderItem item : order.getItems()) {
            BigDecimal orderedQty = safeQuantity(item.getQuantity());
            BigDecimal coveredQty = coveredByProduct.getOrDefault(item.getProductCode(), BigDecimal.ZERO);
            BigDecimal missingQty = orderedQty.subtract(coveredQty).max(BigDecimal.ZERO);
            if (missingQty.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            FinishedGood finishedGood = lockFinishedGood(order.getCompany(), item.getProductCode());
            SalesOrderItem synthetic = new SalesOrderItem();
            synthetic.setProductCode(item.getProductCode());
            synthetic.setQuantity(missingQty);
            allocateItem(order, slip, finishedGood, synthetic, shortages);
        }

        if (shortages.isEmpty()) {
            slip.setStatus("RESERVED");
            updateSlipStatusBasedOnAvailability(slip, shortages);
        } else {
            slip.setStatus("BACKORDER");
            packagingSlipRepository.save(slip);
        }
        return new InventoryReservationResult(toSlipDto(slip), List.copyOf(shortages));
    }

    /**
     * Release all reservations for an order (e.g., when order is cancelled).
     * Returns reserved stock back to available and cancels reservation records.
     */
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

        // Lock finished goods in consistent order to avoid deadlocks
        Map<Long, FinishedGood> lockedGoods = lockFinishedGoodsInOrder(
                company,
                reservations.stream()
                        .map(r -> r.getFinishedGood().getId())
                        .collect(Collectors.toSet()));

        List<FinishedGoodBatch> batchesToSave = new ArrayList<>();

        for (InventoryReservation reservation : reservations) {
            if ("CANCELLED".equalsIgnoreCase(reservation.getStatus()) ||
                "FULFILLED".equalsIgnoreCase(reservation.getStatus())) {
                continue; // Skip already processed reservations
            }

            BigDecimal reservedQty = reservation.getReservedQuantity();
            if (reservedQty == null || reservedQty.compareTo(BigDecimal.ZERO) <= 0) {
                reservedQty = reservation.getQuantity();
            }
            if (reservedQty == null || reservedQty.compareTo(BigDecimal.ZERO) <= 0) {
                reservation.setStatus("CANCELLED");
                continue;
            }

            FinishedGood fg = lockedGoods.get(reservation.getFinishedGood().getId());
            FinishedGoodBatch batch = reservation.getFinishedGoodBatch();

            // Release reserved stock back to finished good
            BigDecimal currentReserved = fg.getReservedStock() != null ? fg.getReservedStock() : BigDecimal.ZERO;
            fg.setReservedStock(currentReserved.subtract(reservedQty).max(BigDecimal.ZERO));

            // Release batch quantity back to available
            if (batch != null) {
                BigDecimal batchAvailable = batch.getQuantityAvailable() != null ? batch.getQuantityAvailable() : BigDecimal.ZERO;
                batch.setQuantityAvailable(batchAvailable.add(reservedQty));
                batchesToSave.add(batch);
            }

            // Record release movement
            recordMovement(fg, batch, InventoryReference.SALES_ORDER, orderId.toString(),
                    "RELEASE", reservedQty, batch != null ? batch.getUnitCost() : BigDecimal.ZERO, null);

            reservation.setStatus("CANCELLED");
            reservation.setReservedQuantity(BigDecimal.ZERO);
        }

        finishedGoodRepository.saveAll(lockedGoods.values());
        lockedGoods.keySet().forEach(wacCache::remove);

        if (!batchesToSave.isEmpty()) {
            finishedGoodBatchRepository.saveAll(batchesToSave);
        }

        inventoryReservationRepository.saveAll(reservations);

        // Cancel any pending packaging slips for this order
        List<PackagingSlip> slips = packagingSlipRepository.findAllByCompanyAndSalesOrderId(company, orderId);
        for (PackagingSlip slip : slips) {
            if (!"DISPATCHED".equalsIgnoreCase(slip.getStatus()) &&
                !"CANCELLED".equalsIgnoreCase(slip.getStatus())) {
                slip.getLines().clear();
                slip.setStatus("CANCELLED");
                slip.setDispatchNotes("Order cancelled");
                packagingSlipRepository.save(slip);
            }
        }
    }

    public Map<String, FinishedGoodAccountingProfile> accountingProfiles(List<String> productCodes) {
        if (productCodes == null || productCodes.isEmpty()) {
            return Map.of();
        }
        Company company = companyContextService.requireCurrentCompany();
        List<FinishedGood> goods = finishedGoodRepository.findByCompanyAndProductCodeIn(company, productCodes);
        Map<String, FinishedGoodAccountingProfile> profiles = new HashMap<>();
        for (FinishedGood fg : goods) {
            profiles.put(fg.getProductCode(), new FinishedGoodAccountingProfile(
                    fg.getProductCode(),
                    fg.getValuationAccountId(),
                    fg.getCogsAccountId(),
                    fg.getRevenueAccountId(),
                    fg.getDiscountAccountId(),
                    fg.getTaxAccountId()
            ));
        }
        return profiles;
    }

    @Transactional
    public record DispatchPosting(Long inventoryAccountId, Long cogsAccountId, BigDecimal cost) {}

    @Transactional
    public List<DispatchPosting> markSlipDispatched(Long salesOrderId) {
        Company company = companyContextService.requireCurrentCompany();
        List<PackagingSlip> slips = packagingSlipRepository.findAllByCompanyAndSalesOrderIdAndIsBackorderFalse(company, salesOrderId);
        if (slips.isEmpty()) {
            throw new IllegalArgumentException("Packaging slip not found for order " + salesOrderId);
        }
        if (slips.size() > 1) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "Multiple packaging slips found for order " + salesOrderId + "; provide packagingSlipId");
        }
        PackagingSlip selected = slips.get(0);
        Long slipId = selected.getId();
        PackagingSlip slip = packagingSlipRepository.findAndLockByIdAndCompany(slipId, company)
                .orElseThrow(() -> new IllegalArgumentException("Packaging slip not found for order " + salesOrderId));
        return markSlipDispatched(salesOrderId, slip);
    }

    /**
     * Dispatch using slip line quantities (supports override quantities from confirmDispatch).
     * Uses the slip line quantities which may have been updated with dispatch overrides.
     */
    @Transactional
    public List<DispatchPosting> markSlipDispatched(Long salesOrderId, PackagingSlip slip) {
        if ("DISPATCHED".equalsIgnoreCase(slip.getStatus())) {
            return List.of();
        }

        // Build map of batch ID to requested quantity from slip lines
        Map<Long, BigDecimal> slipLineQtyByBatch = new HashMap<>();
        Map<Long, List<PackagingSlipLine>> slipLinesByBatch = new HashMap<>();
        Map<PackagingSlipLine, BigDecimal> remainingByLine = new HashMap<>();
        Map<Long, BigDecimal> remainingByBatch = new HashMap<>();
        for (var slipLine : slip.getLines()) {
            FinishedGoodBatch batch = slipLine.getFinishedGoodBatch();
            if (batch != null && slipLine.getQuantity() != null) {
                slipLineQtyByBatch.merge(
                        batch.getId(),
                        slipLine.getQuantity(),
                        BigDecimal::add);
                slipLinesByBatch.computeIfAbsent(batch.getId(), ignored -> new ArrayList<>()).add(slipLine);
            }
            BigDecimal ordered = slipLine.getOrderedQuantity() != null ? slipLine.getOrderedQuantity() : slipLine.getQuantity();
            if (ordered == null) {
                ordered = BigDecimal.ZERO;
            }
            BigDecimal shippedSoFar = safeQuantity(slipLine.getShippedQuantity());
            if (shippedSoFar.compareTo(BigDecimal.ZERO) <= 0 && slipLine.getShippedQuantity() == null) {
                slipLine.setShippedQuantity(BigDecimal.ZERO);
            }
            BigDecimal remaining = ordered.subtract(shippedSoFar).max(BigDecimal.ZERO);
            remainingByLine.put(slipLine, remaining);
        }
        remainingByBatch.putAll(slipLineQtyByBatch);

        List<InventoryReservation> reservations = inventoryReservationRepository
                .findByFinishedGoodCompanyAndReferenceTypeAndReferenceId(
                        slip.getCompany(),
                        InventoryReference.SALES_ORDER,
                        salesOrderId.toString());
        if (reservations.isEmpty()) {
            reservations = rebuildReservationsFromSlip(slip, salesOrderId);
            if (reservations.isEmpty()) {
                throw new IllegalStateException("No reservations found for order " + salesOrderId);
            }
        }

        Map<Long, FinishedGood> lockedGoods = lockFinishedGoodsInOrder(
                slip.getCompany(),
                reservations.stream()
                        .map(r -> r.getFinishedGood().getId())
                        .collect(Collectors.toSet()));
        // Guard against stale WAC snapshots loaded before dispatch starts.
        lockedGoods.keySet().forEach(wacCache::remove);

        Map<String, DispatchPostingBuilder> postingBuilders = new HashMap<>();
        List<FinishedGoodBatch> batchesToSave = new ArrayList<>();
        for (InventoryReservation reservation : reservations) {
            FinishedGoodBatch batch = reservation.getFinishedGoodBatch();
            Long batchId = batch != null ? batch.getId() : null;
            if (batchId == null || !remainingByBatch.containsKey(batchId)) {
                continue;
            }
            // Use slip line quantity if available (supports overrides), fallback to reservation
            BigDecimal reservedQty = reservation.getReservedQuantity() != null
                    ? reservation.getReservedQuantity()
                    : reservation.getQuantity();
            if (reservedQty == null) {
                reservedQty = BigDecimal.ZERO;
            }
            BigDecimal batchRemaining = remainingByBatch.getOrDefault(batchId, BigDecimal.ZERO);
            BigDecimal requested = reservedQty.min(batchRemaining);
            BigDecimal shipQty = requested;
            FinishedGood fg = lockedGoods.get(reservation.getFinishedGood().getId());
            // If nothing to ship, skip but keep reservation for future dispatch
            if (shipQty.compareTo(BigDecimal.ZERO) <= 0) {
                reservation.setStatus("BACKORDER");
                continue;
            }
            BigDecimal current = fg.getCurrentStock() == null ? BigDecimal.ZERO : fg.getCurrentStock();
            if (current.compareTo(shipQty) < 0) {
                shipQty = current;
            }
            remainingByBatch.put(batchId, batchRemaining.subtract(shipQty).max(BigDecimal.ZERO));
            reservation.setFulfilledQuantity(shipQty);
            if (shipQty.compareTo(requested) >= 0) {
                reservation.setStatus("FULFILLED");
                reservation.setReservedQuantity(BigDecimal.ZERO);
            } else {
                reservation.setStatus("PARTIAL");
                // Update reserved quantity to remaining unfulfilled amount to prevent double-shipping on retry
                BigDecimal remaining = requested.subtract(shipQty);
                reservation.setReservedQuantity(remaining.max(BigDecimal.ZERO));
            }

            if (fg.getValuationAccountId() == null || fg.getCogsAccountId() == null) {
                throw new IllegalStateException("Finished good " + fg.getProductCode() + " missing accounting configuration");
            }
            // Resolve cost before mutating quantities so WAC reflects pre-dispatch average.
            BigDecimal unitCost = resolveDispatchUnitCost(fg, batch);
            requireNonZeroDispatchCost(fg, unitCost, shipQty);
            BigDecimal reserved = safeQuantity(fg.getReservedStock());
            requireSufficientQuantity(reserved, shipQty,
                    "Reserved stock insufficient for FG " + fg.getProductCode());
            fg.setReservedStock(reserved.subtract(shipQty));
            fg.setCurrentStock(current.subtract(shipQty));
            if (batch != null) {
                BigDecimal batchTotal = safeQuantity(batch.getQuantityTotal());
                requireSufficientQuantity(batchTotal, shipQty,
                        "Batch stock insufficient for batch " + batch.getBatchCode()
                                + " FG " + fg.getProductCode());
                batch.setQuantityTotal(batchTotal.subtract(shipQty));
                batchesToSave.add(batch);
            }
            recordMovement(fg, batch, InventoryReference.SALES_ORDER, salesOrderId.toString(), "DISPATCH", shipQty, unitCost, slip.getId());

            String postingKey = fg.getValuationAccountId() + ":" + fg.getCogsAccountId();
            postingBuilders
                    .computeIfAbsent(postingKey,
                            key -> new DispatchPostingBuilder(fg.getValuationAccountId(), fg.getCogsAccountId()))
                    .addCost(unitCost.multiply(shipQty));

            if (batch != null) {
                List<PackagingSlipLine> batchLines = slipLinesByBatch.get(batch.getId());
                if (batchLines != null && shipQty.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal remainingToAllocate = shipQty;
                    for (PackagingSlipLine line : batchLines) {
                        BigDecimal lineRemaining = remainingByLine.getOrDefault(line, BigDecimal.ZERO);
                        if (lineRemaining.compareTo(BigDecimal.ZERO) <= 0) {
                            continue;
                        }
                        BigDecimal allocation = remainingToAllocate.min(lineRemaining);
                        if (allocation.compareTo(BigDecimal.ZERO) <= 0) {
                            continue;
                        }
                        BigDecimal shippedSoFar = safeQuantity(line.getShippedQuantity());
                        line.setShippedQuantity(shippedSoFar.add(allocation));
                        remainingByLine.put(line, lineRemaining.subtract(allocation));
                        remainingToAllocate = remainingToAllocate.subtract(allocation);
                        if (remainingToAllocate.compareTo(BigDecimal.ZERO) <= 0) {
                            break;
                        }
                    }
                }
            }
        }
        finishedGoodRepository.saveAll(lockedGoods.values());
        lockedGoods.keySet().forEach(wacCache::remove);
        if (!batchesToSave.isEmpty()) {
            finishedGoodBatchRepository.saveAll(batchesToSave);
        }
        inventoryReservationRepository.saveAll(reservations);

        boolean anyShipped = reservations.stream()
                .anyMatch(r -> r.getFulfilledQuantity() != null && r.getFulfilledQuantity().compareTo(BigDecimal.ZERO) > 0);
        boolean anyPending = reservations.stream()
                .anyMatch(r -> !"FULFILLED".equalsIgnoreCase(r.getStatus()) && !"CANCELLED".equalsIgnoreCase(r.getStatus()));
        if (anyShipped) {
            slip.setStatus("DISPATCHED");
            if (slip.getDispatchedAt() == null) {
                slip.setDispatchedAt(companyClock.now(slip.getCompany()));
            }
        } else if (anyPending) {
            // Nothing shipped yet: remain non-terminal until an actual shipment occurs.
            slip.setStatus("PENDING_STOCK");
        }
        for (PackagingSlipLine line : slip.getLines()) {
            BigDecimal ordered = line.getOrderedQuantity() != null ? line.getOrderedQuantity() : line.getQuantity();
            BigDecimal shipped = safeQuantity(line.getShippedQuantity());
            if (ordered == null) {
                ordered = BigDecimal.ZERO;
            }
            line.setBackorderQuantity(ordered.subtract(shipped).max(BigDecimal.ZERO));
        }
        packagingSlipRepository.save(slip);

        boolean hasBackorder = slip.getLines().stream()
                .map(line -> safeQuantity(line.getBackorderQuantity()))
                .anyMatch(qty -> qty.compareTo(BigDecimal.ZERO) > 0);
        if (hasBackorder) {
            createBackorderSlip(slip);
        }

        return postingBuilders.values().stream()
                .map(DispatchPostingBuilder::build)
                .toList();
    }

    /**
     * Get dispatch preview for factory confirmation modal.
     * Shows what was ordered vs what's available to ship.
     */
    @Transactional
    public DispatchPreviewDto getDispatchPreview(Long packagingSlipId) {
        Company company = companyContextService.requireCurrentCompany();
        PackagingSlip slip = packagingSlipRepository.findByIdAndCompany(packagingSlipId, company)
                .orElseThrow(() -> new IllegalArgumentException("Packaging slip not found"));

        if ("DISPATCHED".equalsIgnoreCase(slip.getStatus())) {
            throw new IllegalStateException("Slip already dispatched");
        }

        SalesOrder order = slip.getSalesOrder();
        String dealerName = order.getDealer() != null ? order.getDealer().getName() : null;
        String dealerCode = order.getDealer() != null ? order.getDealer().getCode() : null;
        Map<Long, BigDecimal> reservedByBatch = inventoryReservationRepository
                .findByFinishedGoodCompanyAndReferenceTypeAndReferenceId(
                        company,
                        InventoryReference.SALES_ORDER,
                        order.getId().toString())
                .stream()
                .filter(reservation -> reservation.getFinishedGoodBatch() != null)
                .collect(Collectors.groupingBy(
                        reservation -> reservation.getFinishedGoodBatch().getId(),
                        Collectors.mapping(
                                reservation -> reservation.getReservedQuantity() != null
                                        ? reservation.getReservedQuantity()
                                        : reservation.getQuantity(),
                                Collectors.reducing(BigDecimal.ZERO, BigDecimal::add)
                        )));

        List<DispatchPreviewDto.LinePreview> linePreviews = new ArrayList<>();
        BigDecimal totalOrdered = BigDecimal.ZERO;
        BigDecimal totalAvailable = BigDecimal.ZERO;

        for (PackagingSlipLine line : slip.getLines()) {
            FinishedGoodBatch batch = line.getFinishedGoodBatch();
            FinishedGood fg = batch.getFinishedGood();
            
            BigDecimal ordered = line.getOrderedQuantity() != null ? line.getOrderedQuantity() : line.getQuantity();
            BigDecimal available = batch.getQuantityAvailable() != null ? batch.getQuantityAvailable() : BigDecimal.ZERO;
            BigDecimal reservedForOrder = reservedByBatch.getOrDefault(batch.getId(), BigDecimal.ZERO);
            available = available.add(reservedForOrder);
            BigDecimal suggestedShip = ordered.min(available);
            boolean hasShortage = available.compareTo(ordered) < 0;
            BigDecimal unitCost = line.getUnitCost() != null ? line.getUnitCost() : BigDecimal.ZERO;

            linePreviews.add(new DispatchPreviewDto.LinePreview(
                    line.getId(),
                    fg.getId(),
                    fg.getProductCode(),
                    fg.getName(),
                    batch.getBatchCode(),
                    ordered,
                    available,
                    suggestedShip,
                    null,
                    null,
                    hasShortage
            ));

            totalOrdered = totalOrdered.add(ordered.multiply(unitCost));
            totalAvailable = totalAvailable.add(suggestedShip.multiply(unitCost));
        }

        return new DispatchPreviewDto(
                slip.getId(),
                slip.getSlipNumber(),
                slip.getStatus(),
                order.getId(),
                order.getOrderNumber(),
                dealerName,
                dealerCode,
                slip.getCreatedAt(),
                null,
                null,
                linePreviews
        );
    }

    /**
     * Confirm dispatch with actual shipped quantities.
     * This is the final step before goods leave the warehouse.
     * Journals and ledger updates happen HERE, not at order creation.
     */
    @Transactional
    public DispatchConfirmationResponse confirmDispatch(DispatchConfirmationRequest request, String username) {
        Company company = companyContextService.requireCurrentCompany();
        PackagingSlip slip = packagingSlipRepository.findAndLockByIdAndCompany(request.packagingSlipId(), company)
                .orElseThrow(() -> new IllegalArgumentException("Packaging slip not found"));

        if ("DISPATCHED".equalsIgnoreCase(slip.getStatus())) {
            return buildDispatchConfirmationResponse(slip, company);
        }

        SalesOrder order = slip.getSalesOrder();
        Map<Long, DispatchConfirmationRequest.LineConfirmation> confirmations = request.lines().stream()
                .collect(Collectors.toMap(DispatchConfirmationRequest.LineConfirmation::lineId, l -> l));

        List<DispatchConfirmationResponse.LineResult> lineResults = new ArrayList<>();
        BigDecimal totalOrdered = BigDecimal.ZERO;
        BigDecimal totalShipped = BigDecimal.ZERO;
        BigDecimal totalBackorder = BigDecimal.ZERO;
        BigDecimal totalCogsCost = BigDecimal.ZERO;
        boolean hasBackorder = false;

        List<InventoryReservation> reservationsToUpdate = new ArrayList<>();
        Map<Long, List<InventoryReservation>> reservationsByBatch = new HashMap<>();
        Map<Long, BigDecimal> reservedByBatch = new HashMap<>();
        if (order != null) {
            List<InventoryReservation> reservations = inventoryReservationRepository
                    .findByFinishedGoodCompanyAndReferenceTypeAndReferenceId(
                            company,
                            InventoryReference.SALES_ORDER,
                            order.getId().toString());
            if (reservations.isEmpty()) {
                reservations = rebuildReservationsFromSlip(slip, order.getId());
            }
            for (InventoryReservation reservation : reservations) {
                if ("CANCELLED".equalsIgnoreCase(reservation.getStatus())) {
                    continue;
                }
                FinishedGoodBatch batch = reservation.getFinishedGoodBatch();
                if (batch == null || batch.getId() == null) {
                    continue;
                }
                BigDecimal reservedQty = resolveReservedQuantity(reservation);
                reservation.setReservedQuantity(reservedQty);
                if (reservation.getFulfilledQuantity() == null) {
                    reservation.setFulfilledQuantity(BigDecimal.ZERO);
                }
                reservationsByBatch
                        .computeIfAbsent(batch.getId(), k -> new ArrayList<>())
                        .add(reservation);
                reservationsToUpdate.add(reservation);
                reservedByBatch.merge(batch.getId(), reservedQty, BigDecimal::add);
            }
            for (List<InventoryReservation> batchReservations : reservationsByBatch.values()) {
                batchReservations.sort(Comparator.comparing(InventoryReservation::getId, Comparator.nullsLast(Long::compareTo)));
            }
        }

        Map<Long, FinishedGood> lockedGoods = new HashMap<>();
        List<FinishedGoodBatch> batchesToSave = new ArrayList<>();

        for (PackagingSlipLine line : slip.getLines()) {
            DispatchConfirmationRequest.LineConfirmation conf = confirmations.get(line.getId());
            if (conf == null) {
                throw new IllegalArgumentException("Missing confirmation for line " + line.getId());
            }

            FinishedGoodBatch batch = line.getFinishedGoodBatch();
            FinishedGood originalFg = batch.getFinishedGood();
            
            // Lock the finished good if not already locked
            if (!lockedGoods.containsKey(originalFg.getId())) {
                lockedGoods.put(originalFg.getId(), lockFinishedGood(company, originalFg.getId()));
            }
            FinishedGood fg = lockedGoods.get(originalFg.getId());

            BigDecimal ordered = line.getOrderedQuantity() != null ? line.getOrderedQuantity() : line.getQuantity();
            BigDecimal shipped = conf.shippedQuantity();
            BigDecimal backorder = ordered.subtract(shipped).max(BigDecimal.ZERO);
            if (backorder.compareTo(BigDecimal.ZERO) > 0) {
                hasBackorder = true;
            }

            // Validate shipped quantity
            if (shipped.compareTo(BigDecimal.ZERO) < 0) {
                throw new IllegalArgumentException("Shipped quantity cannot be negative");
            }
            if (shipped.compareTo(ordered) > 0) {
                throw new IllegalArgumentException("Shipped quantity cannot exceed ordered quantity");
            }

            // Update line
            line.setShippedQuantity(shipped);
            line.setBackorderQuantity(backorder);
            line.setNotes(conf.notes());
            // Re-resolve cost from current persistence state instead of stale cache snapshots.
            wacCache.remove(fg.getId());
            BigDecimal unitCost = resolveDispatchUnitCost(fg, batch);
            requireNonZeroDispatchCost(fg, unitCost, shipped);
            line.setUnitCost(unitCost);

            // Update inventory if actually shipping
            if (shipped.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal currentStock = fg.getCurrentStock() != null ? fg.getCurrentStock() : BigDecimal.ZERO;
                if (currentStock.compareTo(shipped) < 0) {
                    throw new IllegalStateException("Insufficient current stock for FG " + fg.getProductCode() + ": available=" + currentStock + ", requested=" + shipped);
                }
                if (batch.getId() != null) {
                    BigDecimal batchReserved = reservedByBatch.getOrDefault(batch.getId(), BigDecimal.ZERO);
                    requireSufficientQuantity(batchReserved, shipped,
                            "Reserved quantity insufficient for batch " + batch.getBatchCode()
                                    + " FG " + fg.getProductCode());
                    reservedByBatch.put(batch.getId(), batchReserved.subtract(shipped).max(BigDecimal.ZERO));
                }
                BigDecimal reservedStock = safeQuantity(fg.getReservedStock());
                requireSufficientQuantity(reservedStock, shipped,
                        "Reserved stock insufficient for FG " + fg.getProductCode());
                fg.setCurrentStock(currentStock.subtract(shipped));
                fg.setReservedStock(reservedStock.subtract(shipped));
                wacCache.remove(fg.getId());

                BigDecimal batchQty = safeQuantity(batch.getQuantityTotal());
                requireSufficientQuantity(batchQty, shipped,
                        "Batch stock insufficient for batch " + batch.getBatchCode()
                                + " FG " + fg.getProductCode());
                batch.setQuantityTotal(batchQty.subtract(shipped));
                batchesToSave.add(batch);

                totalCogsCost = totalCogsCost.add(shipped.multiply(unitCost));

                recordMovement(fg, batch, InventoryReference.SALES_ORDER, order.getId().toString(), 
                        "DISPATCH", shipped, unitCost, slip.getId());
            }

            if (shipped.compareTo(BigDecimal.ZERO) > 0) {
                applyReservationFulfillment(reservationsByBatch, batch, shipped);
            }

            lineResults.add(new DispatchConfirmationResponse.LineResult(
                    line.getId(),
                    fg.getProductCode(),
                    fg.getName(),
                    ordered,
                    shipped,
                    backorder,
                    line.getUnitCost(),
                    shipped.multiply(line.getUnitCost()),
                    conf.notes()
            ));

            totalOrdered = totalOrdered.add(ordered.multiply(line.getUnitCost()));
            totalShipped = totalShipped.add(shipped.multiply(line.getUnitCost()));
            totalBackorder = totalBackorder.add(backorder.multiply(line.getUnitCost()));
        }

        // Save inventory updates
        finishedGoodRepository.saveAll(lockedGoods.values());
        if (!batchesToSave.isEmpty()) {
            finishedGoodBatchRepository.saveAll(batchesToSave);
        }

        hasBackorder = totalBackorder.compareTo(BigDecimal.ZERO) > 0;
        boolean anyShipped = totalShipped.compareTo(BigDecimal.ZERO) > 0;
        Instant now = companyClock.now(company);
        if (anyShipped) {
            slip.setStatus("DISPATCHED");
            slip.setDispatchedAt(now);
        } else {
            slip.setStatus(hasBackorder ? "PENDING_STOCK" : slip.getStatus());
        }
        slip.setConfirmedAt(now);
        slip.setConfirmedBy(username);
        slip.setDispatchNotes(request.notes());
        packagingSlipRepository.save(slip);

        if (!reservationsToUpdate.isEmpty()) {
            updateReservationStatuses(reservationsToUpdate);
            inventoryReservationRepository.saveAll(reservationsToUpdate);
        }

        // Handle backorders - create new slip if needed
        Long backorderSlipId = null;
        if (hasBackorder && anyShipped) {
            backorderSlipId = createBackorderSlip(slip);
        }

        return new DispatchConfirmationResponse(
                slip.getId(),
                slip.getSlipNumber(),
                slip.getStatus(),
                slip.getConfirmedAt(),
                slip.getConfirmedBy(),
                totalOrdered,
                totalShipped,
                totalBackorder,
                slip.getJournalEntryId(),
                slip.getCogsJournalEntryId(),
                lineResults,
                backorderSlipId
        );
    }

    private void applyReservationFulfillment(Map<Long, List<InventoryReservation>> reservationsByBatch,
                                             FinishedGoodBatch batch,
                                             BigDecimal shipped) {
        if (batch == null || batch.getId() == null || shipped == null || shipped.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        List<InventoryReservation> reservations = reservationsByBatch.get(batch.getId());
        if (reservations == null || reservations.isEmpty()) {
            return;
        }
        BigDecimal remaining = shipped;
        for (InventoryReservation reservation : reservations) {
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
                break;
            }
            BigDecimal reservedQty = resolveReservedQuantity(reservation);
            if (reservedQty.compareTo(BigDecimal.ZERO) <= 0) {
                reservation.setReservedQuantity(BigDecimal.ZERO);
                continue;
            }
            BigDecimal applied = remaining.min(reservedQty);
            reservation.setReservedQuantity(reservedQty.subtract(applied).max(BigDecimal.ZERO));
            BigDecimal fulfilled = safeQuantity(reservation.getFulfilledQuantity());
            reservation.setFulfilledQuantity(fulfilled.add(applied));
            remaining = remaining.subtract(applied);
        }
    }

    private void updateReservationStatuses(List<InventoryReservation> reservations) {
        for (InventoryReservation reservation : reservations) {
            if ("CANCELLED".equalsIgnoreCase(reservation.getStatus())) {
                continue;
            }
            BigDecimal reservedQty = safeQuantity(reservation.getReservedQuantity());
            BigDecimal fulfilledQty = safeQuantity(reservation.getFulfilledQuantity());
            if (fulfilledQty.compareTo(BigDecimal.ZERO) > 0) {
                if (reservedQty.compareTo(BigDecimal.ZERO) > 0) {
                    reservation.setStatus("PARTIAL");
                } else {
                    reservation.setStatus("FULFILLED");
                    reservation.setReservedQuantity(BigDecimal.ZERO);
                }
            } else if (reservedQty.compareTo(BigDecimal.ZERO) > 0) {
                reservation.setStatus("BACKORDER");
            }
        }
    }

    private BigDecimal resolveReservedQuantity(InventoryReservation reservation) {
        String status = reservation.getStatus();
        if (status != null) {
            if ("CANCELLED".equalsIgnoreCase(status) || "FULFILLED".equalsIgnoreCase(status)) {
                return BigDecimal.ZERO;
            }
        }
        BigDecimal reserved = reservation.getReservedQuantity();
        if (reserved == null || reserved.compareTo(BigDecimal.ZERO) <= 0) {
            reserved = reservation.getQuantity();
        }
        return safeQuantity(reserved);
    }

    private DispatchConfirmationResponse buildDispatchConfirmationResponse(PackagingSlip slip, Company company) {
        List<DispatchConfirmationResponse.LineResult> lineResults = new ArrayList<>();
        BigDecimal totalOrdered = BigDecimal.ZERO;
        BigDecimal totalShipped = BigDecimal.ZERO;
        BigDecimal totalBackorder = BigDecimal.ZERO;
        boolean hasBackorder = false;

        for (PackagingSlipLine line : slip.getLines()) {
            FinishedGood fg = line.getFinishedGoodBatch().getFinishedGood();
            BigDecimal orderedQty = line.getOrderedQuantity() != null ? line.getOrderedQuantity() : line.getQuantity();
            if (orderedQty == null) {
                orderedQty = BigDecimal.ZERO;
            }
            BigDecimal shippedQty = line.getShippedQuantity();
            if (shippedQty == null) {
                shippedQty = line.getQuantity();
            }
            if (shippedQty == null) {
                shippedQty = BigDecimal.ZERO;
            }
            BigDecimal backorderQty = line.getBackorderQuantity();
            if (backorderQty == null) {
                backorderQty = orderedQty.subtract(shippedQty).max(BigDecimal.ZERO);
            }
            if (backorderQty.compareTo(BigDecimal.ZERO) > 0) {
                hasBackorder = true;
            }
            BigDecimal unitCost = line.getUnitCost() != null ? line.getUnitCost() : BigDecimal.ZERO;
            BigDecimal lineTotal = shippedQty.multiply(unitCost);
            totalOrdered = totalOrdered.add(orderedQty.multiply(unitCost));
            totalShipped = totalShipped.add(lineTotal);
            totalBackorder = totalBackorder.add(backorderQty.multiply(unitCost));

            lineResults.add(new DispatchConfirmationResponse.LineResult(
                    line.getId(),
                    fg.getProductCode(),
                    fg.getName(),
                    orderedQty,
                    shippedQty,
                    backorderQty,
                    unitCost,
                    lineTotal,
                    line.getNotes()
            ));
        }

        Long backorderSlipId = resolveBackorderSlipIdForResponse(slip, company, hasBackorder);

        return new DispatchConfirmationResponse(
                slip.getId(),
                slip.getSlipNumber(),
                slip.getStatus(),
                slip.getConfirmedAt(),
                slip.getConfirmedBy(),
                totalOrdered,
                totalShipped,
                totalBackorder,
                slip.getJournalEntryId(),
                slip.getCogsJournalEntryId(),
                lineResults,
                backorderSlipId
        );
    }

    /**
     * Read-only dispatcher-friendly response builder (no inventory mutation).
     *
     * CODE-RED: used to avoid double-calling confirmDispatch from controllers.
     */
    @Transactional
    public DispatchConfirmationResponse getDispatchConfirmation(Long packagingSlipId) {
        Company company = companyContextService.requireCurrentCompany();
        PackagingSlip slip = packagingSlipRepository.findByIdAndCompany(packagingSlipId, company)
                .orElseThrow(() -> new IllegalArgumentException("Packaging slip not found"));
        return buildDispatchConfirmationResponse(slip, company);
    }

    /**
     * Create a backorder slip for items that couldn't be shipped.
     */
    private Long createBackorderSlip(PackagingSlip originalSlip) {
        if (originalSlip == null || originalSlip.getSalesOrder() == null) {
            return null;
        }
        boolean hasBackorder = originalSlip.getLines().stream()
                .map(line -> safeQuantity(line.getBackorderQuantity()))
                .anyMatch(qty -> qty.compareTo(BigDecimal.ZERO) > 0);
        if (!hasBackorder) {
            return null;
        }

        Long existingId = findOpenBackorderSlipId(
                originalSlip.getCompany(),
                originalSlip.getSalesOrder().getId(),
                originalSlip.getId());
        if (existingId != null) {
            return existingId;
        }

        PackagingSlip backorderSlip = new PackagingSlip();
        backorderSlip.setCompany(originalSlip.getCompany());
        backorderSlip.setSalesOrder(originalSlip.getSalesOrder());
        backorderSlip.setSlipNumber(generateSlipNumber(originalSlip.getCompany()) + "-BO");
        backorderSlip.setStatus("BACKORDER");
        backorderSlip.setBackorder(true);
        backorderSlip.setDispatchNotes("Backorder from " + originalSlip.getSlipNumber());

        // Create lines for backorder quantities
        for (PackagingSlipLine originalLine : originalSlip.getLines()) {
            BigDecimal backorderQty = originalLine.getBackorderQuantity();
            if (backorderQty != null && backorderQty.compareTo(BigDecimal.ZERO) > 0) {
                PackagingSlipLine boLine = new PackagingSlipLine();
                boLine.setPackagingSlip(backorderSlip);
                boLine.setFinishedGoodBatch(originalLine.getFinishedGoodBatch());
                boLine.setOrderedQuantity(backorderQty);
                boLine.setQuantity(backorderQty);
                boLine.setUnitCost(originalLine.getUnitCost());
                boLine.setNotes("Backorder from " + originalSlip.getSlipNumber());
                backorderSlip.getLines().add(boLine);
            }
        }

        try {
            PackagingSlip saved = packagingSlipRepository.saveAndFlush(backorderSlip);
            return saved.getId();
        } catch (DataIntegrityViolationException ex) {
            Long concurrentExistingId = findOpenBackorderSlipId(
                    originalSlip.getCompany(),
                    originalSlip.getSalesOrder().getId(),
                    originalSlip.getId());
            if (concurrentExistingId != null) {
                return concurrentExistingId;
            }
            throw ex;
        }
    }

    private Long resolveBackorderSlipIdForResponse(PackagingSlip slip, Company company, boolean hasBackorder) {
        if (!hasBackorder || slip == null || slip.getSalesOrder() == null) {
            return null;
        }
        if (slip.isBackorder() && "BACKORDER".equalsIgnoreCase(slip.getStatus())) {
            return slip.getId();
        }
        return findOpenBackorderSlipId(company, slip.getSalesOrder().getId(), slip.getId());
    }

    private Long findOpenBackorderSlipId(Company company, Long salesOrderId, Long excludeSlipId) {
        if (company == null || salesOrderId == null) {
            return null;
        }
        return packagingSlipRepository
                .findAllByCompanyAndSalesOrderIdAndIsBackorderTrue(company, salesOrderId).stream()
                .filter(existing -> existing.getId() != null)
                .filter(existing -> !Objects.equals(existing.getId(), excludeSlipId))
                .filter(existing -> "BACKORDER".equalsIgnoreCase(existing.getStatus()))
                .map(PackagingSlip::getId)
                .findFirst()
                .orElse(null);
    }

    /**
     * Get packaging slip for factory to review before dispatch.
     */
    public PackagingSlipDto getPackagingSlip(Long slipId) {
        Company company = companyContextService.requireCurrentCompany();
        PackagingSlip slip = packagingSlipRepository.findByIdAndCompany(slipId, company)
                .orElseThrow(() -> new IllegalArgumentException("Packaging slip not found"));
        return toSlipDto(slip);
    }

    /**
     * Get packaging slip by sales order ID.
     */
    public PackagingSlipDto getPackagingSlipByOrder(Long salesOrderId) {
        Company company = companyContextService.requireCurrentCompany();
        List<PackagingSlip> slips = packagingSlipRepository
                .findAllByCompanyAndSalesOrderIdAndIsBackorderFalse(company, salesOrderId);
        if (slips.isEmpty()) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE,
                    "Packaging slip not found for order " + salesOrderId);
        }
        if (slips.size() > 1) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "Multiple packaging slips found for order " + salesOrderId + "; provide packingSlipId");
        }
        PackagingSlip selected = slips.get(0);
        Long slipId = selected.getId();
        PackagingSlip slip = packagingSlipRepository.findByIdAndCompany(slipId, company)
                .orElseThrow(() -> new IllegalArgumentException("Packaging slip not found"));
        return toSlipDto(slip);
    }

    /**
     * Update packaging slip status for operational visibility.
     *
     * CODE-RED: this must not become a bypass path for inventory/accounting truth.
     */
    @Transactional
    public PackagingSlipDto updateSlipStatus(Long slipId, String newStatus) {
        Company company = companyContextService.requireCurrentCompany();
        PackagingSlip slip = packagingSlipRepository.findByIdAndCompany(slipId, company)
                .orElseThrow(() -> new IllegalArgumentException("Packaging slip not found"));
        
        if (slip.isBackorder()) {
            throw new IllegalStateException("Backorder slips can only be changed via backorder workflows");
        }
        if ("DISPATCHED".equalsIgnoreCase(slip.getStatus())) {
            throw new IllegalStateException("Cannot update status of dispatched slip");
        }
        if (!StringUtils.hasText(newStatus)) {
            throw new IllegalArgumentException("Slip status is required");
        }
        String normalized = newStatus.trim().toUpperCase();
        if ("DISPATCHED".equalsIgnoreCase(normalized)) {
            throw new IllegalStateException("Use dispatch confirmation to mark a slip as dispatched");
        }
        // Fail-closed: only allow known operational statuses. CANCELLED/BACKORDER require dedicated workflows.
        if (!List.of("PENDING", "PENDING_PRODUCTION", "RESERVED", "PENDING_STOCK").contains(normalized)) {
            throw new IllegalArgumentException("Unsupported slip status: " + normalized);
        }
        if (!canTransitionStatus(slip.getStatus(), normalized)) {
            throw new IllegalStateException("Invalid slip status transition: " + slip.getStatus() + " -> " + normalized);
        }
        slip.setStatus(normalized);
        packagingSlipRepository.save(slip);
        return toSlipDto(slip);
    }

    /**
     * Cancel a backorder slip without consuming inventory. Releases reserved stock and quantityAvailable.
     */
    @Transactional
    public PackagingSlipDto cancelBackorderSlip(Long slipId, String username, String reason) {
        Company company = companyContextService.requireCurrentCompany();
        PackagingSlip slip = packagingSlipRepository.findByIdAndCompany(slipId, company)
                .orElseThrow(() -> new IllegalArgumentException("Packaging slip not found"));
        if (!slip.isBackorder() || !"BACKORDER".equalsIgnoreCase(slip.getStatus())) {
            throw new IllegalStateException("Only BACKORDER slips can be canceled");
        }

        List<InventoryReservation> reservations = inventoryReservationRepository
                .findByFinishedGoodCompanyAndReferenceTypeAndReferenceId(
                        company,
                        InventoryReference.SALES_ORDER,
                        slip.getSalesOrder().getId().toString());
        Map<Long, BigDecimal> remainingByBatch = new HashMap<>();
        for (PackagingSlipLine line : slip.getLines()) {
            FinishedGoodBatch batch = line.getFinishedGoodBatch();
            if (batch == null || batch.getId() == null) {
                continue;
            }
            BigDecimal qty = line.getBackorderQuantity();
            if (qty == null || qty.compareTo(BigDecimal.ZERO) <= 0) {
                qty = line.getOrderedQuantity() != null ? line.getOrderedQuantity() : line.getQuantity();
            }
            if (qty == null) {
                qty = BigDecimal.ZERO;
            }
            if (qty.compareTo(BigDecimal.ZERO) > 0) {
                remainingByBatch.merge(batch.getId(), qty, BigDecimal::add);
            }
        }

        Map<Long, FinishedGood> lockedGoods = new HashMap<>();
        Map<Long, FinishedGoodBatch> lockedBatches = new HashMap<>();
        Map<Long, BigDecimal> cancelledByBatch = new HashMap<>();
        Map<Long, BigDecimal> cancelledByFg = new HashMap<>();
        List<InventoryReservation> reservationsToUpdate = new ArrayList<>();

        if (!reservations.isEmpty() && !remainingByBatch.isEmpty()) {
            for (InventoryReservation reservation : reservations) {
                if ("CANCELLED".equalsIgnoreCase(reservation.getStatus())) {
                    continue;
                }
                FinishedGoodBatch batch = reservation.getFinishedGoodBatch();
                if (batch == null || batch.getId() == null) {
                    continue;
                }
                BigDecimal remaining = remainingByBatch.get(batch.getId());
                if (remaining == null || remaining.compareTo(BigDecimal.ZERO) <= 0) {
                    continue;
                }
                BigDecimal reservedQty = resolveReservedQuantity(reservation);
                if (reservedQty.compareTo(BigDecimal.ZERO) <= 0) {
                    continue;
                }
                BigDecimal cancelQty = remaining.min(reservedQty);
                reservation.setReservedQuantity(reservedQty.subtract(cancelQty).max(BigDecimal.ZERO));
                BigDecimal fulfilled = safeQuantity(reservation.getFulfilledQuantity());
                if (reservation.getReservedQuantity().compareTo(BigDecimal.ZERO) <= 0
                        && fulfilled.compareTo(BigDecimal.ZERO) <= 0) {
                    reservation.setStatus("CANCELLED");
                }
                reservationsToUpdate.add(reservation);

                remainingByBatch.put(batch.getId(), remaining.subtract(cancelQty).max(BigDecimal.ZERO));
                cancelledByBatch.merge(batch.getId(), cancelQty, BigDecimal::add);

                FinishedGood fg = batch.getFinishedGood();
                if (fg != null && fg.getId() != null) {
                    cancelledByFg.merge(fg.getId(), cancelQty, BigDecimal::add);
                    lockedGoods.computeIfAbsent(fg.getId(), id -> lockFinishedGood(company, id));
                }
                lockedBatches.computeIfAbsent(batch.getId(), id -> finishedGoodBatchRepository.lockById(id)
                        .orElse(batch));
            }
        }

        if (!reservationsToUpdate.isEmpty()) {
            updateReservationStatuses(reservationsToUpdate);
            inventoryReservationRepository.saveAll(reservationsToUpdate);
        }

        if (!cancelledByBatch.isEmpty()) {
            for (Map.Entry<Long, BigDecimal> entry : cancelledByBatch.entrySet()) {
                FinishedGoodBatch batch = lockedBatches.get(entry.getKey());
                if (batch == null) {
                    continue;
                }
                BigDecimal available = safeQuantity(batch.getQuantityAvailable());
                batch.setQuantityAvailable(available.add(entry.getValue()));
            }
            finishedGoodBatchRepository.saveAll(lockedBatches.values());
        }
        if (!cancelledByFg.isEmpty()) {
            for (Map.Entry<Long, BigDecimal> entry : cancelledByFg.entrySet()) {
                FinishedGood fg = lockedGoods.get(entry.getKey());
                if (fg == null) {
                    continue;
                }
                BigDecimal reserved = safeQuantity(fg.getReservedStock());
                fg.setReservedStock(reserved.subtract(entry.getValue()).max(BigDecimal.ZERO));
            }
            finishedGoodRepository.saveAll(lockedGoods.values());
        }

        slip.setStatus("CANCELLED");
        slip.setDispatchNotes(reason != null ? reason : "Backorder canceled by " + (username != null ? username : "system"));
        slip.setConfirmedAt(CompanyTime.now(company));
        slip.setConfirmedBy(username != null ? username : "system");
        PackagingSlip savedSlip = packagingSlipRepository.save(slip);
        syncOrderStatusAfterBackorderCancellation(company, savedSlip.getSalesOrder());
        return toSlipDto(savedSlip);
    }

    private void syncOrderStatusAfterBackorderCancellation(Company company, SalesOrder salesOrder) {
        if (company == null || salesOrder == null || salesOrder.getId() == null) {
            return;
        }
        SalesOrder managedOrder = salesOrderRepository.findWithItemsByCompanyAndIdForUpdate(company, salesOrder.getId())
                .orElse(null);
        if (managedOrder == null) {
            return;
        }
        if (!isBackorderCancellationStatusSyncAllowed(managedOrder)) {
            return;
        }
        String nextStatus = normalizeBackorderCancellationTargetStatus(resolveOrderStatusFromSlips(company, managedOrder));
        if (!"READY_TO_SHIP".equalsIgnoreCase(nextStatus)
                || "READY_TO_SHIP".equalsIgnoreCase(managedOrder.getStatus())) {
            return;
        }
        managedOrder.setStatus("READY_TO_SHIP");
        salesOrderRepository.save(managedOrder);
    }

    private boolean isBackorderCancellationStatusSyncAllowed(SalesOrder order) {
        if (order == null) {
            return false;
        }
        String status = order.getStatus();
        boolean allowedStatus = "PENDING_PRODUCTION".equalsIgnoreCase(status)
                || "RESERVED".equalsIgnoreCase(status)
                || "READY_TO_SHIP".equalsIgnoreCase(status);
        if (!allowedStatus) {
            return false;
        }
        return !order.hasInvoiceIssued()
                && !order.hasSalesJournalPosted()
                && !order.hasCogsJournalPosted();
    }

    private String normalizeBackorderCancellationTargetStatus(String status) {
        if (!StringUtils.hasText(status)) {
            return status;
        }
        if ("SHIPPED".equalsIgnoreCase(status)) {
            return "READY_TO_SHIP";
        }
        return status;
    }

    private String resolveOrderStatusFromSlips(Company company, SalesOrder order) {
        if (company == null || order == null || order.getId() == null) {
            return order != null ? order.getStatus() : null;
        }
        List<PackagingSlip> slips = packagingSlipRepository.findAllByCompanyAndSalesOrderId(company, order.getId());
        if (slips.isEmpty()) {
            return order.getStatus();
        }
        List<PackagingSlip> activeSlips = slips.stream()
                .filter(slip -> !"CANCELLED".equalsIgnoreCase(slip.getStatus()))
                .toList();
        if (activeSlips.isEmpty()) {
            return order.getStatus();
        }
        boolean anyBackorder = activeSlips.stream()
                .anyMatch(slip -> "BACKORDER".equalsIgnoreCase(slip.getStatus()));
        if (anyBackorder) {
            return "PENDING_PRODUCTION";
        }
        boolean anyDispatched = activeSlips.stream()
                .anyMatch(slip -> "DISPATCHED".equalsIgnoreCase(slip.getStatus()));
        if (!anyDispatched) {
            return order.getStatus();
        }
        boolean allDispatched = activeSlips.stream()
                .allMatch(slip -> "DISPATCHED".equalsIgnoreCase(slip.getStatus()));
        if (allDispatched) {
            return "SHIPPED";
        }
        return "READY_TO_SHIP";
    }

    private PackagingSlip createSlip(SalesOrder order) {
        return createPrimarySlip(order);
    }

    private PackagingSlip createPrimarySlip(SalesOrder order) {
        Company company = companyContextService.requireCurrentCompany();
        PackagingSlip slip = new PackagingSlip();
        slip.setCompany(company);
        slip.setSalesOrder(order);
        slip.setSlipNumber(generateSlipNumber(company));
        slip.setStatus("PENDING");
        slip.setBackorder(false);
        try {
            return packagingSlipRepository.saveAndFlush(slip);
        } catch (DataIntegrityViolationException ex) {
            return packagingSlipRepository.findAndLockPrimaryBySalesOrderId(order.getId(), company)
                    .orElseThrow(() -> ex);
        }
    }

    private void allocateItem(SalesOrder order,
                              PackagingSlip slip,
                              FinishedGood finishedGood,
                              SalesOrderItem item,
                              List<InventoryShortage> shortages) {
        BigDecimal remaining = item.getQuantity();
        List<FinishedGoodBatch> batches = selectBatchesByCostingMethod(finishedGood);
        for (FinishedGoodBatch batch : batches) {
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) break;
            BigDecimal available = batch.getQuantityAvailable();
            if (available.compareTo(BigDecimal.ZERO) <= 0) continue;
            BigDecimal allocation = available.min(remaining);
            batch.setQuantityAvailable(available.subtract(allocation));
            finishedGoodBatchRepository.save(batch);

            finishedGood.setReservedStock(safeQuantity(finishedGood.getReservedStock()).add(allocation));
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

            recordMovement(finishedGood, batch, InventoryReference.SALES_ORDER, order.getId().toString(), "RESERVE", allocation, batch.getUnitCost(), null);
            remaining = remaining.subtract(allocation);
        }

        if (remaining.compareTo(BigDecimal.ZERO) > 0) {
            shortages.add(new InventoryShortage(finishedGood.getProductCode(), remaining, finishedGood.getName()));
        }
    }

    private BigDecimal safeQuantity(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    private void requireSufficientQuantity(BigDecimal available, BigDecimal required, String context) {
        BigDecimal safeAvailable = safeQuantity(available);
        BigDecimal safeRequired = safeQuantity(required);
        if (safeAvailable.compareTo(safeRequired) < 0) {
            throw new IllegalStateException(context + ": available=" + safeAvailable + ", requested=" + safeRequired);
        }
    }

    private boolean slipLinesMatchOrder(PackagingSlip slip, SalesOrder order) {
        if (slip.getLines() == null || slip.getLines().isEmpty()) {
            return false;
        }
        Map<String, BigDecimal> slipQuantities = new HashMap<>();
        for (PackagingSlipLine line : slip.getLines()) {
            if (line.getFinishedGoodBatch() == null || line.getFinishedGoodBatch().getFinishedGood() == null) {
                return false;
            }
            String productCode = line.getFinishedGoodBatch().getFinishedGood().getProductCode();
            BigDecimal orderedQty = safeQuantity(line.getOrderedQuantity() != null ? line.getOrderedQuantity() : line.getQuantity());
            slipQuantities.merge(productCode, orderedQty, BigDecimal::add);
        }

        for (SalesOrderItem item : order.getItems()) {
            BigDecimal slipQty = slipQuantities.get(item.getProductCode());
            if (slipQty == null || slipQty.compareTo(safeQuantity(item.getQuantity())) != 0) {
                return false;
            }
            slipQuantities.remove(item.getProductCode());
        }
        return slipQuantities.isEmpty();
    }

    private List<InventoryReservation> rebuildReservationsFromSlip(PackagingSlip slip, Long salesOrderId) {
        if (slip.getLines() == null || slip.getLines().isEmpty()) {
            throw new IllegalStateException("No packaging slip lines available to rebuild reservations for order " + salesOrderId);
        }
        List<InventoryReservation> rebuilt = new ArrayList<>();
        Map<Long, FinishedGood> touchedGoods = new HashMap<>();
        Map<Long, FinishedGoodBatch> touchedBatches = new HashMap<>();
        for (PackagingSlipLine line : slip.getLines()) {
            FinishedGoodBatch batch = line.getFinishedGoodBatch();
            if (batch == null || batch.getFinishedGood() == null) {
                throw new IllegalStateException("Cannot rebuild reservation without a finished good batch");
            }
            FinishedGood fg = batch.getFinishedGood();
            if (fg.getId() != null) {
                touchedGoods.computeIfAbsent(fg.getId(), id -> lockFinishedGood(slip.getCompany(), id));
            }
            if (batch.getId() != null) {
                touchedBatches.computeIfAbsent(batch.getId(), id -> finishedGoodBatchRepository.lockById(id).orElse(batch));
            }
            InventoryReservation reservation = new InventoryReservation();
            reservation.setFinishedGood(batch.getFinishedGood());
            reservation.setFinishedGoodBatch(batch);
            reservation.setReferenceType(InventoryReference.SALES_ORDER);
            reservation.setReferenceId(salesOrderId.toString());
            BigDecimal qty = safeQuantity(line.getQuantity());
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
        }
        if (!touchedBatches.isEmpty()) {
            for (FinishedGoodBatch batch : touchedBatches.values()) {
                BigDecimal reservedForBatch = inventoryReservationRepository.findByFinishedGoodBatch(batch).stream()
                        .map(this::resolveReservedQuantity)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                BigDecimal total = safeQuantity(batch.getQuantityTotal());
                batch.setQuantityAvailable(total.subtract(reservedForBatch).max(BigDecimal.ZERO));
            }
            finishedGoodBatchRepository.saveAll(touchedBatches.values());
        }
        return saved;
    }

    private List<FinishedGoodBatch> selectBatchesByCostingMethod(FinishedGood finishedGood) {
        return switch (CostingMethodUtils.resolveFinishedGoodBatchSelectionMethod(finishedGood.getCostingMethod())) {
            case WAC -> finishedGoodBatchRepository.findAllocatableBatches(finishedGood);
            case LIFO -> finishedGoodBatchRepository.findAllocatableBatchesLIFO(finishedGood);
            case FIFO -> finishedGoodBatchRepository.findAllocatableBatchesFIFO(finishedGood);
        };
    }

    private FinishedGood lockFinishedGood(Long id) {
        Company company = companyContextService.requireCurrentCompany();
        return lockFinishedGood(company, id);
    }

    private FinishedGood lockFinishedGood(Company company, Long id) {
        return finishedGoodRepository.lockByCompanyAndId(company, id)
                .orElseThrow(() -> new IllegalArgumentException("Finished good not found"));
    }

    private FinishedGood lockFinishedGood(Company company, String productCode) {
        return finishedGoodRepository.lockByCompanyAndProductCode(company, productCode)
                .orElseThrow(() -> new IllegalArgumentException("Finished good not found for product code " + productCode));
    }

    private void recordMovement(FinishedGood finishedGood,
                                FinishedGoodBatch batch,
                                String referenceType,
                                String referenceId,
                                String movementType,
                                BigDecimal quantity,
                                BigDecimal unitCost,
                                Long packingSlipId) {
        InventoryMovement movement = new InventoryMovement();
        movement.setFinishedGood(finishedGood);
        movement.setFinishedGoodBatch(batch);
        movement.setReferenceType(referenceType);
        movement.setReferenceId(referenceId);
        movement.setPackingSlipId(packingSlipId);
        movement.setMovementType(movementType);
        movement.setQuantity(quantity);
        movement.setUnitCost(unitCost);
        InventoryMovement saved = inventoryMovementRepository.save(movement);
        publishMovementEventIfSupported(finishedGood, saved, referenceType, referenceId, movementType, quantity, unitCost);
    }

    private void publishMovementEventIfSupported(FinishedGood finishedGood,
                                                 InventoryMovement movement,
                                                 String referenceType,
                                                 String referenceId,
                                                 String movementType,
                                                 BigDecimal quantity,
                                                 BigDecimal unitCost) {
        InventoryMovementEvent.MovementType eventType = switch (movementType) {
            case "RECEIPT" -> InventoryMovementEvent.MovementType.RECEIPT;
            case "DISPATCH" -> InventoryMovementEvent.MovementType.ISSUE;
            default -> null;
        };
        if (eventType == null || finishedGood == null || movement == null) {
            return;
        }
        BigDecimal safeQty = safeQuantity(quantity);
        BigDecimal safeCost = unitCost != null ? unitCost : BigDecimal.ZERO;
        String referenceNumber;
        if (StringUtils.hasText(referenceType) && StringUtils.hasText(referenceId)) {
            referenceNumber = referenceType + "-" + referenceId;
        } else if (StringUtils.hasText(referenceId)) {
            referenceNumber = referenceId;
        } else {
            referenceNumber = referenceType;
        }
        Long relatedId = parseLongOrNull(referenceId);
        InventoryMovementEvent event = InventoryMovementEvent.builder()
                .companyId(finishedGood.getCompany() != null ? finishedGood.getCompany().getId() : null)
                .movementType(eventType)
                .inventoryType(InventoryValuationChangedEvent.InventoryType.FINISHED_GOOD)
                .itemId(finishedGood.getId())
                .itemCode(finishedGood.getProductCode())
                .itemName(finishedGood.getName())
                .quantity(safeQty)
                .unitCost(safeCost)
                .totalCost(safeQty.multiply(safeCost))
                .movementId(movement.getId())
                .referenceNumber(referenceNumber)
                .movementDate(companyClock.today(finishedGood.getCompany()))
                .relatedEntityId(relatedId)
                .relatedEntityType(referenceType)
                .build();
        eventPublisher.publishEvent(event);
    }

    private Long parseLongOrNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private BigDecimal resolveDispatchUnitCost(FinishedGood finishedGood, FinishedGoodBatch batch) {
        if (finishedGood == null) {
            return BigDecimal.ZERO;
        }
        if (CostingMethodUtils.isWeightedAverage(finishedGood.getCostingMethod())) {
            return weightedAverageCost(finishedGood);
        }
        return batch != null && batch.getUnitCost() != null ? batch.getUnitCost() : BigDecimal.ZERO;
    }

    private void requireNonZeroDispatchCost(FinishedGood finishedGood, BigDecimal unitCost, BigDecimal shippedQuantity) {
        if (finishedGood == null || shippedQuantity == null) {
            return;
        }
        if (shippedQuantity.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        BigDecimal resolved = unitCost != null ? unitCost : BigDecimal.ZERO;
        if (resolved.compareTo(BigDecimal.ZERO) != 0) {
            return;
        }
        BigDecimal current = finishedGood.getCurrentStock() != null ? finishedGood.getCurrentStock() : BigDecimal.ZERO;
        BigDecimal reserved = finishedGood.getReservedStock() != null ? finishedGood.getReservedStock() : BigDecimal.ZERO;
        BigDecimal onHand = current.max(reserved);
        if (onHand.compareTo(BigDecimal.ZERO) > 0) {
            String code = finishedGood.getProductCode() != null ? finishedGood.getProductCode() : finishedGood.getName();
            throw new IllegalStateException("Dispatch cost is zero for FG " + code + " with on-hand stock");
        }
    }

    @Transactional
    public void linkDispatchMovementsToJournal(Long packingSlipId, Long journalEntryId) {
        if (packingSlipId == null || journalEntryId == null) {
            return;
        }
        Company company = companyContextService.requireCurrentCompany();
        List<InventoryMovement> movements = inventoryMovementRepository
                .findByFinishedGood_CompanyAndPackingSlipIdAndMovementTypeIgnoreCaseOrderByCreatedAtAsc(
                        company,
                        packingSlipId,
                        "DISPATCH");
        if (movements.isEmpty()) {
            movements = findLegacyDispatchMovements(company, packingSlipId);
        }
        if (movements.isEmpty()) {
            return;
        }
        List<InventoryMovement> toUpdate = new ArrayList<>();
        for (InventoryMovement movement : movements) {
            if (!"DISPATCH".equalsIgnoreCase(movement.getMovementType())) {
                continue;
            }
            if (!Objects.equals(movement.getPackingSlipId(), packingSlipId)) {
                movement.setPackingSlipId(packingSlipId);
            }
            if (Objects.equals(movement.getJournalEntryId(), journalEntryId)) {
                continue;
            }
            movement.setJournalEntryId(journalEntryId);
            toUpdate.add(movement);
        }
        if (!toUpdate.isEmpty()) {
            inventoryMovementRepository.saveAll(toUpdate);
        }
    }

    private List<InventoryMovement> findLegacyDispatchMovements(Company company, Long packingSlipId) {
        if (company == null || packingSlipId == null) {
            return List.of();
        }
        PackagingSlip slip = packagingSlipRepository.findByIdAndCompany(packingSlipId, company).orElse(null);
        if (slip == null || slip.getSalesOrder() == null || slip.getSalesOrder().getId() == null) {
            return List.of();
        }
        Long salesOrderId = slip.getSalesOrder().getId();
        List<PackagingSlip> slipsForOrder = packagingSlipRepository.findAllByCompanyAndSalesOrderId(company, salesOrderId);
        if (slipsForOrder.size() != 1) {
            return List.of();
        }
        return inventoryMovementRepository
                .findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdAndPackingSlipIdIsNullAndMovementTypeIgnoreCaseOrderByCreatedAtAsc(
                        company,
                        InventoryReference.SALES_ORDER,
                        salesOrderId.toString(),
                        "DISPATCH");
    }

    private String resolveBatchCode(FinishedGood finishedGood, String provided, Instant manufacturedAt) {
        if (StringUtils.hasText(provided)) {
            return provided.trim();
        }
        String timezone = finishedGood.getCompany().getTimezone() == null ? "UTC" : finishedGood.getCompany().getTimezone();
        LocalDate produced = manufacturedAt != null
                ? LocalDate.ofInstant(manufacturedAt, ZoneId.of(timezone))
                : null;
        return batchNumberService.nextFinishedGoodBatchCode(finishedGood, produced);
    }

    private FinishedGoodDto toDto(FinishedGood finishedGood) {
        return new FinishedGoodDto(
                finishedGood.getId(),
                finishedGood.getPublicId(),
                finishedGood.getProductCode(),
                finishedGood.getName(),
                finishedGood.getUnit(),
                safeQuantity(finishedGood.getCurrentStock()),
                safeQuantity(finishedGood.getReservedStock()),
                finishedGood.getCostingMethod(),
                finishedGood.getValuationAccountId(),
                finishedGood.getCogsAccountId(),
                finishedGood.getRevenueAccountId(),
                finishedGood.getDiscountAccountId(),
                finishedGood.getTaxAccountId()
        );
    }

    private FinishedGoodBatchDto toBatchDto(FinishedGoodBatch batch) {
        return new FinishedGoodBatchDto(
                batch.getId(),
                batch.getPublicId(),
                batch.getBatchCode(),
                batch.getQuantityTotal(),
                batch.getQuantityAvailable(),
                batch.getUnitCost(),
                batch.getManufacturedAt(),
                batch.getExpiryDate()
        );
    }

    private PackagingSlipDto toSlipDto(PackagingSlip slip) {
        List<PackagingSlipLineDto> lines = slip.getLines().stream()
                .map(line -> {
                    FinishedGoodBatch batch = line.getFinishedGoodBatch();
                    FinishedGood fg = batch.getFinishedGood();
                    return new PackagingSlipLineDto(
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
                lines
        );
    }

    private Map<Long, FinishedGood> lockFinishedGoodsInOrder(Company company, Set<Long> ids) {
        List<Long> sortedIds = new ArrayList<>(ids);
        sortedIds.sort(Long::compareTo);
        Map<Long, FinishedGood> locked = new HashMap<>();
        for (Long id : sortedIds) {
            FinishedGood fg = finishedGoodRepository.lockByCompanyAndId(company, id)
                    .orElseThrow(() -> new IllegalArgumentException("Finished good not found: " + id));
            locked.put(id, fg);
        }
        return locked;
    }

    private String generateSlipNumber(Company company) {
        return batchNumberService.nextPackagingSlipNumber(company);
    }

    private boolean canTransitionStatus(String current, String target) {
        String from = current == null ? "PENDING" : current.trim().toUpperCase();
        String to = target == null ? "" : target.trim().toUpperCase();
        return switch (from) {
            case "PENDING" -> List.of("PENDING", "PENDING_STOCK", "PENDING_PRODUCTION", "RESERVED").contains(to);
            case "PENDING_STOCK" -> List.of("PENDING_STOCK", "PENDING_PRODUCTION", "RESERVED").contains(to);
            case "PENDING_PRODUCTION" -> List.of("PENDING_PRODUCTION", "RESERVED").contains(to);
            case "RESERVED" -> List.of("RESERVED", "PENDING_STOCK", "PENDING_PRODUCTION").contains(to);
            default -> false;
        };
    }

    private void updateSlipStatusBasedOnAvailability(PackagingSlip slip, List<InventoryShortage> shortages) {
        if (slip == null) {
            return;
        }
        String currentStatus = slip.getStatus();
        if (currentStatus != null) {
            String normalized = currentStatus.trim().toUpperCase();
            if ("DISPATCHED".equals(normalized) || "CANCELLED".equals(normalized) || "BACKORDER".equals(normalized)) {
                return;
            }
        }
        boolean hasShortage = shortages != null && !shortages.isEmpty();
        slip.setStatus(hasShortage ? "PENDING_PRODUCTION" : "RESERVED");
        packagingSlipRepository.save(slip);
        publishSlipEvent(slip, slip.getStatus(), hasShortage ? "Shortages: " + shortages.size() : "Stock reserved");
    }

    private void publishSlipEvent(PackagingSlip slip, String status, String reason) {
        if (slip == null || slip.getCompany() == null) {
            return;
        }
        eventPublisher.publishEvent(new PackagingSlipEvent(
                slip.getCompany().getId(),
                slip.getSalesOrder() != null ? slip.getSalesOrder().getId() : null,
                slip.getId(),
                status,
                reason
        ));
    }

    private void applyDefaultAccountsIfMissing(FinishedGood finishedGood) {
        boolean needsDefaults = finishedGood.getValuationAccountId() == null
                || finishedGood.getCogsAccountId() == null
                || finishedGood.getRevenueAccountId() == null
                || finishedGood.getTaxAccountId() == null;
        if (!needsDefaults) {
            return;
        }
        var defaults = companyDefaultAccountsService.requireDefaults();
        if (finishedGood.getValuationAccountId() == null) {
            finishedGood.setValuationAccountId(defaults.inventoryAccountId());
        }
        if (finishedGood.getCogsAccountId() == null) {
            finishedGood.setCogsAccountId(defaults.cogsAccountId());
        }
        if (finishedGood.getRevenueAccountId() == null) {
            finishedGood.setRevenueAccountId(defaults.revenueAccountId());
        }
        if (finishedGood.getDiscountAccountId() == null && defaults.discountAccountId() != null) {
            finishedGood.setDiscountAccountId(defaults.discountAccountId());
        }
        if (finishedGood.getTaxAccountId() == null) {
            finishedGood.setTaxAccountId(defaults.taxAccountId());
        }
    }

    private String normalizeCostingMethod(String method) {
        return CostingMethodUtils.normalizeFinishedGoodMethodOrDefault(method);
    }

    public void invalidateWeightedAverageCost(Long finishedGoodId) {
        if (finishedGoodId != null) {
            wacCache.remove(finishedGoodId);
        }
    }

    private BigDecimal stockSummaryUnitCost(FinishedGood finishedGood) {
        if (finishedGood == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal onHand = safeQuantity(finishedGood.getCurrentStock());
        if (onHand.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        CostingMethodUtils.FinishedGoodBatchSelectionMethod selectionMethod =
                CostingMethodUtils.resolveFinishedGoodBatchSelectionMethod(finishedGood.getCostingMethod());
        if (selectionMethod == CostingMethodUtils.FinishedGoodBatchSelectionMethod.WAC) {
            return weightedAverageCost(finishedGood);
        }
        List<FinishedGoodBatch> batches = new ArrayList<>(finishedGoodBatchRepository.findByFinishedGoodOrderByManufacturedAtAsc(finishedGood));
        batches.sort(Comparator
                .comparing(FinishedGoodBatch::getManufacturedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(FinishedGoodBatch::getId, Comparator.nullsLast(Comparator.naturalOrder())));
        if (selectionMethod == CostingMethodUtils.FinishedGoodBatchSelectionMethod.LIFO) {
            Collections.reverse(batches);
        }
        BigDecimal valuedStock = BigDecimal.ZERO;
        BigDecimal remaining = onHand;
        for (FinishedGoodBatch batch : batches) {
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
                break;
            }
            BigDecimal availableQuantity = batch.getQuantityAvailable() != null
                    ? batch.getQuantityAvailable()
                    : batch.getQuantityTotal();
            BigDecimal batchQuantity = safeQuantity(availableQuantity);
            if (batchQuantity.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            BigDecimal unitCost = batch.getUnitCost() != null ? batch.getUnitCost() : BigDecimal.ZERO;
            BigDecimal used = remaining.min(batchQuantity);
            valuedStock = valuedStock.add(used.multiply(unitCost));
            remaining = remaining.subtract(used);
        }
        if (valuedStock.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return valuedStock.divide(onHand, 6, RoundingMode.HALF_UP);
    }

    private BigDecimal weightedAverageCost(FinishedGood fg) {
        CachedWac cached = wacCache.get(fg.getId());
        long now = System.currentTimeMillis();
        if (cached != null && (now - cached.cachedAtMillis()) < WAC_CACHE_MILLIS) {
            return cached.cost();
        }
        BigDecimal cost = finishedGoodBatchRepository.calculateWeightedAverageCost(fg);
        if (cost == null) {
            cost = BigDecimal.ZERO;
        }
        wacCache.put(fg.getId(), new CachedWac(cost, now));
        return cost;
    }

    private record CachedWac(BigDecimal cost, long cachedAtMillis) {}

    private static class DispatchPostingBuilder {
        private final Long inventoryAccountId;
        private final Long cogsAccountId;
        private BigDecimal cost = BigDecimal.ZERO;

        private DispatchPostingBuilder(Long inventoryAccountId, Long cogsAccountId) {
            this.inventoryAccountId = inventoryAccountId;
            this.cogsAccountId = cogsAccountId;
        }

        void addCost(BigDecimal value) {
            cost = cost.add(value);
        }

        DispatchPosting build() {
            return new DispatchPosting(inventoryAccountId, cogsAccountId, cost);
        }
    }

    public record FinishedGoodAccountingProfile(String productCode,
                                                Long valuationAccountId,
                                                Long cogsAccountId,
                                                Long revenueAccountId,
                                                Long discountAccountId,
                                                Long taxAccountId) {}

    public record InventoryReservationResult(PackagingSlipDto packagingSlip,
                                             List<InventoryShortage> shortages) {}

    public record InventoryShortage(String productCode,
                                    BigDecimal shortageQuantity,
                                    String productName) {}
}
