package com.bigbrightpaints.erp.modules.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
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
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLine;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLineRepository;
import com.bigbrightpaints.erp.modules.accounting.dto.BankReconciliationSessionCompletionRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.BankReconciliationSessionCreateRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.BankReconciliationSessionDetailDto;
import com.bigbrightpaints.erp.modules.accounting.dto.BankReconciliationSessionItemsUpdateRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.BankReconciliationSessionSummaryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.BankReconciliationSummaryDto;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.shared.dto.PageResponse;
import com.bigbrightpaints.erp.test.support.ReflectionFieldAccess;

@ExtendWith(MockitoExtension.class)
@Tag("critical")
class BankReconciliationSessionServiceTest {

  @Mock private CompanyContextService companyContextService;
  @Mock private AccountRepository accountRepository;
  @Mock private AccountingPeriodRepository accountingPeriodRepository;
  @Mock private BankReconciliationSessionRepository sessionRepository;
  @Mock private BankReconciliationItemRepository itemRepository;
  @Mock private JournalLineRepository journalLineRepository;
  @Mock private ReconciliationService reconciliationService;
  @Mock private AccountingPeriodService accountingPeriodService;
  @Mock private ReferenceNumberService referenceNumberService;

  private BankReconciliationSessionService service;
  private Company company;

  @BeforeEach
  void setUp() {
    service =
        new BankReconciliationSessionService(
            companyContextService,
            accountRepository,
            accountingPeriodRepository,
            sessionRepository,
            itemRepository,
            journalLineRepository,
            reconciliationService,
            accountingPeriodService,
            referenceNumberService);

    company = new Company();
    ReflectionFieldAccess.setField(company, "id", 7L);
    company.setCode("ACME");
    company.setTimezone("Asia/Kolkata");
    when(companyContextService.requireCurrentCompany()).thenReturn(company);
  }

  @Test
  void startSession_persistsInProgressSessionAndReturnsSummary() {
    Account bankAccount = bankAccount(99L, "BANK", "Main Bank");
    when(accountRepository.findByCompanyAndId(company, 99L)).thenReturn(Optional.of(bankAccount));
    when(referenceNumberService.nextJournalReference(company)).thenReturn("JRN-ACME-202603-0001");

    BankReconciliationSession saved = new BankReconciliationSession();
    saved.setCompany(company);
    saved.setBankAccount(bankAccount);
    saved.setStatus(BankReconciliationSessionStatus.IN_PROGRESS);
    saved.setCreatedBy("admin@acme.test");
    saved.setReferenceNumber("BANK-RECON-JRN-ACME-202603-0001");
    saved.setStatementDate(LocalDate.of(2026, 3, 31));
    saved.setStatementEndingBalance(new BigDecimal("1500.00"));
    saved.setNote("March statement");
    ReflectionFieldAccess.setField(saved, "id", 11L);
    ReflectionFieldAccess.setField(saved, "createdAt", Instant.parse("2026-03-31T08:00:00Z"));
    when(sessionRepository.save(any(BankReconciliationSession.class))).thenReturn(saved);

    BankReconciliationSummaryDto summary = summary("10.00", "5.00", "1.00", false);
    when(reconciliationService.reconcileBankAccount(
            eq(99L),
            eq(LocalDate.of(2026, 3, 31)),
            eq(new BigDecimal("1500.00")),
            eq(LocalDate.of(2026, 3, 1)),
            eq(LocalDate.of(2026, 3, 31)),
            eq(Collections.emptySet()),
            eq(Collections.emptySet())))
        .thenReturn(summary);

    BankReconciliationSessionSummaryDto response =
        service.startSession(
            new BankReconciliationSessionCreateRequest(
                99L,
                LocalDate.of(2026, 3, 31),
                new BigDecimal("1500.00"),
                null,
                null,
                null,
                "March statement"));

    assertThat(response.sessionId()).isEqualTo(11L);
    assertThat(response.status()).isEqualTo("IN_PROGRESS");
    assertThat(response.referenceNumber()).isEqualTo("BANK-RECON-JRN-ACME-202603-0001");
    assertThat(response.summary()).isEqualTo(summary);
    assertThat(response.clearedItemCount()).isZero();

    ArgumentCaptor<BankReconciliationSession> sessionCaptor =
        ArgumentCaptor.forClass(BankReconciliationSession.class);
    verify(sessionRepository).save(sessionCaptor.capture());
    BankReconciliationSession persisted = sessionCaptor.getValue();
    assertThat(persisted.getStatus()).isEqualTo(BankReconciliationSessionStatus.IN_PROGRESS);
    assertThat(persisted.getCreatedBy()).isNotBlank();
    assertThat(persisted.getReferenceNumber()).startsWith("BANK-RECON-");
    assertThat(persisted.getStatementDate()).isEqualTo(LocalDate.of(2026, 3, 31));
  }

