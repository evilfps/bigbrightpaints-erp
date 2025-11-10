package com.bigbrightpaints.erp.modules.inventory.service;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.inventory.domain.*;
import com.bigbrightpaints.erp.modules.inventory.dto.*;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrder;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrderItem;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class FinishedGoodsService {

    private final CompanyContextService companyContextService;
    private final FinishedGoodRepository finishedGoodRepository;
    private final FinishedGoodBatchRepository finishedGoodBatchRepository;
    private final PackagingSlipRepository packagingSlipRepository;
    private final InventoryMovementRepository inventoryMovementRepository;
    private final InventoryReservationRepository inventoryReservationRepository;

    public FinishedGoodsService(CompanyContextService companyContextService,
                                FinishedGoodRepository finishedGoodRepository,
                                FinishedGoodBatchRepository finishedGoodBatchRepository,
                                PackagingSlipRepository packagingSlipRepository,
                                InventoryMovementRepository inventoryMovementRepository,
                                InventoryReservationRepository inventoryReservationRepository) {
        this.companyContextService = companyContextService;
        this.finishedGoodRepository = finishedGoodRepository;
        this.finishedGoodBatchRepository = finishedGoodBatchRepository;
        this.packagingSlipRepository = packagingSlipRepository;
        this.inventoryMovementRepository = inventoryMovementRepository;
        this.inventoryReservationRepository = inventoryReservationRepository;
    }

    public List<FinishedGoodDto> listFinishedGoods() {
        Company company = companyContextService.requireCurrentCompany();
        return finishedGoodRepository.findByCompanyOrderByProductCodeAsc(company)
                .stream()
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
        finishedGood.setValuationAccountId(request.valuationAccountId());
        finishedGood.setCogsAccountId(request.cogsAccountId());
        return toDto(finishedGoodRepository.save(finishedGood));
    }

    @Transactional
    public FinishedGoodBatchDto registerBatch(FinishedGoodBatchRequest request) {
        FinishedGood finishedGood = requireFinishedGood(request.finishedGoodId());
        FinishedGoodBatch batch = new FinishedGoodBatch();
        batch.setFinishedGood(finishedGood);
        batch.setBatchCode(request.batchCode());
        batch.setQuantityTotal(request.quantity());
        batch.setQuantityAvailable(request.quantity());
        batch.setUnitCost(request.unitCost());
        batch.setManufacturedAt(request.manufacturedAt() == null ? Instant.now() : request.manufacturedAt());
        batch.setExpiryDate(request.expiryDate());
        FinishedGoodBatch savedBatch = finishedGoodBatchRepository.save(batch);

        finishedGood.setCurrentStock(finishedGood.getCurrentStock().add(request.quantity()));
        finishedGoodRepository.save(finishedGood);

        recordMovement(finishedGood, savedBatch, "MANUFACTURING", savedBatch.getPublicId().toString(),
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
    public PackagingSlipDto reserveForOrder(SalesOrder order) {
        PackagingSlip slip = packagingSlipRepository.findBySalesOrderId(order.getId())
                .orElseGet(() -> createSlip(order));

        if (!slip.getLines().isEmpty()) {
            return toSlipDto(slip);
        }

        for (SalesOrderItem item : order.getItems()) {
            FinishedGood finishedGood = finishedGoodRepository.findByCompanyAndProductCode(order.getCompany(), item.getProductCode())
                    .orElseThrow(() -> new IllegalArgumentException("Finished good not found for product code " + item.getProductCode()));
            allocateItem(order, slip, finishedGood, item);
        }
        return toSlipDto(slip);
    }

    @Transactional
    public record DispatchPosting(Long inventoryAccountId, Long cogsAccountId, BigDecimal cost) {}

    @Transactional
    public List<DispatchPosting> markSlipDispatched(Long salesOrderId) {
        PackagingSlip slip = packagingSlipRepository.findBySalesOrderId(salesOrderId)
                .orElseThrow(() -> new IllegalArgumentException("Packaging slip not found for order " + salesOrderId));
        if ("DISPATCHED".equalsIgnoreCase(slip.getStatus())) {
            return List.of();
        }
        slip.setStatus("DISPATCHED");
        slip.setDispatchedAt(Instant.now());

        List<InventoryReservation> reservations = inventoryReservationRepository
                .findByReferenceTypeAndReferenceId("SALES_ORDER", salesOrderId.toString());
        Map<Long, DispatchPostingBuilder> postingBuilders = new HashMap<>();
        for (InventoryReservation reservation : reservations) {
            BigDecimal qty = reservation.getReservedQuantity() != null ? reservation.getReservedQuantity() : reservation.getQuantity();
            reservation.setFulfilledQuantity(qty);
            reservation.setStatus("FULFILLED");
            inventoryReservationRepository.save(reservation);

            FinishedGood fg = reservation.getFinishedGood();
            FinishedGoodBatch batch = reservation.getFinishedGoodBatch();
            if (fg != null && qty != null) {
                if (fg.getValuationAccountId() == null || fg.getCogsAccountId() == null) {
                    throw new IllegalStateException("Finished good " + fg.getProductCode() + " missing accounting configuration");
                }
                fg.setReservedStock(fg.getReservedStock().subtract(qty));
                finishedGoodRepository.save(fg);
                BigDecimal unitCost = batch != null ? batch.getUnitCost() : BigDecimal.ZERO;
                recordMovement(fg, batch, "SALES_ORDER", salesOrderId.toString(), "DISPATCH", qty, unitCost);

                postingBuilders
                        .computeIfAbsent(fg.getValuationAccountId(),
                                id -> new DispatchPostingBuilder(fg.getValuationAccountId(), fg.getCogsAccountId()))
                        .addCost(unitCost.multiply(qty));
            }
        }
        return postingBuilders.values().stream()
                .map(DispatchPostingBuilder::build)
                .toList();
    }

    private PackagingSlip createSlip(SalesOrder order) {
        PackagingSlip slip = new PackagingSlip();
        slip.setCompany(order.getCompany());
        slip.setSalesOrder(order);
        slip.setSlipNumber(generateSlipNumber(order.getCompany()));
        return packagingSlipRepository.save(slip);
    }

    private void allocateItem(SalesOrder order, PackagingSlip slip, FinishedGood finishedGood, SalesOrderItem item) {
        BigDecimal remaining = item.getQuantity();
        List<FinishedGoodBatch> batches = finishedGoodBatchRepository.findAllocatableBatches(finishedGood);
        for (FinishedGoodBatch batch : batches) {
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) break;
            BigDecimal available = batch.getQuantityAvailable();
            if (available.compareTo(BigDecimal.ZERO) <= 0) continue;
            BigDecimal allocation = available.min(remaining);
            batch.setQuantityAvailable(available.subtract(allocation));
            finishedGoodBatchRepository.save(batch);

            finishedGood.setReservedStock(finishedGood.getReservedStock().add(allocation));
            finishedGoodRepository.save(finishedGood);

            PackagingSlipLine line = new PackagingSlipLine();
            line.setPackagingSlip(slip);
            line.setFinishedGoodBatch(batch);
            line.setQuantity(allocation);
            line.setUnitCost(batch.getUnitCost());
            slip.getLines().add(line);

            InventoryReservation reservation = new InventoryReservation();
            reservation.setFinishedGood(finishedGood);
            reservation.setFinishedGoodBatch(batch);
            reservation.setReferenceType("SALES_ORDER");
            reservation.setReferenceId(order.getId().toString());
            reservation.setQuantity(allocation);
            reservation.setReservedQuantity(allocation);
            reservation.setStatus("RESERVED");
            inventoryReservationRepository.save(reservation);

            recordMovement(finishedGood, batch, "SALES_ORDER", order.getId().toString(), "RESERVE", allocation, batch.getUnitCost());
            remaining = remaining.subtract(allocation);
        }

        if (remaining.compareTo(BigDecimal.ZERO) > 0) {
            throw new IllegalStateException("Insufficient stock for product " + item.getProductCode());
        }
    }

    private FinishedGood requireFinishedGood(Long id) {
        Company company = companyContextService.requireCurrentCompany();
        return finishedGoodRepository.findByCompanyAndId(company, id)
                .orElseThrow(() -> new IllegalArgumentException("Finished good not found"));
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

    private FinishedGoodDto toDto(FinishedGood finishedGood) {
        return new FinishedGoodDto(
                finishedGood.getId(),
                finishedGood.getPublicId(),
                finishedGood.getProductCode(),
                finishedGood.getName(),
                finishedGood.getUnit(),
                finishedGood.getCurrentStock(),
                finishedGood.getReservedStock(),
                finishedGood.getCostingMethod(),
                finishedGood.getValuationAccountId(),
                finishedGood.getCogsAccountId()
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
                .map(line -> new PackagingSlipLineDto(
                        line.getId(),
                        line.getFinishedGoodBatch().getPublicId(),
                        line.getFinishedGoodBatch().getBatchCode(),
                        line.getQuantity(),
                        line.getUnitCost()
                ))
                .toList();
        return new PackagingSlipDto(
                slip.getId(),
                slip.getPublicId(),
                slip.getSalesOrder().getId(),
                slip.getSlipNumber(),
                slip.getStatus(),
                slip.getCreatedAt(),
                slip.getDispatchedAt(),
                lines
        );
    }

    private String generateSlipNumber(Company company) {
        return company.getCode() + "-PS-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
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

        DispatchPosting build() {
            return new DispatchPosting(inventoryAccountId, cogsAccountId, cost);
        }
    }
}
