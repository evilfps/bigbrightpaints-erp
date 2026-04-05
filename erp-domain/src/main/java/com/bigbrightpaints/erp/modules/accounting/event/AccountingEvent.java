package com.bigbrightpaints.erp.modules.accounting.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.bigbrightpaints.erp.core.util.CompanyTime;
import com.bigbrightpaints.erp.modules.company.domain.Company;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * Event store entity for accounting domain events.
 * This is an audit/diagnostic log and is not the source-of-truth for closed-period reporting.
 */
@Entity
@Table(
    name = "accounting_events",
    indexes = {
      @Index(name = "idx_acct_events_company_ts", columnList = "company_id, event_timestamp"),
      @Index(name = "idx_acct_events_account", columnList = "account_id, event_timestamp"),
      @Index(name = "idx_acct_events_journal", columnList = "journal_entry_id"),
      @Index(name = "idx_acct_events_aggregate", columnList = "aggregate_id, sequence_number")
    })
public class AccountingEvent {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "event_id", nullable = false, unique = true)
  private UUID eventId;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "company_id", nullable = false)
  private Company company;

  @Enumerated(EnumType.STRING)
  @Column(name = "event_type", nullable = false, length = 50)
  private AccountingEventType eventType;

  @Column(name = "aggregate_id", nullable = false)
  private UUID aggregateId;

  @Column(name = "aggregate_type", nullable = false, length = 50)
  private String aggregateType;

  @Column(name = "sequence_number", nullable = false)
  private Long sequenceNumber;

  @Column(name = "event_timestamp", nullable = false)
  private Instant eventTimestamp;

  @Column(name = "effective_date", nullable = false)
  private LocalDate effectiveDate;

  // Denormalized fields for efficient queries
  @Column(name = "account_id")
  private Long accountId;

  @Column(name = "account_code", length = 50)
  private String accountCode;

  @Column(name = "journal_entry_id")
  private Long journalEntryId;

  @Column(name = "journal_reference", length = 100)
  private String journalReference;

  @Column(name = "debit_amount", precision = 19, scale = 4)
  private BigDecimal debitAmount;

  @Column(name = "credit_amount", precision = 19, scale = 4)
  private BigDecimal creditAmount;

  @Column(name = "balance_before", precision = 19, scale = 4)
  private BigDecimal balanceBefore;

  @Column(name = "balance_after", precision = 19, scale = 4)
  private BigDecimal balanceAfter;

  @Column(name = "description", length = 500)
  private String description;

  @Column(name = "user_id", length = 100)
  private String userId;

  @Column(name = "correlation_id")
  private UUID correlationId;

  @Column(name = "payload", columnDefinition = "TEXT")
  private String payload; // JSON for additional event data

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @PrePersist
  protected void onCreate() {
    if (eventId == null) eventId = UUID.randomUUID();
    if (createdAt == null) createdAt = CompanyTime.now(company);
    if (eventTimestamp == null) eventTimestamp = CompanyTime.now(company);
  }

  // Getters and setters
  public Long getId() {
    return id;
  }

  public UUID getEventId() {
    return eventId;
  }

  public void setEventId(UUID eventId) {
    this.eventId = eventId;
  }

  public Company getCompany() {
    return company;
  }

  public void setCompany(Company company) {
    this.company = company;
  }

  public AccountingEventType getEventType() {
    return eventType;
  }

  public void setEventType(AccountingEventType eventType) {
    this.eventType = eventType;
  }

  public UUID getAggregateId() {
    return aggregateId;
  }

  public void setAggregateId(UUID aggregateId) {
    this.aggregateId = aggregateId;
  }

  public String getAggregateType() {
    return aggregateType;
  }

  public void setAggregateType(String aggregateType) {
    this.aggregateType = aggregateType;
  }

  public Long getSequenceNumber() {
    return sequenceNumber;
  }

  public void setSequenceNumber(Long sequenceNumber) {
    this.sequenceNumber = sequenceNumber;
  }

  public Instant getEventTimestamp() {
    return eventTimestamp;
  }

  public void setEventTimestamp(Instant eventTimestamp) {
    this.eventTimestamp = eventTimestamp;
  }

  public LocalDate getEffectiveDate() {
    return effectiveDate;
  }

  public void setEffectiveDate(LocalDate effectiveDate) {
    this.effectiveDate = effectiveDate;
  }

  public Long getAccountId() {
    return accountId;
  }

  public void setAccountId(Long accountId) {
    this.accountId = accountId;
  }

  public String getAccountCode() {
    return accountCode;
  }

  public void setAccountCode(String accountCode) {
    this.accountCode = accountCode;
  }

  public Long getJournalEntryId() {
    return journalEntryId;
  }

  public void setJournalEntryId(Long journalEntryId) {
    this.journalEntryId = journalEntryId;
  }

  public String getJournalReference() {
    return journalReference;
  }

  public void setJournalReference(String journalReference) {
    this.journalReference = journalReference;
  }

  public BigDecimal getDebitAmount() {
    return debitAmount;
  }

  public void setDebitAmount(BigDecimal debitAmount) {
    this.debitAmount = debitAmount;
  }

  public BigDecimal getCreditAmount() {
    return creditAmount;
  }

  public void setCreditAmount(BigDecimal creditAmount) {
    this.creditAmount = creditAmount;
  }

  public BigDecimal getBalanceBefore() {
    return balanceBefore;
  }

  public void setBalanceBefore(BigDecimal balanceBefore) {
    this.balanceBefore = balanceBefore;
  }

  public BigDecimal getBalanceAfter() {
    return balanceAfter;
  }

  public void setBalanceAfter(BigDecimal balanceAfter) {
    this.balanceAfter = balanceAfter;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public String getUserId() {
    return userId;
  }

  public void setUserId(String userId) {
    this.userId = userId;
  }

  public UUID getCorrelationId() {
    return correlationId;
  }

  public void setCorrelationId(UUID correlationId) {
    this.correlationId = correlationId;
  }

  public String getPayload() {
    return payload;
  }

  public void setPayload(String payload) {
    this.payload = payload;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