  @Test
  void updateItems_addAndRemoveJournalLinesWithinSessionAccount() {
    Account bankAccount = bankAccount(99L, "BANK", "Main Bank");
    BankReconciliationSession session =
        session(20L, bankAccount, BankReconciliationSessionStatus.DRAFT);

    when(sessionRepository.findByCompanyAndId(company, 20L)).thenReturn(Optional.of(session));

    JournalLine lineToAdd =
        journalLine(
            1001L,
            company,
            bankAccount,
            "DEP-01",
            LocalDate.of(2026, 3, 2),
            "Deposit",
            "100.00",
            "0.00");
    when(journalLineRepository.findAllById(Set.of(1001L))).thenReturn(List.of(lineToAdd));
    when(itemRepository.findBySessionOrderByClearedAtAscIdAsc(session)).thenReturn(List.of());

    BankReconciliationSession detailed =
        session(20L, bankAccount, BankReconciliationSessionStatus.DRAFT);
    detailed.setNote("updated note");
    when(sessionRepository.findDetailedByCompanyAndId(company, 20L))
        .thenReturn(Optional.of(detailed));
    BankReconciliationItem storedItem =
        item(501L, detailed, lineToAdd, "DEP-01", "100.00", "admin@acme.test");
    when(itemRepository.findDetailedBySession(detailed)).thenReturn(List.of(storedItem));
    when(reconciliationService.reconcileBankAccount(
            eq(99L),
            eq(detailed.getStatementDate()),
            eq(detailed.getStatementEndingBalance()),
            eq(LocalDate.of(2026, 3, 1)),
            eq(LocalDate.of(2026, 3, 31)),
            eq(Collections.emptySet()),
            eq(Collections.emptySet())))
        .thenReturn(summary("100.00", "0.00", "0.00", true));

    BankReconciliationSessionDetailDto response =
        service.updateItems(
            20L,
            new BankReconciliationSessionItemsUpdateRequest(
                List.of(1001L), List.of(1002L), "updated note"));

    assertThat(response.sessionId()).isEqualTo(20L);
    assertThat(response.matchedItems()).isEmpty();
    assertThat(response.unmatchedItems()).hasSize(1);
    assertThat(response.unmatchedItems().get(0).journalLineId()).isEqualTo(1001L);

    verify(itemRepository).save(any(BankReconciliationItem.class));
    verify(itemRepository).deleteBySessionAndJournalLineIdIn(session, Set.of(1002L));
    verify(sessionRepository).save(session);
  }

  @Test
  void updateItems_rejectsJournalLineOutsideBankAccount() {
    Account bankAccount = bankAccount(99L, "BANK", "Main Bank");
    Account otherAccount = bankAccount(100L, "CASH", "Petty Cash");
    BankReconciliationSession session =
        session(20L, bankAccount, BankReconciliationSessionStatus.DRAFT);

    when(sessionRepository.findByCompanyAndId(company, 20L)).thenReturn(Optional.of(session));
    JournalLine wrongLine =
        journalLine(
            888L, company, otherAccount, "REF-1", LocalDate.now(), "wrong", "50.00", "0.00");
    when(journalLineRepository.findAllById(Set.of(888L))).thenReturn(List.of(wrongLine));

    assertThatThrownBy(
            () ->
                service.updateItems(
                    20L,
                    new BankReconciliationSessionItemsUpdateRequest(
                        List.of(888L), List.of(), null)))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("does not belong to the session bank account");
  }

  @Test
  void updateItems_rejectsDuplicateBankItemAssignmentsAcrossJournalLines() {
    Account bankAccount = bankAccount(99L, "BANK", "Main Bank");
    BankReconciliationSession session =
        session(20L, bankAccount, BankReconciliationSessionStatus.IN_PROGRESS);
    when(sessionRepository.findByCompanyAndId(company, 20L)).thenReturn(Optional.of(session));

    assertThatThrownBy(
            () ->
                service.updateItems(
                    20L,
                    new BankReconciliationSessionItemsUpdateRequest(
                        List.of(7601L, 7602L),
                        List.of(),
                        null,
                        List.of(
                            new BankReconciliationSessionItemsUpdateRequest
                                .BankStatementMatchRequest(9001L, null, 7601L),
                            new BankReconciliationSessionItemsUpdateRequest
                                .BankStatementMatchRequest(9001L, null, 7602L)))))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Duplicate bankItemId assignment is not allowed")
        .hasMessageContaining("bankItemId 9001");

    verify(journalLineRepository, never()).findAllById(any());
    verify(itemRepository, never()).save(any(BankReconciliationItem.class));
  }

  @Test
  void updateItems_rejectsBankItemAssignmentConflictingWithPersistedSessionItem() {
    Account bankAccount = bankAccount(99L, "BANK", "Main Bank");
    BankReconciliationSession session =
        session(27L, bankAccount, BankReconciliationSessionStatus.IN_PROGRESS);
    when(sessionRepository.findByCompanyAndId(company, 27L)).thenReturn(Optional.of(session));

    JournalLine existingMatchedLine =
        journalLine(
            7701L,
            company,
            bankAccount,
            "MATCHED-EXISTING",
            LocalDate.of(2026, 3, 12),
            "Existing matched line",
            "25.00",
            "0.00");
    when(itemRepository.findBySessionOrderByClearedAtAscIdAsc(session))
        .thenReturn(
            List.of(
                item(
                    8701L,
                    session,
                    existingMatchedLine,
                    "MATCHED-EXISTING",
                    "25.00",
                    "admin",
                    9001L)));

    assertThatThrownBy(
            () ->
                service.updateItems(
                    27L,
                    new BankReconciliationSessionItemsUpdateRequest(
                        List.of(7702L),
                        List.of(),
                        null,
                        List.of(
                            new BankReconciliationSessionItemsUpdateRequest
                                .BankStatementMatchRequest(9001L, null, 7702L)))))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Duplicate bankItemId assignment is not allowed")
        .hasMessageContaining("bankItemId 9001");

    verify(journalLineRepository, never()).findAllById(any());
    verify(itemRepository, never()).deleteBySessionAndJournalLineIdIn(any(), any());
    verify(itemRepository, never()).save(any(BankReconciliationItem.class));
  }

