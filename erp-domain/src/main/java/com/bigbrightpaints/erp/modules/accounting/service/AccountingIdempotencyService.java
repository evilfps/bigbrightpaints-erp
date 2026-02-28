package com.bigbrightpaints.erp.modules.accounting.service;

import com.bigbrightpaints.erp.modules.accounting.dto.DealerReceiptRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.DealerReceiptSplitRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.DealerSettlementRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.PartnerSettlementResponse;
import com.bigbrightpaints.erp.modules.accounting.dto.SupplierPaymentRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.SupplierSettlementRequest;
import org.springframework.stereotype.Service;

@Service
public class AccountingIdempotencyService {

    private final AccountingCoreService accountingCoreService;

    public AccountingIdempotencyService(AccountingCoreService accountingCoreService) {
        this.accountingCoreService = accountingCoreService;
    }

    public JournalEntryDto createManualJournalEntry(JournalEntryRequest request, String idempotencyKey) {
        return accountingCoreService.createManualJournalEntry(request, idempotencyKey);
    }

    public JournalEntryDto recordDealerReceipt(DealerReceiptRequest request) {
        return accountingCoreService.recordDealerReceipt(request);
    }

    public JournalEntryDto recordDealerReceiptSplit(DealerReceiptSplitRequest request) {
        return accountingCoreService.recordDealerReceiptSplit(request);
    }

    public JournalEntryDto recordSupplierPayment(SupplierPaymentRequest request) {
        return accountingCoreService.recordSupplierPayment(request);
    }

    public PartnerSettlementResponse settleDealerInvoices(DealerSettlementRequest request) {
        return accountingCoreService.settleDealerInvoices(request);
    }

    public PartnerSettlementResponse settleSupplierInvoices(SupplierSettlementRequest request) {
        return accountingCoreService.settleSupplierInvoices(request);
    }
}
