package com.bigbrightpaints.erp.modules.accounting.service;

import com.bigbrightpaints.erp.modules.accounting.dto.DealerReceiptRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.DealerReceiptSplitRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import org.springframework.stereotype.Service;

@Service
public class DealerReceiptService {

    private final AccountingIdempotencyService accountingIdempotencyService;

    public DealerReceiptService(AccountingIdempotencyService accountingIdempotencyService) {
        this.accountingIdempotencyService = accountingIdempotencyService;
    }

    public JournalEntryDto recordDealerReceipt(DealerReceiptRequest request) {
        return accountingIdempotencyService.recordDealerReceipt(request);
    }

    public JournalEntryDto recordDealerReceiptSplit(DealerReceiptSplitRequest request) {
        return accountingIdempotencyService.recordDealerReceiptSplit(request);
    }
}
