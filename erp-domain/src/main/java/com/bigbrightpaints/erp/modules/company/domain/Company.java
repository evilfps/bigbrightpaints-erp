package com.bigbrightpaints.erp.modules.company.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.hibernate.Hibernate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.proxy.HibernateProxy;
import org.hibernate.type.SqlTypes;

import com.bigbrightpaints.erp.core.domain.VersionedEntity;
import com.bigbrightpaints.erp.core.util.CompanyTime;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;

import jakarta.persistence.*;

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

  @Column(name = "state_code")
  private String stateCode;

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

  @Convert(converter = CompanyLifecycleStateConverter.class)
  @Column(name = "lifecycle_state", nullable = false)
  private CompanyLifecycleState lifecycleState = CompanyLifecycleState.ACTIVE;

  @Column(name = "lifecycle_reason")
  private String lifecycleReason;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "enabled_modules", nullable = false, columnDefinition = "jsonb")
  private Set<String> enabledModules =
      new LinkedHashSet<>(CompanyModule.defaultEnabledGatableModuleNames());

  @Column(name = "quota_max_active_users", nullable = false)
  private Long quotaMaxActiveUsers = 0L;

  @Column(name = "quota_max_api_requests", nullable = false)
  private Long quotaMaxApiRequests = 0L;

  @Column(name = "quota_max_storage_bytes", nullable = false)
  private Long quotaMaxStorageBytes = 0L;

  @Column(name = "quota_max_concurrent_requests", nullable = false)
  private Long quotaMaxConcurrentRequests = 0L;

  @Column(name = "quota_soft_limit_enabled", nullable = false)
  private Boolean quotaSoftLimitEnabled = false;

  @Column(name = "quota_hard_limit_enabled", nullable = false)
  private Boolean quotaHardLimitEnabled = true;

  @Column(name = "main_admin_user_id")
  private Long mainAdminUserId;

  @Column(name = "support_notes", columnDefinition = "TEXT")
  private String supportNotes;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "support_tags", nullable = false, columnDefinition = "jsonb")
  private Set<String> supportTags = new LinkedHashSet<>();

  @Column(name = "onboarding_coa_template_code")
  private String onboardingCoaTemplateCode;

  @Column(name = "onboarding_admin_email")
  private String onboardingAdminEmail;

  @Column(name = "onboarding_admin_user_id")
  private Long onboardingAdminUserId;

  @Column(name = "onboarding_completed_at")
  private Instant onboardingCompletedAt;

  @Column(name = "onboarding_credentials_emailed_at")
  private Instant onboardingCredentialsEmailedAt;

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
    enabledModules = CompanyModule.normalizeEnabledGatableModuleNames(enabledModules);
    supportTags = normalizeSupportTags(supportTags);
    initializeQuotaDefaults();
  }

  @PreUpdate
  public void preUpdate() {
    enabledModules = CompanyModule.normalizeEnabledGatableModuleNames(enabledModules);
    supportTags = normalizeSupportTags(supportTags);
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

  public String getStateCode() {
    return stateCode;
  }

  public void setStateCode(String stateCode) {
    if (stateCode == null || stateCode.isBlank()) {
      this.stateCode = null;
      return;
    }
    this.stateCode = stateCode.trim().toUpperCase();
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

  public Set<String> getEnabledModules() {
    return enabledModules == null
        ? new LinkedHashSet<>(CompanyModule.defaultEnabledGatableModuleNames())
        : new LinkedHashSet<>(enabledModules);
  }

  public void setEnabledModules(Set<String> enabledModules) {
    this.enabledModules = CompanyModule.normalizeEnabledGatableModuleNames(enabledModules);
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

  public long getQuotaMaxConcurrentRequests() {
    return sanitizeQuota(quotaMaxConcurrentRequests);
  }

  public void setQuotaMaxConcurrentRequests(Long quotaMaxConcurrentRequests) {
    this.quotaMaxConcurrentRequests = sanitizeQuota(quotaMaxConcurrentRequests);
  }

  public Long getMainAdminUserId() {
    return mainAdminUserId;
  }

  public void setMainAdminUserId(Long mainAdminUserId) {
    this.mainAdminUserId = mainAdminUserId;
  }

  public String getSupportNotes() {
    return supportNotes;
  }

  public void setSupportNotes(String supportNotes) {
    if (supportNotes == null || supportNotes.isBlank()) {
      this.supportNotes = null;
      return;
    }
    this.supportNotes = supportNotes.trim();
  }

  public Set<String> getSupportTags() {
    return supportTags == null ? new LinkedHashSet<>() : new LinkedHashSet<>(supportTags);
  }

  public void setSupportTags(Set<String> supportTags) {
    this.supportTags = normalizeSupportTags(supportTags);
  }

  public String getOnboardingCoaTemplateCode() {
    return onboardingCoaTemplateCode;
  }

  public void setOnboardingCoaTemplateCode(String onboardingCoaTemplateCode) {
    if (onboardingCoaTemplateCode == null || onboardingCoaTemplateCode.isBlank()) {
      this.onboardingCoaTemplateCode = null;
      return;
    }
    this.onboardingCoaTemplateCode = onboardingCoaTemplateCode.trim().toUpperCase();
  }

  public String getOnboardingAdminEmail() {
    return onboardingAdminEmail;
  }

  public void setOnboardingAdminEmail(String onboardingAdminEmail) {
    if (onboardingAdminEmail == null || onboardingAdminEmail.isBlank()) {
      this.onboardingAdminEmail = null;
      return;
    }
    this.onboardingAdminEmail = onboardingAdminEmail.trim().toLowerCase();
  }

  public Long getOnboardingAdminUserId() {
    return onboardingAdminUserId;
  }

  public void setOnboardingAdminUserId(Long onboardingAdminUserId) {
    this.onboardingAdminUserId = onboardingAdminUserId;
  }

  public Instant getOnboardingCompletedAt() {
    return onboardingCompletedAt;
  }

  public void setOnboardingCompletedAt(Instant onboardingCompletedAt) {
    this.onboardingCompletedAt = onboardingCompletedAt;
  }

  public Instant getOnboardingCredentialsEmailedAt() {
    return onboardingCredentialsEmailedAt;
  }

  public void setOnboardingCredentialsEmailedAt(Instant onboardingCredentialsEmailedAt) {
    this.onboardingCredentialsEmailedAt = onboardingCredentialsEmailedAt;
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
    if (quotaMaxConcurrentRequests == null) {
      quotaMaxConcurrentRequests = 0L;
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
    if (!Boolean.TRUE.equals(quotaSoftLimitEnabled)
        && !Boolean.TRUE.equals(quotaHardLimitEnabled)) {
      quotaHardLimitEnabled = true;
    }
  }

  private Set<String> normalizeSupportTags(Set<String> requestedSupportTags) {
    LinkedHashSet<String> normalized = new LinkedHashSet<>();
    if (requestedSupportTags == null) {
      return normalized;
    }
    for (String requestedTag : requestedSupportTags) {
      if (requestedTag == null || requestedTag.isBlank()) {
        continue;
      }
      normalized.add(requestedTag.trim().toUpperCase());
    }
    return normalized;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null) return false;
    if (!Hibernate.getClass(this).equals(Hibernate.getClass(o))) return false;
    Company company = (Company) o;

    Long thisId =
        this instanceof HibernateProxy proxy
            ? (Long) proxy.getHibernateLazyInitializer().getIdentifier()
            : getId();
    Long otherId =
        company instanceof HibernateProxy proxy
            ? (Long) proxy.getHibernateLazyInitializer().getIdentifier()
            : company.getId();

    return thisId != null && Objects.equals(thisId, otherId);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(getId());
  }
}
