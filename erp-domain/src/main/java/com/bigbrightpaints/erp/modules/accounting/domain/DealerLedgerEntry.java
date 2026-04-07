package com.bigbrightpaints.erp.modules.accounting.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import com.bigbrightpaints.erp.core.domain.VersionedEntity;
import com.bigbrightpaints.erp.core.util.CompanyTime;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
    name = "dealer_ledger_entries",
    indexes = {
      @Index(name = "idx_dealer_ledger_company", columnList = "company_id"),
      @Index(name = "idx_dealer_ledger_dealer", columnList = "dealer_id")
    })
public class DealerLedgerEntry extends VersionedEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(optional = false, fetch = FetchType.LAZY)
  @JoinColumn(name = "company_id")
  private Company company;

  @ManyToOne(optional = false, fetch = FetchType.LAZY)
  @JoinColumn(name = "dealer_id")
  private Dealer dealer;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "journal_entry_id")
  private JournalEntry journalEntry;

  @Column(name = "entry_date", nullable = false)
  private LocalDate entryDate;

  @Column(name = "reference_number", nullable = false)
  private String referenceNumber;

  private String memo;

  @Column(nullable = false)
  private BigDecimal debit = BigDecimal.ZERO;

  @Column(nullable = false)
  private BigDecimal credit = BigDecimal.ZERO;

  // Aging and payment tracking fields
  @Column(name = "due_date")
  private LocalDate dueDate;

  @Column(name = "paid_date")
  private LocalDate paidDate;

  @Column(name = "invoice_number")
  private String invoiceNumber;

  @Column(name = "payment_status")
  private String paymentStatus = "UNPAID"; // UNPAID, PARTIAL, PAID, WRITTEN_OFF, VOID, REVERSED

  @Column(name = "amount_paid")
  private BigDecimal amountPaid = BigDecimal.ZERO;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @PrePersist
  public void prePersist() {
    if (createdAt == null) {
      createdAt = CompanyTime.now(company);
    }
    if (entryDate == null) {
      throw new IllegalStateException("Dealer ledger entry date must be set before persisting");
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

  public Dealer getDealer() {
    return dealer;
  }

  public void setDealer(Dealer dealer) {
    this.dealer = dealer;
  }

  public JournalEntry getJournalEntry() {
    return journalEntry;
  }

  public void setJournalEntry(JournalEntry journalEntry) {
    this.journalEntry = journalEntry;
  }

  public LocalDate getEntryDate() {
    return entryDate;
  }

  public void setEntryDate(LocalDate entryDate) {
    this.entryDate = entryDate;
  }

  public String getReferenceNumber() {
    return referenceNumber;
  }

  public void setReferenceNumber(String referenceNumber) {
    this.referenceNumber = referenceNumber;
  }

  public String getMemo() {
    return memo;
  }

  public void setMemo(String memo) {
    this.memo = memo;
  }

  public BigDecimal getDebit() {
    return debit;
  }

  public void setDebit(BigDecimal debit) {
    this.debit = debit;
  }

  public BigDecimal getCredit() {
    return credit;
  }

  public void setCredit(BigDecimal credit) {
    this.credit = credit;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public LocalDate getDueDate() {
    return dueDate;
  }

  public void setDueDate(LocalDate dueDate) {
    this.dueDate = dueDate;
  }

  public LocalDate getPaidDate() {
    return paidDate;
  }

  public void setPaidDate(LocalDate paidDate) {
    this.paidDate = paidDate;
  }

  public String getInvoiceNumber() {
    return invoiceNumber;
  }

  public void setInvoiceNumber(String invoiceNumber) {
    this.invoiceNumber = invoiceNumber;
  }

  public String getPaymentStatus() {
    return paymentStatus;
  }

  public void setPaymentStatus(String paymentStatus) {
    this.paymentStatus = paymentStatus;
  }

  public BigDecimal getAmountPaid() {
    return amountPaid;
  }

  public void setAmountPaid(BigDecimal amountPaid) {
    this.amountPaid = amountPaid;
  }

  public BigDecimal getOutstandingAmount() {
    BigDecimal total = debit.subtract(credit);
    return total.subtract(amountPaid != null ? amountPaid : BigDecimal.ZERO);
  }

  public boolean isOverdue(LocalDate asOfDate) {
    if (asOfDate == null) {
      return false;
    }
    if (dueDate == null || !asOfDate.isAfter(dueDate)) {
      return false;
    }
    String status = paymentStatus != null ? paymentStatus : "";
    return !"PAID".equalsIgnoreCase(status)
        && !"WRITTEN_OFF".equalsIgnoreCase(status)
        && !"VOID".equalsIgnoreCase(status)
        && !"REVERSED".equalsIgnoreCase(status);
  }

  public long getDaysOverdue(LocalDate asOfDate) {
    if (dueDate == null || !isOverdue(asOfDate)) {
      return 0;
    }
    return java.time.temporal.ChronoUnit.DAYS.between(dueDate, asOfDate);
  }
}
