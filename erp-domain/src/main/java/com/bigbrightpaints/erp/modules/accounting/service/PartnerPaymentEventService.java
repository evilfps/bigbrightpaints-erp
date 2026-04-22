package com.bigbrightpaints.erp.modules.accounting.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.MoneyUtils;
import com.bigbrightpaints.erp.core.validation.ValidationUtils;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.PartnerPaymentEvent;
import com.bigbrightpaints.erp.modules.accounting.domain.PartnerPaymentEventRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.PartnerPaymentFlow;
import com.bigbrightpaints.erp.modules.accounting.domain.PartnerType;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;

@Service
class PartnerPaymentEventService {

  private final PartnerPaymentEventRepository partnerPaymentEventRepository;

  PartnerPaymentEventService(PartnerPaymentEventRepository partnerPaymentEventRepository) {
    this.partnerPaymentEventRepository = partnerPaymentEventRepository;
  }

  PartnerPaymentEvent resolveOrCreateDealerPaymentEvent(
      Company company,
      Dealer dealer,
      PartnerPaymentFlow paymentFlow,
      BigDecimal amount,
      LocalDate paymentDate,
      String referenceNumber,
      String idempotencyKey,
      String memo,
      String sourceRoute) {
    Company resolvedCompany = ValidationUtils.requireNotNull(company, "company");
    Dealer resolvedDealer = ValidationUtils.requireNotNull(dealer, "dealer");
    PartnerPaymentFlow resolvedFlow = ValidationUtils.requireNotNull(paymentFlow, "paymentFlow");
    BigDecimal resolvedAmount = ValidationUtils.requirePositive(amount, "amount");
    LocalDate resolvedPaymentDate = ValidationUtils.requireNotNull(paymentDate, "paymentDate");
    String resolvedReference = requiredTrimmedValue(referenceNumber, "referenceNumber");
    String resolvedIdempotencyKey = requiredTrimmedValue(idempotencyKey, "idempotencyKey");
    String resolvedRoute = requiredTrimmedValue(sourceRoute, "sourceRoute");
    String normalizedMemo = normalizeMemo(memo);

    Optional<PartnerPaymentEvent> byIdempotency =
        findFirstByIdempotencyKey(resolvedCompany, resolvedIdempotencyKey);
    if (byIdempotency.isPresent()) {
      PartnerPaymentEvent existing = byIdempotency.get();
      validateDealerPaymentEventReplay(
          existing,
          resolvedDealer,
          resolvedFlow,
          resolvedAmount,
          resolvedReference,
          normalizedMemo,
          resolvedRoute,
          resolvedIdempotencyKey);
      return existing;
    }

    Optional<PartnerPaymentEvent> byReference =
        partnerPaymentEventRepository.findByCompanyAndPaymentFlowAndReferenceNumberIgnoreCase(
            resolvedCompany, resolvedFlow, resolvedReference);
    if (byReference.isPresent()) {
      PartnerPaymentEvent existing = byReference.get();
      validateDealerPaymentEventReplay(
          existing,
          resolvedDealer,
          resolvedFlow,
          resolvedAmount,
          resolvedReference,
          normalizedMemo,
          resolvedRoute,
          resolvedIdempotencyKey);
      return existing;
    }

    PartnerPaymentEvent event = new PartnerPaymentEvent();
    event.setCompany(resolvedCompany);
    event.setPartnerType(PartnerType.DEALER);
    event.setDealer(resolvedDealer);
    event.setPaymentFlow(resolvedFlow);
    event.setSourceRoute(resolvedRoute);
    event.setReferenceNumber(resolvedReference);
    event.setIdempotencyKey(resolvedIdempotencyKey);
    event.setPaymentDate(resolvedPaymentDate);
    event.setAmount(resolvedAmount);
    event.setCurrency(resolveCurrency(resolvedCompany));
    event.setMemo(normalizedMemo);
    return partnerPaymentEventRepository.save(event);
  }

  void linkJournalEntry(PartnerPaymentEvent paymentEvent, JournalEntry journalEntry) {
    if (paymentEvent == null || journalEntry == null) {
      return;
    }
    if (paymentEvent.getJournalEntry() != null) {
      Long existingJournalEntryId = paymentEvent.getJournalEntry().getId();
      Long requestedJournalEntryId = journalEntry.getId();
      if (existingJournalEntryId != null
          && requestedJournalEntryId != null
          && !Objects.equals(existingJournalEntryId, requestedJournalEntryId)) {
        throw new ApplicationException(
                ErrorCode.CONCURRENCY_CONFLICT,
                "Payment event already linked to a different journal entry")
            .withDetail("paymentEventId", paymentEvent.getId())
            .withDetail("existingJournalEntryId", existingJournalEntryId)
            .withDetail("requestedJournalEntryId", requestedJournalEntryId);
      }
      return;
    }
    paymentEvent.setJournalEntry(journalEntry);
    paymentEvent.setPostedAt(resolvePostedAt(journalEntry));
    partnerPaymentEventRepository.save(paymentEvent);
  }

