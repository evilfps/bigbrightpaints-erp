package com.bigbrightpaints.erp.modules.accounting.domain;

import java.math.BigDecimal;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    name = "accounts",
    uniqueConstraints = @UniqueConstraint(columnNames = {"company_id", "code"}))
public class Account extends VersionedEntity {

  private static final Logger log = LoggerFactory.getLogger(Account.class);

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
  private AccountType type;

  @Column(nullable = false)
  private BigDecimal balance = BigDecimal.ZERO;

  @Column(nullable = false)
  private boolean active = true;

  // Parent-child hierarchy for consolidated reports
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "parent_id")
  private Account parent;

  @Column(name = "hierarchy_level")
  private Integer hierarchyLevel = 1; // 1=Category, 2=Subcategory, 3=Detail, etc.

  @PrePersist
  public void prePersist() {
    if (publicId == null) {
      publicId = UUID.randomUUID();
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

  public AccountType getType() {
    return type;
  }

  public void setType(AccountType type) {
    this.type = type;
  }

  public boolean isActive() {
    return active;
  }

  public void setActive(boolean active) {
    this.active = active;
  }

  public BigDecimal getBalance() {
    return balance;
  }

  public void setBalance(BigDecimal balance) {
    validateBalanceUpdate(balance);
    this.balance = balance;
  }

  public Account getParent() {
    return parent;
  }

  public void setParent(Account parent) {
    this.parent = parent;
    this.hierarchyLevel = parent != null ? parent.getHierarchyLevel() + 1 : 1;
  }

  public Integer getHierarchyLevel() {
    return hierarchyLevel != null ? hierarchyLevel : 1;
  }

  public void setHierarchyLevel(Integer hierarchyLevel) {
    this.hierarchyLevel = hierarchyLevel;
  }

  public boolean isLeafAccount() {
    return hierarchyLevel == null || hierarchyLevel >= 3;
  }

  /**
   * Guard against invalid balances by account type. Assets/expenses/COGS must not go negative.
   */
  public void validateBalanceUpdate(BigDecimal newBalance) {
    if (newBalance == null) {
      throw new IllegalArgumentException("Account balance cannot be null");
    }
    AccountType safeType = type;
    if (safeType == null) {
      return;
    }
    // Soft guards: warn on unusual signs but do not block (advances, prepayments can flip signs
    // legitimately)
    if ((safeType == AccountType.ASSET
            || safeType == AccountType.EXPENSE
            || safeType == AccountType.COGS)
        && newBalance.compareTo(BigDecimal.ZERO) < 0) {
      log.warn("Unusual negative balance {} for {} account {}", newBalance, safeType, code);
    }
    if ((safeType == AccountType.LIABILITY
            || safeType == AccountType.REVENUE
            || safeType == AccountType.EQUITY)
        && newBalance.compareTo(BigDecimal.ZERO) > 0) {
      log.warn("Unusual debit balance {} for {} account {}", newBalance, safeType, code);
    }
  }
}
