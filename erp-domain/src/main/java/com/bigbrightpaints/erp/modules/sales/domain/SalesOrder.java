package com.bigbrightpaints.erp.modules.sales.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.bigbrightpaints.erp.core.domain.VersionedEntity;
import com.bigbrightpaints.erp.core.util.CompanyTime;
import com.bigbrightpaints.erp.modules.company.domain.Company;

import jakarta.persistence.*;

@Entity
@Table(
    name = "sales_orders",
    uniqueConstraints = {@UniqueConstraint(columnNames = {"company_id", "order_number"})})
public class SalesOrder extends VersionedEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "public_id", nullable = false)
  private UUID publicId;

  @ManyToOne(optional = false, fetch = FetchType.LAZY)
  @JoinColumn(name = "company_id")
  private Company company;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "dealer_id")
  private Dealer dealer;

  @Column(name = "order_number", nullable = false)
  private String orderNumber;

  @Column(nullable = false)
  private String status;

  @Column(name = "trace_id")
  private String traceId;

  @Column(name = "total_amount", nullable = false)
  private BigDecimal totalAmount;

  @Column(name = "subtotal_amount", nullable = false)
  private BigDecimal subtotalAmount = BigDecimal.ZERO;

  @Column(name = "gst_total", nullable = false)
  private BigDecimal gstTotal = BigDecimal.ZERO;

  @Column(name = "gst_treatment", nullable = false)
  private String gstTreatment = "NONE";

  @Column(name = "gst_inclusive", nullable = false)
  private boolean gstInclusive = false;

  @Column(name = "gst_rate")
  private BigDecimal gstRate = BigDecimal.ZERO;

  @Column(name = "gst_rounding_adjustment", nullable = false)
  private BigDecimal gstRoundingAdjustment = BigDecimal.ZERO;

  @Column(nullable = false)
  private String currency = "INR";

  @Column(name = "idempotency_key", length = 255)
  private String idempotencyKey;

  @Column(name = "idempotency_hash", length = 64)
  private String idempotencyHash;

  @Column(name = "payment_mode", nullable = false, length = 32)
  private String paymentMode = "CREDIT";

  @Column(name = "payment_terms", length = 128)
  private String paymentTerms;

  // Idempotency markers to prevent double posting
  @Column(name = "sales_journal_entry_id")
  private Long salesJournalEntryId;

  @Column(name = "cogs_journal_entry_id")
  private Long cogsJournalEntryId;

  @Column(name = "fulfillment_invoice_id")
  private Long fulfillmentInvoiceId;

  private String notes;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @OneToMany(mappedBy = "salesOrder", cascade = CascadeType.ALL, orphanRemoval = true)
  private List<SalesOrderItem> items = new ArrayList<>();

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

  public Dealer getDealer() {
    return dealer;
  }

  public void setDealer(Dealer dealer) {
    this.dealer = dealer;
  }

  public String getOrderNumber() {
    return orderNumber;
  }

  public void setOrderNumber(String orderNumber) {
    this.orderNumber = orderNumber;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public String getTraceId() {
    return traceId;
  }

  public void setTraceId(String traceId) {
    this.traceId = traceId;
  }

  public BigDecimal getTotalAmount() {
    return totalAmount;
  }

  public void setTotalAmount(BigDecimal totalAmount) {
    this.totalAmount = totalAmount;
  }

  public BigDecimal getSubtotalAmount() {
    return subtotalAmount;
  }

  public void setSubtotalAmount(BigDecimal subtotalAmount) {
    this.subtotalAmount = subtotalAmount;
  }

  public BigDecimal getGstTotal() {
    return gstTotal;
  }

  public void setGstTotal(BigDecimal gstTotal) {
    this.gstTotal = gstTotal;
  }

  public String getGstTreatment() {
    return gstTreatment;
  }

  public void setGstTreatment(String gstTreatment) {
    this.gstTreatment = gstTreatment;
  }

  public boolean isGstInclusive() {
    return gstInclusive;
  }

  public void setGstInclusive(boolean gstInclusive) {
    this.gstInclusive = gstInclusive;
  }

  public BigDecimal getGstRate() {
    return gstRate;
  }

  public void setGstRate(BigDecimal gstRate) {
    this.gstRate = gstRate;
  }

  public BigDecimal getGstRoundingAdjustment() {
    return gstRoundingAdjustment;
  }

  public void setGstRoundingAdjustment(BigDecimal gstRoundingAdjustment) {
    this.gstRoundingAdjustment = gstRoundingAdjustment;
  }

  public String getCurrency() {
    return currency;
  }

  public void setCurrency(String currency) {
    this.currency = currency;
  }

  public String getNotes() {
    return notes;
  }

  public void setNotes(String notes) {
    this.notes = notes;
  }

  public String getIdempotencyHash() {
    return idempotencyHash;
  }

  public void setIdempotencyHash(String idempotencyHash) {
    this.idempotencyHash = idempotencyHash;
  }

  public String getPaymentMode() {
    return paymentMode;
  }

  public void setPaymentMode(String paymentMode) {
    this.paymentMode = paymentMode;
  }

  public String getPaymentTerms() {
    return paymentTerms;
  }

  public void setPaymentTerms(String paymentTerms) {
    this.paymentTerms = paymentTerms;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  @SuppressWarnings("java/internal-representation-exposure")
  public List<SalesOrderItem> getItems() {
    // lgtm [java/internal-representation-exposure]
    return items;
  }

  public void replaceItems(List<SalesOrderItem> items) {
    this.items.clear();
    if (items != null) {
      this.items.addAll(items);
    }
  }

  public String getIdempotencyKey() {
    return idempotencyKey;
  }

  public void setIdempotencyKey(String idempotencyKey) {
    this.idempotencyKey = idempotencyKey;
  }

  // Idempotency marker getters/setters
  public Long getSalesJournalEntryId() {
    return salesJournalEntryId;
  }

  public void setSalesJournalEntryId(Long salesJournalEntryId) {
    this.salesJournalEntryId = salesJournalEntryId;
  }

  public Long getCogsJournalEntryId() {
    return cogsJournalEntryId;
  }

  public void setCogsJournalEntryId(Long cogsJournalEntryId) {
    this.cogsJournalEntryId = cogsJournalEntryId;
  }

  public Long getFulfillmentInvoiceId() {
    return fulfillmentInvoiceId;
  }

  public void setFulfillmentInvoiceId(Long fulfillmentInvoiceId) {
    this.fulfillmentInvoiceId = fulfillmentInvoiceId;
  }

  // Idempotency check helpers
  public boolean hasSalesJournalPosted() {
    return salesJournalEntryId != null;
  }

  public boolean hasCogsJournalPosted() {
    return cogsJournalEntryId != null;
  }

  public boolean hasInvoiceIssued() {
    return fulfillmentInvoiceId != null;
  }
}
