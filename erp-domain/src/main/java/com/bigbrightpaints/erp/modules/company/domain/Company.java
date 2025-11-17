package com.bigbrightpaints.erp.modules.company.domain;

import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import com.bigbrightpaints.erp.core.domain.VersionedEntity;

@Entity
@Table(name = "companies")
public class Company extends VersionedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false)
    private UUID publicId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true)
    private String code;

    @Column(nullable = false)
    private String timezone;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payroll_expense_account_id")
    private Account payrollExpenseAccount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payroll_cash_account_id")
    private Account payrollCashAccount;

    @Column(name = "default_gst_rate", nullable = false)
    private BigDecimal defaultGstRate = BigDecimal.valueOf(18);

    @PrePersist
    public void prePersist() {
        if (publicId == null) {
            publicId = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (timezone == null) {
            timezone = "UTC";
        }
    }

    public Long getId() {
        return id;
    }

    public UUID getPublicId() {
        return publicId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getTimezone() {
        return timezone;
    }

    public void setTimezone(String timezone) {
        this.timezone = timezone;
    }

    public Account getPayrollExpenseAccount() {
        return payrollExpenseAccount;
    }

    public void setPayrollExpenseAccount(Account payrollExpenseAccount) {
        this.payrollExpenseAccount = payrollExpenseAccount;
    }

    public Account getPayrollCashAccount() {
        return payrollCashAccount;
    }

    public void setPayrollCashAccount(Account payrollCashAccount) {
        this.payrollCashAccount = payrollCashAccount;
    }

    public BigDecimal getDefaultGstRate() {
        return defaultGstRate;
    }

    public void setDefaultGstRate(BigDecimal defaultGstRate) {
        if (defaultGstRate == null) {
            this.defaultGstRate = BigDecimal.valueOf(18);
            return;
        }
        BigDecimal sanitized = defaultGstRate;
        if (sanitized.compareTo(BigDecimal.ZERO) < 0) {
            sanitized = BigDecimal.ZERO;
        }
        if (sanitized.compareTo(BigDecimal.valueOf(100)) > 0) {
            sanitized = BigDecimal.valueOf(100);
        }
        this.defaultGstRate = sanitized;
    }
}
