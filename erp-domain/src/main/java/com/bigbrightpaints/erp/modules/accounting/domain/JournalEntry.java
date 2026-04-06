package com.bigbrightpaints.erp.modules.accounting.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import com.bigbrightpaints.erp.core.domain.VersionedEntity;
import com.bigbrightpaints.erp.core.util.CompanyTime;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.purchasing.domain.Supplier;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(
    name = "journal_entries",
    uniqueConstraints = @UniqueConstraint(columnNames = {"company_id", "reference_number"}))
public class JournalEntry extends VersionedEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "public_id", nullable = false)
  private UUID publicId;

  @ManyToOne(optional = false, fetch = FetchType.LAZY)
  @JoinColumn(name = "company_id")
  private Company company;

  @Column(name = "reference_number", nullable = false)
  private String referenceNumber;

  @Column(name = "entry_date", nullable = false)
  private LocalDate entryDate;

  private String memo;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private JournalEntryStatus status = JournalEntryStatus.DRAFT;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "dealer_id")
  private Dealer dealer;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "supplier_id")
  private Supplier supplier;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "accounting_period_id")
  private AccountingPeriod accountingPeriod;

  @Enumerated(EnumType.STRING)
  @Column(name = "journal_type", nullable = false)
  private JournalEntryType journalType = JournalEntryType.AUTOMATED;

  @Column(name = "source_module")
  private String sourceModule;

  @Column(name = "source_reference")
  private String sourceReference;

  @Column(name = "attachment_references", columnDefinition = "TEXT")
  private String attachmentReferences;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "reversal_of_id")
  private JournalEntry reversalOf;

  @OneToOne(mappedBy = "reversalOf", fetch = FetchType.LAZY)
  private JournalEntry reversalEntry;

  @Enumerated(EnumType.STRING)
  @Column(name = "correction_type")
  private JournalCorrectionType correctionType;

  @Column(name = "correction_reason")
  private String correctionReason;

  @Column(name = "void_reason")
  private String voidReason;

  @Column(name = "voided_at")
  private Instant voidedAt;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "posted_at")
  private Instant postedAt;

  @Column(name = "created_by")
  private String createdBy;

  @Column(name = "posted_by")
  private String postedBy;

  @Column(name = "last_modified_by")
  private String lastModifiedBy;

  @Column(name = "currency", nullable = false)
  private String currency = "INR";

  @Column(name = "fx_rate", precision = 19, scale = 6)
  private BigDecimal fxRate = BigDecimal.ONE;

  @Column(name = "foreign_amount_total", precision = 18, scale = 2)
  private BigDecimal foreignAmountTotal;

  @OneToMany(mappedBy = "journalEntry", cascade = CascadeType.ALL, orphanRemoval = true)
  private List<JournalLine> lines = new ArrayList<>();

  @PrePersist
  public void prePersist() {
    if (publicId == null) {
      publicId = UUID.randomUUID();
    }
    Instant now = CompanyTime.now(company);
    if (createdAt == null) {
      createdAt = now;
    }
    if (updatedAt == null) {
      updatedAt = now;
    }
    if (postedAt == null && status == JournalEntryStatus.POSTED) {
      postedAt = now;
    }
  }

  @PreUpdate
  public void preUpdate() {
    updatedAt = CompanyTime.now(company);
  }

  public Long getId() {
    return id;
  }

  public UUID getPublicId() {
    return publicId;
  }

  public Company getCompany() {
    return company;
  }

  public void setCompany(Company company) {
    this.company = company;
  }

  public String getReferenceNumber() {
    return referenceNumber;
  }

  public void setReferenceNumber(String referenceNumber) {
    this.referenceNumber = referenceNumber;
  }

  public LocalDate getEntryDate() {
    return entryDate;
  }

  public void setEntryDate(LocalDate entryDate) {
    this.entryDate = entryDate;
  }

  public String getMemo() {
    return memo;
  }

  public void setMemo(String memo) {
    this.memo = memo;
  }

  public JournalEntryStatus getStatus() {
    return status;
  }

  public void setStatus(JournalEntryStatus status) {
    this.status = status == null ? JournalEntryStatus.DRAFT : status;
  }

  public void setStatus(String status) {
    setStatus(JournalEntryStatus.from(status));
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

  public AccountingPeriod getAccountingPeriod() {
    return accountingPeriod;
  }

  public void setAccountingPeriod(AccountingPeriod accountingPeriod) {
    this.accountingPeriod = accountingPeriod;
  }

  public JournalEntryType getJournalType() {
    return journalType;
  }

  public void setJournalType(JournalEntryType journalType) {
    this.journalType = journalType == null ? JournalEntryType.AUTOMATED : journalType;
  }

  public String getSourceModule() {
    return sourceModule;
  }

  public void setSourceModule(String sourceModule) {
    this.sourceModule = sourceModule;
  }

  public String getSourceReference() {
    return sourceReference;
  }

  public void setSourceReference(String sourceReference) {
    this.sourceReference = sourceReference;
  }

  public String getAttachmentReferences() {
    return attachmentReferences;
  }

  public void setAttachmentReferences(String attachmentReferences) {
    this.attachmentReferences = attachmentReferences;
  }

  public JournalEntry getReversalOf() {
    return reversalOf;
  }

  public void setReversalOf(JournalEntry reversalOf) {
    this.reversalOf = reversalOf;
  }

  public JournalEntry getReversalEntry() {
    return reversalEntry;
  }

  public void setReversalEntry(JournalEntry reversalEntry) {
    this.reversalEntry = reversalEntry;
  }

  public JournalCorrectionType getCorrectionType() {
    return correctionType;
  }

  public void setCorrectionType(JournalCorrectionType correctionType) {
    this.correctionType = correctionType;
  }

  public String getCorrectionReason() {
    return correctionReason;
  }

  public void setCorrectionReason(String correctionReason) {
    this.correctionReason = correctionReason;
  }

  public String getVoidReason() {
    return voidReason;
  }

  public void setVoidReason(String voidReason) {
    this.voidReason = voidReason;
  }

  public Instant getVoidedAt() {
    return voidedAt;
  }

  public void setVoidedAt(Instant voidedAt) {
    this.voidedAt = voidedAt;
  }

  public List<JournalLine> getLines() {
    return Collections.unmodifiableList(new ArrayList<>(lines));
  }

  public void addLine(JournalLine line) {
    if (line == null) {
      return;
    }
    line.setJournalEntry(this);
    lines.add(line);
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(Instant updatedAt) {
    this.updatedAt = updatedAt;
  }

  public Instant getPostedAt() {
    return postedAt;
  }

  public void setPostedAt(Instant postedAt) {
    this.postedAt = postedAt;
  }

  public String getCreatedBy() {
    return createdBy;
  }

  public void setCreatedBy(String createdBy) {
    this.createdBy = createdBy;
  }

  public String getPostedBy() {
    return postedBy;
  }

  public void setPostedBy(String postedBy) {
    this.postedBy = postedBy;
  }

  public String getLastModifiedBy() {
    return lastModifiedBy;
  }

  public void setLastModifiedBy(String lastModifiedBy) {
    this.lastModifiedBy = lastModifiedBy;
  }

  public String getCurrency() {
    return currency;
  }

  public void setCurrency(String currency) {
    this.currency = currency;
  }

  public BigDecimal getFxRate() {
    return fxRate;
  }

  public void setFxRate(BigDecimal fxRate) {
    if (fxRate == null || fxRate.compareTo(BigDecimal.ZERO) <= 0) {
      throw new IllegalArgumentException("FX rate must be positive");
    }
    this.fxRate = fxRate;
  }

  public BigDecimal getForeignAmountTotal() {
    return foreignAmountTotal;
  }

  public void setForeignAmountTotal(BigDecimal foreignAmountTotal) {
    this.foreignAmountTotal = foreignAmountTotal;
  }
}
