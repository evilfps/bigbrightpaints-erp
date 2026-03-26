package com.bigbrightpaints.erp.modules.company.domain;

import java.time.Instant;
import java.util.Locale;

import com.bigbrightpaints.erp.core.domain.VersionedEntity;
import com.bigbrightpaints.erp.core.util.CompanyTime;

import jakarta.persistence.*;

@Entity
@Table(name = "tenant_support_warnings")
public class TenantSupportWarning extends VersionedEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "company_id", nullable = false)
  private Company company;

  @Column(name = "warning_category", nullable = false)
  private String warningCategory;

  @Column(name = "message", nullable = false, length = 500)
  private String message;

  @Column(name = "requested_lifecycle_state", nullable = false, length = 32)
  private String requestedLifecycleState;

  @Column(name = "grace_period_hours", nullable = false)
  private Integer gracePeriodHours;

  @Column(name = "issued_by", nullable = false, length = 255)
  private String issuedBy;

  @Column(name = "issued_at", nullable = false)
  private Instant issuedAt;

  @PrePersist
  void prePersist() {
    if (warningCategory == null || warningCategory.isBlank()) {
      warningCategory = "GENERAL";
    } else {
      warningCategory = warningCategory.trim().toUpperCase(Locale.ROOT);
    }
    if (requestedLifecycleState == null || requestedLifecycleState.isBlank()) {
      requestedLifecycleState = CompanyLifecycleState.SUSPENDED.name();
    } else {
      requestedLifecycleState = requestedLifecycleState.trim().toUpperCase(Locale.ROOT);
    }
    if (issuedBy == null || issuedBy.isBlank()) {
      issuedBy = "UNKNOWN";
    } else {
      issuedBy = issuedBy.trim();
    }
    if (gracePeriodHours == null || gracePeriodHours < 1) {
      gracePeriodHours = 24;
    }
    if (issuedAt == null) {
      issuedAt = company == null ? CompanyTime.now() : CompanyTime.now(company);
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

  public String getWarningCategory() {
    return warningCategory;
  }

  public void setWarningCategory(String warningCategory) {
    this.warningCategory = warningCategory;
  }

  public String getMessage() {
    return message;
  }

  public void setMessage(String message) {
    this.message = message;
  }

  public String getRequestedLifecycleState() {
    return requestedLifecycleState;
  }

  public void setRequestedLifecycleState(String requestedLifecycleState) {
    this.requestedLifecycleState = requestedLifecycleState;
  }

  public Integer getGracePeriodHours() {
    return gracePeriodHours;
  }

  public void setGracePeriodHours(Integer gracePeriodHours) {
    this.gracePeriodHours = gracePeriodHours;
  }

  public String getIssuedBy() {
    return issuedBy;
  }

  public void setIssuedBy(String issuedBy) {
    this.issuedBy = issuedBy;
  }

  public Instant getIssuedAt() {
    return issuedAt;
  }

  public void setIssuedAt(Instant issuedAt) {
    this.issuedAt = issuedAt;
  }
}
