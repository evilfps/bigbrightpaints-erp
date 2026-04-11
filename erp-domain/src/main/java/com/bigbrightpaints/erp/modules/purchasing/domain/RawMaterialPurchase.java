package com.bigbrightpaints.erp.modules.purchasing.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.bigbrightpaints.erp.core.domain.VersionedEntity;
import com.bigbrightpaints.erp.core.util.CompanyTime;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.company.domain.Company;

import jakarta.persistence.*;

@Entity
@Table(
    name = "raw_material_purchases",
    uniqueConstraints = @UniqueConstraint(columnNames = {"company_id", "invoice_number"}))
public class RawMaterialPurchase extends VersionedEntity {

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

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "journal_entry_id")
  private JournalEntry journalEntry;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "purchase_order_id")
  private PurchaseOrder purchaseOrder;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "goods_receipt_id")
  private GoodsReceipt goodsReceipt;

  @Column(name = "invoice_number", nullable = false)
  private String invoiceNumber;

  @Column(name = "invoice_date", nullable = false)
  private LocalDate invoiceDate;

  @Column(nullable = false)
  private BigDecimal totalAmount = BigDecimal.ZERO;

  @Column(name = "tax_amount", nullable = false)
  private BigDecimal taxAmount = BigDecimal.ZERO;

  @Column(name = "outstanding_amount", nullable = false)
  private BigDecimal outstandingAmount = BigDecimal.ZERO;

  @Column(nullable = false)
  private String status = "POSTED";

  @Column(name = "memo")
  private String memo;

  @Column(name = "idempotency_key", length = 128)
  private String idempotencyKey;

  @Column(name = "idempotency_hash", length = 64)
  private String idempotencyHash;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @OneToMany(mappedBy = "purchase", cascade = CascadeType.ALL, orphanRemoval = true)
  private List<RawMaterialPurchaseLine> lines = new ArrayList<>();

  @PrePersist
  public void prePersist() {
    if (publicId == null) {
      publicId = UUID.randomUUID();
    }
    if (createdAt == null) {
      createdAt = CompanyTime.now(company);
    }
    updatedAt = CompanyTime.now(company);
    if (outstandingAmount == null || outstandingAmount.compareTo(BigDecimal.ZERO) == 0) {
      outstandingAmount = totalAmount != null ? totalAmount : BigDecimal.ZERO;
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

  public Supplier getSupplier() {
    return supplier;
  }

  public void setSupplier(Supplier supplier) {
    this.supplier = supplier;
  }

  public JournalEntry getJournalEntry() {
    return journalEntry;
  }

  public void setJournalEntry(JournalEntry journalEntry) {
    this.journalEntry = journalEntry;
  }

  public PurchaseOrder getPurchaseOrder() {
    return purchaseOrder;
  }

  public void setPurchaseOrder(PurchaseOrder purchaseOrder) {
    this.purchaseOrder = purchaseOrder;
  }

  public GoodsReceipt getGoodsReceipt() {
    return goodsReceipt;
  }

  public void setGoodsReceipt(GoodsReceipt goodsReceipt) {
    this.goodsReceipt = goodsReceipt;
  }

  public String getInvoiceNumber() {
    return invoiceNumber;
  }

  public void setInvoiceNumber(String invoiceNumber) {
    this.invoiceNumber = invoiceNumber;
  }

  public LocalDate getInvoiceDate() {
    return invoiceDate;
  }

  public void setInvoiceDate(LocalDate invoiceDate) {
    this.invoiceDate = invoiceDate;
  }

  public BigDecimal getTotalAmount() {
    return totalAmount;
  }

  public void setTotalAmount(BigDecimal totalAmount) {
    this.totalAmount = totalAmount;
  }

  public BigDecimal getTaxAmount() {
    return taxAmount;
  }

  public void setTaxAmount(BigDecimal taxAmount) {
    this.taxAmount = taxAmount;
  }

  public BigDecimal getOutstandingAmount() {
    return outstandingAmount;
  }

  public void setOutstandingAmount(BigDecimal outstandingAmount) {
    this.outstandingAmount = outstandingAmount;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public String getMemo() {
    return memo;
  }

  public void setMemo(String memo) {
    this.memo = memo;
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

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  @SuppressWarnings("java/internal-representation-exposure")
  public List<RawMaterialPurchaseLine> getLines() {
    // lgtm [java/internal-representation-exposure]
    return lines;
  }

  public void setLines(List<RawMaterialPurchaseLine> lines) {
    this.lines = lines == null ? new ArrayList<>() : new ArrayList<>(lines);
  }

  public void addLine(RawMaterialPurchaseLine line) {
    if (line != null) {
      lines.add(line);
    }
  }
}