  @Test
  void updateItems_allowsBankItemMoveWhenOriginalLineRemovedInSameRequest() {
    Account bankAccount = bankAccount(99L, "BANK", "Main Bank");
    BankReconciliationSession session =
        session(28L, bankAccount, BankReconciliationSessionStatus.IN_PROGRESS);
    when(sessionRepository.findByCompanyAndId(company, 28L)).thenReturn(Optional.of(session));

    JournalLine sourceLine =
        journalLine(
            7801L,
            company,
            bankAccount,
            "MATCH-SOURCE",
            LocalDate.of(2026, 3, 12),
            "Source matched line",
            "40.00",
            "0.00");
    JournalLine targetLine =
        journalLine(
            7802L,
            company,
            bankAccount,
            "MATCH-TARGET",
            LocalDate.of(2026, 3, 13),
            "Target matched line",
            "40.00",
            "0.00");

    when(itemRepository.findBySessionOrderByClearedAtAscIdAsc(session))
        .thenReturn(
            List.of(item(8801L, session, sourceLine, "MATCH-SOURCE", "40.00", "admin", 9001L)));
    when(journalLineRepository.findAllById(Set.of(7802L))).thenReturn(List.of(targetLine));

    BankReconciliationSession detailed =
        session(28L, bankAccount, BankReconciliationSessionStatus.IN_PROGRESS);
    when(sessionRepository.findDetailedByCompanyAndId(company, 28L))
        .thenReturn(Optional.of(detailed));
    when(itemRepository.findDetailedBySession(detailed))
        .thenReturn(
            List.of(item(8802L, detailed, targetLine, "MATCH-TARGET", "40.00", "admin", 9001L)));
    when(reconciliationService.reconcileBankAccount(
            eq(99L),
            eq(detailed.getStatementDate()),
            eq(detailed.getStatementEndingBalance()),
            eq(LocalDate.of(2026, 3, 1)),
            eq(LocalDate.of(2026, 3, 31)),
            eq(Set.of(7802L)),
            eq(Collections.emptySet())))
        .thenReturn(summary("0.00", "0.00", "0.00", true));

    BankReconciliationSessionDetailDto response =
        service.updateItems(
            28L,
            new BankReconciliationSessionItemsUpdateRequest(
                List.of(7802L),
                List.of(7801L),
                "move existing bank item",
                List.of(
                    new BankReconciliationSessionItemsUpdateRequest.BankStatementMatchRequest(
                        9001L, null, 7802L))));

    assertThat(response.matchedItems()).hasSize(1);
    assertThat(response.matchedItems().get(0).journalLineId()).isEqualTo(7802L);
    assertThat(response.matchedItems().get(0).bankItemId()).isEqualTo(9001L);
    var persistenceOrder = inOrder(itemRepository);
    persistenceOrder
        .verify(itemRepository)
        .deleteBySessionAndJournalLineIdIn(session, Set.of(7801L));
    persistenceOrder.verify(itemRepository).flush();
    persistenceOrder.verify(itemRepository).save(any(BankReconciliationItem.class));
  }

  @Test
  void updateItems_allowsSwappingPersistedBankItemAssignmentsInSingleRequest() {
    Account bankAccount = bankAccount(99L, "BANK", "Main Bank");
    BankReconciliationSession session =
        session(29L, bankAccount, BankReconciliationSessionStatus.IN_PROGRESS);
    when(sessionRepository.findByCompanyAndId(company, 29L)).thenReturn(Optional.of(session));

    JournalLine firstLine =
        journalLine(
            7901L,
            company,
            bankAccount,
            "SWAP-FIRST",
            LocalDate.of(2026, 3, 14),
            "First persisted match",
            "50.00",
            "0.00");
    JournalLine secondLine =
        journalLine(
            7902L,
            company,
            bankAccount,
            "SWAP-SECOND",
            LocalDate.of(2026, 3, 14),
            "Second persisted match",
            "0.00",
            "50.00");
    BankReconciliationItem firstPersisted =
        item(8901L, session, firstLine, "SWAP-FIRST", "50.00", "admin", 91001L);
    BankReconciliationItem secondPersisted =
        item(8902L, session, secondLine, "SWAP-SECOND", "50.00", "admin", 91002L);

    when(itemRepository.findBySessionOrderByClearedAtAscIdAsc(session))
        .thenReturn(List.of(firstPersisted, secondPersisted));
    when(journalLineRepository.findAllById(Set.of(7901L, 7902L)))
        .thenReturn(List.of(firstLine, secondLine));

    BankReconciliationSession detailed =
        session(29L, bankAccount, BankReconciliationSessionStatus.IN_PROGRESS);
    when(sessionRepository.findDetailedByCompanyAndId(company, 29L))
        .thenReturn(Optional.of(detailed));
    when(itemRepository.findDetailedBySession(detailed))
        .thenAnswer(invocation -> List.of(firstPersisted, secondPersisted));
    when(reconciliationService.reconcileBankAccount(
            eq(99L),
            eq(detailed.getStatementDate()),
            eq(detailed.getStatementEndingBalance()),
            eq(LocalDate.of(2026, 3, 1)),
            eq(LocalDate.of(2026, 3, 31)),
            eq(Set.of(7901L, 7902L)),
            eq(Collections.emptySet())))
        .thenReturn(summary("0.00", "0.00", "0.00", true));

    BankReconciliationSessionDetailDto response =
        service.updateItems(
            29L,
            new BankReconciliationSessionItemsUpdateRequest(
                List.of(7901L, 7902L),
                List.of(),
                null,
                List.of(
                    new BankReconciliationSessionItemsUpdateRequest.BankStatementMatchRequest(
                        91002L, null, 7901L),
                    new BankReconciliationSessionItemsUpdateRequest.BankStatementMatchRequest(
                        91001L, null, 7902L))));

    assertThat(response.matchedItems())
        .anyMatch(
            item ->
                item.journalLineId().equals(7901L)
                    && Long.valueOf(91002L).equals(item.bankItemId()))
        .anyMatch(
            item ->
                item.journalLineId().equals(7902L)
                    && Long.valueOf(91001L).equals(item.bankItemId()));
    var persistenceOrder = inOrder(itemRepository);
    persistenceOrder.verify(itemRepository).save(firstPersisted);
    persistenceOrder.verify(itemRepository).save(secondPersisted);
    persistenceOrder.verify(itemRepository).flush();
    persistenceOrder.verify(itemRepository).save(firstPersisted);
    persistenceOrder.verify(itemRepository).save(secondPersisted);
  }

