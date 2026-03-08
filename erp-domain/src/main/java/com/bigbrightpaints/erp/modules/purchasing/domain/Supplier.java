package com.bigbrightpaints.erp.modules.purchasing.domain;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.domain.VersionedEntity;
import com.bigbrightpaints.erp.core.util.CompanyTime;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.GstRegistrationType;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import jakarta.persistence.*;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "suppliers", uniqueConstraints = @UniqueConstraint(columnNames = {"company_id", "code"}))
public class Supplier extends VersionedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false)
    private UUID publicId;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id")
    private Company company;

    @Column(nullable = false)
    private String code;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SupplierStatus status = SupplierStatus.PENDING;

    private String email;
    private String phone;
    private String address;

    @Column(name = "gst_number")
    private String gstNumber;

    @Column(name = "state_code")
    private String stateCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "gst_registration_type", nullable = false)
    private GstRegistrationType gstRegistrationType = GstRegistrationType.UNREGISTERED;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_terms", nullable = false)
    private SupplierPaymentTerms paymentTerms = SupplierPaymentTerms.NET_30;

    @Column(name = "bank_account_name_encrypted")
    private String bankAccountNameEncrypted;

    @Column(name = "bank_account_number_encrypted")
    private String bankAccountNumberEncrypted;

    @Column(name = "bank_ifsc_encrypted")
    private String bankIfscEncrypted;

    @Column(name = "bank_branch_encrypted")
    private String bankBranchEncrypted;

    @Column(name = "credit_limit", nullable = false)
    @PositiveOrZero
    private BigDecimal creditLimit = BigDecimal.ZERO;

    @Column(name = "outstanding_balance", nullable = false)
    private BigDecimal outstandingBalance = BigDecimal.ZERO;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payable_account_id")
    private Account payableAccount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    public void prePersist() {
        if (publicId == null) {
            publicId = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = CompanyTime.now(company);
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

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getStatus() {
        return status.name();
    }

    public SupplierStatus getStatusEnum() {
        return status;
    }

    public boolean isTransactionalUsageAllowed() {
        return getResolvedStatus() == SupplierStatus.ACTIVE;
    }

    public void requireTransactionalUsage(String action) {
        SupplierStatus resolvedStatus = getResolvedStatus();
        if (resolvedStatus == SupplierStatus.ACTIVE) {
            return;
        }
        throw new ApplicationException(
                ErrorCode.BUSINESS_INVALID_STATE,
                transactionalBlockReason(resolvedStatus, action))
                .withDetail("supplierId", id)
                .withDetail("supplierCode", code)
                .withDetail("supplierStatus", resolvedStatus.name())
                .withDetail("action", org.springframework.util.StringUtils.hasText(action) ? action.trim() : null);
    }

    public void setStatus(SupplierStatus status) {
        this.status = status == null ? SupplierStatus.PENDING : status;
    }

    public String getStatusValue() {
        return status.name();
    }

    public void setStatus(String status) {
        if (!org.springframework.util.StringUtils.hasText(status)) {
            this.status = SupplierStatus.PENDING;
            return;
        }
        String normalized = status.trim().toUpperCase(java.util.Locale.ROOT);
        this.status = switch (normalized) {
            case "INACTIVE", "DISABLED" -> SupplierStatus.SUSPENDED;
            case "NEW" -> SupplierStatus.PENDING;
            default -> SupplierStatus.valueOf(normalized);
        };
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public String getGstNumber() {
        return gstNumber;
    }

    public void setGstNumber(String gstNumber) {
        this.gstNumber = gstNumber;
    }

    public String getStateCode() {
        return stateCode;
    }

    public void setStateCode(String stateCode) {
        this.stateCode = stateCode;
    }

    public GstRegistrationType getGstRegistrationType() {
        return gstRegistrationType;
    }

    public void setGstRegistrationType(GstRegistrationType gstRegistrationType) {
        this.gstRegistrationType = gstRegistrationType == null ? GstRegistrationType.UNREGISTERED : gstRegistrationType;
    }

    public SupplierPaymentTerms getPaymentTerms() {
        return paymentTerms;
    }

    public void setPaymentTerms(SupplierPaymentTerms paymentTerms) {
        this.paymentTerms = paymentTerms == null ? SupplierPaymentTerms.NET_30 : paymentTerms;
    }

    public String getBankAccountNameEncrypted() {
        return bankAccountNameEncrypted;
    }

    public void setBankAccountNameEncrypted(String bankAccountNameEncrypted) {
        this.bankAccountNameEncrypted = bankAccountNameEncrypted;
    }

    public String getBankAccountNumberEncrypted() {
        return bankAccountNumberEncrypted;
    }

    public void setBankAccountNumberEncrypted(String bankAccountNumberEncrypted) {
        this.bankAccountNumberEncrypted = bankAccountNumberEncrypted;
    }

    public String getBankIfscEncrypted() {
        return bankIfscEncrypted;
    }

    public void setBankIfscEncrypted(String bankIfscEncrypted) {
        this.bankIfscEncrypted = bankIfscEncrypted;
    }

    public String getBankBranchEncrypted() {
        return bankBranchEncrypted;
    }

    public void setBankBranchEncrypted(String bankBranchEncrypted) {
        this.bankBranchEncrypted = bankBranchEncrypted;
    }

    public BigDecimal getCreditLimit() {
        return creditLimit;
    }

    public void setCreditLimit(BigDecimal creditLimit) {
        this.creditLimit = creditLimit;
    }

    public BigDecimal getOutstandingBalance() {
        return outstandingBalance;
    }

    public void setOutstandingBalance(BigDecimal outstandingBalance) {
        this.outstandingBalance = outstandingBalance;
    }

    public Account getPayableAccount() {
        return payableAccount;
    }

    public void setPayableAccount(Account payableAccount) {
        this.payableAccount = payableAccount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    private SupplierStatus getResolvedStatus() {
        return status == null ? SupplierStatus.PENDING : status;
    }

    private String transactionalBlockReason(SupplierStatus resolvedStatus, String action) {
        String supplierName = org.springframework.util.StringUtils.hasText(name) ? name : "Supplier";
        String normalizedAction = org.springframework.util.StringUtils.hasText(action)
                ? action.trim()
                : "continue this purchasing flow";
        if (resolvedStatus == SupplierStatus.PENDING) {
            return supplierName + " is pending approval and remains visible for reference only; approve and activate it before you can " + normalizedAction;
        }
        if (resolvedStatus == SupplierStatus.APPROVED) {
            return supplierName + " is approved but not yet active and remains visible for reference only; activate it before you can " + normalizedAction;
        }
        return supplierName + " is suspended and remains visible for reference only; resolve the suspension and reactivate it before you can " + normalizedAction;
    }
}
