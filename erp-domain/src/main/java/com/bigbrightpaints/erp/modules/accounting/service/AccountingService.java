package com.bigbrightpaints.erp.modules.accounting.service;

import com.bigbrightpaints.erp.modules.accounting.domain.*;
import com.bigbrightpaints.erp.modules.accounting.dto.*;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

@Service
public class AccountingService {

    private final CompanyContextService companyContextService;
    private final AccountRepository accountRepository;
    private final JournalEntryRepository journalEntryRepository;
    private final DealerRepository dealerRepository;

    public AccountingService(CompanyContextService companyContextService,
                             AccountRepository accountRepository,
                             JournalEntryRepository journalEntryRepository,
                             DealerRepository dealerRepository) {
        this.companyContextService = companyContextService;
        this.accountRepository = accountRepository;
        this.journalEntryRepository = journalEntryRepository;
        this.dealerRepository = dealerRepository;
    }

    /* Accounts */
    public List<AccountDto> listAccounts() {
        Company company = companyContextService.requireCurrentCompany();
        return accountRepository.findByCompanyOrderByCodeAsc(company).stream().map(this::toDto).toList();
    }

    @Transactional
    public AccountDto createAccount(AccountRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        Account account = new Account();
        account.setCompany(company);
        account.setCode(request.code());
        account.setName(request.name());
        account.setType(request.type());
        return toDto(accountRepository.save(account));
    }

    /* Journal Entries */
    public List<JournalEntryDto> listJournalEntries(Long dealerId) {
        Company company = companyContextService.requireCurrentCompany();
        List<JournalEntry> entries;
        if (dealerId != null) {
            Dealer dealer = requireDealer(company, dealerId);
            entries = journalEntryRepository.findByCompanyAndDealerOrderByEntryDateDesc(company, dealer);
        } else {
            entries = journalEntryRepository.findByCompanyOrderByEntryDateDesc(company);
        }
        return entries.stream().map(this::toDto).toList();
    }

    @Transactional
    public JournalEntryDto createJournalEntry(JournalEntryRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        BigDecimal totalDebit = request.lines().stream()
                .map(JournalEntryRequest.JournalLineRequest::debit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalCredit = request.lines().stream()
                .map(JournalEntryRequest.JournalLineRequest::credit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (totalDebit.subtract(totalCredit).abs().compareTo(new BigDecimal("0.01")) > 0) {
            throw new IllegalArgumentException("Journal entry must balance");
        }
        JournalEntry entry = new JournalEntry();
        entry.setCompany(company);
        entry.setReferenceNumber(request.referenceNumber());
        entry.setEntryDate(request.entryDate());
        entry.setMemo(request.memo());
        entry.setStatus("POSTED");
        if (request.dealerId() != null) {
            entry.setDealer(requireDealer(company, request.dealerId()));
        }
        for (JournalEntryRequest.JournalLineRequest lineRequest : request.lines()) {
            Account account = accountRepository.findByCompanyAndId(company, lineRequest.accountId())
                    .orElseThrow(() -> new IllegalArgumentException("Account not found"));
            JournalLine line = new JournalLine();
            line.setJournalEntry(entry);
            line.setAccount(account);
            line.setDescription(lineRequest.description());
            line.setDebit(lineRequest.debit());
            line.setCredit(lineRequest.credit());
            entry.getLines().add(line);
        }
        JournalEntry saved = journalEntryRepository.save(entry);
        return toDto(saved);
    }

    private AccountDto toDto(Account account) {
        return new AccountDto(account.getId(), account.getPublicId(), account.getCode(), account.getName(), account.getType(), account.getBalance());
    }

    private JournalEntryDto toDto(JournalEntry entry) {
        List<JournalLineDto> lines = entry.getLines().stream()
                .map(line -> new JournalLineDto(
                        line.getAccount().getId(),
                        line.getAccount().getCode(),
                        line.getDescription(),
                        line.getDebit(),
                        line.getCredit()))
                .toList();
        Dealer dealer = entry.getDealer();
        String dealerName = dealer != null ? dealer.getName() : null;
        return new JournalEntryDto(entry.getId(), entry.getPublicId(), entry.getReferenceNumber(),
                entry.getEntryDate(), entry.getMemo(), entry.getStatus(),
                dealer != null ? dealer.getId() : null, dealerName, lines);
    }

    private Dealer requireDealer(Company company, Long dealerId) {
        return dealerRepository.findByCompanyAndId(company, dealerId)
                .orElseThrow(() -> new IllegalArgumentException("Dealer not found"));
    }
}
