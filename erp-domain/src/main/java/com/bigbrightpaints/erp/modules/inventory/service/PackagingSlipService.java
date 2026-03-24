package com.bigbrightpaints.erp.modules.inventory.service;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.CompanyTime;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryReference;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryReservation;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryReservationRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlip;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlipLine;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlipRepository;
import com.bigbrightpaints.erp.modules.inventory.dto.PackagingSlipDto;
import com.bigbrightpaints.erp.modules.inventory.dto.PackagingSlipLineDto;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrder;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrderRepository;
import jakarta.transaction.Transactional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class PackagingSlipService {

    private final CompanyContextService companyContextService;
    private final PackagingSlipRepository packagingSlipRepository;
    private final InventoryReservationRepository inventoryReservationRepository;
    private final FinishedGoodRepository finishedGoodRepository;
    private final FinishedGoodBatchRepository finishedGoodBatchRepository;
    private final SalesOrderRepository salesOrderRepository;
    private final InventoryValuationService inventoryValuationService;
    private final BatchNumberService batchNumberService;

    public PackagingSlipService(CompanyContextService companyContextService,
                               PackagingSlipRepository packagingSlipRepository,
                               InventoryReservationRepository inventoryReservationRepository,
                               FinishedGoodRepository finishedGoodRepository,
                               FinishedGoodBatchRepository finishedGoodBatchRepository,
                               SalesOrderRepository salesOrderRepository,
                               InventoryValuationService inventoryValuationService,
                               BatchNumberService batchNumberService) {
        this.companyContextService = companyContextService;
        this.packagingSlipRepository = packagingSlipRepository;
        this.inventoryReservationRepository = inventoryReservationRepository;
        this.finishedGoodRepository = finishedGoodRepository;
        this.finishedGoodBatchRepository = finishedGoodBatchRepository;
        this.salesOrderRepository = salesOrderRepository;
        this.inventoryValuationService = inventoryValuationService;
        this.batchNumberService = batchNumberService;
    }

    public List<PackagingSlipDto> listPackagingSlips() {
        Company company = companyContextService.requireCurrentCompany();
        return packagingSlipRepository.findByCompanyOrderByCreatedAtDesc(company)
                .stream()
                .map(this::toSlipDto)
                .toList();
    }

    public PackagingSlipDto getPackagingSlip(Long slipId) {
        Company company = companyContextService.requireCurrentCompany();
        PackagingSlip slip = packagingSlipRepository.findByIdAndCompany(slipId, company)
                .orElseThrow(() -> com.bigbrightpaints.erp.core.validation.ValidationUtils
                        .invalidInput("Packaging slip not found"));
        return toSlipDto(slip);
    }

    public PackagingSlipDto getPackagingSlipByOrder(Long salesOrderId) {
        Company company = companyContextService.requireCurrentCompany();
        List<PackagingSlip> slips = packagingSlipRepository.findPrimarySlipsByOrderId(company, salesOrderId);
        if (slips.isEmpty()) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE,
                    "Packaging slip not found for order " + salesOrderId);
        }
        if (slips.size() > 1) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "Multiple packaging slips found for order " + salesOrderId + "; provide packingSlipId");
        }
        PackagingSlip selected = slips.get(0);
        PackagingSlip slip = packagingSlipRepository.findByIdAndCompany(selected.getId(), company)
                .orElseThrow(() -> com.bigbrightpaints.erp.core.validation.ValidationUtils
                        .invalidInput("Packaging slip not found"));
        return toSlipDto(slip);
    }

    @Transactional
    public PackagingSlipDto updateSlipStatus(Long slipId, String newStatus) {
        Company company = companyContextService.requireCurrentCompany();
        PackagingSlip slip = packagingSlipRepository.findByIdAndCompany(slipId, company)
                .orElseThrow(() -> com.bigbrightpaints.erp.core.validation.ValidationUtils
                        .invalidInput("Packaging slip not found"));

        if (slip.isBackorder()) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils
                    .invalidState("Backorder slips can only be changed via backorder workflows");
        }
        if ("DISPATCHED".equalsIgnoreCase(slip.getStatus())) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils
                    .invalidState("Cannot update status of dispatched slip");
        }
        if (!StringUtils.hasText(newStatus)) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput("Slip status is required");
        }

        String normalized = newStatus.trim().toUpperCase();
        if ("DISPATCHED".equals(normalized)) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils
                    .invalidState("Use dispatch confirmation to mark a slip as dispatched");
        }
        if (!List.of("PENDING", "PENDING_PRODUCTION", "RESERVED", "PENDING_STOCK").contains(normalized)) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils
                    .invalidInput("Unsupported slip status: " + normalized);
        }
        if (!canTransitionStatus(slip.getStatus(), normalized)) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils
                    .invalidState("Invalid slip status transition: " + slip.getStatus() + " -> " + normalized);
        }

        slip.setStatus(normalized);
        packagingSlipRepository.save(slip);
        return toSlipDto(slip);
    }

    @Transactional
    public PackagingSlipDto cancelBackorderSlip(Long slipId, String username, String reason) {
        Company company = companyContextService.requireCurrentCompany();
        PackagingSlip slip = packagingSlipRepository.findByIdAndCompany(slipId, company)
                .orElseThrow(() -> com.bigbrightpaints.erp.core.validation.ValidationUtils
                        .invalidInput("Packaging slip not found"));

        if (!slip.isBackorder() || !"BACKORDER".equalsIgnoreCase(slip.getStatus())) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils
                    .invalidState("Only BACKORDER slips can be canceled");
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
            qty = inventoryValuationService.safeQuantity(qty);
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
                BigDecimal fulfilled = inventoryValuationService.safeQuantity(reservation.getFulfilledQuantity());
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
                    lockedGoods.computeIfAbsent(fg.getId(), id -> finishedGoodRepository
                            .lockByCompanyAndId(company, id)
                            .orElseThrow(() -> com.bigbrightpaints.erp.core.validation.ValidationUtils
                                    .invalidInput("Finished good not found: " + id)));
                }
                lockedBatches.computeIfAbsent(batch.getId(), id ->
                        finishedGoodBatchRepository.lockById(id).orElse(batch));
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
                BigDecimal available = inventoryValuationService.safeQuantity(batch.getQuantityAvailable());
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
                BigDecimal reserved = inventoryValuationService.safeQuantity(fg.getReservedStock());
                fg.setReservedStock(reserved.subtract(entry.getValue()).max(BigDecimal.ZERO));
            }
            finishedGoodRepository.saveAll(lockedGoods.values());
            lockedGoods.keySet().forEach(inventoryValuationService::invalidateWeightedAverageCost);
        }

        slip.setStatus("CANCELLED");
        slip.setDispatchNotes(reason != null ? reason : "Backorder canceled by " + (username != null ? username : "system"));
        slip.setConfirmedAt(CompanyTime.now(company));
        slip.setConfirmedBy(username != null ? username : "system");
        PackagingSlip savedSlip = packagingSlipRepository.save(slip);

        syncOrderStatusAfterBackorderCancellation(company, savedSlip.getSalesOrder());
        return toSlipDto(savedSlip);
    }

    @Transactional
    public Long createBackorderSlip(PackagingSlip originalSlip) {
        if (originalSlip == null || originalSlip.getSalesOrder() == null) {
            return null;
        }
        boolean hasBackorder = originalSlip.getLines().stream()
                .map(line -> inventoryValuationService.safeQuantity(line.getBackorderQuantity()))
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
        backorderSlip.setSlipNumber(batchNumberService.nextPackagingSlipNumber(originalSlip.getCompany()) + "-BO");
        backorderSlip.setStatus("BACKORDER");
        backorderSlip.setBackorder(true);
        backorderSlip.setDispatchNotes("Backorder from " + originalSlip.getSlipNumber());

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

    public Long resolveBackorderSlipIdForResponse(PackagingSlip slip, Company company, boolean hasBackorder) {
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
        return packagingSlipRepository.findActiveBackorderSlipIds(company, salesOrderId).stream()
                .filter(id -> id != null)
                .filter(id -> !Objects.equals(id, excludeSlipId))
                .findFirst()
                .orElse(null);
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

    private BigDecimal resolveReservedQuantity(InventoryReservation reservation) {
        String status = reservation.getStatus();
        if (status != null && ("CANCELLED".equalsIgnoreCase(status) || "FULFILLED".equalsIgnoreCase(status))) {
            return BigDecimal.ZERO;
        }
        BigDecimal reserved = reservation.getReservedQuantity();
        if (reserved == null || reserved.compareTo(BigDecimal.ZERO) <= 0) {
            reserved = reservation.getQuantity();
        }
        return inventoryValuationService.safeQuantity(reserved);
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
