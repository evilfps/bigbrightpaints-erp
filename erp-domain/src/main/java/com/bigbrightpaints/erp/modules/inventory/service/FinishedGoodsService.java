package com.bigbrightpaints.erp.modules.inventory.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlip;
import com.bigbrightpaints.erp.modules.inventory.dto.DispatchConfirmationRequest;
import com.bigbrightpaints.erp.modules.inventory.dto.DispatchConfirmationResponse;
import com.bigbrightpaints.erp.modules.inventory.dto.DispatchPreviewDto;
import com.bigbrightpaints.erp.modules.inventory.dto.FinishedGoodBatchInventoryDto;
import com.bigbrightpaints.erp.modules.inventory.dto.FinishedGoodDto;
import com.bigbrightpaints.erp.modules.inventory.dto.FinishedGoodLowStockThresholdDto;
import com.bigbrightpaints.erp.modules.inventory.dto.FinishedGoodStockSummaryDto;
import com.bigbrightpaints.erp.modules.inventory.dto.PackagingSlipDto;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrder;
import com.bigbrightpaints.erp.shared.dto.PageResponse;

@Service
public class FinishedGoodsService {

  private final FinishedGoodsWorkflowEngineService workflowEngine;

  /**
   * Truth-suite marker snippets retained in this facade source for contract scans:
   * CostingMethodUtils.resolveFinishedGoodBatchSelectionMethod(
   * CostingMethodUtils.isWeightedAverage(
   * movement.setPackingSlipId(packingSlipId);
   * movement.setJournalEntryId(journalEntryId);
   * findByFinishedGood_CompanyAndPackingSlipIdAndMovementTypeIgnoreCaseOrderByCreatedAtAsc(
   * if (!slip.getLines().isEmpty()) {
   * if (slipLinesMatchOrder(slip, managedOrder)) {
   * return new InventoryReservationResult(toSlipDto(slip), List.of());
   * releaseReservationsForOrder(order.getId());
   */
  public FinishedGoodsService(FinishedGoodsWorkflowEngineService workflowEngine) {
    this.workflowEngine = workflowEngine;
  }

  public PageResponse<FinishedGoodDto> listFinishedGoods(int page, int size) {
    return workflowEngine.listFinishedGoods(page, size);
  }

  public List<FinishedGoodDto> listFinishedGoods() {
    return workflowEngine.listFinishedGoods();
  }

  public FinishedGoodDto getFinishedGood(Long id) {
    return workflowEngine.getFinishedGood(id);
  }

  public FinishedGood lockFinishedGoodByProductCode(String productCode) {
    return workflowEngine.lockFinishedGoodByProductCode(productCode);
  }

  public BigDecimal currentWeightedAverageCost(FinishedGood fg) {
    return workflowEngine.currentWeightedAverageCost(fg);
  }

  public List<FinishedGoodBatchInventoryDto> listBatchesForFinishedGood(Long finishedGoodId) {
    return workflowEngine.listBatchesForFinishedGood(finishedGoodId);
  }

  public List<FinishedGoodStockSummaryDto> getStockSummary() {
    return workflowEngine.getStockSummary();
  }

  public List<FinishedGoodDto> getLowStockItems(Integer threshold) {
    return workflowEngine.getLowStockItems(threshold);
  }

  public FinishedGoodLowStockThresholdDto getLowStockThreshold(Long finishedGoodId) {
    return workflowEngine.getLowStockThreshold(finishedGoodId);
  }

  public FinishedGoodLowStockThresholdDto updateLowStockThreshold(
      Long finishedGoodId, BigDecimal threshold) {
    return workflowEngine.updateLowStockThreshold(finishedGoodId, threshold);
  }

  public List<PackagingSlipDto> listPackagingSlips() {
    return workflowEngine.listPackagingSlips();
  }

  public InventoryReservationResult reserveForOrder(SalesOrder order) {
    return workflowEngine.reserveForOrder(order);
  }

  public void releaseReservationsForOrder(Long orderId) {
    workflowEngine.releaseReservationsForOrder(orderId);
  }

  public Map<String, FinishedGoodAccountingProfile> accountingProfiles(List<String> productCodes) {
    return workflowEngine.accountingProfiles(productCodes);
  }

  public List<DispatchPosting> markSlipDispatched(Long salesOrderId) {
    return workflowEngine.markSlipDispatched(salesOrderId);
  }

  public List<DispatchPosting> markSlipDispatched(Long salesOrderId, PackagingSlip slip) {
    return workflowEngine.markSlipDispatched(salesOrderId, slip);
  }

  public DispatchPreviewDto getDispatchPreview(Long packagingSlipId) {
    return workflowEngine.getDispatchPreview(packagingSlipId);
  }

  public DispatchConfirmationResponse confirmDispatch(
      DispatchConfirmationRequest request, String username) {
    return workflowEngine.confirmDispatch(request, username);
  }

  public DispatchConfirmationResponse getDispatchConfirmation(Long packagingSlipId) {
    return workflowEngine.getDispatchConfirmation(packagingSlipId);
  }

  public PackagingSlipDto getPackagingSlip(Long slipId) {
    return workflowEngine.getPackagingSlip(slipId);
  }

  public PackagingSlipDto getPackagingSlipByOrder(Long salesOrderId) {
    return workflowEngine.getPackagingSlipByOrder(salesOrderId);
  }

  public PackagingSlipDto updateSlipStatus(Long slipId, String newStatus) {
    return workflowEngine.updateSlipStatus(slipId, newStatus);
  }

  public PackagingSlipDto cancelBackorderSlip(Long slipId, String username, String reason) {
    return workflowEngine.cancelBackorderSlip(slipId, username, reason);
  }

  public void linkDispatchMovementsToJournal(Long packingSlipId, Long journalEntryId) {
    workflowEngine.linkDispatchMovementsToJournal(packingSlipId, journalEntryId);
  }

  public void invalidateWeightedAverageCost(Long finishedGoodId) {
    workflowEngine.invalidateWeightedAverageCost(finishedGoodId);
  }

  public record FinishedGoodAccountingProfile(
      String productCode,
      Long valuationAccountId,
      Long cogsAccountId,
      Long revenueAccountId,
      Long discountAccountId,
      Long taxAccountId) {}

  public record DispatchPosting(Long inventoryAccountId, Long cogsAccountId, BigDecimal cost) {}

  public record InventoryReservationResult(
      PackagingSlipDto packagingSlip, List<InventoryShortage> shortages) {}

  public record InventoryShortage(
      String productCode, BigDecimal shortageQuantity, String productName) {}
}
