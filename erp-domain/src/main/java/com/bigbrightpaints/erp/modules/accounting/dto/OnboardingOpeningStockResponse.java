package com.bigbrightpaints.erp.modules.accounting.dto;

public record OnboardingOpeningStockResponse(String referenceNumber,
                                             Long journalEntryId,
                                             int rawMaterialBatchesCreated,
                                             int finishedGoodBatchesCreated,
                                             int rawMaterialBatchesSkipped,
                                             int finishedGoodBatchesSkipped) {}