  @Test
  void updateItems_keepsPersistedAssignmentWhenRequestedBankItemAlreadyMatches() {
    Account bankAccount = bankAccount(99L, "BANK", "Main Bank");
    BankReconciliationSession session =
        session(30L, bankAccount, BankReconciliationSessionStatus.IN_PROGRESS);
    when(sessionRepository.findByCompanyAndId(company, 30L)).thenReturn(Optional.of(session));

    JournalLine line =
        journalLine(
            8001L,
            company,
            bankAccount,
            "MATCH-STABLE",
            LocalDate.of(2026, 3, 14),
            "Stable assignment",
            "50.00",
            "0.00");
    BankReconciliationItem persisted =
        item(9001L, session, line, "MATCH-STABLE", "50.00", "admin", 9301L);
    when(itemRepository.findBySessionOrderByClearedAtAscIdAsc(session))
        .thenReturn(List.of(persisted));
    when(journalLineRepository.findAllById(Set.of(8001L))).thenReturn(List.of(line));

    BankReconciliationSession detailed =
        session(30L, bankAccount, BankReconciliationSessionStatus.IN_PROGRESS);
    when(sessionRepository.findDetailedByCompanyAndId(company, 30L))
        .thenReturn(Optional.of(detailed));
    when(itemRepository.findDetailedBySession(detailed)).thenReturn(List.of(persisted));
    when(reconciliationService.reconcileBankAccount(
            eq(99L),
            eq(detailed.getStatementDate()),
            eq(detailed.getStatementEndingBalance()),
            eq(LocalDate.of(2026, 3, 1)),
            eq(LocalDate.of(2026, 3, 31)),
            eq(Set.of(8001L)),
            eq(Collections.emptySet())))
        .thenReturn(summary("0.00", "0.00", "0.00", true));

    service.updateItems(
        30L,
        new BankReconciliationSessionItemsUpdateRequest(
            List.of(8001L),
            List.of(),
            null,
            List.of(
                new BankReconciliationSessionItemsUpdateRequest.BankStatementMatchRequest(
                    9301L, null, 8001L))));

    verify(itemRepository, never()).flush();
    verify(itemRepository, never()).save(persisted);
  }

  @Test
  void updateItems_reassignsPersistedNullHolderWithoutClearingPass() {
    Account bankAccount = bankAccount(99L, "BANK", "Main Bank");
    BankReconciliationSession session =
        session(31L, bankAccount, BankReconciliationSessionStatus.IN_PROGRESS);
    when(sessionRepository.findByCompanyAndId(company, 31L)).thenReturn(Optional.of(session));

    JournalLine line =
        journalLine(
            8101L,
            company,
            bankAccount,
            "MATCH-NULL",
            LocalDate.of(2026, 3, 15),
            "Null holder",
            "40.00",
            "0.00");
    BankReconciliationItem persisted =
        item(9101L, session, line, "MATCH-NULL", "40.00", "admin", null);
    when(itemRepository.findBySessionOrderByClearedAtAscIdAsc(session))
        .thenReturn(List.of(persisted));
    when(journalLineRepository.findAllById(Set.of(8101L))).thenReturn(List.of(line));

    BankReconciliationSession detailed =
        session(31L, bankAccount, BankReconciliationSessionStatus.IN_PROGRESS);
    when(sessionRepository.findDetailedByCompanyAndId(company, 31L))
        .thenReturn(Optional.of(detailed));
    when(itemRepository.findDetailedBySession(detailed)).thenReturn(List.of(persisted));
    when(reconciliationService.reconcileBankAccount(
            eq(99L),
            eq(detailed.getStatementDate()),
            eq(detailed.getStatementEndingBalance()),
            eq(LocalDate.of(2026, 3, 1)),
            eq(LocalDate.of(2026, 3, 31)),
            eq(Set.of(8101L)),
            eq(Collections.emptySet())))
        .thenReturn(summary("0.00", "0.00", "0.00", true));

    service.updateItems(
        31L,
        new BankReconciliationSessionItemsUpdateRequest(
            List.of(8101L),
            List.of(),
            null,
            List.of(
                new BankReconciliationSessionItemsUpdateRequest.BankStatementMatchRequest(
                    9401L, null, 8101L))));

    verify(itemRepository, never()).flush();
    verify(itemRepository).save(persisted);
  }

