package com.bigbrightpaints.erp.modules.accounting.domain;

import java.math.BigDecimal;
import java.time.Instant;

import com.bigbrightpaints.erp.core.domain.VersionedEntity;
import com.bigbrightpaints.erp.core.util.CompanyTime;
import com.bigbrightpaints.erp.modules.company.domain.Company;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(
    name = "bank_reconciliation_items",
    uniqueConstraints = {
      @UniqueConstraint(
          name = "uq_bank_recon_item_session_line",
          columnNames = {"session_id", "journal_line_id"}),
      @UniqueConstraint(
          name = "uq_bank_recon_item_session_bank_item",
          columnNames = {"session_id", "bank_item_id"})
    })
public class BankReconciliationItem extends VersionedEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(optional = false, fetch = FetchType.LAZY)
  @JoinColumn(name = "company_id")
  private Company company;

  @ManyToOne(optional = false, fetch = FetchType.LAZY)
  @JoinColumn(name = "session_id")
  private BankReconciliationSession session;

  @ManyToOne(optional = false, fetch = FetchType.LAZY)
  @JoinColumn(name = "journal_line_id")
  private JournalLine journalLine;

  @Column(name = "bank_item_id")
  private Long bankItemId;

  @Column(name = "reference_number", length = 128)
  private String referenceNumber;

  @Column(name = "amount", nullable = false, precision = 19, scale = 2)
  private BigDecimal amount;

  @Column(name = "cleared_at", nullable = false)
  private Instant clearedAt;

  @Column(name = "cleared_by", nullable = false, length = 255)
  private String clearedBy;

  @PrePersist
  public void prePersist() {
    if (clearedAt == null) {
      clearedAt = CompanyTime.now(company);
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

  public BankReconciliationSession getSession() {
    return session;
  }

  public void setSession(BankReconciliationSession session) {
    this.session = session;
  }

  public JournalLine getJournalLine() {
    return journalLine;
  }

  public void setJournalLine(JournalLine journalLine) {
    this.journalLine = journalLine;
  }

  public Long getBankItemId() {
    return bankItemId;
  }

  public void setBankItemId(Long bankItemId) {
    this.bankItemId = bankItemId;
  }

  public String getReferenceNumber() {
    return referenceNumber;
  }

  public void setReferenceNumber(String referenceNumber) {
    this.referenceNumber = referenceNumber;
  }

  public BigDecimal getAmount() {
    return amount;
  }

  public void setAmount(BigDecimal amount) {
    this.amount = amount;
  }

  public Instant getClearedAt() {
    return clearedAt;
  }

  public void setClearedAt(Instant clearedAt) {
    this.clearedAt = clearedAt;
  }

  public String getClearedBy() {
    return clearedBy;
  }

  public void setClearedBy(String clearedBy) {
    this.clearedBy = clearedBy;
  }
}