  private Optional<PartnerPaymentEvent> findFirstByIdempotencyKey(Company company, String key) {
    List<PartnerPaymentEvent> matches =
        partnerPaymentEventRepository.findByCompanyAndIdempotencyKeyIgnoreCaseOrderByCreatedAtAsc(
            company, key);
    if (matches == null || matches.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(matches.getFirst());
  }

  private void validateDealerPaymentEventReplay(
      PartnerPaymentEvent existing,
      Dealer dealer,
      PartnerPaymentFlow paymentFlow,
      BigDecimal amount,
      String referenceNumber,
      String memo,
      String sourceRoute,
      String idempotencyKey) {
    if (existing == null) {
      throw replayConflict("Payment event missing for replay", idempotencyKey);
    }
    if (existing.getPartnerType() != PartnerType.DEALER
        || existing.getDealer() == null
        || existing.getDealer().getId() == null
        || !Objects.equals(existing.getDealer().getId(), dealer.getId())) {
      throw replayConflict(
          "Idempotency key already used for a different dealer payment event", idempotencyKey);
    }
    if (existing.getPaymentFlow() != paymentFlow) {
      throw replayConflict(
          "Idempotency key already used for a different dealer payment flow", idempotencyKey);
    }
    if (!equalsIgnoreCaseTrimmed(existing.getReferenceNumber(), referenceNumber)) {
      throw replayConflict(
              "Idempotency key already used for a different dealer payment reference",
              idempotencyKey)
          .withDetail("existingReference", existing.getReferenceNumber())
          .withDetail("requestedReference", referenceNumber);
    }
    if (MoneyUtils.zeroIfNull(existing.getAmount())
            .compareTo(MoneyUtils.zeroIfNull(amount).abs())
        != 0) {
      throw replayConflict(
              "Idempotency key already used for a different dealer payment amount", idempotencyKey)
          .withDetail("existingAmount", existing.getAmount())
          .withDetail("requestedAmount", amount);
    }
    if (StringUtils.hasText(memo) && !Objects.equals(existing.getMemo(), memo)) {
      throw replayConflict(
              "Idempotency key already used with a different dealer payment memo", idempotencyKey)
          .withDetail("existingMemo", existing.getMemo())
          .withDetail("requestedMemo", memo);
    }
    if (StringUtils.hasText(sourceRoute) && !Objects.equals(existing.getSourceRoute(), sourceRoute)) {
      throw replayConflict(
              "Idempotency key already used for a different dealer payment route", idempotencyKey)
          .withDetail("existingRoute", existing.getSourceRoute())
          .withDetail("requestedRoute", sourceRoute);
    }
  }

  private String requiredTrimmedValue(String value, String field) {
    if (!StringUtils.hasText(value)) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD, field + " is required");
    }
    return value.trim();
  }

  private String normalizeMemo(String memo) {
    return StringUtils.hasText(memo) ? memo.trim() : null;
  }

  private boolean equalsIgnoreCaseTrimmed(String left, String right) {
    if (!StringUtils.hasText(left) && !StringUtils.hasText(right)) {
      return true;
    }
    if (!StringUtils.hasText(left) || !StringUtils.hasText(right)) {
      return false;
    }
    return left.trim().equalsIgnoreCase(right.trim());
  }

  private String resolveCurrency(Company company) {
    if (company == null || !StringUtils.hasText(company.getBaseCurrency())) {
      return "INR";
    }
    return company.getBaseCurrency().trim().toUpperCase(Locale.ROOT);
  }

  private Instant resolvePostedAt(JournalEntry journalEntry) {
    if (journalEntry == null) {
      return null;
    }
    if (journalEntry.getPostedAt() != null) {
      return journalEntry.getPostedAt();
    }
    if (journalEntry.getCreatedAt() != null) {
      return journalEntry.getCreatedAt();
    }
    return Instant.now();
  }

  private ApplicationException replayConflict(String message, String idempotencyKey) {
    return new ApplicationException(ErrorCode.CONCURRENCY_CONFLICT, message)
        .withDetail("idempotencyKey", idempotencyKey);
  }
}
