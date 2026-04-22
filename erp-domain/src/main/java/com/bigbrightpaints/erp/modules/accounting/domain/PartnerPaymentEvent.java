package com.bigbrightpaints.erp.modules.accounting.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.bigbrightpaints.erp.core.domain.VersionedEntity;
import com.bigbrightpaints.erp.core.util.CompanyTime;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.purchasing.domain.Supplier;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;

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
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(
    name = "partner_payment_events",
    indexes = {
      @Index(name = "idx_partner_payment_events_company", columnList = "company_id"),
      @Index(
          name = "idx_partner_payment_events_partner_date",
          columnList = "company_id, partner_type, dealer_id, supplier_id, payment_date"),
      @Index(
          name = "idx_partner_payment_events_idempotency_ci",
          columnList = "company_id, idempotency_key"),
      @Index(
          name = "idx_partner_payment_events_journal_entry",
          columnList = "company_id, journal_entry_id")
    },
    uniqueConstraints = {
      @UniqueConstraint(columnNames = {"public_id"}),
      @UniqueConstraint(columnNames = {"company_id", "payment_flow", "reference_number"})
    })
public class PartnerPaymentEvent extends VersionedEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "public_id", nullable = false)
  private UUID publicId;

  @ManyToOne(optional = false, fetch = FetchType.LAZY)
  @JoinColumn(name = "company_id")
  private Company company;

  @Enumerated(EnumType.STRING)
  @Column(name = "partner_type", nullable = false)
  private PartnerType partnerType;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "dealer_id")
  private Dealer dealer;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "supplier_id")
  private Supplier supplier;

  @Enumerated(EnumType.STRING)
  @Column(name = "payment_flow", nullable = false)
  private PartnerPaymentFlow paymentFlow;

  @Column(name = "source_route", nullable = false)
  private String sourceRoute;

  @Column(name = "reference_number", nullable = false)
  private String referenceNumber;

  @Column(name = "idempotency_key", nullable = false, length = 128)
  private String idempotencyKey;

  @Column(name = "payment_date", nullable = false)
  private LocalDate paymentDate;

  @Column(name = "amount", nullable = false)
  private BigDecimal amount = BigDecimal.ZERO;

  @Column(name = "currency", nullable = false)
  private String currency = "INR";

  @Column(name = "memo")
  private String memo;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "journal_entry_id")
  private JournalEntry journalEntry;

  @Column(name = "posted_at")
  private Instant postedAt;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @PrePersist
  public void prePersist() {
    if (publicId == null) {
      publicId = UUID.randomUUID();
    }
    if (createdAt == null) {
      createdAt = CompanyTime.now(company);
    }
    if (paymentDate == null) {
      paymentDate = CompanyTime.today(company);
    }
  }

  public Long getId() {
    return id;
  }

  public UUID getPublicId() {
    return publicId;
  }

  public void setPublicId(UUID publicId) {
    this.publicId = publicId;
  }

  public Company getCompany() {
    return company;
  }

  public void setCompany(Company company) {
    this.company = company;
  }

  public PartnerType getPartnerType() {
    return partnerType;
  }

  public void setPartnerType(PartnerType partnerType) {
    this.partnerType = partnerType;
  }

  public Dealer getDealer() {
    return dealer;
  }

  public void setDealer(Dealer dealer) {
    this.dealer = dealer;
  }

  public Supplier getSupplier() {
    return supplier;
  }

  public void setSupplier(Supplier supplier) {
    this.supplier = supplier;
  }

  public PartnerPaymentFlow getPaymentFlow() {
    return paymentFlow;
  }

  public void setPaymentFlow(PartnerPaymentFlow paymentFlow) {
    this.paymentFlow = paymentFlow;
  }

  public String getSourceRoute() {
    return sourceRoute;
  }

  public void setSourceRoute(String sourceRoute) {
    this.sourceRoute = sourceRoute;
  }

  public String getReferenceNumber() {
    return referenceNumber;
  }

  public void setReferenceNumber(String referenceNumber) {
    this.referenceNumber = referenceNumber;
  }

  public String getIdempotencyKey() {
    return idempotencyKey;
  }

  public void setIdempotencyKey(String idempotencyKey) {
    this.idempotencyKey = idempotencyKey;
  }

  public LocalDate getPaymentDate() {
    return paymentDate;
  }

  public void setPaymentDate(LocalDate paymentDate) {
    this.paymentDate = paymentDate;
  }

  public BigDecimal getAmount() {
    return amount;
  }

  public void setAmount(BigDecimal amount) {
    this.amount = amount;
  }

  public String getCurrency() {
    return currency;
  }

  public void setCurrency(String currency) {
    this.currency = currency;
  }

  public String getMemo() {
    return memo;
  }

  public void setMemo(String memo) {
    this.memo = memo;
  }

  public JournalEntry getJournalEntry() {
    return journalEntry;
  }

  public void setJournalEntry(JournalEntry journalEntry) {
    this.journalEntry = journalEntry;
  }

  public Instant getPostedAt() {
    return postedAt;
  }

  public void setPostedAt(Instant postedAt) {
    this.postedAt = postedAt;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
