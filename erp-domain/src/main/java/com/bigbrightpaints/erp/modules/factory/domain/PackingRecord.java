package com.bigbrightpaints.erp.modules.factory.domain;

import com.bigbrightpaints.erp.core.util.CompanyTime;
import com.bigbrightpaints.erp.core.domain.VersionedEntity;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatch;
import jakarta.persistence.*;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "packing_records")
public class PackingRecord extends VersionedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false)
    private UUID publicId;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id")
    private Company company;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "production_log_id")
    private ProductionLog productionLog;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "finished_good_id")
    private FinishedGood finishedGood;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "finished_good_batch_id")
    private FinishedGoodBatch finishedGoodBatch;

    @Column(name = "packaging_size", nullable = false)
    private String packagingSize;

    @Column(name = "quantity_packed", nullable = false)
    private BigDecimal quantityPacked = BigDecimal.ZERO;

    @Column(name = "pieces_count")
    private Integer piecesCount;

    @Column(name = "boxes_count")
    private Integer boxesCount;

    @Column(name = "pieces_per_box")
    private Integer piecesPerBox;

    @Column(name = "packed_date", nullable = false)
    private LocalDate packedDate;

    @Column(name = "packed_by")
    private String packedBy;

    @Column(name = "packaging_cost")
    private BigDecimal packagingCost = BigDecimal.ZERO;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "packaging_material_id")
    private com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial packagingMaterial;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "size_variant_id")
    private SizeVariant sizeVariant;

    @Column(name = "packaging_quantity")
    private BigDecimal packagingQuantity;

    @Column(name = "child_batch_count")
    private Integer childBatchCount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

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
        if (packedDate == null) {
            packedDate = CompanyTime.today(company);
        }
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

    public ProductionLog getProductionLog() {
        return productionLog;
    }

    public void setProductionLog(ProductionLog productionLog) {
        this.productionLog = productionLog;
    }

    public FinishedGood getFinishedGood() {
        return finishedGood;
    }

    public void setFinishedGood(FinishedGood finishedGood) {
        this.finishedGood = finishedGood;
    }

    public FinishedGoodBatch getFinishedGoodBatch() {
        return finishedGoodBatch;
    }

    public void setFinishedGoodBatch(FinishedGoodBatch finishedGoodBatch) {
        this.finishedGoodBatch = finishedGoodBatch;
    }

    public String getPackagingSize() {
        return packagingSize;
    }

    public void setPackagingSize(String packagingSize) {
        this.packagingSize = StringUtils.hasText(packagingSize) ? packagingSize.trim() : null;
    }

    public BigDecimal getQuantityPacked() {
        return quantityPacked;
    }

    public void setQuantityPacked(BigDecimal quantityPacked) {
        this.quantityPacked = quantityPacked;
    }

    public Integer getPiecesCount() {
        return piecesCount;
    }

    public void setPiecesCount(Integer piecesCount) {
        this.piecesCount = piecesCount;
    }

    public Integer getBoxesCount() {
        return boxesCount;
    }

    public void setBoxesCount(Integer boxesCount) {
        this.boxesCount = boxesCount;
    }

    public Integer getPiecesPerBox() {
        return piecesPerBox;
    }

    public void setPiecesPerBox(Integer piecesPerBox) {
        this.piecesPerBox = piecesPerBox;
    }

    public LocalDate getPackedDate() {
        return packedDate;
    }

    public void setPackedDate(LocalDate packedDate) {
        this.packedDate = packedDate;
    }

    public String getPackedBy() {
        return packedBy;
    }

    public void setPackedBy(String packedBy) {
        this.packedBy = packedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public BigDecimal getPackagingCost() {
        return packagingCost;
    }

    public void setPackagingCost(BigDecimal packagingCost) {
        this.packagingCost = packagingCost;
    }

    public com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial getPackagingMaterial() {
        return packagingMaterial;
    }

    public void setPackagingMaterial(com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial packagingMaterial) {
        this.packagingMaterial = packagingMaterial;
    }

    public SizeVariant getSizeVariant() {
        return sizeVariant;
    }

    public void setSizeVariant(SizeVariant sizeVariant) {
        this.sizeVariant = sizeVariant;
    }

    public BigDecimal getPackagingQuantity() {
        return packagingQuantity;
    }

    public void setPackagingQuantity(BigDecimal packagingQuantity) {
        this.packagingQuantity = packagingQuantity;
    }

    public Integer getChildBatchCount() {
        return childBatchCount;
    }

    public void setChildBatchCount(Integer childBatchCount) {
        this.childBatchCount = childBatchCount;
    }
}
