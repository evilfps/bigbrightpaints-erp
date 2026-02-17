package com.bigbrightpaints.erp.modules.company.domain;

import com.bigbrightpaints.erp.core.util.CompanyTime;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import jakarta.persistence.*;

import com.bigbrightpaints.erp.core.domain.VersionedEntity;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.Hibernate;
import org.hibernate.proxy.HibernateProxy;

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

    @Column(name = "gst_input_tax_account_id")
    private Long gstInputTaxAccountId;

    @Column(name = "gst_output_tax_account_id")
    private Long gstOutputTaxAccountId;

    @Column(name = "gst_payable_account_id")
    private Long gstPayableAccountId;

    // Company-wide default accounts for automatic postings
    @Column(name = "default_inventory_account_id")
    private Long defaultInventoryAccountId;

    @Column(name = "default_cogs_account_id")
    private Long defaultCogsAccountId;

    @Column(name = "default_revenue_account_id")
    private Long defaultRevenueAccountId;

    @Column(name = "default_discount_account_id")
    private Long defaultDiscountAccountId;

    @Column(name = "default_tax_account_id")
    private Long defaultTaxAccountId;

    @Column(name = "default_gst_rate", nullable = false)
    private BigDecimal defaultGstRate = BigDecimal.valueOf(18);

    @Column(name = "base_currency", nullable = false)
    private String baseCurrency = "INR";

    @Enumerated(EnumType.STRING)
    @Column(name = "lifecycle_state", nullable = false)
    private CompanyLifecycleState lifecycleState = CompanyLifecycleState.ACTIVE;

    @Column(name = "lifecycle_reason")
    private String lifecycleReason;

    @Column(name = "quota_max_active_users", nullable = false)
    private Long quotaMaxActiveUsers = 0L;

    @Column(name = "quota_max_api_requests", nullable = false)
    private Long quotaMaxApiRequests = 0L;

    @Column(name = "quota_max_storage_bytes", nullable = false)
    private Long quotaMaxStorageBytes = 0L;

    @Column(name = "quota_max_concurrent_sessions", nullable = false)
    private Long quotaMaxConcurrentSessions = 0L;

    @Column(name = "quota_soft_limit_enabled", nullable = false)
    private Boolean quotaSoftLimitEnabled = false;

    @Column(name = "quota_hard_limit_enabled", nullable = false)
    private Boolean quotaHardLimitEnabled = true;

    @PrePersist
    public void prePersist() {
        if (publicId == null) {
            publicId = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = CompanyTime.now();
        }
        if (timezone == null) {
            timezone = "UTC";
        }
        if (lifecycleState == null) {
            lifecycleState = CompanyLifecycleState.ACTIVE;
        }
        initializeQuotaDefaults();
    }

    @PreUpdate
    public void preUpdate() {
        initializeQuotaDefaults();
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

    public Long getGstInputTaxAccountId() {
        return gstInputTaxAccountId;
    }

    public void setGstInputTaxAccountId(Long gstInputTaxAccountId) {
        this.gstInputTaxAccountId = gstInputTaxAccountId;
    }

    public Long getGstOutputTaxAccountId() {
        return gstOutputTaxAccountId;
    }

    public void setGstOutputTaxAccountId(Long gstOutputTaxAccountId) {
        this.gstOutputTaxAccountId = gstOutputTaxAccountId;
    }

    public Long getGstPayableAccountId() {
        return gstPayableAccountId;
    }

    public void setGstPayableAccountId(Long gstPayableAccountId) {
        this.gstPayableAccountId = gstPayableAccountId;
    }

    public Long getDefaultInventoryAccountId() {
        return defaultInventoryAccountId;
    }

    public void setDefaultInventoryAccountId(Long defaultInventoryAccountId) {
        this.defaultInventoryAccountId = defaultInventoryAccountId;
    }

    public Long getDefaultCogsAccountId() {
        return defaultCogsAccountId;
    }

    public void setDefaultCogsAccountId(Long defaultCogsAccountId) {
        this.defaultCogsAccountId = defaultCogsAccountId;
    }

    public Long getDefaultRevenueAccountId() {
        return defaultRevenueAccountId;
    }

    public void setDefaultRevenueAccountId(Long defaultRevenueAccountId) {
        this.defaultRevenueAccountId = defaultRevenueAccountId;
    }

    public Long getDefaultDiscountAccountId() {
        return defaultDiscountAccountId;
    }

    public void setDefaultDiscountAccountId(Long defaultDiscountAccountId) {
        this.defaultDiscountAccountId = defaultDiscountAccountId;
    }

    public Long getDefaultTaxAccountId() {
        return defaultTaxAccountId;
    }

    public void setDefaultTaxAccountId(Long defaultTaxAccountId) {
        this.defaultTaxAccountId = defaultTaxAccountId;
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

    public String getBaseCurrency() {
        return baseCurrency;
    }

    public void setBaseCurrency(String baseCurrency) {
        if (baseCurrency == null || baseCurrency.isBlank()) {
            this.baseCurrency = "INR";
        } else {
            this.baseCurrency = baseCurrency.trim().toUpperCase();
        }
    }

    public CompanyLifecycleState getLifecycleState() {
        return lifecycleState;
    }

    public void setLifecycleState(CompanyLifecycleState lifecycleState) {
        this.lifecycleState = lifecycleState == null ? CompanyLifecycleState.ACTIVE : lifecycleState;
    }

    public String getLifecycleReason() {
        return lifecycleReason;
    }

    public void setLifecycleReason(String lifecycleReason) {
        if (lifecycleReason == null || lifecycleReason.isBlank()) {
            this.lifecycleReason = null;
            return;
        }
        this.lifecycleReason = lifecycleReason.trim();
    }

    public long getQuotaMaxActiveUsers() {
        return sanitizeQuota(quotaMaxActiveUsers);
    }

    public void setQuotaMaxActiveUsers(Long quotaMaxActiveUsers) {
        this.quotaMaxActiveUsers = sanitizeQuota(quotaMaxActiveUsers);
    }

    public long getQuotaMaxApiRequests() {
        return sanitizeQuota(quotaMaxApiRequests);
    }

    public void setQuotaMaxApiRequests(Long quotaMaxApiRequests) {
        this.quotaMaxApiRequests = sanitizeQuota(quotaMaxApiRequests);
    }

    public long getQuotaMaxStorageBytes() {
        return sanitizeQuota(quotaMaxStorageBytes);
    }

    public void setQuotaMaxStorageBytes(Long quotaMaxStorageBytes) {
        this.quotaMaxStorageBytes = sanitizeQuota(quotaMaxStorageBytes);
    }

    public long getQuotaMaxConcurrentSessions() {
        return sanitizeQuota(quotaMaxConcurrentSessions);
    }

    public void setQuotaMaxConcurrentSessions(Long quotaMaxConcurrentSessions) {
        this.quotaMaxConcurrentSessions = sanitizeQuota(quotaMaxConcurrentSessions);
    }

    public boolean isQuotaSoftLimitEnabled() {
        return Boolean.TRUE.equals(quotaSoftLimitEnabled);
    }

    public void setQuotaSoftLimitEnabled(Boolean quotaSoftLimitEnabled) {
        this.quotaSoftLimitEnabled = Boolean.TRUE.equals(quotaSoftLimitEnabled);
        enforceFailClosedQuotaPolicy();
    }

    public boolean isQuotaHardLimitEnabled() {
        return !Boolean.FALSE.equals(quotaHardLimitEnabled);
    }

    public void setQuotaHardLimitEnabled(Boolean quotaHardLimitEnabled) {
        this.quotaHardLimitEnabled = quotaHardLimitEnabled == null || quotaHardLimitEnabled;
        enforceFailClosedQuotaPolicy();
    }

    private long sanitizeQuota(Long value) {
        if (value == null || value < 0L) {
            return 0L;
        }
        return value;
    }

    private void initializeQuotaDefaults() {
        if (quotaMaxActiveUsers == null) {
            quotaMaxActiveUsers = 0L;
        }
        if (quotaMaxApiRequests == null) {
            quotaMaxApiRequests = 0L;
        }
        if (quotaMaxStorageBytes == null) {
            quotaMaxStorageBytes = 0L;
        }
        if (quotaMaxConcurrentSessions == null) {
            quotaMaxConcurrentSessions = 0L;
        }
        if (quotaSoftLimitEnabled == null) {
            quotaSoftLimitEnabled = false;
        }
        if (quotaHardLimitEnabled == null) {
            quotaHardLimitEnabled = true;
        }
        enforceFailClosedQuotaPolicy();
    }

    private void enforceFailClosedQuotaPolicy() {
        if (!Boolean.TRUE.equals(quotaSoftLimitEnabled) && !Boolean.TRUE.equals(quotaHardLimitEnabled)) {
            quotaHardLimitEnabled = true;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null) return false;
        if (!Hibernate.getClass(this).equals(Hibernate.getClass(o))) return false;
        Company company = (Company) o;

        Long thisId = this instanceof HibernateProxy proxy
                ? (Long) proxy.getHibernateLazyInitializer().getIdentifier()
                : getId();
        Long otherId = company instanceof HibernateProxy proxy
                ? (Long) proxy.getHibernateLazyInitializer().getIdentifier()
                : company.getId();

        return thisId != null && Objects.equals(thisId, otherId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(getId());
    }
}
