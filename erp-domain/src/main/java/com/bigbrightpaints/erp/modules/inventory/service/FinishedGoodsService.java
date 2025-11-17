package com.bigbrightpaints.erp.modules.inventory.service;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.inventory.domain.*;
import com.bigbrightpaints.erp.modules.inventory.dto.*;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrder;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrderItem;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
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
    private final BatchNumberService batchNumberService;

    public FinishedGoodsService(CompanyContextService companyContextService,
                                FinishedGoodRepository finishedGoodRepository,
                                FinishedGoodBatchRepository finishedGoodBatchRepository,
                                PackagingSlipRepository packagingSlipRepository,
                                InventoryMovementRepository inventoryMovementRepository,
                                InventoryReservationRepository inventoryReservationRepository,
                                BatchNumberService batchNumberService) {
        this.companyContextService = companyContextService;
        this.finishedGoodRepository = finishedGoodRepository;
        this.finishedGoodBatchRepository = finishedGoodBatchRepository;
        this.packagingSlipRepository = packagingSlipRepository;
        this.inventoryMovementRepository = inventoryMovementRepository;
        this.inventoryReservationRepository = inventoryReservationRepository;
        this.batchNumberService = batchNumberService;
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
        finishedGood.setRevenueAccountId(request.revenueAccountId());
        finishedGood.setDiscountAccountId(request.discountAccountId());
        finishedGood.setTaxAccountId(request.taxAccountId());
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
        PackagingSlip slip = packagingSlipRepository.findBySalesOrderId(order.getId())
                .orElseGet(() -> createSlip(order));

        if (!slip.getLines().isEmpty()) {
            return new InventoryReservationResult(toSlipDto(slip), List.of());
        }

        List<InventoryShortage> shortages = new ArrayList<>();
        for (SalesOrderItem item : order.getItems()) {
            FinishedGood finishedGood = lockFinishedGood(order.getCompany(), item.getProductCode());
            allocateItem(order, slip, finishedGood, item, shortages);
        }
        return new InventoryReservationResult(toSlipDto(slip), List.copyOf(shortages));
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
        PackagingSlip slip = packagingSlipRepository.findBySalesOrderId(salesOrderId)
                .orElseThrow(() -> new IllegalArgumentException("Packaging slip not found for order " + salesOrderId));
        if ("DISPATCHED".equalsIgnoreCase(slip.getStatus())) {
            return List.of();
        }
        slip.setStatus("DISPATCHED");
        slip.setDispatchedAt(Instant.now());

        List<InventoryReservation> reservations = inventoryReservationRepository
                .findByReferenceTypeAndReferenceId(InventoryReference.SALES_ORDER, salesOrderId.toString());
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
                FinishedGood lockedFg = lockFinishedGood(slip.getCompany(), fg.getId());
                lockedFg.setReservedStock(lockedFg.getReservedStock().subtract(qty));
                finishedGoodRepository.save(lockedFg);
                BigDecimal unitCost = batch != null ? batch.getUnitCost() : BigDecimal.ZERO;
                recordMovement(lockedFg, batch, InventoryReference.SALES_ORDER, salesOrderId.toString(), "DISPATCH", qty, unitCost);

                postingBuilders
                        .computeIfAbsent(lockedFg.getValuationAccountId(),
                                id -> new DispatchPostingBuilder(lockedFg.getValuationAccountId(), lockedFg.getCogsAccountId()))
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

    private void allocateItem(SalesOrder order,
                              PackagingSlip slip,
                              FinishedGood finishedGood,
                              SalesOrderItem item,
                              List<InventoryShortage> shortages) {
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
                finishedGood.getCurrentStock(),
                finishedGood.getReservedStock(),
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