  @Test
  void updateItems_skipsSecondReassignmentWriteWhenTargetAlreadyClearedToNull() {
    Account bankAccount = bankAccount(99L, "BANK", "Main Bank");
    BankReconciliationSession session =
        session(32L, bankAccount, BankReconciliationSessionStatus.IN_PROGRESS);
    when(sessionRepository.findByCompanyAndId(company, 32L)).thenReturn(Optional.of(session));

    JournalLine line =
        journalLine(
            8201L,
            company,
            bankAccount,
            "MATCH-CLEAR",
            LocalDate.of(2026, 3, 16),
            "Clear to null",
            "30.00",
            "0.00");
    BankReconciliationItem persisted =
        item(9201L, session, line, "MATCH-CLEAR", "30.00", "admin", 9501L);
    when(itemRepository.findBySessionOrderByClearedAtAscIdAsc(session))
        .thenReturn(List.of(persisted));
    when(journalLineRepository.findAllById(Set.of(8201L))).thenReturn(List.of(line));

    BankReconciliationSession detailed =
        session(32L, bankAccount, BankReconciliationSessionStatus.IN_PROGRESS);
    when(sessionRepository.findDetailedByCompanyAndId(company, 32L))
        .thenReturn(Optional.of(detailed));
    when(itemRepository.findDetailedBySession(detailed)).thenReturn(List.of(persisted));
    when(reconciliationService.reconcileBankAccount(
            eq(99L),
            eq(detailed.getStatementDate()),
            eq(detailed.getStatementEndingBalance()),
            eq(LocalDate.of(2026, 3, 1)),
            eq(LocalDate.of(2026, 3, 31)),
            eq(Collections.emptySet()),
            eq(Collections.emptySet())))
        .thenReturn(summary("0.00", "0.00", "0.00", true));

    service.updateItems(
        32L,
        new BankReconciliationSessionItemsUpdateRequest(
            List.of(8201L),
            List.of(),
            null,
            List.of(
                new BankReconciliationSessionItemsUpdateRequest.BankStatementMatchRequest(
                    null, null, 8201L))));

    verify(itemRepository).save(persisted);
    verify(itemRepository).flush();
  }

  @Test
  void updateItems_acceptsMatchPayloadUsingJournalEntryIds() {
    Account bankAccount = bankAccount(99L, "BANK", "Main Bank");
    BankReconciliationSession session =
        session(26L, bankAccount, BankReconciliationSessionStatus.IN_PROGRESS);
    when(sessionRepository.findByCompanyAndId(company, 26L)).thenReturn(Optional.of(session));
    when(itemRepository.findBySessionOrderByClearedAtAscIdAsc(session)).thenReturn(List.of());

    JournalLine matchedLine =
        journalLine(
            7601L,
            company,
            bankAccount,
            "MATCH-01",
            LocalDate.of(2026, 3, 11),
            "Matched line",
            "40.00",
            "0.00");
    Long journalEntryId = matchedLine.getJournalEntry().getId();
    when(journalLineRepository.findPostedLinesForAccountByJournalEntryIds(
            company, Set.of(journalEntryId), bankAccount.getId()))
        .thenReturn(List.of(matchedLine));
    when(journalLineRepository.findAllById(Set.of(7601L))).thenReturn(List.of(matchedLine));

    BankReconciliationSession detailed =
        session(26L, bankAccount, BankReconciliationSessionStatus.IN_PROGRESS);
    when(sessionRepository.findDetailedByCompanyAndId(company, 26L))
        .thenReturn(Optional.of(detailed));
    when(itemRepository.findDetailedBySession(detailed))
        .thenReturn(List.of(item(8601L, detailed, matchedLine, "MATCH-01", "40.00", "admin", 1L)));
    when(reconciliationService.reconcileBankAccount(
            eq(99L),
            eq(detailed.getStatementDate()),
            eq(detailed.getStatementEndingBalance()),
            eq(LocalDate.of(2026, 3, 1)),
            eq(LocalDate.of(2026, 3, 31)),
            eq(Set.of(7601L)),
            eq(Collections.emptySet())))
        .thenReturn(summary("0.00", "0.00", "0.00", true));

    BankReconciliationSessionDetailDto response =
        service.updateItems(
            26L,
            new BankReconciliationSessionItemsUpdateRequest(
                List.of(),
                List.of(),
                null,
                List.of(
                    new BankReconciliationSessionItemsUpdateRequest.BankStatementMatchRequest(
                        1L, journalEntryId, null))));

    assertThat(response.matchedItems()).hasSize(1);
    assertThat(response.matchedItems().get(0).journalLineId()).isEqualTo(7601L);
    assertThat(response.matchedItems().get(0).bankItemId()).isEqualTo(1L);
    assertThat(response.unmatchedItems()).isEmpty();
    verify(journalLineRepository)
        .findPostedLinesForAccountByJournalEntryIds(
            company, Set.of(journalEntryId), bankAccount.getId());
    ArgumentCaptor<BankReconciliationItem> persistedItemCaptor =
        ArgumentCaptor.forClass(BankReconciliationItem.class);
    verify(itemRepository).save(persistedItemCaptor.capture());
    assertThat(persistedItemCaptor.getValue().getBankItemId()).isEqualTo(1L);
  }

