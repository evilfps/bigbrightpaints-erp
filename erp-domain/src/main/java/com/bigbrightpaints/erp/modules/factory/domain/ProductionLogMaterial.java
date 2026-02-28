package com.bigbrightpaints.erp.modules.factory.domain;

import com.bigbrightpaints.erp.core.util.CompanyTime;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import jakarta.persistence.*;

import java.math.BigDecimal;
import com.bigbrightpaints.erp.core.domain.VersionedEntity;
import java.time.Instant;

@Entity
@Table(name = "production_log_materials")
public class ProductionLogMaterial extends VersionedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "log_id")
    private ProductionLog log;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "raw_material_id")
    private RawMaterial rawMaterial;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "raw_material_batch_id")
    private RawMaterialBatch rawMaterialBatch;

    @Column(name = "raw_material_movement_id")
    private Long rawMaterialMovementId;

    @Column(name = "material_name", nullable = false)
    private String materialName;

    @Column(nullable = false)
    private BigDecimal quantity;

    @Column(name = "unit_of_measure", nullable = false)
    private String unitOfMeasure;

    @Column(name = "cost_per_unit")
    private BigDecimal costPerUnit = BigDecimal.ZERO;

    @Column(name = "total_cost", nullable = false)
    private BigDecimal totalCost = BigDecimal.ZERO;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            Company company = log != null ? log.getCompany() : null;
            createdAt = CompanyTime.now(company);
        }
    }

    public Long getId() {
        return id;
    }

    public ProductionLog getLog() {
        return log;
    }

    public void setLog(ProductionLog log) {
        this.log = log;
    }

    public RawMaterial getRawMaterial() {
        return rawMaterial;
    }

    public void setRawMaterial(RawMaterial rawMaterial) {
        this.rawMaterial = rawMaterial;
    }

    public RawMaterialBatch getRawMaterialBatch() {
        return rawMaterialBatch;
    }

    public void setRawMaterialBatch(RawMaterialBatch rawMaterialBatch) {
        this.rawMaterialBatch = rawMaterialBatch;
    }

    public Long getRawMaterialMovementId() {
        return rawMaterialMovementId;
    }

    public void setRawMaterialMovementId(Long rawMaterialMovementId) {
        this.rawMaterialMovementId = rawMaterialMovementId;
    }

    public String getMaterialName() {
        return materialName;
    }

    public void setMaterialName(String materialName) {
        this.materialName = materialName;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public void setQuantity(BigDecimal quantity) {
        this.quantity = quantity;
    }

    public String getUnitOfMeasure() {
        return unitOfMeasure;
    }

    public void setUnitOfMeasure(String unitOfMeasure) {
        this.unitOfMeasure = unitOfMeasure;
    }

    public BigDecimal getCostPerUnit() {
        return costPerUnit;
    }

    public void setCostPerUnit(BigDecimal costPerUnit) {
        this.costPerUnit = costPerUnit;
    }

    public BigDecimal getTotalCost() {
        return totalCost;
    }

    public void setTotalCost(BigDecimal totalCost) {
        this.totalCost = totalCost;
    }
}
