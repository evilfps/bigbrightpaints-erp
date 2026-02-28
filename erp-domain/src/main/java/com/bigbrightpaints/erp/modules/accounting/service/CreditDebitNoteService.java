package com.bigbrightpaints.erp.modules.accounting.service;

import com.bigbrightpaints.erp.modules.accounting.dto.AccrualRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.BadDebtWriteOffRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.CreditNoteRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.DebitNoteRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import org.springframework.stereotype.Service;

@Service
public class CreditDebitNoteService {

    private final AccountingCoreService accountingCoreService;

    public CreditDebitNoteService(AccountingCoreService accountingCoreService) {
        this.accountingCoreService = accountingCoreService;
    }

    public JournalEntryDto postCreditNote(CreditNoteRequest request) {
        return accountingCoreService.postCreditNote(request);
    }

    public JournalEntryDto postDebitNote(DebitNoteRequest request) {
        return accountingCoreService.postDebitNote(request);
    }

    public JournalEntryDto postAccrual(AccrualRequest request) {
        return accountingCoreService.postAccrual(request);
    }

    public JournalEntryDto writeOffBadDebt(BadDebtWriteOffRequest request) {
        return accountingCoreService.writeOffBadDebt(request);
    }
}