  @Test
  void completeSession_marksCompletedAndConfirmsPeriodWhenLinked() {
    Account bankAccount = bankAccount(99L, "BANK", "Main Bank");
    AccountingPeriod period = new AccountingPeriod();
    ReflectionFieldAccess.setField(period, "id", 61L);
    period.setCompany(company);
    period.setYear(2026);
    period.setMonth(3);
    period.setStatus(AccountingPeriodStatus.OPEN);

    BankReconciliationSession draft =
        session(40L, bankAccount, BankReconciliationSessionStatus.DRAFT);
    draft.setAccountingPeriod(period);
    draft.setNote("closing note");

    when(sessionRepository.findByCompanyAndId(company, 40L)).thenReturn(Optional.of(draft));
    when(accountingPeriodRepository.findByCompanyAndId(company, 61L))
        .thenReturn(Optional.of(period));
    when(sessionRepository.save(draft)).thenReturn(draft);
    when(sessionRepository.findDetailedByCompanyAndId(company, 40L)).thenReturn(Optional.of(draft));
    when(itemRepository.findDetailedBySession(draft)).thenReturn(List.of());
    when(reconciliationService.reconcileBankAccount(
            eq(99L),
            eq(draft.getStatementDate()),
            eq(draft.getStatementEndingBalance()),
            eq(LocalDate.of(2026, 3, 1)),
            eq(LocalDate.of(2026, 3, 31)),
            eq(Collections.emptySet()),
            eq(Collections.emptySet())))
        .thenReturn(summary("0.00", "0.00", "0.00", true));

    BankReconciliationSessionDetailDto response =
        service.completeSession(
            40L, new BankReconciliationSessionCompletionRequest("closing note", 61L));

    assertThat(response.status()).isEqualTo("COMPLETED");
    verify(accountingPeriodService)
        .confirmBankReconciliation(61L, draft.getStatementDate(), "closing note");
    assertThat(draft.getCompletedAt()).isNotNull();
    assertThat(draft.getCompletedBy()).isNotBlank();
  }

  @Test
  void listSessions_returnsPaginatedNewestFirstHistory() {
    Account bankA = bankAccount(90L, "BANK-A", "Bank A");
    Account bankB = bankAccount(91L, "BANK-B", "Bank B");

    BankReconciliationSession sessionNew =
        session(101L, bankA, BankReconciliationSessionStatus.COMPLETED);
    ReflectionFieldAccess.setField(sessionNew, "createdAt", Instant.parse("2026-03-20T10:00:00Z"));
    ReflectionFieldAccess.setField(
        sessionNew, "completedAt", Instant.parse("2026-03-21T09:00:00Z"));
    BankReconciliationSession sessionOld =
        session(100L, bankB, BankReconciliationSessionStatus.DRAFT);
    ReflectionFieldAccess.setField(sessionOld, "createdAt", Instant.parse("2026-03-10T10:00:00Z"));

    Page<BankReconciliationSession> page =
        new PageImpl<>(
            List.of(sessionNew, sessionOld),
            PageRequest.of(0, 2, Sort.by(Sort.Direction.DESC, "createdAt", "id")),
            2);
    when(sessionRepository.findHistoryByCompany(eq(company), any(PageRequest.class)))
        .thenReturn(page);

    JournalLine lineA =
        journalLine(
            3001L, company, bankA, "DEP-A", LocalDate.of(2026, 3, 15), "memo", "20.00", "0.00");
    JournalLine lineB =
        journalLine(
            3002L, company, bankB, "CHK-B", LocalDate.of(2026, 3, 9), "memo", "0.00", "15.00");
    BankReconciliationItem itemA = item(7001L, sessionNew, lineA, "DEP-A", "20.00", "u1", 9001L);
    BankReconciliationItem itemB = item(7002L, sessionOld, lineB, "CHK-B", "-15.00", "u2");
    when(itemRepository.findByCompanyAndSessionIds(company, List.of(101L, 100L)))
        .thenReturn(List.of(itemA, itemB));

    when(reconciliationService.reconcileBankAccount(
            eq(90L), any(), any(), any(), any(), eq(Set.of(3001L)), any()))
        .thenReturn(summary("20.00", "0.00", "1.00", false));
    when(reconciliationService.reconcileBankAccount(
            eq(91L), any(), any(), any(), any(), eq(Set.of(3002L)), any()))
        .thenReturn(summary("0.00", "15.00", "2.00", false));

    PageResponse<BankReconciliationSessionSummaryDto> response = service.listSessions(0, 2);

    assertThat(response.content()).hasSize(2);
    assertThat(response.content().get(0).sessionId()).isEqualTo(101L);
    assertThat(response.content().get(1).sessionId()).isEqualTo(100L);
    assertThat(response.content().get(0).clearedItemCount()).isEqualTo(1);
    assertThat(response.content().get(1).clearedItemCount()).isZero();
    assertThat(response.totalElements()).isEqualTo(2L);
  }

