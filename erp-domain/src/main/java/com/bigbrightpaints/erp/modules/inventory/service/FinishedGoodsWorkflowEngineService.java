package com.bigbrightpaints.erp.modules.inventory.service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.modules.accounting.service.CostingMethodService;
import com.bigbrightpaints.erp.modules.accounting.service.GstService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryReservationRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlip;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlipRepository;
import com.bigbrightpaints.erp.modules.inventory.dto.DispatchConfirmationRequest;
import com.bigbrightpaints.erp.modules.inventory.dto.DispatchConfirmationResponse;
import com.bigbrightpaints.erp.modules.inventory.dto.DispatchPreviewDto;
import com.bigbrightpaints.erp.modules.inventory.dto.FinishedGoodBatchDto;
import com.bigbrightpaints.erp.modules.inventory.dto.FinishedGoodDto;
import com.bigbrightpaints.erp.modules.inventory.dto.FinishedGoodLowStockThresholdDto;
import com.bigbrightpaints.erp.modules.inventory.dto.PackagingSlipDto;
import com.bigbrightpaints.erp.modules.inventory.dto.StockSummaryDto;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrder;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrderRepository;

import jakarta.transaction.Transactional;

@Service
public class FinishedGoodsWorkflowEngineService {
  private final CompanyContextService companyContextService;
  private final FinishedGoodRepository finishedGoodRepository;
  private final FinishedGoodBatchRepository finishedGoodBatchRepository;
  private final BatchNumberService batchNumberService;
  private final InventoryValuationService inventoryValuationService;
  private final InventoryMovementRecorder movementRecorder;
  private final FinishedGoodsReservationEngine reservationEngine;
  private final FinishedGoodsDispatchEngine dispatchEngine;
  private final PackagingSlipService packagingSlipService;

  public FinishedGoodsWorkflowEngineService(
      CompanyContextService companyContextService,
      FinishedGoodRepository finishedGoodRepository,
      FinishedGoodBatchRepository finishedGoodBatchRepository,
      PackagingSlipRepository packagingSlipRepository,
      InventoryMovementRepository inventoryMovementRepository,
      InventoryReservationRepository inventoryReservationRepository,
      BatchNumberService batchNumberService,
      SalesOrderRepository salesOrderRepository,
      CostingMethodService costingMethodService,
      GstService gstService,
      ApplicationEventPublisher eventPublisher,
      CompanyClock companyClock) {
    this.companyContextService = companyContextService;
    this.finishedGoodRepository = finishedGoodRepository;
    this.finishedGoodBatchRepository = finishedGoodBatchRepository;
    this.batchNumberService = batchNumberService;
    this.movementRecorder =
        new InventoryMovementRecorder(inventoryMovementRepository, eventPublisher, companyClock);
    this.inventoryValuationService =
        new InventoryValuationService(
            finishedGoodBatchRepository, costingMethodService, companyClock);
    this.packagingSlipService =
        new PackagingSlipService(
            companyContextService,
            packagingSlipRepository,
            inventoryReservationRepository,
            finishedGoodRepository,
            finishedGoodBatchRepository,
            salesOrderRepository,
            this.inventoryValuationService,
            batchNumberService);
    this.reservationEngine =
        new FinishedGoodsReservationEngine(
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
    this.dispatchEngine =
        new FinishedGoodsDispatchEngine(
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
    return finishedGoodRepository.findByCompanyOrderByProductCodeAsc(company).stream()
        .map(this::toDto)
        .toList();
  }

  public FinishedGoodDto getFinishedGood(Long id) {
    Company company = companyContextService.requireCurrentCompany();
    FinishedGood fg =
        finishedGoodRepository
            .findByCompanyAndId(company, id)
            .orElseThrow(
                () ->
                    com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
                        "Finished good not found"));
    return toDto(fg);
  }

  public FinishedGood lockFinishedGoodByProductCode(String productCode) {
    Company company = companyContextService.requireCurrentCompany();
    return finishedGoodRepository
        .lockByCompanyAndProductCode(company, productCode)
        .orElseThrow(
            () ->
                com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
                    "Finished good not found for product code " + productCode));
  }

  public BigDecimal currentWeightedAverageCost(FinishedGood fg) {
    return inventoryValuationService.currentWeightedAverageCost(fg);
  }

