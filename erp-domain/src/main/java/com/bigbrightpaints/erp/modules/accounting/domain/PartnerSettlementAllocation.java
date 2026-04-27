package com.bigbrightpaints.erp.modules.accounting.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import com.bigbrightpaints.erp.core.domain.VersionedEntity;
import com.bigbrightpaints.erp.core.util.CompanyTime;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.invoice.domain.Invoice;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchase;
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

@Entity
@Table(
    name = "partner_settlement_allocations",
    indexes = {
      @Index(name = "idx_partner_settlement_company", columnList = "company_id"),
      @Index(
          name = "idx_partner_settlement_partner",
          columnList = "company_id, partner_type, dealer_id, supplier_id"),
      @Index(name = "idx_partner_settlement_invoice", columnList = "company_id, invoice_id"),
      @Index(name = "idx_partner_settlement_purchase", columnList = "company_id, purchase_id"),
      @Index(
          name = "idx_partner_settlement_payment_event",
          columnList = "company_id, payment_event_id")
    })
public class PartnerSettlementAllocation extends VersionedEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

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

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "invoice_id")
  private Invoice invoice;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "purchase_id")
  private RawMaterialPurchase purchase;

  @ManyToOne(optional = false, fetch = FetchType.LAZY)
  @JoinColumn(name = "journal_entry_id")
  private JournalEntry journalEntry;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "payment_event_id")
  private PartnerPaymentEvent paymentEvent;

  @Column(name = "settlement_date", nullable = false)
  private LocalDate settlementDate;

  @Column(name = "allocation_amount", nullable = false)
  private BigDecimal allocationAmount = BigDecimal.ZERO;

  @Column(name = "discount_amount", nullable = false)
  private BigDecimal discountAmount = BigDecimal.ZERO;

  @Column(name = "write_off_amount", nullable = false)
  private BigDecimal writeOffAmount = BigDecimal.ZERO;

  @Column(name = "fx_difference_amount", nullable = false)
  private BigDecimal fxDifferenceAmount = BigDecimal.ZERO;

  @Column(name = "currency", nullable = false)
  private String currency = "INR";

  @Column(name = "idempotency_key", length = 128)
  private String idempotencyKey;

  private String memo;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @PrePersist
  public void prePersist() {
    if (createdAt == null) {
      createdAt = CompanyTime.now(company);
    }
    if (settlementDate == null) {
      settlementDate = CompanyTime.today(company);
    }
  }

  public Long getId() {
    return id;
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

  public Invoice getInvoice() {
    return invoice;
  }

  public void setInvoice(Invoice invoice) {
    this.invoice = invoice;
  }

  public RawMaterialPurchase getPurchase() {
    return purchase;
  }

  public void setPurchase(RawMaterialPurchase purchase) {
    this.purchase = purchase;
  }

  public JournalEntry getJournalEntry() {
    return journalEntry;
  }

  public void setJournalEntry(JournalEntry journalEntry) {
    this.journalEntry = journalEntry;
  }

  public PartnerPaymentEvent getPaymentEvent() {
    return paymentEvent;
  }

  public void setPaymentEvent(PartnerPaymentEvent paymentEvent) {
    this.paymentEvent = paymentEvent;
  }

  public LocalDate getSettlementDate() {
    return settlementDate;
  }

  public void setSettlementDate(LocalDate settlementDate) {
    this.settlementDate = settlementDate;
  }

  public BigDecimal getAllocationAmount() {
    return allocationAmount;
  }

  public void setAllocationAmount(BigDecimal allocationAmount) {
    this.allocationAmount = allocationAmount;
  }

  public BigDecimal getDiscountAmount() {
    return discountAmount;
  }

  public void setDiscountAmount(BigDecimal discountAmount) {
    this.discountAmount = discountAmount;
  }

  public BigDecimal getWriteOffAmount() {
    return writeOffAmount;
  }

  public void setWriteOffAmount(BigDecimal writeOffAmount) {
    this.writeOffAmount = writeOffAmount;
  }

  public BigDecimal getFxDifferenceAmount() {
    return fxDifferenceAmount;
  }

  public void setFxDifferenceAmount(BigDecimal fxDifferenceAmount) {
    this.fxDifferenceAmount = fxDifferenceAmount;
  }

  public String getCurrency() {
    return currency;
  }

  public void setCurrency(String currency) {
    this.currency = currency;
  }

  public String getIdempotencyKey() {
    return idempotencyKey;
  }

  public void setIdempotencyKey(String idempotencyKey) {
    this.idempotencyKey = idempotencyKey;
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
}
