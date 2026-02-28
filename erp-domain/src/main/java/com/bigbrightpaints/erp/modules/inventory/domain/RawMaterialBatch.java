package com.bigbrightpaints.erp.modules.inventory.domain;

import com.bigbrightpaints.erp.core.util.CompanyTime;
import com.bigbrightpaints.erp.modules.purchasing.domain.Supplier;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import com.bigbrightpaints.erp.core.domain.VersionedEntity;

@Entity
@Table(name = "raw_material_batches")
public class RawMaterialBatch extends VersionedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false)
    private UUID publicId;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "raw_material_id")
    private RawMaterial rawMaterial;

    @Column(name = "batch_code", nullable = false)
    private String batchCode;

    @Column(nullable = false)
    private BigDecimal quantity;

    @Column(nullable = false)
    private String unit;

    @Column(name = "cost_per_unit", nullable = false)
    private BigDecimal costPerUnit;

    @Column(name = "supplier")
    private String supplierName;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier_id")
    private Supplier supplier;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "manufactured_at", nullable = false)
    private Instant manufacturedAt;

    @Column(name = "expiry_date")
    private LocalDate expiryDate;

    private String notes;

    @Enumerated(EnumType.STRING)
    @Column(name = "inventory_type", nullable = false)
    private InventoryType inventoryType = InventoryType.STANDARD;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false)
    private InventoryBatchSource source = InventoryBatchSource.PURCHASE;

    @PrePersist
    public void prePersist() {
        if (publicId == null) {
            publicId = UUID.randomUUID();
        }
        if (receivedAt == null) {
            receivedAt = CompanyTime.now(rawMaterial != null ? rawMaterial.getCompany() : null);
        }
        if (manufacturedAt == null) {
            manufacturedAt = receivedAt;
        }
    }

    public Long getId() {
        return id;
    }

    public UUID getPublicId() {
        return publicId;
    }

    public RawMaterial getRawMaterial() {
        return rawMaterial;
    }

    public void setRawMaterial(RawMaterial rawMaterial) {
        this.rawMaterial = rawMaterial;
    }

    public String getBatchCode() {
        return batchCode;
    }

    public void setBatchCode(String batchCode) {
        this.batchCode = batchCode;
    }

    public BigDecimal getQuantity() { return quantity; }

    public void setQuantity(BigDecimal quantity) {
        if (quantity == null) {
            this.quantity = BigDecimal.ZERO;
        } else if (quantity.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Batch " + batchCode + " quantity cannot be negative");
        } else {
            this.quantity = quantity;
        }
    }

    public String getUnit() {
        return unit;
    }

    public void setUnit(String unit) {
        this.unit = unit;
    }

    public BigDecimal getCostPerUnit() {
        return costPerUnit;
    }

    public void setCostPerUnit(BigDecimal costPerUnit) {
        this.costPerUnit = costPerUnit;
    }

    public String getSupplierName() {
        return supplierName;
    }

    public void setSupplierName(String supplierName) {
        this.supplierName = supplierName;
    }

    public Supplier getSupplier() { return supplier; }
    public void setSupplier(Supplier supplier) { this.supplier = supplier; }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public void setReceivedAt(Instant receivedAt) {
        this.receivedAt = receivedAt;
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

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
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
        this.source = source == null ? InventoryBatchSource.PURCHASE : source;
    }
}
