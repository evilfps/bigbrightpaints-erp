package com.bigbrightpaints.erp.modules.purchasing.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.bigbrightpaints.erp.core.domain.VersionedEntity;
import com.bigbrightpaints.erp.core.util.CompanyTime;
import com.bigbrightpaints.erp.modules.company.domain.Company;

import jakarta.persistence.*;

@Entity
@Table(
    name = "purchase_orders",
    uniqueConstraints = @UniqueConstraint(columnNames = {"company_id", "order_number"}))
public class PurchaseOrder extends VersionedEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "public_id", nullable = false)
  private UUID publicId;

  @ManyToOne(optional = false, fetch = FetchType.LAZY)
  @JoinColumn(name = "company_id")
  private Company company;

  @ManyToOne(optional = false, fetch = FetchType.LAZY)
  @JoinColumn(name = "supplier_id")
  private Supplier supplier;

  @Column(name = "order_number", nullable = false)
  private String orderNumber;

  @Column(name = "order_date", nullable = false)
  private LocalDate orderDate;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private PurchaseOrderStatus status = PurchaseOrderStatus.DRAFT;

  @Column(name = "memo")
  private String memo;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @OneToMany(mappedBy = "purchaseOrder", cascade = CascadeType.ALL, orphanRemoval = true)
  private List<PurchaseOrderLine> lines = new ArrayList<>();

  @PrePersist
  public void prePersist() {
    if (publicId == null) {
      publicId = UUID.randomUUID();
    }
    Instant now = CompanyTime.now(company);
    if (createdAt == null) {
      createdAt = now;
    }
    updatedAt = now;
  }

  @PreUpdate
  public void preUpdate() {
    updatedAt = CompanyTime.now(company);
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

  public Supplier getSupplier() {
    return supplier;
  }

  public void setSupplier(Supplier supplier) {
    this.supplier = supplier;
  }

  public String getOrderNumber() {
    return orderNumber;
  }

  public void setOrderNumber(String orderNumber) {
    this.orderNumber = orderNumber;
  }

  public LocalDate getOrderDate() {
    return orderDate;
  }

  public void setOrderDate(LocalDate orderDate) {
    this.orderDate = orderDate;
  }

  public String getStatus() {
    return status.name();
  }

  public PurchaseOrderStatus getStatusEnum() {
    return status;
  }

  public void setStatus(PurchaseOrderStatus status) {
    this.status = status == null ? PurchaseOrderStatus.DRAFT : status;
  }

  public String getMemo() {
    return memo;
  }

  public void setMemo(String memo) {
    this.memo = memo;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  @SuppressWarnings("java/internal-representation-exposure")
  public List<PurchaseOrderLine> getLines() {
    // lgtm [java/internal-representation-exposure]
    return lines;
  }

  public void setLines(List<PurchaseOrderLine> lines) {
    this.lines = lines == null ? new ArrayList<>() : new ArrayList<>(lines);
  }

  public void addLine(PurchaseOrderLine line) {
    if (line != null) {
      lines.add(line);
    }
  }

  public String getStatusValue() {
    return status.name();
  }

  public void setStatus(String status) {
    if (!org.springframework.util.StringUtils.hasText(status)) {
      this.status = PurchaseOrderStatus.DRAFT;
      return;
    }
    String normalized = status.trim().toUpperCase(java.util.Locale.ROOT);
    this.status =
        switch (normalized) {
          case "OPEN" -> PurchaseOrderStatus.APPROVED;
          case "PARTIAL" -> PurchaseOrderStatus.PARTIALLY_RECEIVED;
          case "RECEIVED" -> PurchaseOrderStatus.FULLY_RECEIVED;
          case "CANCELLED", "CANCELED" -> PurchaseOrderStatus.VOID;
          default -> PurchaseOrderStatus.valueOf(normalized);
        };
  }
}