  @Test
  void getSessionDetail_returnsMatchedAndUnmatchedBreakdown() {
    Account bankAccount = bankAccount(99L, "BANK", "Main Bank");
    AccountingPeriod period = new AccountingPeriod();
    ReflectionFieldAccess.setField(period, "id", 5L);
    period.setCompany(company);
    period.setYear(2026);
    period.setMonth(3);
    period.setStatus(AccountingPeriodStatus.OPEN);

    BankReconciliationSession session =
        session(88L, bankAccount, BankReconciliationSessionStatus.COMPLETED);
    session.setAccountingPeriod(period);
    ReflectionFieldAccess.setField(session, "createdAt", Instant.parse("2026-03-31T12:00:00Z"));
    ReflectionFieldAccess.setField(session, "completedAt", Instant.parse("2026-03-31T12:30:00Z"));

    JournalLine clearedLine =
        journalLine(
            5001L,
            company,
            bankAccount,
            "CLR-5001",
            LocalDate.of(2026, 3, 28),
            "Cleared",
            "70.00",
            "0.00");
    BankReconciliationItem clearedItem =
        item(901L, session, clearedLine, "CLR-5001", "70.00", "admin", 44L);

    when(sessionRepository.findDetailedByCompanyAndId(company, 88L))
        .thenReturn(Optional.of(session));
    when(itemRepository.findDetailedBySession(session)).thenReturn(List.of(clearedItem));
    when(reconciliationService.reconcileBankAccount(
            eq(99L),
            eq(session.getStatementDate()),
            eq(session.getStatementEndingBalance()),
            eq(LocalDate.of(2026, 3, 1)),
            eq(LocalDate.of(2026, 3, 31)),
            eq(Set.of(5001L)),
            eq(Collections.emptySet())))
        .thenReturn(summary("0.00", "0.00", "0.00", true));

    BankReconciliationSessionDetailDto response = service.getSessionDetail(88L);

    assertThat(response.sessionId()).isEqualTo(88L);
    assertThat(response.accountingPeriodId()).isEqualTo(5L);
    assertThat(response.matchedItems()).hasSize(1);
    assertThat(response.matchedItems().get(0).bankItemId()).isEqualTo(44L);
    assertThat(response.unmatchedItems()).isEmpty();
    assertThat(response.summary().balanced()).isTrue();
  }

  @Test
  void getSessionDetail_includesSummaryUnclearedAsUnmatchedItems() {
    Account bankAccount = bankAccount(99L, "BANK", "Main Bank");
    BankReconciliationSession session =
        session(89L, bankAccount, BankReconciliationSessionStatus.IN_PROGRESS);

    when(sessionRepository.findDetailedByCompanyAndId(company, 89L))
        .thenReturn(Optional.of(session));
    when(itemRepository.findDetailedBySession(session)).thenReturn(List.of());
    BankReconciliationSummaryDto summary =
        new BankReconciliationSummaryDto(
            99L,
            "BANK",
            "Main Bank",
            LocalDate.of(2026, 3, 31),
            new BigDecimal("1000.00"),
            new BigDecimal("1000.00"),
            new BigDecimal("15.00"),
            BigDecimal.ZERO,
            new BigDecimal("15.00"),
            false,
            List.of(
                new BankReconciliationSummaryDto.BankReconciliationItemDto(
                    6401L,
                    "UNC-6401",
                    LocalDate.of(2026, 3, 28),
                    "Uncleared deposit",
                    new BigDecimal("15.00"),
                    BigDecimal.ZERO,
                    new BigDecimal("15.00"))),
            List.of());
    when(reconciliationService.reconcileBankAccount(
            eq(99L),
            eq(session.getStatementDate()),
            eq(session.getStatementEndingBalance()),
            eq(LocalDate.of(2026, 3, 1)),
            eq(LocalDate.of(2026, 3, 31)),
            eq(Collections.emptySet()),
            eq(Collections.emptySet())))
        .thenReturn(summary);

    BankReconciliationSessionDetailDto response = service.getSessionDetail(89L);

    assertThat(response.matchedItems()).isEmpty();
    assertThat(response.unmatchedItems()).hasSize(1);
    assertThat(response.unmatchedItems().get(0).referenceNumber()).isEqualTo("UNC-6401");
    assertThat(response.unmatchedItems().get(0).bankItemId()).isNull();
    assertThat(response.unmatchedItems().get(0).journalEntryId()).isEqualTo(6401L);
  }

