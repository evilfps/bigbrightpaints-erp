package com.bigbrightpaints.erp.modules.inventory.service;

import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.dto.FinishedGoodBatchDto;
import com.bigbrightpaints.erp.modules.inventory.dto.FinishedGoodBatchRequest;
import com.bigbrightpaints.erp.modules.inventory.dto.FinishedGoodDto;
import com.bigbrightpaints.erp.modules.inventory.dto.FinishedGoodLowStockThresholdDto;
import com.bigbrightpaints.erp.modules.inventory.dto.StockSummaryDto;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

@Service
public class FinishedGoodsStockService {

    private final FinishedGoodsWorkflowService workflowService;

    public FinishedGoodsStockService(FinishedGoodsWorkflowService workflowService) {
        this.workflowService = workflowService;
    }

    public FinishedGood lockFinishedGoodByProductCode(String productCode) {
        return workflowService.lockFinishedGoodByProductCode(productCode);
    }

    public BigDecimal currentWeightedAverageCost(FinishedGood fg) {
        return workflowService.currentWeightedAverageCost(fg);
    }

    public List<FinishedGoodBatchDto> listBatchesForFinishedGood(Long finishedGoodId) {
        return workflowService.listBatchesForFinishedGood(finishedGoodId);
    }

    public List<StockSummaryDto> getStockSummary() {
        return workflowService.getStockSummary();
    }

    public List<FinishedGoodDto> getLowStockItems(Integer threshold) {
        return workflowService.getLowStockItems(threshold);
    }

    public FinishedGoodLowStockThresholdDto getLowStockThreshold(Long finishedGoodId) {
        return workflowService.getLowStockThreshold(finishedGoodId);
    }

    public FinishedGoodLowStockThresholdDto updateLowStockThreshold(Long finishedGoodId, BigDecimal threshold) {
        return workflowService.updateLowStockThreshold(finishedGoodId, threshold);
    }

    public FinishedGoodBatchDto registerBatch(FinishedGoodBatchRequest request) {
        return workflowService.registerBatch(request);
    }

    public void invalidateWeightedAverageCost(Long finishedGoodId) {
        workflowService.invalidateWeightedAverageCost(finishedGoodId);
    }
}
