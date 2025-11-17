package com.bigbrightpaints.erp.modules.inventory.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import com.bigbrightpaints.erp.core.domain.VersionedEntity;

@Entity
@Table(name = "packaging_slip_lines")
public class PackagingSlipLine extends VersionedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "packaging_slip_id")
    private PackagingSlip packagingSlip;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "finished_good_batch_id")
    private FinishedGoodBatch finishedGoodBatch;

    @Column(nullable = false)
    private BigDecimal quantity;

    @Column(name = "unit_cost", nullable = false)
    private BigDecimal unitCost = BigDecimal.ZERO;

    public Long getId() { return id; }
    public PackagingSlip getPackagingSlip() { return packagingSlip; }
    public void setPackagingSlip(PackagingSlip packagingSlip) { this.packagingSlip = packagingSlip; }
    public FinishedGoodBatch getFinishedGoodBatch() { return finishedGoodBatch; }
    public void setFinishedGoodBatch(FinishedGoodBatch finishedGoodBatch) { this.finishedGoodBatch = finishedGoodBatch; }
    public BigDecimal getQuantity() { return quantity; }
    public void setQuantity(BigDecimal quantity) { this.quantity = quantity; }
    public BigDecimal getUnitCost() { return unitCost; }
    public void setUnitCost(BigDecimal unitCost) { this.unitCost = unitCost; }
}