  @Test
  void getSessionDetail_excludesPersistedUnmatchedItemsFromClearedSummarySet() {
    Account bankAccount = bankAccount(99L, "BANK", "Main Bank");
    BankReconciliationSession session =
        session(90L, bankAccount, BankReconciliationSessionStatus.IN_PROGRESS);

    JournalLine unmatchedLine =
        journalLine(
            5101L,
            company,
            bankAccount,
            "UNMATCHED-5101",
            LocalDate.of(2026, 3, 27),
            "Unmatched",
            "25.00",
            "0.00");
    BankReconciliationItem unmatchedItem =
        item(902L, session, unmatchedLine, "UNMATCHED-5101", "25.00", "admin");

    when(sessionRepository.findDetailedByCompanyAndId(company, 90L))
        .thenReturn(Optional.of(session));
    when(itemRepository.findDetailedBySession(session)).thenReturn(List.of(unmatchedItem));
    BankReconciliationSummaryDto summary =
        new BankReconciliationSummaryDto(
            99L,
            "BANK",
            "Main Bank",
            LocalDate.of(2026, 3, 31),
            new BigDecimal("1000.00"),
            new BigDecimal("1000.00"),
            new BigDecimal("25.00"),
            BigDecimal.ZERO,
            new BigDecimal("25.00"),
            false,
            List.of(
                new BankReconciliationSummaryDto.BankReconciliationItemDto(
                    5101L,
                    "UNMATCHED-5101",
                    LocalDate.of(2026, 3, 27),
                    "Unmatched",
                    new BigDecimal("25.00"),
                    BigDecimal.ZERO,
                    new BigDecimal("25.00"))),
            List.of());
    when(reconciliationService.reconcileBankAccount(
            eq(99L),
            eq(session.getStatementDate()),
            eq(session.getStatementEndingBalance()),
            eq(LocalDate.of(2026, 3, 1)),
            eq(LocalDate.of(2026, 3, 31)),
            eq(Collections.emptySet()),
            eq(Collections.emptySet())))
        .thenReturn(summary);

    BankReconciliationSessionDetailDto response = service.getSessionDetail(90L);

    assertThat(response.matchedItems()).isEmpty();
    assertThat(response.unmatchedItems()).hasSize(2);
    assertThat(response.unmatchedItems().get(0).journalLineId()).isEqualTo(5101L);
    assertThat(response.unmatchedItems().get(0).bankItemId()).isNull();
    verify(reconciliationService)
        .reconcileBankAccount(
            eq(99L),
            eq(session.getStatementDate()),
            eq(session.getStatementEndingBalance()),
            eq(LocalDate.of(2026, 3, 1)),
            eq(LocalDate.of(2026, 3, 31)),
            eq(Collections.emptySet()),
            eq(Collections.emptySet()));
  }

  private BankReconciliationSummaryDto summary(
      String outstandingDeposits, String outstandingChecks, String difference, boolean balanced) {
    return new BankReconciliationSummaryDto(
        99L,
        "BANK",
        "Main Bank",
        LocalDate.of(2026, 3, 31),
        new BigDecimal("1000.00"),
        new BigDecimal("1000.00"),
        new BigDecimal(outstandingDeposits),
        new BigDecimal(outstandingChecks),
        new BigDecimal(difference),
        balanced,
        List.of(),
        List.of());
  }

  private BankReconciliationSession session(
      Long id, Account bankAccount, BankReconciliationSessionStatus status) {
    BankReconciliationSession session = new BankReconciliationSession();
    ReflectionFieldAccess.setField(session, "id", id);
    session.setCompany(company);
    session.setBankAccount(bankAccount);
    session.setStatus(status);
    session.setReferenceNumber("BANK-RECON-" + id);
    session.setCreatedBy("admin@acme.test");
    session.setStatementDate(LocalDate.of(2026, 3, 31));
    session.setStatementEndingBalance(new BigDecimal("1000.00"));
    session.setNote("session-note");
    return session;
  }

  private Account bankAccount(Long id, String code, String name) {
    Account account = new Account();
    ReflectionFieldAccess.setField(account, "id", id);
    account.setCode(code);
    account.setName(name);
    account.setType(AccountType.ASSET);
    return account;
  }

  private JournalLine journalLine(
      Long lineId,
      Company company,
      Account account,
      String reference,
      LocalDate entryDate,
      String memo,
      String debit,
      String credit) {
    JournalEntry entry = new JournalEntry();
    ReflectionFieldAccess.setField(entry, "id", lineId + 10000);
    entry.setCompany(company);
    entry.setReferenceNumber(reference);
    entry.setEntryDate(entryDate);
    entry.setMemo(memo);
    entry.setStatus("POSTED");

    JournalLine line = new JournalLine();
    ReflectionFieldAccess.setField(line, "id", lineId);
    line.setJournalEntry(entry);
    line.setAccount(account);
    line.setDebit(new BigDecimal(debit));
    line.setCredit(new BigDecimal(credit));
    return line;
  }

  private BankReconciliationItem item(
      Long id,
      BankReconciliationSession session,
      JournalLine line,
      String reference,
      String amount,
      String clearedBy) {
    return item(id, session, line, reference, amount, clearedBy, null);
  }

  private BankReconciliationItem item(
      Long id,
      BankReconciliationSession session,
      JournalLine line,
      String reference,
      String amount,
      String clearedBy,
      Long bankItemId) {
    BankReconciliationItem item = new BankReconciliationItem();
    ReflectionFieldAccess.setField(item, "id", id);
    item.setCompany(company);
    item.setSession(session);
    item.setJournalLine(line);
    item.setBankItemId(bankItemId);
    item.setReferenceNumber(reference);
    item.setAmount(new BigDecimal(amount));
    item.setClearedBy(clearedBy);
    item.setClearedAt(Instant.parse("2026-03-31T10:00:00Z"));
    return item;
  }
}
