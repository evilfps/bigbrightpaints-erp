package com.bigbrightpaints.erp.modules.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalCreationRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryReversalRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalLineDto;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalListItemDto;
import com.bigbrightpaints.erp.shared.dto.PageResponse;

@ExtendWith(MockitoExtension.class)
class JournalEntryServiceTest {

  @Mock private JournalQueryService journalQueryService;
  @Mock private JournalPostingService journalPostingService;
  @Mock private JournalReversalService journalReversalService;
  @Mock private PeriodValidationService periodValidationService;
  @Mock private AccountingCoreSupport accountingCoreSupport;

  private JournalEntryService journalEntryService;

  @BeforeEach
  void setUp() {
    journalEntryService =
        new JournalEntryService(
            journalQueryService,
            journalPostingService,
            journalReversalService,
            periodValidationService,
            accountingCoreSupport);
  }

  @Test
  void listJournalEntries_delegatesToJournalQueryService() {
    List<JournalEntryDto> expected = List.of(journalEntryDto(2001L, "JRN-2001"));
    when(journalQueryService.listJournalEntries(1L, 2L, 0, 50)).thenReturn(expected);

    assertThat(journalEntryService.listJournalEntries(1L, 2L, 0, 50)).isSameAs(expected);

    verify(journalQueryService).listJournalEntries(1L, 2L, 0, 50);
  }

  @Test
  void createJournalEntry_delegatesToJournalPostingService() {
    JournalEntryRequest request = journalEntryRequest("JRN-2002");
    JournalEntryDto expected = journalEntryDto(2002L, "JRN-2002");
    when(journalPostingService.createJournalEntry(request)).thenReturn(expected);

    assertThat(journalEntryService.createJournalEntry(request)).isSameAs(expected);

    verify(journalPostingService).createJournalEntry(request);
  }

  @Test
  void createStandardJournal_delegatesToJournalPostingService() {
    JournalCreationRequest request =
        new JournalCreationRequest(
            new BigDecimal("10.00"),
            11L,
            12L,
            "standard",
            "MANUAL",
            "SRC-2003",
            null,
            List.of(
                new JournalCreationRequest.LineRequest(
                    11L, new BigDecimal("10.00"), BigDecimal.ZERO, "Debit"),
                new JournalCreationRequest.LineRequest(
                    12L, BigDecimal.ZERO, new BigDecimal("10.00"), "Credit")),
            LocalDate.of(2026, 4, 1),
            null,
            null,
            false);
    JournalEntryDto expected = journalEntryDto(2003L, "JRN-2003");
    when(journalPostingService.createStandardJournal(request)).thenReturn(expected);

    assertThat(journalEntryService.createStandardJournal(request)).isSameAs(expected);

    verify(journalPostingService).createStandardJournal(request);
  }

  @Test
  void listJournals_delegatesToJournalQueryService() {
    PageResponse<JournalListItemDto> expected = PageResponse.of(List.of(), 0, 0, 50);
    when(journalQueryService.listJournals(
            LocalDate.of(2026, 4, 1),
            LocalDate.of(2026, 4, 30),
            "MANUAL",
            "SALES",
            0,
            50))
        .thenReturn(expected);

    assertThat(
            journalEntryService.listJournals(
                LocalDate.of(2026, 4, 1),
                LocalDate.of(2026, 4, 30),
                "MANUAL",
                "SALES",
                0,
                50))
        .isSameAs(expected);

    verify(journalQueryService)
        .listJournals(
            LocalDate.of(2026, 4, 1),
            LocalDate.of(2026, 4, 30),
            "MANUAL",
            "SALES",
            0,
            50);
  }

  @Test
  void reverseJournalEntry_delegatesToJournalReversalService() {
    JournalEntryReversalRequest request =
        new JournalEntryReversalRequest(
            LocalDate.of(2026, 4, 2), false, "reason", null, Boolean.FALSE, null, false, List.of(), null, null, null);
    JournalEntryDto expected = journalEntryDto(2004L, "REV-2004");
    when(journalReversalService.reverseJournalEntry(44L, request)).thenReturn(expected);

    assertThat(journalEntryService.reverseJournalEntry(44L, request)).isSameAs(expected);

    verify(journalReversalService).reverseJournalEntry(44L, request);
  }

  @Test
  void cascadeReverseRelatedEntries_delegatesToJournalReversalService() {
    JournalEntryReversalRequest request =
        new JournalEntryReversalRequest(
            LocalDate.of(2026, 4, 2), false, "reason", null, Boolean.FALSE, null, true, List.of(45L), null, null, null);
    List<JournalEntryDto> expected = List.of(journalEntryDto(2005L, "REV-2005"));
    when(journalReversalService.cascadeReverseRelatedEntries(44L, request)).thenReturn(expected);

    assertThat(journalEntryService.cascadeReverseRelatedEntries(44L, request)).isSameAs(expected);

    verify(journalReversalService).cascadeReverseRelatedEntries(44L, request);
  }

  @Test
  void createManualJournalEntry_rejectsNullRequest() {
    assertThatThrownBy(() -> journalEntryService.createManualJournalEntry(null, "manual-key"))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Journal entry request is required");
  }

  private JournalEntryRequest journalEntryRequest(String referenceNumber) {
    return new JournalEntryRequest(
        referenceNumber,
        LocalDate.of(2026, 4, 1),
        "memo",
        null,
        null,
        Boolean.FALSE,
        List.of(
            new JournalEntryRequest.JournalLineRequest(
                11L, "Debit", new BigDecimal("10.00"), BigDecimal.ZERO),
            new JournalEntryRequest.JournalLineRequest(
                12L, "Credit", BigDecimal.ZERO, new BigDecimal("10.00"))));
  }

  private JournalEntryDto journalEntryDto(Long id, String referenceNumber) {
    return new JournalEntryDto(
        id,
        null,
        referenceNumber,
        LocalDate.of(2026, 4, 1),
        "memo",
        "POSTED",
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        List.<JournalLineDto>of(),
        null,
        null,
        null,
        null,
        null,
        null);
  }
}
