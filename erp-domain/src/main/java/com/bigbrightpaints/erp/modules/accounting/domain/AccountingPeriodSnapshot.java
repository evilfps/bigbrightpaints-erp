package com.bigbrightpaints.erp.modules.accounting.domain;

import com.bigbrightpaints.erp.core.domain.VersionedEntity;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "accounting_period_snapshots",
        uniqueConstraints = @UniqueConstraint(columnNames = {"company_id", "accounting_period_id"}))
public class AccountingPeriodSnapshot extends VersionedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id")
    private Company company;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "accounting_period_id")
    private AccountingPeriod period;

    @Column(name = "as_of_date", nullable = false)
    private LocalDate asOfDate;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "trial_balance_total_debit", nullable = false)
    private BigDecimal trialBalanceTotalDebit = BigDecimal.ZERO;

    @Column(name = "trial_balance_total_credit", nullable = false)
    private BigDecimal trialBalanceTotalCredit = BigDecimal.ZERO;

    @Column(name = "inventory_total_value", nullable = false)
    private BigDecimal inventoryTotalValue = BigDecimal.ZERO;

    @Column(name = "inventory_low_stock", nullable = false)
    private Long inventoryLowStock = 0L;

    @Column(name = "ar_subledger_total", nullable = false)
    private BigDecimal arSubledgerTotal = BigDecimal.ZERO;

    @Column(name = "ap_subledger_total", nullable = false)
    private BigDecimal apSubledgerTotal = BigDecimal.ZERO;

    public Long getId() {
        return id;
    }

    public Company getCompany() {
        return company;
    }

    public void setCompany(Company company) {
        this.company = company;
    }

    public AccountingPeriod getPeriod() {
        return period;
    }

    public void setPeriod(AccountingPeriod period) {
        this.period = period;
    }

    public LocalDate getAsOfDate() {
        return asOfDate;
    }

    public void setAsOfDate(LocalDate asOfDate) {
        this.asOfDate = asOfDate;
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

    public BigDecimal getTrialBalanceTotalDebit() {
        return trialBalanceTotalDebit;
    }

    public void setTrialBalanceTotalDebit(BigDecimal trialBalanceTotalDebit) {
        this.trialBalanceTotalDebit = trialBalanceTotalDebit;
    }

    public BigDecimal getTrialBalanceTotalCredit() {
        return trialBalanceTotalCredit;
    }

    public void setTrialBalanceTotalCredit(BigDecimal trialBalanceTotalCredit) {
        this.trialBalanceTotalCredit = trialBalanceTotalCredit;
    }

    public BigDecimal getInventoryTotalValue() {
        return inventoryTotalValue;
    }

    public void setInventoryTotalValue(BigDecimal inventoryTotalValue) {
        this.inventoryTotalValue = inventoryTotalValue;
    }

    public Long getInventoryLowStock() {
        return inventoryLowStock;
    }

    public void setInventoryLowStock(Long inventoryLowStock) {
        this.inventoryLowStock = inventoryLowStock;
    }

    public BigDecimal getArSubledgerTotal() {
        return arSubledgerTotal;
    }

    public void setArSubledgerTotal(BigDecimal arSubledgerTotal) {
        this.arSubledgerTotal = arSubledgerTotal;
    }

    public BigDecimal getApSubledgerTotal() {
        return apSubledgerTotal;
    }

    public void setApSubledgerTotal(BigDecimal apSubledgerTotal) {
        this.apSubledgerTotal = apSubledgerTotal;
    }
}
