package com.bigbrightpaints.erp.modules.accounting.service;

import java.math.BigDecimal;
import java.util.List;

import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest;

record SettlementLineDraft(
    List<JournalEntryRequest.JournalLineRequest> lines, BigDecimal cashAmount) {}
