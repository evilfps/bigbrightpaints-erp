package com.bigbrightpaints.erp.modules.hr.domain;

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
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "leave_balances",
        uniqueConstraints = @UniqueConstraint(
                columnNames = {"company_id", "employee_id", "leave_type", "balance_year"}))
public class LeaveBalance extends VersionedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false)
    private UUID publicId;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id")
    private Company company;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "employee_id")
    private Employee employee;

    @Column(name = "leave_type", nullable = false)
    private String leaveType;

    @Column(name = "balance_year", nullable = false)
    private Integer balanceYear;

    @Column(name = "opening_balance", precision = 10, scale = 2, nullable = false)
    private BigDecimal openingBalance = BigDecimal.ZERO;

    @Column(precision = 10, scale = 2, nullable = false)
    private BigDecimal accrued = BigDecimal.ZERO;

    @Column(precision = 10, scale = 2, nullable = false)
    private BigDecimal used = BigDecimal.ZERO;

    @Column(precision = 10, scale = 2, nullable = false)
    private BigDecimal remaining = BigDecimal.ZERO;

    @Column(name = "carry_forward_applied", precision = 10, scale = 2, nullable = false)
    private BigDecimal carryForwardApplied = BigDecimal.ZERO;

    @Column(name = "last_recalculated_at")
    private Instant lastRecalculatedAt;

    @PrePersist
    public void prePersist() {
        if (publicId == null) {
            publicId = UUID.randomUUID();
        }
        if (lastRecalculatedAt == null) {
            lastRecalculatedAt = CompanyTime.now(company);
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

    public Employee getEmployee() {
        return employee;
    }

    public void setEmployee(Employee employee) {
        this.employee = employee;
    }

    public String getLeaveType() {
        return leaveType;
    }

    public void setLeaveType(String leaveType) {
        this.leaveType = leaveType;
    }

    public Integer getBalanceYear() {
        return balanceYear;
    }

    public void setBalanceYear(Integer balanceYear) {
        this.balanceYear = balanceYear;
    }

    public BigDecimal getOpeningBalance() {
        return openingBalance;
    }

    public void setOpeningBalance(BigDecimal openingBalance) {
        this.openingBalance = openingBalance;
    }

    public BigDecimal getAccrued() {
        return accrued;
    }

    public void setAccrued(BigDecimal accrued) {
        this.accrued = accrued;
    }

    public BigDecimal getUsed() {
        return used;
    }

    public void setUsed(BigDecimal used) {
        this.used = used;
    }

    public BigDecimal getRemaining() {
        return remaining;
    }

    public void setRemaining(BigDecimal remaining) {
        this.remaining = remaining;
    }

    public BigDecimal getCarryForwardApplied() {
        return carryForwardApplied;
    }

    public void setCarryForwardApplied(BigDecimal carryForwardApplied) {
        this.carryForwardApplied = carryForwardApplied;
    }

    public Instant getLastRecalculatedAt() {
        return lastRecalculatedAt;
    }

    public void setLastRecalculatedAt(Instant lastRecalculatedAt) {
        this.lastRecalculatedAt = lastRecalculatedAt;
    }
}
