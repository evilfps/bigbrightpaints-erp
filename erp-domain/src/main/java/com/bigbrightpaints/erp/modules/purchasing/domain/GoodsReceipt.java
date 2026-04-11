package com.bigbrightpaints.erp.modules.purchasing.domain;

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
@Table(
    name = "goods_receipts",
    uniqueConstraints = @UniqueConstraint(columnNames = {"company_id", "receipt_number"}))
public class GoodsReceipt extends VersionedEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "public_id", nullable = false)
  private UUID publicId;

  @ManyToOne(optional = false, fetch = FetchType.LAZY)
  @JoinColumn(name = "company_id")
  private Company company;

  @ManyToOne(optional = false, fetch = FetchType.LAZY)
  @JoinColumn(name = "supplier_id")
  private Supplier supplier;

  @ManyToOne(optional = false, fetch = FetchType.LAZY)
  @JoinColumn(name = "purchase_order_id")
  private PurchaseOrder purchaseOrder;

  @Column(name = "receipt_number", nullable = false)
  private String receiptNumber;

  @Column(name = "receipt_date", nullable = false)
  private LocalDate receiptDate;

  @Column(name = "idempotency_key", length = 128)
  private String idempotencyKey;

  @Column(name = "idempotency_hash", length = 64)
  private String idempotencyHash;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private GoodsReceiptStatus status = GoodsReceiptStatus.RECEIVED;

  @Column(name = "memo")
  private String memo;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @OneToMany(mappedBy = "goodsReceipt", cascade = CascadeType.ALL, orphanRemoval = true)
  private List<GoodsReceiptLine> lines = new ArrayList<>();

  @PrePersist
  public void prePersist() {
    if (publicId == null) {
      publicId = UUID.randomUUID();
    }
    Instant now = CompanyTime.now(company);
    if (createdAt == null) {
      createdAt = now;
    }
    updatedAt = now;
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

  public Supplier getSupplier() {
    return supplier;
  }

  public void setSupplier(Supplier supplier) {
    this.supplier = supplier;
  }

  public PurchaseOrder getPurchaseOrder() {
    return purchaseOrder;
  }

  public void setPurchaseOrder(PurchaseOrder purchaseOrder) {
    this.purchaseOrder = purchaseOrder;
  }

  public String getReceiptNumber() {
    return receiptNumber;
  }

  public void setReceiptNumber(String receiptNumber) {
    this.receiptNumber = receiptNumber;
  }

  public LocalDate getReceiptDate() {
    return receiptDate;
  }

  public void setReceiptDate(LocalDate receiptDate) {
    this.receiptDate = receiptDate;
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

  public String getStatus() {
    return status.name();
  }

  public GoodsReceiptStatus getStatusEnum() {
    return status;
  }

  public void setStatus(GoodsReceiptStatus status) {
    this.status = status == null ? GoodsReceiptStatus.RECEIVED : status;
  }

  public String getMemo() {
    return memo;
  }

  public void setMemo(String memo) {
    this.memo = memo;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  @SuppressWarnings("java/internal-representation-exposure")
  public List<GoodsReceiptLine> getLines() {
    // lgtm [java/internal-representation-exposure]
    return lines;
  }

  public void setLines(List<GoodsReceiptLine> lines) {
    this.lines = lines == null ? new ArrayList<>() : new ArrayList<>(lines);
  }

  public void addLine(GoodsReceiptLine line) {
    if (line != null) {
      lines.add(line);
    }
  }

  public String getStatusValue() {
    return status.name();
  }

  public void setStatus(String status) {
    if (!org.springframework.util.StringUtils.hasText(status)) {
      this.status = GoodsReceiptStatus.RECEIVED;
      return;
    }
    String normalized = status.trim().toUpperCase(java.util.Locale.ROOT);
    this.status =
        switch (normalized) {
          case "PARTIALLY_RECEIVED" -> GoodsReceiptStatus.PARTIAL;
          default -> GoodsReceiptStatus.valueOf(normalized);
        };
  }
}
