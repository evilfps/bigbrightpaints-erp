package com.bigbrightpaints.erp.modules.factory.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.bigbrightpaints.erp.core.domain.VersionedEntity;
import com.bigbrightpaints.erp.core.util.CompanyTime;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrand;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProduct;

import jakarta.persistence.*;

@Entity
@Table(
    name = "production_logs",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uq_production_log_code",
            columnNames = {"company_id", "production_code"}))
public class ProductionLog extends VersionedEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "public_id", nullable = false)
  private UUID publicId;

  @ManyToOne(optional = false, fetch = FetchType.LAZY)
  @JoinColumn(name = "company_id")
  private Company company;

  @ManyToOne(optional = false, fetch = FetchType.LAZY)
  @JoinColumn(name = "brand_id")
  private ProductionBrand brand;

  @ManyToOne(optional = false, fetch = FetchType.LAZY)
  @JoinColumn(name = "product_id")
  private ProductionProduct product;

  @Column(name = "production_code", nullable = false)
  private String productionCode;

  @Column(name = "batch_colour")
  private String batchColour;

  @Column(name = "batch_size", nullable = false)
  private BigDecimal batchSize;

  @Column(name = "unit_of_measure", nullable = false)
  private String unitOfMeasure;

  @Column(name = "mixed_quantity", nullable = false)
  private BigDecimal mixedQuantity;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false)
  private ProductionLogStatus status = ProductionLogStatus.MIXED;

  @Column(name = "total_packed_quantity", nullable = false)
  private BigDecimal totalPackedQuantity = BigDecimal.ZERO;

  @Column(name = "wastage_quantity", nullable = false)
  private BigDecimal wastageQuantity = BigDecimal.ZERO;

  @Column(name = "wastage_reason_code", nullable = false)
  private String wastageReasonCode = "PROCESS_LOSS";

  @Column(name = "material_cost_total", nullable = false)
  private BigDecimal materialCostTotal = BigDecimal.ZERO;

  @Column(name = "labor_cost_total", nullable = false)
  private BigDecimal laborCostTotal = BigDecimal.ZERO;

  @Column(name = "overhead_cost_total", nullable = false)
  private BigDecimal overheadCostTotal = BigDecimal.ZERO;

  @Column(name = "unit_cost", nullable = false)
  private BigDecimal unitCost = BigDecimal.ZERO;

  @Column(name = "produced_at", nullable = false)
  private Instant producedAt;

  @Column private String notes;

  @Column(name = "created_by")
  private String createdBy;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "sales_order_id")
  private Long salesOrderId;

  @Column(name = "sales_order_number")
  private String salesOrderNumber;

  @OneToMany(mappedBy = "log", cascade = CascadeType.ALL, orphanRemoval = true)
  private List<ProductionLogMaterial> materials = new ArrayList<>();

  @OneToMany(mappedBy = "productionLog", cascade = CascadeType.ALL, orphanRemoval = true)
  private List<PackingRecord> packingRecords = new ArrayList<>();

  @PrePersist
  public void prePersist() {
    if (publicId == null) {
      publicId = UUID.randomUUID();
    }
    Instant now = CompanyTime.now(company);
    if (createdAt == null) {
      createdAt = now;
    }
    if (producedAt == null) {
      producedAt = now;
    }
    if (wastageReasonCode == null || wastageReasonCode.isBlank()) {
      wastageReasonCode = "PROCESS_LOSS";
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

  public ProductionBrand getBrand() {
    return brand;
  }

  public void setBrand(ProductionBrand brand) {
    this.brand = brand;
  }

  public ProductionProduct getProduct() {
    return product;
  }

  public void setProduct(ProductionProduct product) {
    this.product = product;
  }

  public String getProductionCode() {
    return productionCode;
  }

  public void setProductionCode(String productionCode) {
    this.productionCode = productionCode;
  }

  public String getBatchColour() {
    return batchColour;
  }

  public void setBatchColour(String batchColour) {
    this.batchColour = batchColour;
  }

  public BigDecimal getBatchSize() {
    return batchSize;
  }

  public void setBatchSize(BigDecimal batchSize) {
    this.batchSize = batchSize;
  }

  public String getUnitOfMeasure() {
    return unitOfMeasure;
  }

  public void setUnitOfMeasure(String unitOfMeasure) {
    this.unitOfMeasure = unitOfMeasure;
  }

  public BigDecimal getMixedQuantity() {
    return mixedQuantity;
  }

  public void setMixedQuantity(BigDecimal mixedQuantity) {
    this.mixedQuantity = mixedQuantity;
  }

  public Instant getProducedAt() {
    return producedAt;
  }

  public void setProducedAt(Instant producedAt) {
    this.producedAt = producedAt;
  }

  public String getNotes() {
    return notes;
  }

  public void setNotes(String notes) {
    this.notes = notes;
  }

  public String getCreatedBy() {
    return createdBy;
  }

  public void setCreatedBy(String createdBy) {
    this.createdBy = createdBy;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  @SuppressWarnings("java/internal-representation-exposure")
  public List<ProductionLogMaterial> getMaterials() {
    // lgtm [java/internal-representation-exposure]
    return materials;
  }

  public BigDecimal getMaterialCostTotal() {
    return materialCostTotal;
  }

  public void setMaterialCostTotal(BigDecimal materialCostTotal) {
    this.materialCostTotal = materialCostTotal;
  }

  public BigDecimal getLaborCostTotal() {
    return laborCostTotal;
  }

  public void setLaborCostTotal(BigDecimal laborCostTotal) {
    this.laborCostTotal = laborCostTotal;
  }

  public BigDecimal getOverheadCostTotal() {
    return overheadCostTotal;
  }

  public void setOverheadCostTotal(BigDecimal overheadCostTotal) {
    this.overheadCostTotal = overheadCostTotal;
  }

  public BigDecimal getUnitCost() {
    return unitCost;
  }

  public void setUnitCost(BigDecimal unitCost) {
    this.unitCost = unitCost;
  }

  public ProductionLogStatus getStatus() {
    return status;
  }

  public void setStatus(ProductionLogStatus status) {
    this.status = status;
  }

  public BigDecimal getTotalPackedQuantity() {
    return totalPackedQuantity;
  }

  public void setTotalPackedQuantity(BigDecimal totalPackedQuantity) {
    this.totalPackedQuantity = totalPackedQuantity;
  }

  public BigDecimal getWastageQuantity() {
    return wastageQuantity;
  }

  public void setWastageQuantity(BigDecimal wastageQuantity) {
    this.wastageQuantity = wastageQuantity;
  }

  public String getWastageReasonCode() {
    return wastageReasonCode;
  }

  public void setWastageReasonCode(String wastageReasonCode) {
    this.wastageReasonCode = wastageReasonCode;
  }

  @SuppressWarnings("java/internal-representation-exposure")
  public List<PackingRecord> getPackingRecords() {
    // lgtm [java/internal-representation-exposure]
    return packingRecords;
  }

  public void addMaterials(List<ProductionLogMaterial> materials) {
    if (materials != null && !materials.isEmpty()) {
      this.materials.addAll(materials);
    }
  }

  public void addPackingRecord(PackingRecord packingRecord) {
    if (packingRecord != null) {
      this.packingRecords.add(packingRecord);
    }
  }

  public Long getSalesOrderId() {
    return salesOrderId;
  }

  public void setSalesOrderId(Long salesOrderId) {
    this.salesOrderId = salesOrderId;
  }

  public String getSalesOrderNumber() {
    return salesOrderNumber;
  }

  public void setSalesOrderNumber(String salesOrderNumber) {
    this.salesOrderNumber = salesOrderNumber;
  }
}
