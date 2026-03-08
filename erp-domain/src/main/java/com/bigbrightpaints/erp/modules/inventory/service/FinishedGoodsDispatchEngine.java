package com.bigbrightpaints.erp.modules.inventory.service;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.CompanyClock;
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
import com.bigbrightpaints.erp.modules.inventory.dto.DispatchConfirmationRequest;
import com.bigbrightpaints.erp.modules.inventory.dto.DispatchConfirmationResponse;
import com.bigbrightpaints.erp.modules.inventory.dto.DispatchPreviewDto;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrder;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrderItem;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrderRepository;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class FinishedGoodsDispatchEngine {

    private final CompanyContextService companyContextService;
    private final FinishedGoodRepository finishedGoodRepository;
    private final FinishedGoodBatchRepository finishedGoodBatchRepository;
    private final PackagingSlipRepository packagingSlipRepository;
    private final InventoryMovementRepository inventoryMovementRepository;
    private final InventoryReservationRepository inventoryReservationRepository;
    private final SalesOrderRepository salesOrderRepository;
    private final GstService gstService;
    private final CompanyClock companyClock;
    private final InventoryMovementRecorder movementRecorder;
    private final FinishedGoodsReservationEngine reservationEngine;
    private final PackagingSlipService packagingSlipService;
    private final InventoryValuationService inventoryValuationService;

    public FinishedGoodsDispatchEngine(CompanyContextService companyContextService,
                                       FinishedGoodRepository finishedGoodRepository,
                                       FinishedGoodBatchRepository finishedGoodBatchRepository,
                                       PackagingSlipRepository packagingSlipRepository,
                                       InventoryMovementRepository inventoryMovementRepository,
                                       InventoryReservationRepository inventoryReservationRepository,
                                       SalesOrderRepository salesOrderRepository,
                                       GstService gstService,
                                       CompanyClock companyClock,
                                       InventoryMovementRecorder movementRecorder,
                                       FinishedGoodsReservationEngine reservationEngine,
                                       PackagingSlipService packagingSlipService,
                                       InventoryValuationService inventoryValuationService) {
        this.companyContextService = companyContextService;
        this.finishedGoodRepository = finishedGoodRepository;
        this.finishedGoodBatchRepository = finishedGoodBatchRepository;
        this.packagingSlipRepository = packagingSlipRepository;
        this.inventoryMovementRepository = inventoryMovementRepository;
        this.inventoryReservationRepository = inventoryReservationRepository;
        this.salesOrderRepository = salesOrderRepository;
        this.gstService = gstService;
        this.companyClock = companyClock;
        this.movementRecorder = movementRecorder;
        this.reservationEngine = reservationEngine;
        this.packagingSlipService = packagingSlipService;
        this.inventoryValuationService = inventoryValuationService;
    }

    @Transactional
    public List<FinishedGoodsService.DispatchPosting> markSlipDispatched(Long salesOrderId) {
        Company company = companyContextService.requireCurrentCompany();
        List<PackagingSlip> slips = packagingSlipRepository.findPrimarySlipsByOrderId(company, salesOrderId);
        if (slips.isEmpty()) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils
                    .invalidInput("Packaging slip not found for order " + salesOrderId);
        }
        if (slips.size() > 1) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "Multiple packaging slips found for order " + salesOrderId + "; provide packagingSlipId");
        }
        PackagingSlip selected = slips.get(0);
        PackagingSlip slip = packagingSlipRepository.findAndLockByIdAndCompany(selected.getId(), company)
                .orElseThrow(() -> com.bigbrightpaints.erp.core.validation.ValidationUtils
                        .invalidInput("Packaging slip not found for order " + salesOrderId));
        return markSlipDispatched(salesOrderId, slip);
    }

    @Transactional
    public List<FinishedGoodsService.DispatchPosting> markSlipDispatched(Long salesOrderId, PackagingSlip slip) {
        if ("DISPATCHED".equalsIgnoreCase(slip.getStatus())) {
            return List.of();
        }

        Map<Long, BigDecimal> slipLineQtyByBatch = new HashMap<>();
        Map<Long, List<PackagingSlipLine>> slipLinesByBatch = new HashMap<>();
        Map<PackagingSlipLine, BigDecimal> remainingByLine = new HashMap<>();
        Map<Long, BigDecimal> remainingByBatch = new HashMap<>();

        for (PackagingSlipLine slipLine : slip.getLines()) {
            FinishedGoodBatch batch = slipLine.getFinishedGoodBatch();
            if (batch != null && slipLine.getQuantity() != null) {
                slipLineQtyByBatch.merge(batch.getId(), slipLine.getQuantity(), BigDecimal::add);
                slipLinesByBatch.computeIfAbsent(batch.getId(), ignored -> new ArrayList<>()).add(slipLine);
            }
            BigDecimal ordered = slipLine.getOrderedQuantity() != null ? slipLine.getOrderedQuantity() : slipLine.getQuantity();
            if (ordered == null) {
                ordered = BigDecimal.ZERO;
            }
            BigDecimal shippedSoFar = inventoryValuationService.safeQuantity(slipLine.getShippedQuantity());
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
            reservations = reservationEngine.rebuildReservationsFromSlip(slip, salesOrderId);
            if (reservations.isEmpty()) {
                throw com.bigbrightpaints.erp.core.validation.ValidationUtils
                        .invalidState("No reservations found for order " + salesOrderId);
            }
        }

        Map<Long, FinishedGood> lockedGoods = lockFinishedGoodsInOrder(
                slip.getCompany(),
                reservations.stream()
                        .map(r -> r.getFinishedGood().getId())
                        .collect(Collectors.toSet()));

        lockedGoods.keySet().forEach(inventoryValuationService::invalidateWeightedAverageCost);

        Map<String, DispatchPostingBuilder> postingBuilders = new HashMap<>();
        List<FinishedGoodBatch> batchesToSave = new ArrayList<>();

        for (InventoryReservation reservation : reservations) {
            FinishedGoodBatch batch = reservation.getFinishedGoodBatch();
            Long batchId = batch != null ? batch.getId() : null;
            if (batchId == null || !remainingByBatch.containsKey(batchId)) {
                continue;
            }

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

            if (shipQty.compareTo(BigDecimal.ZERO) <= 0) {
                reservation.setStatus("BACKORDER");
                continue;
            }

            BigDecimal current = inventoryValuationService.safeQuantity(fg.getCurrentStock());
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
                BigDecimal remaining = requested.subtract(shipQty);
                reservation.setReservedQuantity(remaining.max(BigDecimal.ZERO));
            }

            if (fg.getValuationAccountId() == null || fg.getCogsAccountId() == null) {
                throw com.bigbrightpaints.erp.core.validation.ValidationUtils
                        .invalidState("Finished good " + fg.getProductCode() + " missing accounting configuration");
            }

            BigDecimal unitCost = inventoryValuationService.resolveDispatchUnitCost(
                    fg,
                    batch,
                    companyClock.today(slip.getCompany()));
            inventoryValuationService.requireNonZeroDispatchCost(fg, unitCost, shipQty);

            BigDecimal reserved = inventoryValuationService.safeQuantity(fg.getReservedStock());
            requireSufficientQuantity(reserved, shipQty,
                    "Reserved stock insufficient for FG " + fg.getProductCode());
            fg.setReservedStock(reserved.subtract(shipQty));
            fg.setCurrentStock(current.subtract(shipQty));

            if (batch != null) {
                BigDecimal batchTotal = inventoryValuationService.safeQuantity(batch.getQuantityTotal());
                requireSufficientQuantity(batchTotal, shipQty,
                        "Batch stock insufficient for batch " + batch.getBatchCode()
                                + " FG " + fg.getProductCode());
                batch.setQuantityTotal(batchTotal.subtract(shipQty));
                batchesToSave.add(batch);
            }

            movementRecorder.recordFinishedGoodMovement(
                    fg,
                    batch,
                    InventoryReference.SALES_ORDER,
                    salesOrderId.toString(),
                    "DISPATCH",
                    shipQty,
                    unitCost,
                    slip.getId());

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
                        BigDecimal shippedSoFar = inventoryValuationService.safeQuantity(line.getShippedQuantity());
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
        lockedGoods.keySet().forEach(inventoryValuationService::invalidateWeightedAverageCost);
        if (!batchesToSave.isEmpty()) {
            finishedGoodBatchRepository.saveAll(batchesToSave);
        }
        inventoryReservationRepository.saveAll(reservations);

        boolean anyShipped = reservations.stream().anyMatch(r ->
                r.getFulfilledQuantity() != null && r.getFulfilledQuantity().compareTo(BigDecimal.ZERO) > 0);
        boolean anyPending = reservations.stream().anyMatch(r ->
                !"FULFILLED".equalsIgnoreCase(r.getStatus()) && !"CANCELLED".equalsIgnoreCase(r.getStatus()));

        if (anyShipped) {
            slip.setStatus("DISPATCHED");
            if (slip.getDispatchedAt() == null) {
                slip.setDispatchedAt(companyClock.now(slip.getCompany()));
            }
        } else if (anyPending) {
            slip.setStatus("PENDING_STOCK");
        }

        for (PackagingSlipLine line : slip.getLines()) {
            BigDecimal ordered = line.getOrderedQuantity() != null ? line.getOrderedQuantity() : line.getQuantity();
            BigDecimal shipped = inventoryValuationService.safeQuantity(line.getShippedQuantity());
            if (ordered == null) {
                ordered = BigDecimal.ZERO;
            }
            line.setBackorderQuantity(ordered.subtract(shipped).max(BigDecimal.ZERO));
        }
        packagingSlipRepository.save(slip);

        boolean hasBackorder = slip.getLines().stream()
                .map(line -> inventoryValuationService.safeQuantity(line.getBackorderQuantity()))
                .anyMatch(qty -> qty.compareTo(BigDecimal.ZERO) > 0);
        if (hasBackorder) {
            packagingSlipService.createBackorderSlip(slip);
        }

        return postingBuilders.values().stream()
                .map(DispatchPostingBuilder::build)
                .toList();
    }

    @Transactional
    public DispatchPreviewDto getDispatchPreview(Long packagingSlipId) {
        Company company = companyContextService.requireCurrentCompany();
        PackagingSlip slip = packagingSlipRepository.findByIdAndCompany(packagingSlipId, company)
                .orElseThrow(() -> com.bigbrightpaints.erp.core.validation.ValidationUtils
                        .invalidInput("Packaging slip not found"));

        if ("DISPATCHED".equalsIgnoreCase(slip.getStatus())) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidState("Slip already dispatched");
        }

        SalesOrder order = slip.getSalesOrder();
        if (order == null || order.getId() == null) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE,
                    "Packaging slip is not linked to a valid sales order");
        }

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

        Map<String, Deque<SalesOrderItem>> orderItemsByProductCode = new HashMap<>();
        if (order.getItems() != null) {
            List<SalesOrderItem> sortedItems = new ArrayList<>(order.getItems());
            sortedItems.sort(Comparator.comparing(SalesOrderItem::getId, Comparator.nullsLast(Long::compareTo)));
            for (SalesOrderItem item : sortedItems) {
                if (item.getProductCode() == null) {
                    continue;
                }
                orderItemsByProductCode
                        .computeIfAbsent(item.getProductCode(), ignored -> new ArrayDeque<>())
                        .add(item);
            }
        }

        Dealer dealer = order.getDealer();
        String companyStateCode = inventoryValuationService.normalizeStateCode(company != null ? company.getStateCode() : null);
        String dealerStateCode = inventoryValuationService.normalizeStateCode(dealer != null ? dealer.getStateCode() : null);

        List<DispatchPreviewDto.LinePreview> linePreviews = new ArrayList<>();
        BigDecimal totalOrdered = BigDecimal.ZERO;
        BigDecimal totalAvailable = BigDecimal.ZERO;
        BigDecimal gstTaxable = BigDecimal.ZERO;
        BigDecimal totalCgst = BigDecimal.ZERO;
        BigDecimal totalSgst = BigDecimal.ZERO;
        BigDecimal totalIgst = BigDecimal.ZERO;

        for (PackagingSlipLine line : slip.getLines()) {
            FinishedGoodBatch batch = line.getFinishedGoodBatch();
            FinishedGood fg = batch.getFinishedGood();

            BigDecimal ordered = inventoryValuationService.safeQuantity(
                    line.getOrderedQuantity() != null ? line.getOrderedQuantity() : line.getQuantity());
            BigDecimal available = inventoryValuationService.safeQuantity(batch.getQuantityAvailable());
            BigDecimal reservedForOrder = reservedByBatch.getOrDefault(batch.getId(), BigDecimal.ZERO);
            available = available.add(reservedForOrder);
            BigDecimal suggestedShip = ordered.min(available);
            boolean hasShortage = available.compareTo(ordered) < 0;

            SalesOrderItem matchedItem = pollMatchingOrderItem(orderItemsByProductCode, fg.getProductCode());
            BigDecimal unitPrice = matchedItem != null && matchedItem.getUnitPrice() != null
                    ? matchedItem.getUnitPrice()
                    : BigDecimal.ZERO;
            BigDecimal lineSubtotal = inventoryValuationService.currency(unitPrice.multiply(suggestedShip));
            BigDecimal lineRate = matchedItem != null
                    ? inventoryValuationService.safePercent(matchedItem.getGstRate())
                    : BigDecimal.ZERO;

            GstService.GstBreakdown lineBreakdown = lineRate.compareTo(BigDecimal.ZERO) > 0
                    ? gstService.splitTaxAmount(
                            lineSubtotal,
                            inventoryValuationService.currency(
                                    lineSubtotal.multiply(lineRate).divide(new BigDecimal("100"), 4, RoundingMode.HALF_UP)),
                            companyStateCode,
                            dealerStateCode)
                    : gstService.splitTaxAmount(lineSubtotal, BigDecimal.ZERO, companyStateCode, dealerStateCode);

            BigDecimal lineTax = inventoryValuationService.safeQuantity(lineBreakdown.cgst())
                    .add(inventoryValuationService.safeQuantity(lineBreakdown.sgst()))
                    .add(inventoryValuationService.safeQuantity(lineBreakdown.igst()));
            BigDecimal lineTotal = lineSubtotal.add(lineTax);

            linePreviews.add(new DispatchPreviewDto.LinePreview(
                    line.getId(),
                    fg.getId(),
                    fg.getProductCode(),
                    fg.getName(),
                    batch.getBatchCode(),
                    ordered,
                    available,
                    suggestedShip,
                    unitPrice,
                    lineSubtotal,
                    lineTax,
                    lineTotal,
                    hasShortage
            ));

            totalOrdered = totalOrdered.add(inventoryValuationService.currency(unitPrice.multiply(ordered)));
            totalAvailable = totalAvailable.add(lineTotal);
            gstTaxable = gstTaxable.add(lineSubtotal);
            totalCgst = totalCgst.add(inventoryValuationService.safeQuantity(lineBreakdown.cgst()));
            totalSgst = totalSgst.add(inventoryValuationService.safeQuantity(lineBreakdown.sgst()));
            totalIgst = totalIgst.add(inventoryValuationService.safeQuantity(lineBreakdown.igst()));
        }

        DispatchPreviewDto.GstBreakdown breakdown = new DispatchPreviewDto.GstBreakdown(
                gstTaxable,
                totalCgst,
                totalSgst,
                totalIgst,
                totalCgst.add(totalSgst).add(totalIgst),
                totalAvailable
        );

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
                breakdown,
                linePreviews
        );
    }

    @Transactional
    public DispatchConfirmationResponse confirmDispatch(DispatchConfirmationRequest request, String username) {
        Company company = companyContextService.requireCurrentCompany();
        PackagingSlip slip = packagingSlipRepository.findAndLockByIdAndCompany(request.packagingSlipId(), company)
                .orElseThrow(() -> com.bigbrightpaints.erp.core.validation.ValidationUtils
                        .invalidInput("Packaging slip not found"));

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
                reservations = reservationEngine.rebuildReservationsFromSlip(slip, order.getId());
            }
            for (InventoryReservation reservation : reservations) {
                if ("CANCELLED".equalsIgnoreCase(reservation.getStatus())) {
                    continue;
                }
                FinishedGoodBatch batch = reservation.getFinishedGoodBatch();
                if (batch == null || batch.getId() == null) {
                    continue;
                }
                BigDecimal reservedQty = reservationEngine.resolveReservedQuantity(reservation);
                reservation.setReservedQuantity(reservedQty);
                if (reservation.getFulfilledQuantity() == null) {
                    reservation.setFulfilledQuantity(BigDecimal.ZERO);
                }
                reservationsByBatch.computeIfAbsent(batch.getId(), k -> new ArrayList<>()).add(reservation);
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
                throw com.bigbrightpaints.erp.core.validation.ValidationUtils
                        .invalidInput("Missing confirmation for line " + line.getId());
            }

            FinishedGoodBatch batch = line.getFinishedGoodBatch();
            FinishedGood originalFg = batch.getFinishedGood();
            if (!lockedGoods.containsKey(originalFg.getId())) {
                lockedGoods.put(originalFg.getId(), finishedGoodRepository
                        .lockByCompanyAndId(company, originalFg.getId())
                        .orElseThrow(() -> com.bigbrightpaints.erp.core.validation.ValidationUtils
                                .invalidInput("Finished good not found: " + originalFg.getId())));
            }
            FinishedGood fg = lockedGoods.get(originalFg.getId());

            BigDecimal ordered = line.getOrderedQuantity() != null ? line.getOrderedQuantity() : line.getQuantity();
            BigDecimal shipped = conf.shippedQuantity();
            BigDecimal backorder = ordered.subtract(shipped).max(BigDecimal.ZERO);

            if (shipped.compareTo(BigDecimal.ZERO) < 0) {
                throw com.bigbrightpaints.erp.core.validation.ValidationUtils
                        .invalidInput("Shipped quantity cannot be negative");
            }
            if (shipped.compareTo(ordered) > 0) {
                throw com.bigbrightpaints.erp.core.validation.ValidationUtils
                        .invalidInput("Shipped quantity cannot exceed ordered quantity");
            }

            line.setShippedQuantity(shipped);
            line.setBackorderQuantity(backorder);
            line.setNotes(conf.notes());

            inventoryValuationService.invalidateWeightedAverageCost(fg.getId());
            BigDecimal unitCost = inventoryValuationService.resolveDispatchUnitCost(
                    fg,
                    batch,
                    companyClock.today(company));
            inventoryValuationService.requireNonZeroDispatchCost(fg, unitCost, shipped);
            line.setUnitCost(unitCost);

            if (shipped.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal currentStock = inventoryValuationService.safeQuantity(fg.getCurrentStock());
                if (currentStock.compareTo(shipped) < 0) {
                    throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidState(
                            "Insufficient current stock for FG " + fg.getProductCode()
                                    + ": available=" + currentStock + ", requested=" + shipped);
                }

                if (batch.getId() != null) {
                    BigDecimal batchReserved = reservedByBatch.getOrDefault(batch.getId(), BigDecimal.ZERO);
                    requireSufficientQuantity(batchReserved, shipped,
                            "Reserved quantity insufficient for batch " + batch.getBatchCode()
                                    + " FG " + fg.getProductCode());
                    reservedByBatch.put(batch.getId(), batchReserved.subtract(shipped).max(BigDecimal.ZERO));
                }

                BigDecimal reservedStock = inventoryValuationService.safeQuantity(fg.getReservedStock());
                requireSufficientQuantity(reservedStock, shipped,
                        "Reserved stock insufficient for FG " + fg.getProductCode());

                fg.setCurrentStock(currentStock.subtract(shipped));
                fg.setReservedStock(reservedStock.subtract(shipped));
                inventoryValuationService.invalidateWeightedAverageCost(fg.getId());

                BigDecimal batchQty = inventoryValuationService.safeQuantity(batch.getQuantityTotal());
                requireSufficientQuantity(batchQty, shipped,
                        "Batch stock insufficient for batch " + batch.getBatchCode() + " FG " + fg.getProductCode());
                batch.setQuantityTotal(batchQty.subtract(shipped));
                batchesToSave.add(batch);

                movementRecorder.recordFinishedGoodMovement(
                        fg,
                        batch,
                        InventoryReference.SALES_ORDER,
                        order.getId().toString(),
                        "DISPATCH",
                        shipped,
                        unitCost,
                        slip.getId());
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

        finishedGoodRepository.saveAll(lockedGoods.values());
        if (!batchesToSave.isEmpty()) {
            finishedGoodBatchRepository.saveAll(batchesToSave);
        }

        boolean hasBackorder = totalBackorder.compareTo(BigDecimal.ZERO) > 0;
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
        applyDispatchLogistics(slip, request);
        packagingSlipRepository.save(slip);

        if (!reservationsToUpdate.isEmpty()) {
            updateReservationStatuses(reservationsToUpdate);
            inventoryReservationRepository.saveAll(reservationsToUpdate);
        }

        Long backorderSlipId = null;
        if (hasBackorder && anyShipped) {
            backorderSlipId = packagingSlipService.createBackorderSlip(slip);
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
                backorderSlipId,
                slip.getTransporterName(),
                slip.getDriverName(),
                slip.getVehicleNumber(),
                slip.getChallanReference(),
                DispatchArtifactPaths.deliveryChallanNumber(slip.getSlipNumber()),
                DispatchArtifactPaths.deliveryChallanPdfPath(slip.getId())
        );
    }

    @Transactional
    public DispatchConfirmationResponse getDispatchConfirmation(Long packagingSlipId) {
        Company company = companyContextService.requireCurrentCompany();
        PackagingSlip slip = packagingSlipRepository.findByIdAndCompany(packagingSlipId, company)
                .orElseThrow(() -> com.bigbrightpaints.erp.core.validation.ValidationUtils
                        .invalidInput("Packaging slip not found"));
        return buildDispatchConfirmationResponse(slip, company);
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
            if (!java.util.Objects.equals(movement.getPackingSlipId(), packingSlipId)) {
                movement.setPackingSlipId(packingSlipId);
            }
            if (java.util.Objects.equals(movement.getJournalEntryId(), journalEntryId)) {
                continue;
            }
            movement.setJournalEntryId(journalEntryId);
            toUpdate.add(movement);
        }
        if (!toUpdate.isEmpty()) {
            inventoryMovementRepository.saveAll(toUpdate);
        }
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
            BigDecimal reservedQty = reservationEngine.resolveReservedQuantity(reservation);
            if (reservedQty.compareTo(BigDecimal.ZERO) <= 0) {
                reservation.setReservedQuantity(BigDecimal.ZERO);
                continue;
            }
            BigDecimal applied = remaining.min(reservedQty);
            reservation.setReservedQuantity(reservedQty.subtract(applied).max(BigDecimal.ZERO));
            BigDecimal fulfilled = inventoryValuationService.safeQuantity(reservation.getFulfilledQuantity());
            reservation.setFulfilledQuantity(fulfilled.add(applied));
            remaining = remaining.subtract(applied);
        }
    }

    private void updateReservationStatuses(List<InventoryReservation> reservations) {
        for (InventoryReservation reservation : reservations) {
            if ("CANCELLED".equalsIgnoreCase(reservation.getStatus())) {
                continue;
            }
            BigDecimal reservedQty = inventoryValuationService.safeQuantity(reservation.getReservedQuantity());
            BigDecimal fulfilledQty = inventoryValuationService.safeQuantity(reservation.getFulfilledQuantity());
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
            BigDecimal shippedQty = line.getShippedQuantity() != null ? line.getShippedQuantity() : line.getQuantity();
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

        Long backorderSlipId = packagingSlipService.resolveBackorderSlipIdForResponse(slip, company, hasBackorder);

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
                backorderSlipId,
                slip.getTransporterName(),
                slip.getDriverName(),
                slip.getVehicleNumber(),
                slip.getChallanReference(),
                DispatchArtifactPaths.deliveryChallanNumber(slip.getSlipNumber()),
                DispatchArtifactPaths.deliveryChallanPdfPath(slip.getId())
        );
    }

    private void applyDispatchLogistics(PackagingSlip slip, DispatchConfirmationRequest request) {
        if (slip == null || request == null) {
            return;
        }
        slip.setTransporterName(trimToNull(request.transporterName()));
        slip.setDriverName(trimToNull(request.driverName()));
        slip.setVehicleNumber(trimToNull(request.vehicleNumber()));
        slip.setChallanReference(trimToNull(request.challanReference()));
    }

    private String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
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

    private Map<Long, FinishedGood> lockFinishedGoodsInOrder(Company company, Set<Long> ids) {
        List<Long> sortedIds = new ArrayList<>(ids);
        sortedIds.sort(Long::compareTo);
        Map<Long, FinishedGood> locked = new HashMap<>();
        for (Long id : sortedIds) {
            FinishedGood fg = finishedGoodRepository.lockByCompanyAndId(company, id)
                    .orElseThrow(() -> com.bigbrightpaints.erp.core.validation.ValidationUtils
                            .invalidInput("Finished good not found: " + id));
            locked.put(id, fg);
        }
        return locked;
    }

    private SalesOrderItem pollMatchingOrderItem(Map<String, Deque<SalesOrderItem>> orderItemsByProductCode,
                                                 String productCode) {
        if (orderItemsByProductCode == null || !StringUtils.hasText(productCode)) {
            return null;
        }
        Deque<SalesOrderItem> items = orderItemsByProductCode.get(productCode);
        if (items == null || items.isEmpty()) {
            return null;
        }
        return items.pollFirst();
    }

    private void requireSufficientQuantity(BigDecimal available, BigDecimal required, String context) {
        BigDecimal safeAvailable = inventoryValuationService.safeQuantity(available);
        BigDecimal safeRequired = inventoryValuationService.safeQuantity(required);
        if (safeAvailable.compareTo(safeRequired) < 0) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils
                    .invalidState(context + ": available=" + safeAvailable + ", requested=" + safeRequired);
        }
    }


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

        FinishedGoodsService.DispatchPosting build() {
            return new FinishedGoodsService.DispatchPosting(inventoryAccountId, cogsAccountId, cost);
        }
    }
}
