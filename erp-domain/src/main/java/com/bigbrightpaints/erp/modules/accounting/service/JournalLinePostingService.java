package com.bigbrightpaints.erp.modules.accounting.service;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLine;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest;

@Service
class JournalLinePostingService {

  private static final Logger log = LoggerFactory.getLogger(JournalLinePostingService.class);
  private static final BigDecimal FX_ROUNDING_TOLERANCE = new BigDecimal("0.05");
  private static final String CREDIT_ROUNDING_ADJUSTMENT_REASON = "FX_ROUNDING_CREDIT_ADJUSTMENT";
  private static final String DEBIT_ROUNDING_ADJUSTMENT_REASON = "FX_ROUNDING_DEBIT_ADJUSTMENT";

  private final NormalBalanceConflictMode normalBalanceConflictMode;
  private final JournalReferenceService journalReferenceService;

  JournalLinePostingService(
      JournalReferenceService journalReferenceService,
      @Value("${erp.accounting.journal-line.normal-balance-conflict-mode:WARN}")
          String normalBalanceConflictMode) {
    this.journalReferenceService = journalReferenceService;
    this.normalBalanceConflictMode =
        NormalBalanceConflictMode.fromConfig(normalBalanceConflictMode);
  }

  JournalLine buildPostedLine(
      JournalEntry entry,
      JournalEntryRequest.JournalLineRequest lineRequest,
      Map<Long, Account> lockedAccounts,
      BigDecimal fxRate) {
    if (lineRequest.accountId() == null) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_INVALID_INPUT, "Account is required for every journal line");
    }
    Account account = lockedAccounts.get(lineRequest.accountId());
    if (account == null) {
      throw new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE, "Account not found");
    }
    if (!account.isActive()) {
      throw new ApplicationException(
              ErrorCode.VALIDATION_INVALID_INPUT, "Cannot post journal line to inactive account")
          .withDetail("accountId", account.getId())
          .withDetail("accountCode", account.getCode());
    }
    BigDecimal debitInput = lineRequest.debit() == null ? BigDecimal.ZERO : lineRequest.debit();
    BigDecimal creditInput = lineRequest.credit() == null ? BigDecimal.ZERO : lineRequest.credit();
    if (debitInput.compareTo(BigDecimal.ZERO) < 0 || creditInput.compareTo(BigDecimal.ZERO) < 0) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_INVALID_INPUT, "Debit/Credit cannot be negative");
    }
    if (debitInput.compareTo(BigDecimal.ZERO) > 0 && creditInput.compareTo(BigDecimal.ZERO) > 0) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_INVALID_INPUT,
          "Debit and credit cannot both be non-zero on the same line");
    }
    validateNormalBalanceDirection(account, debitInput, creditInput);
    JournalLine line = new JournalLine();
    line.setJournalEntry(entry);
    line.setAccount(account);
    line.setDescription(lineRequest.description());
    line.setDebit(journalReferenceService.toBaseCurrency(debitInput, fxRate));
    line.setCredit(journalReferenceService.toBaseCurrency(creditInput, fxRate));
    entry.addLine(line);
    return line;
  }

  RoundingAdjustment absorbRoundingDelta(
      BigDecimal roundingDelta,
      List<JournalLine> postedLines,
      Map<Account, BigDecimal> accountDeltas) {
    if (roundingDelta.abs().compareTo(FX_ROUNDING_TOLERANCE) > 0) {
      throw new ApplicationException(
              ErrorCode.VALIDATION_INVALID_INPUT, "Journal entry must balance")
          .withDetail("delta", roundingDelta);
    }
    if (roundingDelta.signum() > 0) {
      JournalLine target =
          postedLines.stream()
              .filter(line -> line.getCredit().compareTo(BigDecimal.ZERO) > 0)
              .max(Comparator.comparing(JournalLine::getCredit))
              .orElse(null);
      if (target != null) {
        BigDecimal originalAmount = target.getCredit();
        BigDecimal adjustedAmount = originalAmount.add(roundingDelta);
        target.setCredit(adjustedAmount);
        accountDeltas.merge(target.getAccount(), roundingDelta.negate(), BigDecimal::add);
        return new RoundingAdjustment(
            target, originalAmount, adjustedAmount, CREDIT_ROUNDING_ADJUSTMENT_REASON);
      }
      return null;
    }
    BigDecimal adjust = roundingDelta.abs();
    JournalLine target =
        postedLines.stream()
            .filter(line -> line.getDebit().compareTo(BigDecimal.ZERO) > 0)
            .max(Comparator.comparing(JournalLine::getDebit))
            .orElse(null);
    if (target != null) {
      BigDecimal originalAmount = target.getDebit();
      BigDecimal adjustedAmount = originalAmount.add(adjust);
      target.setDebit(adjustedAmount);
      accountDeltas.merge(target.getAccount(), adjust, BigDecimal::add);
      return new RoundingAdjustment(
          target, originalAmount, adjustedAmount, DEBIT_ROUNDING_ADJUSTMENT_REASON);
    }
    return null;
  }

  record RoundingAdjustment(
      JournalLine adjustedLine,
      BigDecimal originalAmount,
      BigDecimal adjustedAmount,
      String adjustmentReason) {}

  private void validateNormalBalanceDirection(
      Account account, BigDecimal debitInput, BigDecimal creditInput) {
    if (account.getType() == null) {
      return;
    }
    boolean hasDebit = debitInput.compareTo(BigDecimal.ZERO) > 0;
    boolean hasCredit = creditInput.compareTo(BigDecimal.ZERO) > 0;
    if (!hasDebit && !hasCredit) {
      return;
    }
    boolean debitNormal = account.getType().isDebitNormalBalance();
    boolean conflict = (hasDebit && !debitNormal) || (hasCredit && debitNormal);
    if (!conflict) {
      return;
    }
    String direction = hasDebit ? "DEBIT" : "CREDIT";
    if (normalBalanceConflictMode == NormalBalanceConflictMode.REJECT) {
      throw new ApplicationException(
              ErrorCode.VALIDATION_INVALID_INPUT,
              "Journal line direction conflicts with account normal balance")
          .withDetail("accountId", account.getId())
          .withDetail("accountCode", account.getCode())
          .withDetail("accountType", account.getType().name())
          .withDetail("direction", direction)
          .withDetail("policy", normalBalanceConflictMode.name());
    }
    log.warn(
        "Journal line direction {} conflicts with normal balance for account {} ({})",
        direction,
        account.getCode(),
        account.getType());
  }

  private enum NormalBalanceConflictMode {
    WARN,
    REJECT;

    static NormalBalanceConflictMode fromConfig(String configuredMode) {
      if (configuredMode == null || configuredMode.isBlank()) {
        return WARN;
      }
      try {
        return NormalBalanceConflictMode.valueOf(configuredMode.trim().toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException ex) {
        return WARN;
      }
    }
  }
}
