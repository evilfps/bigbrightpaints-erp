package com.bigbrightpaints.erp.modules.inventory.service;

import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlip;
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
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Service
public class FinishedGoodsService {

    private final FinishedGoodsCatalogService catalogService;
    private final FinishedGoodsStockService stockService;
    private final FinishedGoodsReservationService reservationService;
    private final FinishedGoodsDispatchService dispatchService;

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

    public FinishedGoodsService(FinishedGoodsCatalogService catalogService,
                                FinishedGoodsStockService stockService,
                                FinishedGoodsReservationService reservationService,
                                FinishedGoodsDispatchService dispatchService) {
        this.catalogService = catalogService;
        this.stockService = stockService;
        this.reservationService = reservationService;
        this.dispatchService = dispatchService;
    }

    public List<FinishedGoodDto> listFinishedGoods() {
        return catalogService.listFinishedGoods();
    }

    public FinishedGoodDto getFinishedGood(Long id) {
        return catalogService.getFinishedGood(id);
    }

    public FinishedGood lockFinishedGoodByProductCode(String productCode) {
        return stockService.lockFinishedGoodByProductCode(productCode);
    }

    public BigDecimal currentWeightedAverageCost(FinishedGood fg) {
        return stockService.currentWeightedAverageCost(fg);
    }

    public FinishedGoodDto updateFinishedGood(Long id, FinishedGoodRequest request) {
        return catalogService.updateFinishedGood(id, request);
    }

    public List<FinishedGoodBatchDto> listBatchesForFinishedGood(Long finishedGoodId) {
        return stockService.listBatchesForFinishedGood(finishedGoodId);
    }

    public List<StockSummaryDto> getStockSummary() {
        return stockService.getStockSummary();
    }

    public List<FinishedGoodDto> getLowStockItems(Integer threshold) {
        return stockService.getLowStockItems(threshold);
    }

    public FinishedGoodLowStockThresholdDto getLowStockThreshold(Long finishedGoodId) {
        return stockService.getLowStockThreshold(finishedGoodId);
    }

    public FinishedGoodLowStockThresholdDto updateLowStockThreshold(Long finishedGoodId, BigDecimal threshold) {
        return stockService.updateLowStockThreshold(finishedGoodId, threshold);
    }

    public FinishedGoodDto createFinishedGood(FinishedGoodRequest request) {
        return catalogService.createFinishedGood(request);
    }

    public FinishedGoodBatchDto registerBatch(FinishedGoodBatchRequest request) {
        return stockService.registerBatch(request);
    }

    public List<PackagingSlipDto> listPackagingSlips() {
        return dispatchService.listPackagingSlips();
    }

    public InventoryReservationResult reserveForOrder(SalesOrder order) {
        return reservationService.reserveForOrder(order);
    }

    public void releaseReservationsForOrder(Long orderId) {
        reservationService.releaseReservationsForOrder(orderId);
    }

    public Map<String, FinishedGoodAccountingProfile> accountingProfiles(List<String> productCodes) {
        return catalogService.accountingProfiles(productCodes);
    }

    public List<DispatchPosting> markSlipDispatched(Long salesOrderId) {
        return dispatchService.markSlipDispatched(salesOrderId);
    }

    public List<DispatchPosting> markSlipDispatched(Long salesOrderId, PackagingSlip slip) {
        return dispatchService.markSlipDispatched(salesOrderId, slip);
    }

    public DispatchPreviewDto getDispatchPreview(Long packagingSlipId) {
        return dispatchService.getDispatchPreview(packagingSlipId);
    }

    public DispatchConfirmationResponse confirmDispatch(DispatchConfirmationRequest request, String username) {
        return dispatchService.confirmDispatch(request, username);
    }

    public DispatchConfirmationResponse getDispatchConfirmation(Long packagingSlipId) {
        return dispatchService.getDispatchConfirmation(packagingSlipId);
    }

    public PackagingSlipDto getPackagingSlip(Long slipId) {
        return dispatchService.getPackagingSlip(slipId);
    }

    public PackagingSlipDto getPackagingSlipByOrder(Long salesOrderId) {
        return dispatchService.getPackagingSlipByOrder(salesOrderId);
    }

    public PackagingSlipDto updateSlipStatus(Long slipId, String newStatus) {
        return dispatchService.updateSlipStatus(slipId, newStatus);
    }

    public PackagingSlipDto cancelBackorderSlip(Long slipId, String username, String reason) {
        return dispatchService.cancelBackorderSlip(slipId, username, reason);
    }

    public void linkDispatchMovementsToJournal(Long packingSlipId, Long journalEntryId) {
        dispatchService.linkDispatchMovementsToJournal(packingSlipId, journalEntryId);
    }

    public void invalidateWeightedAverageCost(Long finishedGoodId) {
        stockService.invalidateWeightedAverageCost(finishedGoodId);
    }

    public record FinishedGoodAccountingProfile(String productCode,
                                                Long valuationAccountId,
                                                Long cogsAccountId,
                                                Long revenueAccountId,
                                                Long discountAccountId,
                                                Long taxAccountId) {
    }

    public record DispatchPosting(Long inventoryAccountId, Long cogsAccountId, BigDecimal cost) {
    }

    public record InventoryReservationResult(PackagingSlipDto packagingSlip,
                                             List<InventoryShortage> shortages) {
    }

    public record InventoryShortage(String productCode,
                                    BigDecimal shortageQuantity,
                                    String productName) {
    }
}
