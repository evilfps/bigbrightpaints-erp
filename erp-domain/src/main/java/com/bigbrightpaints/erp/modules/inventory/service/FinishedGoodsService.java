package com.bigbrightpaints.erp.modules.inventory.service;

import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.inventory.domain.*;
import com.bigbrightpaints.erp.modules.inventory.dto.*;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrder;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrderItem;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrderRepository;
import com.bigbrightpaints.erp.modules.accounting.service.CompanyDefaultAccountsService;
import com.bigbrightpaints.erp.modules.factory.event.PackagingSlipEvent;
import jakarta.transaction.Transactional;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class FinishedGoodsService {

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
                                CompanyClock companyClock) {
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
            fg.setCostingMethod(request.costingMethod());
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
                        weightedAverageCost(fg),
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
        finishedGood.setCostingMethod(request.costingMethod() == null ? "FIFO" : request.costingMethod());
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
        FinishedGood finishedGood = lockFinishedGood(request.finishedGoodId());
        FinishedGoodBatch batch = new FinishedGoodBatch();
        batch.setFinishedGood(finishedGood);
        batch.setBatchCode(resolveBatchCode(finishedGood, request.batchCode(), request.manufacturedAt()));
        batch.setQuantityTotal(request.quantity());
        batch.setQuantityAvailable(request.quantity());
        batch.setUnitCost(request.unitCost());
        batch.setManufacturedAt(request.manufacturedAt() == null ? Instant.now() : request.manufacturedAt());
        batch.setExpiryDate(request.expiryDate());
        FinishedGoodBatch savedBatch = finishedGoodBatchRepository.save(batch);

        finishedGood.setCurrentStock(finishedGood.getCurrentStock().add(request.quantity()));
        finishedGoodRepository.save(finishedGood);
        wacCache.remove(finishedGood.getId());

        recordMovement(finishedGood, savedBatch, InventoryReference.MANUFACTURING_ORDER, savedBatch.getPublicId().toString(),
                "RECEIPT", request.quantity(), request.unitCost());

        return toBatchDto(savedBatch);
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
        SalesOrder managedOrder = salesOrderRepository.findWithItemsByCompanyAndId(company, order.getId())
                .orElseThrow(() -> new IllegalArgumentException("Sales order not found: " + order.getId()));
        List<PackagingSlip> existingSlips = packagingSlipRepository.findAllByCompanyAndSalesOrderId(company, order.getId());
        if (existingSlips.size() > 1) {
            throw new IllegalArgumentException("Multiple packaging slips found for order; provide packingSlipId");
        }
        PackagingSlip slip = existingSlips.isEmpty()
                ? createSlip(order)
                : existingSlips.get(0);

        if ("CANCELLED".equalsIgnoreCase(slip.getStatus())) {
            slip.getLines().clear();
            slip.setStatus("PENDING");
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
                    "RELEASE", reservedQty, batch != null ? batch.getUnitCost() : BigDecimal.ZERO);

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
        List<PackagingSlip> slips = packagingSlipRepository.findAllByCompanyAndSalesOrderId(company, salesOrderId);
        if (slips.isEmpty()) {
            throw new IllegalArgumentException("Packaging slip not found for order " + salesOrderId);
        }
        if (slips.size() > 1) {
            throw new IllegalArgumentException("Multiple packaging slips found for order; provide packingSlipId");
        }
        Long slipId = slips.get(0).getId();
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
        for (var slipLine : slip.getLines()) {
            if (slipLine.getFinishedGoodBatch() != null && slipLine.getQuantity() != null) {
                slipLineQtyByBatch.merge(
                        slipLine.getFinishedGoodBatch().getId(),
                        slipLine.getQuantity(),
                        BigDecimal::add);
            }
        }

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

        Map<String, DispatchPostingBuilder> postingBuilders = new HashMap<>();
        List<FinishedGoodBatch> batchesToSave = new ArrayList<>();
        for (InventoryReservation reservation : reservations) {
            // Use slip line quantity if available (supports overrides), fallback to reservation
            BigDecimal requested;
            if (reservation.getFinishedGoodBatch() != null && 
                slipLineQtyByBatch.containsKey(reservation.getFinishedGoodBatch().getId())) {
                requested = slipLineQtyByBatch.get(reservation.getFinishedGoodBatch().getId());
            } else {
                requested = reservation.getReservedQuantity() != null ? reservation.getReservedQuantity() : reservation.getQuantity();
            }
            if (requested == null) {
                requested = BigDecimal.ZERO;
            }
            FinishedGood fg = lockedGoods.get(reservation.getFinishedGood().getId());
            FinishedGoodBatch batch = reservation.getFinishedGoodBatch();
            BigDecimal current = fg.getCurrentStock() == null ? BigDecimal.ZERO : fg.getCurrentStock();
            BigDecimal shipQty = requested;
            if (current.compareTo(shipQty) < 0) {
                shipQty = current;
            }
            // If nothing to ship, skip but keep reservation for future dispatch
            if (shipQty.compareTo(BigDecimal.ZERO) <= 0) {
                reservation.setStatus("BACKORDER");
                continue;
            }
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
            BigDecimal reserved = fg.getReservedStock() == null ? BigDecimal.ZERO : fg.getReservedStock();
            BigDecimal newReserved = reserved.subtract(shipQty).max(BigDecimal.ZERO);
            fg.setReservedStock(newReserved);
            fg.setCurrentStock(current.subtract(shipQty).max(BigDecimal.ZERO));
            if (batch != null) {
                BigDecimal qtyAvailable = batch.getQuantityAvailable() == null ? BigDecimal.ZERO : batch.getQuantityAvailable();
                BigDecimal updatedAvailable = qtyAvailable.subtract(shipQty.min(qtyAvailable)).max(BigDecimal.ZERO);
                batch.setQuantityAvailable(updatedAvailable);
                batchesToSave.add(batch);
            }
            BigDecimal unitCost = batch != null ? batch.getUnitCost() : BigDecimal.ZERO;
            recordMovement(fg, batch, InventoryReference.SALES_ORDER, salesOrderId.toString(), "DISPATCH", shipQty, unitCost);

            String postingKey = fg.getValuationAccountId() + ":" + fg.getCogsAccountId();
            postingBuilders
                    .computeIfAbsent(postingKey,
                            key -> new DispatchPostingBuilder(fg.getValuationAccountId(), fg.getCogsAccountId()))
                    .addCost(unitCost.multiply(shipQty));
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
            if (!anyPending) {
                slip.setStatus("DISPATCHED");
            } else {
                slip.setStatus("PARTIAL");
            }
            if (slip.getDispatchedAt() == null) {
                slip.setDispatchedAt(companyClock.now(slip.getCompany()));
            }
        } else {
            slip.setStatus(anyPending ? "PENDING_STOCK" : slip.getStatus());
        }
        packagingSlipRepository.save(slip);

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

        List<DispatchPreviewDto.LinePreview> linePreviews = new ArrayList<>();
        BigDecimal totalOrdered = BigDecimal.ZERO;
        BigDecimal totalAvailable = BigDecimal.ZERO;

        for (PackagingSlipLine line : slip.getLines()) {
            FinishedGoodBatch batch = line.getFinishedGoodBatch();
            FinishedGood fg = batch.getFinishedGood();
            
            BigDecimal ordered = line.getOrderedQuantity() != null ? line.getOrderedQuantity() : line.getQuantity();
            BigDecimal available = batch.getQuantityAvailable() != null ? batch.getQuantityAvailable() : BigDecimal.ZERO;
            BigDecimal suggestedShip = ordered.min(available);
            boolean hasShortage = available.compareTo(ordered) < 0;

            linePreviews.add(new DispatchPreviewDto.LinePreview(
                    line.getId(),
                    fg.getId(),
                    fg.getProductCode(),
                    fg.getName(),
                    batch.getBatchCode(),
                    ordered,
                    available,
                    suggestedShip,
                    line.getUnitCost(),
                    suggestedShip.multiply(line.getUnitCost()),
                    hasShortage
            ));

            totalOrdered = totalOrdered.add(ordered.multiply(line.getUnitCost()));
            totalAvailable = totalAvailable.add(suggestedShip.multiply(line.getUnitCost()));
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
                totalOrdered,
                totalAvailable,
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
        if (order != null) {
            List<InventoryReservation> reservations = inventoryReservationRepository
                    .findByFinishedGoodCompanyAndReferenceTypeAndReferenceId(
                            company,
                            InventoryReference.SALES_ORDER,
                            order.getId().toString());
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
            BigDecimal unitCost = batch.getUnitCost();
            if (unitCost == null) {
                unitCost = line.getUnitCost();
            }
            if (unitCost == null) {
                unitCost = BigDecimal.ZERO;
            }
            line.setUnitCost(unitCost);

            // Update inventory if actually shipping
            if (shipped.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal currentStock = fg.getCurrentStock() != null ? fg.getCurrentStock() : BigDecimal.ZERO;
                if (currentStock.compareTo(shipped) < 0) {
                    throw new IllegalStateException("Insufficient current stock for FG " + fg.getProductCode() + ": available=" + currentStock + ", requested=" + shipped);
                }
                BigDecimal reservedStock = fg.getReservedStock() != null ? fg.getReservedStock() : BigDecimal.ZERO;

                fg.setCurrentStock(currentStock.subtract(shipped));
                fg.setReservedStock(reservedStock.subtract(shipped).max(BigDecimal.ZERO));
                wacCache.remove(fg.getId());

                BigDecimal batchQty = batch.getQuantityTotal() != null ? batch.getQuantityTotal() : BigDecimal.ZERO;
                batch.setQuantityTotal(batchQty.subtract(shipped).max(BigDecimal.ZERO));
                batchesToSave.add(batch);

                totalCogsCost = totalCogsCost.add(shipped.multiply(unitCost));

                recordMovement(fg, batch, InventoryReference.SALES_ORDER, order.getId().toString(), 
                        "DISPATCH", shipped, unitCost);
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
        // Update slip status (always dispatched; backorders are tracked on a new slip)
        slip.setStatus("DISPATCHED");
        Instant now = companyClock.now(company);
        slip.setDispatchedAt(now);
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
        if (hasBackorder) {
            backorderSlipId = createBackorderSlip(slip, lineResults);
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

        Long backorderSlipId = null;
        if (hasBackorder && slip.getSalesOrder() != null) {
            backorderSlipId = packagingSlipRepository.findAllByCompanyAndSalesOrderId(company, slip.getSalesOrder().getId()).stream()
                    .filter(existing -> "BACKORDER".equalsIgnoreCase(existing.getStatus()))
                    .findFirst()
                    .map(PackagingSlip::getId)
                    .orElse(null);
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

    /**
     * Create a backorder slip for items that couldn't be shipped.
     */
    private Long createBackorderSlip(PackagingSlip originalSlip, List<DispatchConfirmationResponse.LineResult> lineResults) {
        List<DispatchConfirmationResponse.LineResult> backorderLines = lineResults.stream()
                .filter(l -> l.backorderQuantity().compareTo(BigDecimal.ZERO) > 0)
                .toList();

        if (backorderLines.isEmpty()) {
            return null;
        }

        PackagingSlip backorderSlip = new PackagingSlip();
        backorderSlip.setCompany(originalSlip.getCompany());
        backorderSlip.setSalesOrder(originalSlip.getSalesOrder());
        backorderSlip.setSlipNumber(generateSlipNumber(originalSlip.getCompany()) + "-BO");
        backorderSlip.setStatus("BACKORDER");
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

        PackagingSlip saved = packagingSlipRepository.saveAndFlush(backorderSlip);
        return saved.getId();
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
        // First try to find an existing slip
        List<PackagingSlip> slips = packagingSlipRepository.findAllByCompanyAndSalesOrderId(company, salesOrderId);
        if (!slips.isEmpty()) {
            if (slips.size() > 1) {
                throw new IllegalArgumentException("Multiple packaging slips found for order; provide packingSlipId");
            }
            Long slipId = slips.get(0).getId();
            PackagingSlip slip = packagingSlipRepository.findByIdAndCompany(slipId, company)
                    .orElseThrow(() -> new IllegalArgumentException("Packaging slip not found"));
            return toSlipDto(slip);
        }

        // If no slip exists yet (e.g. legacy order or reservation failed earlier),
        // lazily create one by running the reservation workflow for this order.
        SalesOrder order = salesOrderRepository.findByCompanyAndId(company, salesOrderId)
                .orElseThrow(() -> new IllegalArgumentException("Sales order not found: " + salesOrderId));

        InventoryReservationResult result = reserveForOrder(order);
        if (result.packagingSlip() == null) {
            throw new IllegalStateException("Unable to create packaging slip for order " + salesOrderId);
        }
        return result.packagingSlip();
    }

    /**
     * Update packaging slip status (e.g., PENDING -> PACKING -> READY).
     */
    @Transactional
    public PackagingSlipDto updateSlipStatus(Long slipId, String newStatus) {
        Company company = companyContextService.requireCurrentCompany();
        PackagingSlip slip = packagingSlipRepository.findByIdAndCompany(slipId, company)
                .orElseThrow(() -> new IllegalArgumentException("Packaging slip not found"));
        
        if ("DISPATCHED".equalsIgnoreCase(slip.getStatus())) {
            throw new IllegalStateException("Cannot update status of dispatched slip");
        }
        
        slip.setStatus(newStatus.toUpperCase());
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
        if (!"BACKORDER".equalsIgnoreCase(slip.getStatus())) {
            throw new IllegalStateException("Only BACKORDER slips can be canceled");
        }

        Map<Long, FinishedGood> lockedGoods = new HashMap<>();
        List<FinishedGoodBatch> batchesToSave = new ArrayList<>();
        for (PackagingSlipLine line : slip.getLines()) {
            FinishedGoodBatch batch = line.getFinishedGoodBatch();
            FinishedGood fg = batch.getFinishedGood();
            lockedGoods.computeIfAbsent(fg.getId(), id -> lockFinishedGood(company, id));

            BigDecimal qty = line.getOrderedQuantity() != null ? line.getOrderedQuantity() : line.getQuantity();
            if (qty == null) {
                qty = BigDecimal.ZERO;
            }
            // Release reserved stock and batch availability for the unshipped backorder quantity
            BigDecimal reserved = fg.getReservedStock() != null ? fg.getReservedStock() : BigDecimal.ZERO;
            fg.setReservedStock(reserved.subtract(qty).max(BigDecimal.ZERO));

            BigDecimal available = batch.getQuantityAvailable() != null ? batch.getQuantityAvailable() : BigDecimal.ZERO;
            batch.setQuantityAvailable(available.add(qty));
            batchesToSave.add(batch);
        }
        if (!lockedGoods.isEmpty()) {
            finishedGoodRepository.saveAll(lockedGoods.values());
        }
        if (!batchesToSave.isEmpty()) {
            finishedGoodBatchRepository.saveAll(batchesToSave);
        }

        slip.setStatus("CANCELLED");
        slip.setDispatchNotes(reason != null ? reason : "Backorder canceled by " + (username != null ? username : "system"));
        slip.setConfirmedAt(Instant.now());
        slip.setConfirmedBy(username != null ? username : "system");
        return toSlipDto(packagingSlipRepository.save(slip));
    }

    private PackagingSlip createSlip(SalesOrder order) {
        Company company = companyContextService.requireCurrentCompany();
        PackagingSlip slip = new PackagingSlip();
        slip.setCompany(company);
        slip.setSalesOrder(order);
        slip.setSlipNumber(generateSlipNumber(company));
        slip.setStatus("PENDING");
        return packagingSlipRepository.save(slip);
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

            recordMovement(finishedGood, batch, InventoryReference.SALES_ORDER, order.getId().toString(), "RESERVE", allocation, batch.getUnitCost());
            remaining = remaining.subtract(allocation);
        }

        if (remaining.compareTo(BigDecimal.ZERO) > 0) {
            shortages.add(new InventoryShortage(finishedGood.getProductCode(), remaining, finishedGood.getName()));
        }
    }

    private BigDecimal safeQuantity(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
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
        for (PackagingSlipLine line : slip.getLines()) {
            FinishedGoodBatch batch = line.getFinishedGoodBatch();
            if (batch == null || batch.getFinishedGood() == null) {
                throw new IllegalStateException("Cannot rebuild reservation without a finished good batch");
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
        return inventoryReservationRepository.saveAll(rebuilt);
    }

    private List<FinishedGoodBatch> selectBatchesByCostingMethod(FinishedGood finishedGood) {
        String method = finishedGood.getCostingMethod() == null ? "FIFO" : finishedGood.getCostingMethod().trim().toUpperCase();
        return switch (method) {
            case "LIFO" -> finishedGoodBatchRepository.findAllocatableBatchesLIFO(finishedGood);
            default -> finishedGoodBatchRepository.findAllocatableBatchesFIFO(finishedGood);
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
                                BigDecimal unitCost) {
        InventoryMovement movement = new InventoryMovement();
        movement.setFinishedGood(finishedGood);
        movement.setFinishedGoodBatch(batch);
        movement.setReferenceType(referenceType);
        movement.setReferenceId(referenceId);
        movement.setMovementType(movementType);
        movement.setQuantity(quantity);
        movement.setUnitCost(unitCost);
        inventoryMovementRepository.save(movement);
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
                            line.getUnitCost(),
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
        return company.getCode() + "-PS-" + batchNumberService.nextPackagingSlipNumber(company);
    }

    private void updateSlipStatusBasedOnAvailability(PackagingSlip slip, List<InventoryShortage> shortages) {
        if (slip == null) {
            return;
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
