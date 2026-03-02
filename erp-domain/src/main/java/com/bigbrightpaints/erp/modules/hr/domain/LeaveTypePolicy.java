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
@Table(name = "leave_type_policies",
        uniqueConstraints = @UniqueConstraint(columnNames = {"company_id", "leave_type"}))
public class LeaveTypePolicy extends VersionedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false)
    private UUID publicId;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id")
    private Company company;

    @Column(name = "leave_type", nullable = false)
    private String leaveType;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "annual_entitlement", precision = 10, scale = 2, nullable = false)
    private BigDecimal annualEntitlement;

    @Column(name = "carry_forward_limit", precision = 10, scale = 2, nullable = false)
    private BigDecimal carryForwardLimit = BigDecimal.ZERO;

    @Column(nullable = false)
    private boolean active = true;

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

    public String getLeaveType() {
        return leaveType;
    }

    public void setLeaveType(String leaveType) {
        this.leaveType = leaveType;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public BigDecimal getAnnualEntitlement() {
        return annualEntitlement;
    }

    public void setAnnualEntitlement(BigDecimal annualEntitlement) {
        this.annualEntitlement = annualEntitlement;
    }

    public BigDecimal getCarryForwardLimit() {
        return carryForwardLimit;
    }

    public void setCarryForwardLimit(BigDecimal carryForwardLimit) {
        this.carryForwardLimit = carryForwardLimit;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
