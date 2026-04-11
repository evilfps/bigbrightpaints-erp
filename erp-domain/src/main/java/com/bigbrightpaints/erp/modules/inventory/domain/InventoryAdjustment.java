package com.bigbrightpaints.erp.modules.inventory.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.bigbrightpaints.erp.core.domain.VersionedEntity;
import com.bigbrightpaints.erp.core.util.CompanyTime;
import com.bigbrightpaints.erp.modules.company.domain.Company;

import jakarta.persistence.*;

@Entity
@Table(name = "inventory_adjustments")
public class InventoryAdjustment extends VersionedEntity {

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

  @Column(name = "adjustment_date", nullable = false)
  private LocalDate adjustmentDate;

  @Enumerated(EnumType.STRING)
  @Column(name = "adjustment_type", nullable = false)
  private InventoryAdjustmentType type;

  private String reason;

  @Column(nullable = false)
  private String status = "DRAFT";

  @Column(name = "journal_entry_id")
  private Long journalEntryId;

  @Column(name = "total_amount", nullable = false)
  private BigDecimal totalAmount = BigDecimal.ZERO;

  @Column(name = "idempotency_key", length = 128)
  private String idempotencyKey;

  @Column(name = "idempotency_hash", length = 64)
  private String idempotencyHash;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "created_by")
  private String createdBy;

  @OneToMany(mappedBy = "adjustment", cascade = CascadeType.ALL, orphanRemoval = true)
  private List<InventoryAdjustmentLine> lines = new ArrayList<>();

  @PrePersist
  public void prePersist() {
    if (publicId == null) {
      publicId = UUID.randomUUID();
    }
    if (createdAt == null) {
      createdAt = CompanyTime.now(company);
    }
    if (adjustmentDate == null) {
      adjustmentDate = CompanyTime.today(company);
    }
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

  public LocalDate getAdjustmentDate() {
    return adjustmentDate;
  }

  public void setAdjustmentDate(LocalDate adjustmentDate) {
    this.adjustmentDate = adjustmentDate;
  }

  public InventoryAdjustmentType getType() {
    return type;
  }

  public void setType(InventoryAdjustmentType type) {
    this.type = type;
  }

  public String getReason() {
    return reason;
  }

  public void setReason(String reason) {
    this.reason = reason;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public Long getJournalEntryId() {
    return journalEntryId;
  }

  public void setJournalEntryId(Long journalEntryId) {
    this.journalEntryId = journalEntryId;
  }

  public String getIdempotencyKey() {
    return idempotencyKey;
  }

  public void setIdempotencyKey(String idempotencyKey) {
    this.idempotencyKey = idempotencyKey;
  }

  public String getIdempotencyHash() {
    return idempotencyHash;
  }

  public void setIdempotencyHash(String idempotencyHash) {
    this.idempotencyHash = idempotencyHash;
  }

  public BigDecimal getTotalAmount() {
    return totalAmount;
  }

  public void setTotalAmount(BigDecimal totalAmount) {
    this.totalAmount = totalAmount;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt;
  }

  public String getCreatedBy() {
    return createdBy;
  }

  public void setCreatedBy(String createdBy) {
    this.createdBy = createdBy;
  }

  @SuppressWarnings("java/internal-representation-exposure")
  public List<InventoryAdjustmentLine> getLines() {
    // lgtm [java/internal-representation-exposure]
    return lines;
  }

  public void setLines(List<InventoryAdjustmentLine> lines) {
    this.lines = lines == null ? new ArrayList<>() : new ArrayList<>(lines);
  }

  public void addLine(InventoryAdjustmentLine line) {
    if (line != null) {
      lines.add(line);
    }
  }
}
