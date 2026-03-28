package com.bigbrightpaints.erp.modules.inventory.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.bigbrightpaints.erp.core.domain.VersionedEntity;
import com.bigbrightpaints.erp.core.util.CompanyTime;
import com.bigbrightpaints.erp.modules.company.domain.Company;

import jakarta.persistence.*;

@Entity
@Table(
    name = "finished_good_batches",
    uniqueConstraints = @UniqueConstraint(columnNames = {"finished_good_id", "batch_code"}))
public class FinishedGoodBatch extends VersionedEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "public_id", nullable = false)
  private UUID publicId;

  @ManyToOne(optional = false, fetch = FetchType.LAZY)
  @JoinColumn(name = "finished_good_id")
  private FinishedGood finishedGood;

  @Column(name = "batch_code", nullable = false)
  private String batchCode;

  @Column(name = "quantity_total", nullable = false)
  private BigDecimal quantityTotal;

  @Column(name = "quantity_available", nullable = false)
  private BigDecimal quantityAvailable;

  @Column(name = "unit_cost", nullable = false)
  private BigDecimal unitCost = BigDecimal.ZERO;

  @Column(name = "manufactured_at", nullable = false)
  private Instant manufacturedAt;

  @Column(name = "expiry_date")
  private LocalDate expiryDate;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Enumerated(EnumType.STRING)
  @Column(name = "inventory_type", nullable = false)
  private InventoryType inventoryType = InventoryType.STANDARD;

  @Enumerated(EnumType.STRING)
  @Column(name = "source", nullable = false)
  private InventoryBatchSource source = InventoryBatchSource.PRODUCTION;

  @Column(name = "size_label")
  private String sizeLabel; // e.g., "1L", "4L", "20L"

  @PrePersist
  public void prePersist() {
    if (publicId == null) {
      publicId = UUID.randomUUID();
    }
    Company company = finishedGood != null ? finishedGood.getCompany() : null;
    if (manufacturedAt == null) {
      manufacturedAt = CompanyTime.now(company);
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

  public FinishedGood getFinishedGood() {
    return finishedGood;
  }

  public void setFinishedGood(FinishedGood finishedGood) {
    this.finishedGood = finishedGood;
  }

  public String getBatchCode() {
    return batchCode;
  }

  public void setBatchCode(String batchCode) {
    this.batchCode = batchCode;
  }

  public BigDecimal getQuantityTotal() {
    return quantityTotal;
  }

  public void setQuantityTotal(BigDecimal quantityTotal) {
    if (quantityTotal == null) {
      this.quantityTotal = BigDecimal.ZERO;
    } else if (quantityTotal.compareTo(BigDecimal.ZERO) < 0) {
      throw new IllegalArgumentException(
          "Batch " + batchCode + " total quantity cannot be negative");
    } else {
      this.quantityTotal = quantityTotal;
    }
  }

  public BigDecimal getQuantityAvailable() {
    return quantityAvailable;
  }

  public void setQuantityAvailable(BigDecimal quantityAvailable) {
    if (quantityAvailable == null) {
      this.quantityAvailable = BigDecimal.ZERO;
    } else if (quantityAvailable.compareTo(BigDecimal.ZERO) < 0) {
      throw new IllegalArgumentException(
          "Batch " + batchCode + " available quantity cannot be negative");
    } else {
      this.quantityAvailable = quantityAvailable;
    }
  }

  public BigDecimal getUnitCost() {
    return unitCost;
  }

  public void setUnitCost(BigDecimal unitCost) {
    this.unitCost = unitCost;
  }

  public Instant getManufacturedAt() {
    return manufacturedAt;
  }

  public void setManufacturedAt(Instant manufacturedAt) {
    this.manufacturedAt = manufacturedAt;
  }

  public LocalDate getExpiryDate() {
    return expiryDate;
  }

  public void setExpiryDate(LocalDate expiryDate) {
    this.expiryDate = expiryDate;
  }

  public InventoryType getInventoryType() {
    return inventoryType;
  }

  public void setInventoryType(InventoryType inventoryType) {
    this.inventoryType = inventoryType;
  }

  public InventoryBatchSource getSource() {
    return source;
  }

  public void setSource(InventoryBatchSource source) {
    this.source = source == null ? InventoryBatchSource.PRODUCTION : source;
  }

  public String getSizeLabel() {
    return sizeLabel;
  }

  public void setSizeLabel(String sizeLabel) {
    this.sizeLabel = sizeLabel;
  }

  /**
   * Allocate quantity from this batch in a single step, preventing negatives.
   */
  @Transient
  public BigDecimal allocate(BigDecimal requested) {
    BigDecimal safeAvailable = quantityAvailable == null ? BigDecimal.ZERO : quantityAvailable;
    BigDecimal toAllocate = requested == null ? BigDecimal.ZERO : requested.min(safeAvailable);
    setQuantityAvailable(safeAvailable.subtract(toAllocate));
    return toAllocate;
  }
}
