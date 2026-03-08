package com.bigbrightpaints.erp.modules.inventory.service;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.CompanyTime;
import com.bigbrightpaints.erp.modules.accounting.service.CompanyDefaultAccountsService;
import com.bigbrightpaints.erp.modules.accounting.service.CostingMethodService;
import com.bigbrightpaints.erp.modules.accounting.service.GstService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryBatchSource;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryReference;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryReservationRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlip;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlipRepository;
import com.bigbrightpaints.erp.modules.inventory.dto.DispatchConfirmationRequest;
import com.bigbrightpaints.erp.modules.inventory.dto.DispatchConfirmationResponse;
import com.bigbrightpaints.erp.modules.inventory.dto.DispatchPreviewDto;
import com.bigbrightpaints.erp.modules.inventory.dto.FinishedGoodBatchDto;
import com.bigbrightpaints.erp.modules.inventory.dto.FinishedGoodBatchRequest;
import com.bigbrightpaints.erp.modules.inventory.dto.FinishedGoodDto;
import com.bigbrightpaints.erp.modules.inventory.dto.FinishedGoodLowStockThresholdDto;
import com.bigbrightpaints.erp.modules.inventory.dto.FinishedGoodRequest;
import com.bigbrightpaints.erp.modules.inventory.dto.PackagingSlipDto;
import com.bigbrightpaints.erp.modules.inventory.dto.StockSummaryDto;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrder;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrderRepository;
import jakarta.transaction.Transactional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
@Service
public class FinishedGoodsWorkflowEngineService {
    private final CompanyContextService companyContextService;
    private final FinishedGoodRepository finishedGoodRepository;
    private final FinishedGoodBatchRepository finishedGoodBatchRepository;
    private final BatchNumberService batchNumberService;
    private final CompanyDefaultAccountsService companyDefaultAccountsService;
    private final Environment environment;
    private final boolean manualBatchEnabled;
    private final InventoryValuationService inventoryValuationService;
    private final InventoryMovementRecorder movementRecorder;
    private final FinishedGoodsReservationEngine reservationEngine;
    private final FinishedGoodsDispatchEngine dispatchEngine;
    private final PackagingSlipService packagingSlipService;
    public FinishedGoodsWorkflowEngineService(CompanyContextService companyContextService,
                                              FinishedGoodRepository finishedGoodRepository,
                                              FinishedGoodBatchRepository finishedGoodBatchRepository,
                                              PackagingSlipRepository packagingSlipRepository,
                                              InventoryMovementRepository inventoryMovementRepository,
                                              InventoryReservationRepository inventoryReservationRepository,
                                              BatchNumberService batchNumberService,
                                              SalesOrderRepository salesOrderRepository,
                                              CompanyDefaultAccountsService companyDefaultAccountsService,
                                              CostingMethodService costingMethodService,
                                              GstService gstService,
                                              ApplicationEventPublisher eventPublisher,
                                              CompanyClock companyClock,
                                              Environment environment,
                                              @Value("${erp.inventory.finished-goods.batch.enabled:false}") boolean manualBatchEnabled) {
        this.companyContextService = companyContextService;
        this.finishedGoodRepository = finishedGoodRepository;
        this.finishedGoodBatchRepository = finishedGoodBatchRepository;
        this.batchNumberService = batchNumberService;
        this.companyDefaultAccountsService = companyDefaultAccountsService;
        this.environment = environment;
        this.manualBatchEnabled = manualBatchEnabled;
        this.movementRecorder = new InventoryMovementRecorder(
                inventoryMovementRepository,
                eventPublisher,
                companyClock);
        this.inventoryValuationService = new InventoryValuationService(finishedGoodBatchRepository);
        this.packagingSlipService = new PackagingSlipService(
                companyContextService,
                packagingSlipRepository,
                inventoryReservationRepository,
                finishedGoodRepository,
                finishedGoodBatchRepository,
                salesOrderRepository,
                this.inventoryValuationService,
                batchNumberService);
        this.reservationEngine = new FinishedGoodsReservationEngine(
                companyContextService,
                finishedGoodRepository,
                finishedGoodBatchRepository,
                packagingSlipRepository,
                inventoryMovementRepository,
                inventoryReservationRepository,
                salesOrderRepository,
                batchNumberService,
                costingMethodService,
                companyClock,
                this.movementRecorder,
                this.inventoryValuationService);
        this.dispatchEngine = new FinishedGoodsDispatchEngine(
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
                this.reservationEngine,
                this.packagingSlipService,
                this.inventoryValuationService);
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
                .orElseThrow(() -> com.bigbrightpaints.erp.core.validation.ValidationUtils
                        .invalidInput("Finished good not found"));
        return toDto(fg);
    }
    public FinishedGood lockFinishedGoodByProductCode(String productCode) {
        Company company = companyContextService.requireCurrentCompany();
        return finishedGoodRepository.lockByCompanyAndProductCode(company, productCode)
                .orElseThrow(() -> com.bigbrightpaints.erp.core.validation.ValidationUtils
                        .invalidInput("Finished good not found for product code " + productCode));
    }
    public BigDecimal currentWeightedAverageCost(FinishedGood fg) {
        return inventoryValuationService.currentWeightedAverageCost(fg);
    }
    @Transactional
    public FinishedGoodDto updateFinishedGood(Long id, FinishedGoodRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        FinishedGood fg = finishedGoodRepository.lockByCompanyAndId(company, id)
                .orElseThrow(() -> com.bigbrightpaints.erp.core.validation.ValidationUtils
                        .invalidInput("Finished good not found"));
        if (request.name() != null) {
            fg.setName(request.name());
        }
        if (request.unit() != null) {
            fg.setUnit(request.unit());
        }
        if (request.costingMethod() != null) {
            fg.setCostingMethod(inventoryValuationService.normalizeCostingMethod(request.costingMethod()));
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
                .orElseThrow(() -> com.bigbrightpaints.erp.core.validation.ValidationUtils
                        .invalidInput("Finished good not found"));
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
                        inventoryValuationService.safeQuantity(fg.getCurrentStock()),
                        inventoryValuationService.safeQuantity(fg.getReservedStock()),
                        inventoryValuationService.safeQuantity(fg.getCurrentStock())
                                .subtract(inventoryValuationService.safeQuantity(fg.getReservedStock())),
                        inventoryValuationService.stockSummaryUnitCost(fg),
                        null,
                        null,
                        null,
                        null
                ))
                .toList();
    }
    public List<FinishedGoodDto> getLowStockItems(Integer threshold) {
        Company company = companyContextService.requireCurrentCompany();
        return finishedGoodRepository.findByCompanyOrderByProductCodeAsc(company)
                .stream()
                .filter(fg -> inventoryValuationService.safeQuantity(fg.getCurrentStock())
                        .subtract(inventoryValuationService.safeQuantity(fg.getReservedStock()))
                        .compareTo(inventoryValuationService.resolveLowStockThreshold(fg, threshold)) < 0)
                .map(this::toDto)
                .toList();
    }
    public FinishedGoodLowStockThresholdDto getLowStockThreshold(Long finishedGoodId) {
        Company company = companyContextService.requireCurrentCompany();
        FinishedGood fg = finishedGoodRepository.findByCompanyAndId(company, finishedGoodId)
                .orElseThrow(() -> com.bigbrightpaints.erp.core.validation.ValidationUtils
                        .invalidInput("Finished good not found"));
        return new FinishedGoodLowStockThresholdDto(
                fg.getId(),
                fg.getProductCode(),
                inventoryValuationService.safeQuantity(fg.getLowStockThreshold()));
    }
    @Transactional
    public FinishedGoodLowStockThresholdDto updateLowStockThreshold(Long finishedGoodId, BigDecimal threshold) {
        Company company = companyContextService.requireCurrentCompany();
        FinishedGood fg = finishedGoodRepository.lockByCompanyAndId(company, finishedGoodId)
                .orElseThrow(() -> com.bigbrightpaints.erp.core.validation.ValidationUtils
                        .invalidInput("Finished good not found"));
        BigDecimal resolvedThreshold = inventoryValuationService.safeQuantity(threshold);
        if (resolvedThreshold.compareTo(BigDecimal.ZERO) < 0) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils
                    .invalidInput("Low stock threshold cannot be negative");
        }
        fg.setLowStockThreshold(resolvedThreshold);
        finishedGoodRepository.save(fg);
        return new FinishedGoodLowStockThresholdDto(fg.getId(), fg.getProductCode(), resolvedThreshold);
    }
    @Transactional
    public FinishedGoodDto createFinishedGood(FinishedGoodRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        FinishedGood finishedGood = new FinishedGood();
        finishedGood.setCompany(company);
        finishedGood.setProductCode(request.productCode());
        finishedGood.setName(request.name());
        finishedGood.setUnit(request.unit() == null ? "UNIT" : request.unit());
        finishedGood.setCostingMethod(inventoryValuationService.normalizeCostingMethod(request.costingMethod()));
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
        BigDecimal quantity = inventoryValuationService.safeQuantity(request.quantity());
        if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils
                    .invalidInput("Batch quantity must be greater than zero");
        }
        BigDecimal unitCost = request.unitCost() != null ? request.unitCost() : BigDecimal.ZERO;
        if (unitCost.compareTo(BigDecimal.ZERO) < 0) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils
                    .invalidInput("Batch unit cost cannot be negative");
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
        batch.setSource(InventoryBatchSource.PRODUCTION);
        FinishedGoodBatch savedBatch = finishedGoodBatchRepository.save(batch);
        finishedGood.setCurrentStock(inventoryValuationService.safeQuantity(finishedGood.getCurrentStock()).add(quantity));
        finishedGoodRepository.save(finishedGood);
        inventoryValuationService.invalidateWeightedAverageCost(finishedGood.getId());
        movementRecorder.recordFinishedGoodMovement(
                finishedGood,
                savedBatch,
                InventoryReference.MANUFACTURING_ORDER,
                savedBatch.getPublicId().toString(),
                "RECEIPT",
                quantity,
                unitCost,
                null);
        return toBatchDto(savedBatch);
    }
    public List<PackagingSlipDto> listPackagingSlips() {
        return packagingSlipService.listPackagingSlips();
    }
    @Transactional
    public FinishedGoodsService.InventoryReservationResult reserveForOrder(SalesOrder order) {
        return reservationEngine.reserveForOrder(order);
    }
    @Transactional
    public void releaseReservationsForOrder(Long orderId) {
        reservationEngine.releaseReservationsForOrder(orderId);
    }
    public Map<String, FinishedGoodsService.FinishedGoodAccountingProfile> accountingProfiles(List<String> productCodes) {
        if (productCodes == null || productCodes.isEmpty()) {
            return Map.of();
        }
        Company company = companyContextService.requireCurrentCompany();
        List<FinishedGood> goods = finishedGoodRepository.findByCompanyAndProductCodeIn(company, productCodes);
        Map<String, FinishedGoodsService.FinishedGoodAccountingProfile> profiles = new HashMap<>();
        for (FinishedGood fg : goods) {
            profiles.put(fg.getProductCode(), new FinishedGoodsService.FinishedGoodAccountingProfile(
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
    public List<FinishedGoodsService.DispatchPosting> markSlipDispatched(Long salesOrderId) {
        return dispatchEngine.markSlipDispatched(salesOrderId);
    }
    @Transactional
    public List<FinishedGoodsService.DispatchPosting> markSlipDispatched(Long salesOrderId, PackagingSlip slip) {
        return dispatchEngine.markSlipDispatched(salesOrderId, slip);
    }
    @Transactional
    public DispatchPreviewDto getDispatchPreview(Long packagingSlipId) {
        return dispatchEngine.getDispatchPreview(packagingSlipId);
    }
    @Transactional
    public DispatchConfirmationResponse confirmDispatch(DispatchConfirmationRequest request, String username) {
        return dispatchEngine.confirmDispatch(request, username);
    }
    @Transactional
    public DispatchConfirmationResponse getDispatchConfirmation(Long packagingSlipId) {
        return dispatchEngine.getDispatchConfirmation(packagingSlipId);
    }
    public PackagingSlipDto getPackagingSlip(Long slipId) {
        return packagingSlipService.getPackagingSlip(slipId);
    }
    public PackagingSlipDto getPackagingSlipByOrder(Long salesOrderId) {
        return packagingSlipService.getPackagingSlipByOrder(salesOrderId);
    }
    @Transactional
    public PackagingSlipDto updateSlipStatus(Long slipId, String newStatus) {
        return packagingSlipService.updateSlipStatus(slipId, newStatus);
    }
    @Transactional
    public PackagingSlipDto cancelBackorderSlip(Long slipId, String username, String reason) {
        return packagingSlipService.cancelBackorderSlip(slipId, username, reason);
    }
    @Transactional
    public void linkDispatchMovementsToJournal(Long packingSlipId, Long journalEntryId) {
        dispatchEngine.linkDispatchMovementsToJournal(packingSlipId, journalEntryId);
    }
    public void invalidateWeightedAverageCost(Long finishedGoodId) {
        inventoryValuationService.invalidateWeightedAverageCost(finishedGoodId);
    }
    private FinishedGood lockFinishedGood(Long id) {
        Company company = companyContextService.requireCurrentCompany();
        return finishedGoodRepository.lockByCompanyAndId(company, id)
                .orElseThrow(() -> com.bigbrightpaints.erp.core.validation.ValidationUtils
                        .invalidInput("Finished good not found"));
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
    private FinishedGoodDto toDto(FinishedGood finishedGood) {
        return new FinishedGoodDto(
                finishedGood.getId(),
                finishedGood.getPublicId(),
                finishedGood.getProductCode(),
                finishedGood.getName(),
                finishedGood.getUnit(),
                inventoryValuationService.safeQuantity(finishedGood.getCurrentStock()),
                inventoryValuationService.safeQuantity(finishedGood.getReservedStock()),
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
}
