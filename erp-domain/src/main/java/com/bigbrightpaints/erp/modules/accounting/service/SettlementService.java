package com.bigbrightpaints.erp.modules.accounting.service;

import com.bigbrightpaints.erp.modules.accounting.dto.DealerSettlementRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.PartnerSettlementResponse;
import com.bigbrightpaints.erp.modules.accounting.dto.SupplierPaymentRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.SupplierSettlementRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import org.springframework.stereotype.Service;

@Service
public class SettlementService {

    private final AccountingIdempotencyService accountingIdempotencyService;

    public SettlementService(AccountingIdempotencyService accountingIdempotencyService) {
        this.accountingIdempotencyService = accountingIdempotencyService;
    }

    public JournalEntryDto recordSupplierPayment(SupplierPaymentRequest request) {
        return accountingIdempotencyService.recordSupplierPayment(request);
    }

    public PartnerSettlementResponse settleDealerInvoices(DealerSettlementRequest request) {
        return accountingIdempotencyService.settleDealerInvoices(request);
    }

    public PartnerSettlementResponse settleSupplierInvoices(SupplierSettlementRequest request) {
        return accountingIdempotencyService.settleSupplierInvoices(request);
    }
}
