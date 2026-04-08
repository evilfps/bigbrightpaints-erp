package com.bigbrightpaints.erp.e2e.accounting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.BankReconciliationItem;
import com.bigbrightpaints.erp.modules.accounting.domain.BankReconciliationItemRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.BankReconciliationSession;
import com.bigbrightpaints.erp.modules.accounting.domain.BankReconciliationSessionRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLine;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLineRepository;
import com.bigbrightpaints.erp.modules.accounting.dto.BankReconciliationSessionCompletionRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.BankReconciliationSessionCreateRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.BankReconciliationSessionDetailDto;
import com.bigbrightpaints.erp.modules.accounting.dto.BankReconciliationSessionItemsUpdateRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.BankReconciliationSessionSummaryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingService;
import com.bigbrightpaints.erp.modules.accounting.service.BankReconciliationSessionService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;

class BankReconciliationSessionPersistenceIT extends AbstractIntegrationTest {

  @Autowired private CompanyRepository companyRepository;
  @Autowired private AccountRepository accountRepository;
  @Autowired private JournalLineRepository journalLineRepository;
  @Autowired private BankReconciliationSessionRepository sessionRepository;
  @Autowired private BankReconciliationItemRepository itemRepository;
  @Autowired private AccountingService accountingService;
  @Autowired private BankReconciliationSessionService sessionService;

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
    CompanyContextHolder.clear();
  }

  @Test
  void startSession_persistsInProgressStatusOnFreshSchema() {
    Company company = useCompany("BRS-STATUS-FRESH");
    Account bankAccount = requireAccount(company, "CASH");

    BankReconciliationSessionSummaryDto response =
        sessionService.startSession(
            new BankReconciliationSessionCreateRequest(
                bankAccount.getId(),
                LocalDate.of(2026, 3, 31),
                new BigDecimal("1000.00"),
                null,
                null,
                null,
                "fresh status proof"));

    assertThat(response.status()).isEqualTo("IN_PROGRESS");

    BankReconciliationSession stored =
        sessionRepository.findByCompanyAndId(company, response.sessionId()).orElseThrow();
    assertThat(stored.getStatus().name()).isEqualTo("IN_PROGRESS");
  }

  @Test
  void
      updateItems_persistsBankItemIdAndCompletedSessionDetailReturnsMatchedAndUnmatchedStatementItems() {
    Company company = useCompany("BRS-MATCH-PERSIST");
    Account bankAccount = requireAccount(company, "CASH");
    Account revenueAccount = requireAccount(company, "REV");
    Account cogsAccount = requireAccount(company, "COGS");
    LocalDate statementDate = LocalDate.of(2026, 3, 31);

    BankReconciliationSessionSummaryDto started =
        sessionService.startSession(
            new BankReconciliationSessionCreateRequest(
                bankAccount.getId(),
                statementDate,
                new BigDecimal("1000.00"),
                null,
                null,
                null,
                "match persistence proof"));
    Long sessionId = started.sessionId();

    JournalEntryDto deposit =
        createJournal(
            bankAccount.getId(),
            revenueAccount.getId(),
            statementDate,
            "Bank deposit",
            new BigDecimal("120.00"),
            BigDecimal.ZERO);
    JournalEntryDto withdrawal =
        createJournal(
            bankAccount.getId(),
            cogsAccount.getId(),
            statementDate,
            "Bank withdrawal",
            BigDecimal.ZERO,
            new BigDecimal("30.00"));

    List<JournalLine> bankLines =
        journalLineRepository.findPostedLinesForAccountByJournalEntryIds(
            company, Set.of(deposit.id(), withdrawal.id()), bankAccount.getId());
    assertThat(bankLines).hasSize(2);
    JournalLine matchedLine =
        bankLines.stream().filter(line -> line.getDebit().compareTo(BigDecimal.ZERO) > 0).findFirst().orElseThrow();
    JournalLine unmatchedLine =
        bankLines.stream()
            .filter(line -> line.getCredit().compareTo(BigDecimal.ZERO) > 0)
            .findFirst()
            .orElseThrow();

    sessionService.updateItems(
        sessionId,
        new BankReconciliationSessionItemsUpdateRequest(
            List.of(matchedLine.getId(), unmatchedLine.getId()),
            List.of(),
            "matched one statement line",
            List.of(
                new BankReconciliationSessionItemsUpdateRequest.BankStatementMatchRequest(
                    99001L, null, matchedLine.getId()))));

    BankReconciliationSession session =
        sessionRepository.findByCompanyAndId(company, sessionId).orElseThrow();
    List<BankReconciliationItem> persistedItems =
        itemRepository.findBySessionAndJournalLineIdIn(
            session, Set.of(matchedLine.getId(), unmatchedLine.getId()));
    assertThat(persistedItems).hasSize(2);
    assertThat(
            persistedItems.stream()
                .filter(item -> item.getJournalLine().getId().equals(matchedLine.getId()))
                .findFirst()
                .orElseThrow()
                .getBankItemId())
        .isEqualTo(99001L);
    assertThat(
            persistedItems.stream()
                .filter(item -> item.getJournalLine().getId().equals(unmatchedLine.getId()))
                .findFirst()
                .orElseThrow()
                .getBankItemId())
        .isNull();

    BankReconciliationSessionDetailDto completed =
        sessionService.completeSession(
            sessionId, new BankReconciliationSessionCompletionRequest("complete with unmatched", null));

    assertThat(completed.status()).isEqualTo("COMPLETED");
    assertThat(completed.matchedItems())
        .anyMatch(
            item ->
                item.journalLineId().equals(matchedLine.getId())
                    && Long.valueOf(99001L).equals(item.bankItemId()));
    assertThat(completed.unmatchedItems())
        .anyMatch(
            item ->
                item.journalLineId() != null
                    && item.journalLineId().equals(unmatchedLine.getId())
                    && item.bankItemId() == null);
  }

  @Test
  void updateItems_rejectsDuplicateBankItemAssignmentsBeforePersistence() {
    Company company = useCompany("BRS-DUP-BANK-ITEM");
    Account bankAccount = requireAccount(company, "CASH");
    Account revenueAccount = requireAccount(company, "REV");
    Account cogsAccount = requireAccount(company, "COGS");
    LocalDate statementDate = LocalDate.of(2026, 3, 31);

    BankReconciliationSessionSummaryDto started =
        sessionService.startSession(
            new BankReconciliationSessionCreateRequest(
                bankAccount.getId(),
                statementDate,
                new BigDecimal("1000.00"),
                null,
                null,
                null,
                "duplicate bank-item validation"));
    Long sessionId = started.sessionId();

    JournalEntryDto deposit =
        createJournal(
            bankAccount.getId(),
            revenueAccount.getId(),
            statementDate,
            "Duplicate deposit",
            new BigDecimal("120.00"),
            BigDecimal.ZERO);
    JournalEntryDto withdrawal =
        createJournal(
            bankAccount.getId(),
            cogsAccount.getId(),
            statementDate,
            "Duplicate withdrawal",
            BigDecimal.ZERO,
            new BigDecimal("30.00"));

    List<JournalLine> bankLines =
        journalLineRepository.findPostedLinesForAccountByJournalEntryIds(
            company, Set.of(deposit.id(), withdrawal.id()), bankAccount.getId());
    assertThat(bankLines).hasSize(2);
    Long firstLineId = bankLines.get(0).getId();
    Long secondLineId = bankLines.get(1).getId();

    assertThatThrownBy(
            () ->
                sessionService.updateItems(
                    sessionId,
                    new BankReconciliationSessionItemsUpdateRequest(
                        List.of(firstLineId, secondLineId),
                        List.of(),
                        "duplicate bank-item",
                        List.of(
                            new BankReconciliationSessionItemsUpdateRequest.BankStatementMatchRequest(
                                88001L, null, firstLineId),
                            new BankReconciliationSessionItemsUpdateRequest.BankStatementMatchRequest(
                                88001L, null, secondLineId)))))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Duplicate bankItemId assignment is not allowed")
        .hasMessageContaining("bankItemId 88001");

    BankReconciliationSession session =
        sessionRepository.findByCompanyAndId(company, sessionId).orElseThrow();
    assertThat(itemRepository.findBySessionAndJournalLineIdIn(session, Set.of(firstLineId, secondLineId)))
        .isEmpty();
  }

  private Company useCompany(String companyCode) {
    CompanyContextHolder.setCompanyCode(companyCode);
    Company company = dataSeeder.ensureCompany(companyCode, companyCode + " Ltd");
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken("recon-tester", "n/a"));
    return companyRepository.findByCodeIgnoreCase(companyCode).orElse(company);
  }

  private Account requireAccount(Company company, String code) {
    return accountRepository.findByCompanyAndCodeIgnoreCase(company, code).orElseThrow();
  }

  private JournalEntryDto createJournal(
      Long bankAccountId,
      Long counterAccountId,
      LocalDate entryDate,
      String memo,
      BigDecimal bankDebit,
      BigDecimal bankCredit) {
    return accountingService.createJournalEntry(
        new JournalEntryRequest(
            "BRS-" + memo.replace(' ', '-') + "-" + System.nanoTime(),
            entryDate,
            memo,
            null,
            null,
            false,
            List.of(
                new JournalEntryRequest.JournalLineRequest(
                    bankAccountId, memo + " bank line", bankDebit, bankCredit),
                new JournalEntryRequest.JournalLineRequest(
                    counterAccountId, memo + " offset line", bankCredit, bankDebit))));
  }
}
