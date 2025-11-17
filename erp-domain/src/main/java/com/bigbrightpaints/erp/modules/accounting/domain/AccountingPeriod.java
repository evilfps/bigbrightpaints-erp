package com.bigbrightpaints.erp.modules.accounting.domain;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.core.domain.VersionedEntity;
import jakarta.persistence.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.UUID;
import java.util.Locale;

@Entity
@Table(name = "accounting_periods", uniqueConstraints = @UniqueConstraint(columnNames = {"company_id", "year", "month"}))
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

    @PrePersist
    public void prePersist() {
        if (publicId == null) {
            publicId = UUID.randomUUID();
        }
        if (startDate == null || endDate == null) {
            YearMonth yearMonth = YearMonth.of(year, month);
            this.startDate = yearMonth.atDay(1);
            this.endDate = yearMonth.atEndOfMonth();
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

    public String getLabel() {
        YearMonth yearMonth = YearMonth.of(year, month);
        return yearMonth.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + year;
    }

    public boolean contains(LocalDate date) {
        if (date == null) {
            return false;
        }
        return (date.isEqual(startDate) || date.isAfter(startDate)) && (date.isEqual(endDate) || date.isBefore(endDate));
    }
}
