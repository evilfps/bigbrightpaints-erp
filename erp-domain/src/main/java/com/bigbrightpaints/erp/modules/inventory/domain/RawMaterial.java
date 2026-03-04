package com.bigbrightpaints.erp.modules.inventory.domain;

import com.bigbrightpaints.erp.core.domain.VersionedEntity;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.CompanyTime;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "raw_materials",
        uniqueConstraints = @UniqueConstraint(columnNames = {"company_id", "sku"}))
public class RawMaterial extends VersionedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false)
    private UUID publicId;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id")
    private Company company;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String sku;

    @Column(name = "unit_type", nullable = false)
    private String unitType;

    @Column(name = "reorder_level", nullable = false)
    private BigDecimal reorderLevel = BigDecimal.ZERO;

    @Column(name = "current_stock", nullable = false)
    private BigDecimal currentStock = BigDecimal.ZERO;

    @Column(name = "min_stock", nullable = false)
    private BigDecimal minStock = BigDecimal.ZERO;

    @Column(name = "max_stock", nullable = false)
    private BigDecimal maxStock = BigDecimal.ZERO;

    @Column(name = "inventory_account_id")
    private Long inventoryAccountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "inventory_type", nullable = false)
    private InventoryType inventoryType = InventoryType.STANDARD;

    @Enumerated(EnumType.STRING)
    @Column(name = "material_type", nullable = false)
    private MaterialType materialType = MaterialType.PRODUCTION;

    @Column(name = "costing_method", nullable = false)
    private String costingMethod = "FIFO";

    @Column(name = "gst_rate")
    private BigDecimal gstRate = BigDecimal.ZERO;

    @Column(name = "private_stock", nullable = false)
    private BigDecimal privateStock = BigDecimal.ZERO;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    public void prePersist() {
        if (publicId == null) {
            publicId = UUID.randomUUID();
        }
        if (sku == null || sku.isBlank()) {
            sku = "RM-" + System.currentTimeMillis() % 100000000;
        }
        if (createdAt == null) {
            createdAt = CompanyTime.now(company);
        }
        updatedAt = CompanyTime.now(company);
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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSku() {
        return sku;
    }

    public void setSku(String sku) {
        this.sku = sku;
    }

    public String getUnitType() {
        return unitType;
    }

    public void setUnitType(String unitType) {
        this.unitType = unitType;
    }

    public BigDecimal getReorderLevel() {
        return reorderLevel;
    }

    public void setReorderLevel(BigDecimal reorderLevel) {
        this.reorderLevel = reorderLevel;
    }

    public BigDecimal getCurrentStock() {
        return currentStock;
    }

    public void setCurrentStock(BigDecimal currentStock) {
        if (currentStock == null) {
            this.currentStock = BigDecimal.ZERO;
            return;
        }
        if (currentStock.compareTo(BigDecimal.ZERO) < 0) {
            throw new ApplicationException(
                    ErrorCode.BUSINESS_CONSTRAINT_VIOLATION,
                    "Raw material stock cannot be negative")
                    .withDetail("currentStock", currentStock)
                    .withDetail("rawMaterialSku", sku)
                    .withDetail("rawMaterialName", name);
        }
        this.currentStock = currentStock;
    }

    public BigDecimal getMinStock() {
        return minStock;
    }

    public void setMinStock(BigDecimal minStock) {
        this.minStock = minStock;
    }

    public BigDecimal getMaxStock() {
        return maxStock;
    }

    public void setMaxStock(BigDecimal maxStock) {
        this.maxStock = maxStock;
    }

    public Long getInventoryAccountId() {
        return inventoryAccountId;
    }

    public void setInventoryAccountId(Long inventoryAccountId) {
        this.inventoryAccountId = inventoryAccountId;
    }

    public InventoryType getInventoryType() {
        return inventoryType;
    }

    public void setInventoryType(InventoryType inventoryType) {
        this.inventoryType = inventoryType;
    }

    public MaterialType getMaterialType() {
        return materialType;
    }

    public void setMaterialType(MaterialType materialType) {
        this.materialType = materialType;
    }

    public String getCostingMethod() {
        return costingMethod;
    }

    public void setCostingMethod(String costingMethod) {
        this.costingMethod = costingMethod;
    }

    public BigDecimal getGstRate() {
        return gstRate;
    }

    public void setGstRate(BigDecimal gstRate) {
        this.gstRate = gstRate;
    }

    public BigDecimal getPrivateStock() {
        return privateStock;
    }

    public void setPrivateStock(BigDecimal privateStock) {
        if (privateStock == null) {
            this.privateStock = BigDecimal.ZERO;
        } else {
            this.privateStock = privateStock.max(BigDecimal.ZERO);
        }
    }

    public boolean isGstApplicable() {
        return inventoryType == InventoryType.STANDARD;
    }
}
