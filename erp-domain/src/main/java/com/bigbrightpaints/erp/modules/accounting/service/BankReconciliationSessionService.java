package com.bigbrightpaints.erp.modules.accounting.service;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.security.SecurityActorResolver;
import com.bigbrightpaints.erp.core.util.CompanyTime;
import com.bigbrightpaints.erp.core.validation.ValidationUtils;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriod;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodStatus;
import com.bigbrightpaints.erp.modules.accounting.domain.BankReconciliationItem;
import com.bigbrightpaints.erp.modules.accounting.domain.BankReconciliationItemRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.BankReconciliationSession;
import com.bigbrightpaints.erp.modules.accounting.domain.BankReconciliationSessionRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.BankReconciliationSessionStatus;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLine;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLineRepository;
import com.bigbrightpaints.erp.modules.accounting.dto.BankReconciliationRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.BankReconciliationSessionCompletionRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.BankReconciliationSessionCreateRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.BankReconciliationSessionDetailDto;
import com.bigbrightpaints.erp.modules.accounting.dto.BankReconciliationSessionItemsUpdateRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.BankReconciliationSessionSummaryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.BankReconciliationSummaryDto;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.shared.dto.PageResponse;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class BankReconciliationSessionService {

    private static final int MAX_PAGE_SIZE = 100;

    private final CompanyContextService companyContextService;
    private final AccountRepository accountRepository;
    private final AccountingPeriodRepository accountingPeriodRepository;
    private final BankReconciliationSessionRepository sessionRepository;
    private final BankReconciliationItemRepository itemRepository;
    private final JournalLineRepository journalLineRepository;
    private final ReconciliationService reconciliationService;
    private final AccountingPeriodService accountingPeriodService;
    private final ReferenceNumberService referenceNumberService;

    public BankReconciliationSessionService(CompanyContextService companyContextService,
                                            AccountRepository accountRepository,
                                            AccountingPeriodRepository accountingPeriodRepository,
                                            BankReconciliationSessionRepository sessionRepository,
                                            BankReconciliationItemRepository itemRepository,
                                            JournalLineRepository journalLineRepository,
                                            ReconciliationService reconciliationService,
                                            AccountingPeriodService accountingPeriodService,
                                            ReferenceNumberService referenceNumberService) {
        this.companyContextService = companyContextService;
        this.accountRepository = accountRepository;
        this.accountingPeriodRepository = accountingPeriodRepository;
        this.sessionRepository = sessionRepository;
        this.itemRepository = itemRepository;
        this.journalLineRepository = journalLineRepository;
        this.reconciliationService = reconciliationService;
        this.accountingPeriodService = accountingPeriodService;
        this.referenceNumberService = referenceNumberService;
    }

    @Transactional
    public BankReconciliationSessionSummaryDto startSession(BankReconciliationSessionCreateRequest request) {
        ValidationUtils.requireNotNull(request, "request");
        Company company = companyContextService.requireCurrentCompany();
        Account bankAccount = resolveBankAccount(company, request.bankAccountId());
        LocalDate statementDate = ValidationUtils.requireNotNull(request.statementDate(), "statementDate");
        BigDecimal statementEndingBalance = ValidationUtils.requireNotNull(request.statementEndingBalance(), "statementEndingBalance");

        BankReconciliationSession session = new BankReconciliationSession();
        session.setCompany(company);
        session.setBankAccount(bankAccount);
        session.setStatementDate(statementDate);
        session.setStatementEndingBalance(statementEndingBalance);
        session.setStatus(BankReconciliationSessionStatus.DRAFT);
        session.setCreatedBy(resolveCurrentActor());
        session.setReferenceNumber(nextSessionReference(company));
        session.setNote(normalizeNote(request.note()));
        session.setAccountingPeriod(resolveAccountingPeriod(company, request.accountingPeriodId(), statementDate, false));

        BankReconciliationSession saved = sessionRepository.save(session);
        BankReconciliationSummaryDto summary = buildSummary(saved, Collections.emptySet(), request.startDate(), request.endDate());
        return toSummary(saved, summary, 0);
    }

    @Transactional
    public BankReconciliationSessionDetailDto updateItems(Long sessionId,
                                                          BankReconciliationSessionItemsUpdateRequest request) {
        ValidationUtils.requireNotNull(request, "request");
        Company company = companyContextService.requireCurrentCompany();
        BankReconciliationSession session = requireSession(company, sessionId);
        assertSessionDraft(session);

        Set<Long> addIds = normalizeIds(request.addJournalLineIds());
        Set<Long> removeIds = normalizeIds(request.removeJournalLineIds());
        if (!addIds.isEmpty()) {
            List<JournalLine> lines = journalLineRepository.findAllById(addIds);
            if (lines.size() != addIds.size()) {
                throw new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE,
                        "One or more journal lines were not found");
            }
            Set<Long> existingLineIds = itemRepository.findJournalLineIdsBySession(session);
            String actor = resolveCurrentActor();
            for (JournalLine line : lines) {
                validateJournalLineForSession(company, session, line);
                if (existingLineIds.contains(line.getId())) {
                    continue;
                }
                BankReconciliationItem item = new BankReconciliationItem();
                item.setCompany(company);
                item.setSession(session);
                item.setJournalLine(line);
                item.setReferenceNumber(resolveReference(line));
                item.setAmount(resolveNetAmount(line));
                item.setClearedBy(actor);
                itemRepository.save(item);
            }
        }

        if (!removeIds.isEmpty()) {
            itemRepository.deleteBySessionAndJournalLineIdIn(session, removeIds);
        }

        String note = normalizeNote(request.note());
        if (note != null) {
            session.setNote(note);
            sessionRepository.save(session);
        }

        return getSessionDetail(sessionId);
    }

    @Transactional
    public BankReconciliationSessionDetailDto completeSession(Long sessionId,
                                                              BankReconciliationSessionCompletionRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        BankReconciliationSession session = requireSession(company, sessionId);
        assertSessionDraft(session);

        String note = request == null ? null : normalizeNote(request.note());
        if (note != null) {
            session.setNote(note);
        }

        Long requestedPeriodId = request == null ? null : request.accountingPeriodId();
        AccountingPeriod period = resolveAccountingPeriod(company, requestedPeriodId, session.getStatementDate(), true);
        if (period != null) {
            session.setAccountingPeriod(period);
        }

        session.setStatus(BankReconciliationSessionStatus.COMPLETED);
        session.setCompletedAt(CompanyTime.now(company));
        session.setCompletedBy(resolveCurrentActor());
        BankReconciliationSession saved = sessionRepository.save(session);

        if (saved.getAccountingPeriod() != null) {
            accountingPeriodService.confirmBankReconciliation(
                    saved.getAccountingPeriod().getId(),
                    saved.getStatementDate(),
                    saved.getNote());
        }

        return getSessionDetail(saved.getId());
    }

    @Transactional(readOnly = true)
    public PageResponse<BankReconciliationSessionSummaryDto> listSessions(int page, int size) {
        Company company = companyContextService.requireCurrentCompany();
        int safePage = Math.max(page, 0);
        int safeSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        PageRequest pageable = PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "createdAt", "id"));
        Page<BankReconciliationSession> history = sessionRepository.findHistoryByCompany(company, pageable);
        List<Long> sessionIds = history.getContent().stream()
                .map(BankReconciliationSession::getId)
                .toList();

        List<BankReconciliationItem> allItems = itemRepository.findByCompanyAndSessionIds(company, sessionIds);
        Map<Long, Long> itemCountBySession = allItems.stream()
                .collect(Collectors.groupingBy(item -> item.getSession().getId(), Collectors.counting()));

        Map<Long, Set<Long>> lineIdsBySession = allItems.stream()
                .collect(Collectors.groupingBy(
                        item -> item.getSession().getId(),
                        Collectors.mapping(item -> item.getJournalLine().getId(), Collectors.toSet())));

        List<BankReconciliationSessionSummaryDto> content = history.getContent().stream()
                .map(session -> toSummary(session,
                        buildSummary(session,
                                lineIdsBySession.getOrDefault(session.getId(), Collections.emptySet()),
                                null,
                                null),
                        itemCountBySession.getOrDefault(session.getId(), 0L).intValue()))
                .toList();
        return PageResponse.of(content, history.getTotalElements(), safePage, safeSize);
    }

    @Transactional(readOnly = true)
    public BankReconciliationSessionDetailDto getSessionDetail(Long sessionId) {
        Company company = companyContextService.requireCurrentCompany();
        BankReconciliationSession session = sessionRepository.findDetailedByCompanyAndId(company, sessionId)
                .orElseThrow(() -> new ApplicationException(ErrorCode.BUSINESS_ENTITY_NOT_FOUND,
                        "Bank reconciliation session not found: " + sessionId));

        List<BankReconciliationItem> items = itemRepository.findDetailedBySession(session);
        Set<Long> clearedLineIds = items.stream()
                .map(item -> item.getJournalLine().getId())
                .collect(Collectors.toSet());
        BankReconciliationSummaryDto summary = buildSummary(session, clearedLineIds, null, null);
        List<BankReconciliationSessionDetailDto.ClearedItemDto> clearedItems = items.stream()
                .map(this::toClearedItem)
                .toList();

        return new BankReconciliationSessionDetailDto(
                session.getId(),
                session.getReferenceNumber(),
                session.getBankAccount().getId(),
                session.getBankAccount().getCode(),
                session.getBankAccount().getName(),
                session.getStatementDate(),
                session.getStatementEndingBalance(),
                session.getStatus().name(),
                session.getAccountingPeriod() != null ? session.getAccountingPeriod().getId() : null,
                session.getNote(),
                session.getCreatedBy(),
                session.getCreatedAt(),
                session.getCompletedBy(),
                session.getCompletedAt(),
                clearedItems,
                summary.unclearedDeposits(),
                summary.unclearedChecks(),
                summary
        );
    }

    @Transactional
    public BankReconciliationSummaryDto reconcileLegacy(BankReconciliationRequest request) {
        ValidationUtils.requireNotNull(request, "request");

        BankReconciliationSessionCreateRequest createRequest = new BankReconciliationSessionCreateRequest(
                request.bankAccountId(),
                request.statementDate(),
                request.statementEndingBalance(),
                request.startDate(),
                request.endDate(),
                request.accountingPeriodId(),
                request.note()
        );
        BankReconciliationSessionSummaryDto sessionSummary = startSession(createRequest);

        BankReconciliationSessionItemsUpdateRequest updateRequest = new BankReconciliationSessionItemsUpdateRequest(
                resolveJournalLineIds(sessionSummary.sessionId(), request.clearedReferences(), request.startDate(), request.endDate()),
                Collections.emptyList(),
                request.note()
        );
        updateItems(sessionSummary.sessionId(), updateRequest);

        if (Boolean.TRUE.equals(request.markAsComplete())) {
            completeSession(sessionSummary.sessionId(), new BankReconciliationSessionCompletionRequest(
                    request.note(),
                    request.accountingPeriodId()));
        }

        return buildSummary(
                requireSession(companyContextService.requireCurrentCompany(), sessionSummary.sessionId()),
                null,
                request.startDate(),
                request.endDate());
    }

    private List<Long> resolveJournalLineIds(Long sessionId,
                                             Collection<String> clearedReferences,
                                             LocalDate startDate,
                                             LocalDate endDate) {
        if (clearedReferences == null || clearedReferences.isEmpty()) {
            return List.of();
        }
        BankReconciliationSession session = requireSession(companyContextService.requireCurrentCompany(), sessionId);
        LocalDate start = startDate != null ? startDate : session.getStatementDate().withDayOfMonth(1);
        LocalDate end = endDate != null ? endDate : session.getStatementDate();
        if (start.isAfter(end)) {
            throw ValidationUtils.invalidInput("startDate must be on or before endDate");
        }

        Set<String> normalizedRefs = clearedReferences.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(StringUtils::hasText)
                .collect(Collectors.toSet());
        if (normalizedRefs.isEmpty()) {
            return List.of();
        }

        List<JournalLine> lines = journalLineRepository.findLinesForAccountBetween(
                session.getCompany(),
                session.getBankAccount().getId(),
                start,
                end);

        return lines.stream()
                .filter(line -> {
                    String ref = resolveReference(line);
                    return StringUtils.hasText(ref) && normalizedRefs.stream()
                            .anyMatch(candidate -> candidate.equalsIgnoreCase(ref.trim()));
                })
                .map(JournalLine::getId)
                .distinct()
                .toList();
    }

    private BankReconciliationSessionSummaryDto toSummary(BankReconciliationSession session,
                                                           BankReconciliationSummaryDto summary,
                                                           int itemCount) {
        return new BankReconciliationSessionSummaryDto(
                session.getId(),
                session.getReferenceNumber(),
                session.getBankAccount().getId(),
                session.getBankAccount().getCode(),
                session.getBankAccount().getName(),
                session.getStatementDate(),
                session.getStatementEndingBalance(),
                session.getStatus().name(),
                session.getCreatedBy(),
                session.getCreatedAt(),
                session.getCompletedAt(),
                summary,
                itemCount);
    }

    private BankReconciliationSummaryDto buildSummary(BankReconciliationSession session,
                                                      Collection<Long> clearedJournalLineIds,
                                                      LocalDate startDate,
                                                      LocalDate endDate) {
        LocalDate start = startDate != null ? startDate : session.getStatementDate().withDayOfMonth(1);
        LocalDate end = endDate != null ? endDate : session.getStatementDate();

        if (start.isAfter(end)) {
            throw ValidationUtils.invalidInput("startDate must be on or before endDate");
        }

        Set<Long> clearedIds = clearedJournalLineIds == null
                ? itemRepository.findJournalLineIdsBySession(session)
                : new HashSet<>(clearedJournalLineIds);

        return reconciliationService.reconcileBankAccount(
                session.getBankAccount().getId(),
                session.getStatementDate(),
                session.getStatementEndingBalance(),
                start,
                end,
                clearedIds,
                Collections.emptySet());
    }

    private void validateJournalLineForSession(Company company,
                                               BankReconciliationSession session,
                                               JournalLine line) {
        if (line.getJournalEntry() == null
                || line.getJournalEntry().getCompany() == null
                || !Objects.equals(line.getJournalEntry().getCompany().getId(), company.getId())) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE,
                    "Journal line does not belong to the active company");
        }
        if (line.getAccount() == null || !Objects.equals(line.getAccount().getId(), session.getBankAccount().getId())) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "Journal line does not belong to the session bank account");
        }
    }

    private BankReconciliationSession requireSession(Company company, Long sessionId) {
        if (sessionId == null) {
            throw new ApplicationException(ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD,
                    "sessionId is required");
        }
        return sessionRepository.findByCompanyAndId(company, sessionId)
                .orElseThrow(() -> new ApplicationException(ErrorCode.BUSINESS_ENTITY_NOT_FOUND,
                        "Bank reconciliation session not found: " + sessionId));
    }

    private void assertSessionDraft(BankReconciliationSession session) {
        if (session.getStatus() != BankReconciliationSessionStatus.DRAFT) {
            throw new ApplicationException(ErrorCode.BUSINESS_INVALID_STATE,
                    "Bank reconciliation session is already completed");
        }
    }

    private String resolveReference(JournalLine line) {
        if (line.getJournalEntry() == null) {
            return null;
        }
        return line.getJournalEntry().getReferenceNumber();
    }

    private BigDecimal resolveNetAmount(JournalLine line) {
        BigDecimal debit = line.getDebit() == null ? BigDecimal.ZERO : line.getDebit();
        BigDecimal credit = line.getCredit() == null ? BigDecimal.ZERO : line.getCredit();
        return debit.subtract(credit);
    }

    private String nextSessionReference(Company company) {
        String raw = referenceNumberService.nextJournalReference(company);
        return "BANK-RECON-" + raw;
    }

    private String resolveCurrentActor() {
        return SecurityActorResolver.resolveActorWithSystemProcessFallback();
    }

    private Account resolveBankAccount(Company company, Long accountId) {
        if (accountId == null) {
            throw new ApplicationException(ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD,
                    "bankAccountId is required");
        }
        Account account = accountRepository.findByCompanyAndId(company, accountId)
                .orElseThrow(() -> new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE,
                        "Bank account not found"));
        if (account.getType() != AccountType.ASSET) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "Bank reconciliation account must be an ASSET account");
        }
        return account;
    }

    private AccountingPeriod resolveAccountingPeriod(Company company,
                                                     Long accountingPeriodId,
                                                     LocalDate statementDate,
                                                     boolean requireOpen) {
        if (accountingPeriodId == null) {
            return null;
        }
        AccountingPeriod period = accountingPeriodRepository.findByCompanyAndId(company, accountingPeriodId)
                .orElseThrow(() -> new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE,
                        "Accounting period not found"));

        if (statementDate != null && (period.getYear() != statementDate.getYear()
                || period.getMonth() != statementDate.getMonthValue())) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "Accounting period does not match statement date month");
        }

        if (requireOpen && period.getStatus() != AccountingPeriodStatus.OPEN) {
            throw new ApplicationException(ErrorCode.BUSINESS_INVALID_STATE,
                    "Accounting period " + period.getLabel() + " is locked/closed");
        }
        return period;
    }

    private String normalizeNote(String note) {
        if (!StringUtils.hasText(note)) {
            return null;
        }
        return note.trim();
    }

    private Set<Long> normalizeIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptySet();
        }
        Set<Long> normalized = new HashSet<>();
        for (Long id : ids) {
            if (id == null) {
                continue;
            }
            if (id <= 0L) {
                throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                        "Journal line ids must be positive");
            }
            normalized.add(id);
        }
        return normalized;
    }

    private BankReconciliationSessionDetailDto.ClearedItemDto toClearedItem(BankReconciliationItem item) {
        JournalLine line = item.getJournalLine();
        BigDecimal debit = line.getDebit() == null ? BigDecimal.ZERO : line.getDebit();
        BigDecimal credit = line.getCredit() == null ? BigDecimal.ZERO : line.getCredit();
        return new BankReconciliationSessionDetailDto.ClearedItemDto(
                item.getId(),
                line.getId(),
                line.getJournalEntry() != null ? line.getJournalEntry().getId() : null,
                item.getReferenceNumber(),
                line.getJournalEntry() != null ? line.getJournalEntry().getEntryDate() : null,
                line.getJournalEntry() != null ? line.getJournalEntry().getMemo() : null,
                debit,
                credit,
                debit.subtract(credit),
                item.getClearedAt(),
                item.getClearedBy()
        );
    }
}
