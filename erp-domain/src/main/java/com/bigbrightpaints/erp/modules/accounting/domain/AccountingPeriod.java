package com.bigbrightpaints.erp.modules.accounting.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.Locale;
import java.util.UUID;

import com.bigbrightpaints.erp.core.domain.VersionedEntity;
import com.bigbrightpaints.erp.modules.company.domain.Company;

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
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(
    name = "accounting_periods",
    uniqueConstraints = @UniqueConstraint(columnNames = {"company_id", "year", "month"}))
public class AccountingPeriod extends VersionedEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "public_id", nullable = false)
  private UUID publicId;

  @ManyToOne(optional = false, fetch = FetchType.LAZY)
  @JoinColumn(name = "company_id")
  private Company company;

  @Column(nullable = false)
  private int year;

  @Column(nullable = false)
  private int month;

  @Column(name = "start_date", nullable = false)
  private LocalDate startDate;

  @Column(name = "end_date", nullable = false)
  private LocalDate endDate;

  @Enumerated(EnumType.STRING)
  @Column(name = "costing_method", nullable = false)
  private CostingMethod costingMethod = CostingMethod.FIFO;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private AccountingPeriodStatus status = AccountingPeriodStatus.OPEN;

  @Column(name = "bank_reconciled", nullable = false)
  private boolean bankReconciled;

  @Column(name = "bank_reconciled_at")
  private Instant bankReconciledAt;

  @Column(name = "bank_reconciled_by")
  private String bankReconciledBy;

  @Column(name = "inventory_counted", nullable = false)
  private boolean inventoryCounted;

  @Column(name = "inventory_counted_at")
  private Instant inventoryCountedAt;

  @Column(name = "inventory_counted_by")
  private String inventoryCountedBy;

  @Column(name = "checklist_notes")
  private String checklistNotes;

  @Column(name = "closed_at")
  private Instant closedAt;

  @Column(name = "closed_by")
  private String closedBy;

  @Column(name = "locked_at")
  private Instant lockedAt;

  @Column(name = "locked_by")
  private String lockedBy;

  @Column(name = "lock_reason")
  private String lockReason;

  @Column(name = "reopened_at")
  private Instant reopenedAt;

  @Column(name = "reopened_by")
  private String reopenedBy;

  @Column(name = "reopen_reason")
  private String reopenReason;

  @Column(name = "closing_journal_entry_id")
  private Long closingJournalEntryId;

  @PrePersist
  public void prePersist() {
    if (publicId == null) {
      publicId = UUID.randomUUID();
    }
    if ((year == 0 || month == 0) && startDate != null) {
      this.year = startDate.getYear();
      this.month = startDate.getMonthValue();
    }
    if (startDate == null || endDate == null) {
      YearMonth yearMonth = YearMonth.of(year, month);
      this.startDate = yearMonth.atDay(1);
      this.endDate = yearMonth.atEndOfMonth();
    }
    if (costingMethod == null) {
      costingMethod = CostingMethod.FIFO;
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

  public int getYear() {
    return year;
  }

  public void setYear(int year) {
    this.year = year;
  }

  public int getMonth() {
    return month;
  }

  public void setMonth(int month) {
    this.month = month;
  }

  public LocalDate getStartDate() {
    return startDate;
  }

  public void setStartDate(LocalDate startDate) {
    this.startDate = startDate;
  }

  public LocalDate getEndDate() {
    return endDate;
  }

  public void setEndDate(LocalDate endDate) {
    this.endDate = endDate;
  }

  public CostingMethod getCostingMethod() {
    return costingMethod;
  }

  public void setCostingMethod(CostingMethod costingMethod) {
    this.costingMethod = costingMethod;
  }

  public AccountingPeriodStatus getStatus() {
    return status;
  }

  public void setStatus(AccountingPeriodStatus status) {
    this.status = status;
  }

  public boolean isBankReconciled() {
    return bankReconciled;
  }

  public void setBankReconciled(boolean bankReconciled) {
    this.bankReconciled = bankReconciled;
  }

  public boolean isInventoryCounted() {
    return inventoryCounted;
  }

  public void setInventoryCounted(boolean inventoryCounted) {
    this.inventoryCounted = inventoryCounted;
  }

  public Instant getBankReconciledAt() {
    return bankReconciledAt;
  }

  public void setBankReconciledAt(Instant bankReconciledAt) {
    this.bankReconciledAt = bankReconciledAt;
  }

  public String getBankReconciledBy() {
    return bankReconciledBy;
  }

  public void setBankReconciledBy(String bankReconciledBy) {
    this.bankReconciledBy = bankReconciledBy;
  }

  public Instant getInventoryCountedAt() {
    return inventoryCountedAt;
  }

  public void setInventoryCountedAt(Instant inventoryCountedAt) {
    this.inventoryCountedAt = inventoryCountedAt;
  }

  public String getInventoryCountedBy() {
    return inventoryCountedBy;
  }

  public void setInventoryCountedBy(String inventoryCountedBy) {
    this.inventoryCountedBy = inventoryCountedBy;
  }

  public String getChecklistNotes() {
    return checklistNotes;
  }

  public void setChecklistNotes(String checklistNotes) {
    this.checklistNotes = checklistNotes;
  }

  public Instant getClosedAt() {
    return closedAt;
  }

  public void setClosedAt(Instant closedAt) {
    this.closedAt = closedAt;
  }

  public String getClosedBy() {
    return closedBy;
  }

  public void setClosedBy(String closedBy) {
    this.closedBy = closedBy;
  }

  public Instant getLockedAt() {
    return lockedAt;
  }

  public void setLockedAt(Instant lockedAt) {
    this.lockedAt = lockedAt;
  }

  public String getLockedBy() {
    return lockedBy;
  }

  public void setLockedBy(String lockedBy) {
    this.lockedBy = lockedBy;
  }

  public String getLockReason() {
    return lockReason;
  }

  public void setLockReason(String lockReason) {
    this.lockReason = lockReason;
  }

  public Instant getReopenedAt() {
    return reopenedAt;
  }

  public void setReopenedAt(Instant reopenedAt) {
    this.reopenedAt = reopenedAt;
  }

  public String getReopenedBy() {
    return reopenedBy;
  }

  public void setReopenedBy(String reopenedBy) {
    this.reopenedBy = reopenedBy;
  }

  public String getReopenReason() {
    return reopenReason;
  }

  public void setReopenReason(String reopenReason) {
    this.reopenReason = reopenReason;
  }

  public Long getClosingJournalEntryId() {
    return closingJournalEntryId;
  }

  public void setClosingJournalEntryId(Long closingJournalEntryId) {
    this.closingJournalEntryId = closingJournalEntryId;
  }

  public String getLabel() {
    YearMonth yearMonth = YearMonth.of(year, month);
    return yearMonth.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + year;
  }

  public boolean contains(LocalDate date) {
    if (date == null) {
      return false;
    }
    return (date.isEqual(startDate) || date.isAfter(startDate))
        && (date.isEqual(endDate) || date.isBefore(endDate));
  }
}
