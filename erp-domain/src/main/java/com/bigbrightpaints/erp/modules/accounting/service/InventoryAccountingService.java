package com.bigbrightpaints.erp.modules.accounting.service;

import com.bigbrightpaints.erp.modules.accounting.dto.InventoryRevaluationRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.LandedCostRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.WipAdjustmentRequest;
import org.springframework.stereotype.Service;

@Service
public class InventoryAccountingService {

    private final AccountingCoreService accountingCoreService;

    public InventoryAccountingService(AccountingCoreService accountingCoreService) {
        this.accountingCoreService = accountingCoreService;
    }

    public JournalEntryDto recordLandedCost(LandedCostRequest request) {
        return accountingCoreService.recordLandedCost(request);
    }

    public JournalEntryDto revalueInventory(InventoryRevaluationRequest request) {
        return accountingCoreService.revalueInventory(request);
    }

    public JournalEntryDto adjustWip(WipAdjustmentRequest request) {
        return accountingCoreService.adjustWip(request);
    }
}
