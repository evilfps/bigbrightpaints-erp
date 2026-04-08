package com.bigbrightpaints.erp.modules.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLine;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest;
import com.bigbrightpaints.erp.test.support.ReflectionFieldAccess;

@ExtendWith(MockitoExtension.class)
class JournalLinePostingServiceTest {

  @Mock private JournalReferenceService journalReferenceService;

  @BeforeEach
  void setUp() {
    lenient()
        .when(journalReferenceService.toBaseCurrency(any(BigDecimal.class), eq(BigDecimal.ONE)))
        .thenAnswer(invocation -> invocation.getArgument(0));
  }

  @Test
  void buildPostedLine_rejectsInactiveAccount() {
    JournalLinePostingService service =
        new JournalLinePostingService(journalReferenceService, "WARN");
    Account inactiveExpense = account(11L, "EXP-11", AccountType.EXPENSE, false);

    assertThatThrownBy(
            () ->
                service.buildPostedLine(
                    new JournalEntry(),
                    new JournalEntryRequest.JournalLineRequest(
                        11L, "line", new BigDecimal("10.00"), BigDecimal.ZERO),
                    Map.of(11L, inactiveExpense),
                    BigDecimal.ONE))
        .isInstanceOf(ApplicationException.class)
        .satisfies(
            ex -> {
              ApplicationException applicationException = (ApplicationException) ex;
              assertThat(applicationException.getErrorCode())
                  .isEqualTo(ErrorCode.VALIDATION_INVALID_INPUT);
              assertThat(applicationException.getUserMessage()).contains("inactive account");
            });
  }

  @Test
  void buildPostedLine_warnModeAllowsNormalBalanceDirectionConflict() {
    JournalLinePostingService service =
        new JournalLinePostingService(journalReferenceService, "WARN");
    Account expense = account(22L, "EXP-22", AccountType.EXPENSE, true);

    var line =
        service.buildPostedLine(
            new JournalEntry(),
            new JournalEntryRequest.JournalLineRequest(
                22L, "line", BigDecimal.ZERO, new BigDecimal("10.00")),
            Map.of(22L, expense),
            BigDecimal.ONE);

    assertThat(line.getAccount()).isEqualTo(expense);
    assertThat(line.getDebit()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(line.getCredit()).isEqualByComparingTo(new BigDecimal("10.00"));
  }

  @Test
  void buildPostedLine_rejectModeBlocksNormalBalanceDirectionConflict() {
    JournalLinePostingService service =
        new JournalLinePostingService(journalReferenceService, "REJECT");
    Account revenue = account(33L, "REV-33", AccountType.REVENUE, true);

    assertThatThrownBy(
            () ->
                service.buildPostedLine(
                    new JournalEntry(),
                    new JournalEntryRequest.JournalLineRequest(
                        33L, "line", new BigDecimal("10.00"), BigDecimal.ZERO),
                    Map.of(33L, revenue),
                    BigDecimal.ONE))
        .isInstanceOf(ApplicationException.class)
        .satisfies(
            ex -> {
              ApplicationException applicationException = (ApplicationException) ex;
              assertThat(applicationException.getErrorCode())
                  .isEqualTo(ErrorCode.VALIDATION_INVALID_INPUT);
              assertThat(applicationException.getUserMessage()).contains("normal balance");
            });
  }

  @Test
  void absorbRoundingDelta_positiveDelta_adjustsLargestCreditLine_andReturnsMetadata() {
    JournalLinePostingService service =
        new JournalLinePostingService(journalReferenceService, "WARN");
    Account revenue = account(44L, "REV-44", AccountType.REVENUE, true);
    Account liability = account(45L, "LIAB-45", AccountType.LIABILITY, true);
    JournalLine smallerCredit = line(revenue, BigDecimal.ZERO, new BigDecimal("10.00"));
    JournalLine largerCredit = line(liability, BigDecimal.ZERO, new BigDecimal("20.00"));
    Map<Account, BigDecimal> accountDeltas =
        new java.util.HashMap<>(
            Map.of(revenue, new BigDecimal("-10.00"), liability, new BigDecimal("-20.00")));

    JournalLinePostingService.RoundingAdjustment adjustment =
        service.absorbRoundingDelta(
            new BigDecimal("0.02"), List.of(smallerCredit, largerCredit), accountDeltas);

    assertThat(adjustment).isNotNull();
    assertThat(adjustment.adjustedLine()).isSameAs(largerCredit);
    assertThat(adjustment.originalAmount()).isEqualByComparingTo(new BigDecimal("20.00"));
    assertThat(adjustment.adjustedAmount()).isEqualByComparingTo(new BigDecimal("20.02"));
    assertThat(adjustment.adjustmentReason()).isEqualTo("FX_ROUNDING_CREDIT_ADJUSTMENT");
    assertThat(largerCredit.getCredit()).isEqualByComparingTo(new BigDecimal("20.02"));
    assertThat(accountDeltas.get(liability)).isEqualByComparingTo(new BigDecimal("-20.02"));
  }

  @Test
  void absorbRoundingDelta_negativeDelta_adjustsLargestDebitLine_andReturnsMetadata() {
    JournalLinePostingService service =
        new JournalLinePostingService(journalReferenceService, "WARN");
    Account expense = account(46L, "EXP-46", AccountType.EXPENSE, true);
    Account asset = account(47L, "ASSET-47", AccountType.ASSET, true);
    JournalLine smallerDebit = line(expense, new BigDecimal("15.00"), BigDecimal.ZERO);
    JournalLine largerDebit = line(asset, new BigDecimal("25.00"), BigDecimal.ZERO);
    Map<Account, BigDecimal> accountDeltas =
        new java.util.HashMap<>(
            Map.of(expense, new BigDecimal("15.00"), asset, new BigDecimal("25.00")));

    JournalLinePostingService.RoundingAdjustment adjustment =
        service.absorbRoundingDelta(
            new BigDecimal("-0.03"), List.of(smallerDebit, largerDebit), accountDeltas);

    assertThat(adjustment).isNotNull();
    assertThat(adjustment.adjustedLine()).isSameAs(largerDebit);
    assertThat(adjustment.originalAmount()).isEqualByComparingTo(new BigDecimal("25.00"));
    assertThat(adjustment.adjustedAmount()).isEqualByComparingTo(new BigDecimal("25.03"));
    assertThat(adjustment.adjustmentReason()).isEqualTo("FX_ROUNDING_DEBIT_ADJUSTMENT");
    assertThat(largerDebit.getDebit()).isEqualByComparingTo(new BigDecimal("25.03"));
    assertThat(accountDeltas.get(asset)).isEqualByComparingTo(new BigDecimal("25.03"));
  }

  private Account account(Long id, String code, AccountType type, boolean active) {
    Account account = new Account();
    ReflectionFieldAccess.setField(account, "id", id);
    account.setCode(code);
    account.setType(type);
    account.setActive(active);
    account.setBalance(BigDecimal.ZERO);
    return account;
  }

  private JournalLine line(Account account, BigDecimal debit, BigDecimal credit) {
    JournalLine line = new JournalLine();
    line.setAccount(account);
    line.setDebit(debit);
    line.setCredit(credit);
    return line;
  }
}
