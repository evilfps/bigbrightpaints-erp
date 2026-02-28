package com.bigbrightpaints.erp.modules.accounting.service;

import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriod;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryReversalRequest;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class JournalEntryService {

    private final AccountingCoreService accountingCoreService;
    private final AccountingIdempotencyService accountingIdempotencyService;

    public JournalEntryService(AccountingCoreService accountingCoreService,
                               AccountingIdempotencyService accountingIdempotencyService) {
        this.accountingCoreService = accountingCoreService;
        this.accountingIdempotencyService = accountingIdempotencyService;
    }

    public List<JournalEntryDto> listJournalEntries(Long dealerId, Long supplierId, int page, int size) {
        return accountingCoreService.listJournalEntries(dealerId, supplierId, page, size);
    }

    public List<JournalEntryDto> listJournalEntries(Long dealerId) {
        return accountingCoreService.listJournalEntries(dealerId);
    }

    public List<JournalEntryDto> listJournalEntriesByReferencePrefix(String prefix) {
        return accountingCoreService.listJournalEntriesByReferencePrefix(prefix);
    }

    public JournalEntryDto createJournalEntry(JournalEntryRequest request) {
        return accountingCoreService.createJournalEntry(request);
    }

    public JournalEntryDto createManualJournalEntry(JournalEntryRequest request, String idempotencyKey) {
        return accountingIdempotencyService.createManualJournalEntry(request, idempotencyKey);
    }

    public JournalEntryDto reverseJournalEntry(Long entryId, JournalEntryReversalRequest request) {
        return accountingCoreService.reverseJournalEntry(entryId, request);
    }

    JournalEntryDto reverseClosingEntryForPeriodReopen(JournalEntry entry, AccountingPeriod period, String reason) {
        return accountingCoreService.reverseClosingEntryForPeriodReopen(entry, period, reason);
    }

    public List<JournalEntryDto> cascadeReverseRelatedEntries(Long primaryEntryId, JournalEntryReversalRequest request) {
        return accountingCoreService.cascadeReverseRelatedEntries(primaryEntryId, request);
    }
}