  public List<FinishedGoodBatchDto> listBatchesForFinishedGood(Long finishedGoodId) {
    Company company = companyContextService.requireCurrentCompany();
    FinishedGood fg =
        finishedGoodRepository
            .findByCompanyAndId(company, finishedGoodId)
            .orElseThrow(
                () ->
                    com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
                        "Finished good not found"));
    return finishedGoodBatchRepository.findByFinishedGoodOrderByManufacturedAtAsc(fg).stream()
        .map(this::toBatchDto)
        .toList();
  }

  public List<StockSummaryDto> getStockSummary() {
    Company company = companyContextService.requireCurrentCompany();
    return finishedGoodRepository.findByCompanyOrderByProductCodeAsc(company).stream()
        .map(
            fg ->
                new StockSummaryDto(
                    fg.getId(),
                    fg.getPublicId(),
                    fg.getProductCode(),
                    fg.getName(),
                    inventoryValuationService.safeQuantity(fg.getCurrentStock()),
                    inventoryValuationService.safeQuantity(fg.getReservedStock()),
                    inventoryValuationService
                        .safeQuantity(fg.getCurrentStock())
                        .subtract(inventoryValuationService.safeQuantity(fg.getReservedStock())),
                    inventoryValuationService.stockSummaryUnitCost(fg),
                    null,
                    null,
                    null,
                    null))
        .toList();
  }

  public List<FinishedGoodDto> getLowStockItems(Integer threshold) {
    Company company = companyContextService.requireCurrentCompany();
    return finishedGoodRepository.findByCompanyOrderByProductCodeAsc(company).stream()
        .filter(
            fg ->
                inventoryValuationService
                        .safeQuantity(fg.getCurrentStock())
                        .subtract(inventoryValuationService.safeQuantity(fg.getReservedStock()))
                        .compareTo(
                            inventoryValuationService.resolveLowStockThreshold(fg, threshold))
                    < 0)
        .map(this::toDto)
        .toList();
  }

  public FinishedGoodLowStockThresholdDto getLowStockThreshold(Long finishedGoodId) {
    Company company = companyContextService.requireCurrentCompany();
    FinishedGood fg =
        finishedGoodRepository
            .findByCompanyAndId(company, finishedGoodId)
            .orElseThrow(
                () ->
                    com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
                        "Finished good not found"));
    return new FinishedGoodLowStockThresholdDto(
        fg.getId(),
        fg.getProductCode(),
        inventoryValuationService.safeQuantity(fg.getLowStockThreshold()));
  }

  @Transactional
  public FinishedGoodLowStockThresholdDto updateLowStockThreshold(
      Long finishedGoodId, BigDecimal threshold) {
    Company company = companyContextService.requireCurrentCompany();
    FinishedGood fg =
        finishedGoodRepository
            .lockByCompanyAndId(company, finishedGoodId)
            .orElseThrow(
                () ->
                    com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
                        "Finished good not found"));
    BigDecimal resolvedThreshold = inventoryValuationService.safeQuantity(threshold);
    if (resolvedThreshold.compareTo(BigDecimal.ZERO) < 0) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          "Low stock threshold cannot be negative");
    }
    fg.setLowStockThreshold(resolvedThreshold);
    finishedGoodRepository.save(fg);
    return new FinishedGoodLowStockThresholdDto(fg.getId(), fg.getProductCode(), resolvedThreshold);
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

  public Map<String, FinishedGoodsService.FinishedGoodAccountingProfile> accountingProfiles(
      List<String> productCodes) {
    if (productCodes == null || productCodes.isEmpty()) {
      return Map.of();
    }
    Company company = companyContextService.requireCurrentCompany();
    List<FinishedGood> goods =
        finishedGoodRepository.findByCompanyAndProductCodeIn(company, productCodes);
    Map<String, FinishedGoodsService.FinishedGoodAccountingProfile> profiles = new HashMap<>();
    for (FinishedGood fg : goods) {
      profiles.put(
          fg.getProductCode(),
          new FinishedGoodsService.FinishedGoodAccountingProfile(
              fg.getProductCode(),
              fg.getValuationAccountId(),
              fg.getCogsAccountId(),
              fg.getRevenueAccountId(),
              fg.getDiscountAccountId(),
              fg.getTaxAccountId()));
    }
    return profiles;
  }

  @Transactional
  public List<FinishedGoodsService.DispatchPosting> markSlipDispatched(Long salesOrderId) {
    return dispatchEngine.markSlipDispatched(salesOrderId);
  }

  @Transactional
  public List<FinishedGoodsService.DispatchPosting> markSlipDispatched(
      Long salesOrderId, PackagingSlip slip) {
    return dispatchEngine.markSlipDispatched(salesOrderId, slip);
  }

  @Transactional
  public DispatchPreviewDto getDispatchPreview(Long packagingSlipId) {
    return dispatchEngine.getDispatchPreview(packagingSlipId);
  }

  @Transactional
  public DispatchConfirmationResponse confirmDispatch(
      DispatchConfirmationRequest request, String username) {
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
        finishedGood.getTaxAccountId());
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
        batch.getExpiryDate());
  }

}
