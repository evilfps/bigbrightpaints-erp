package com.bigbrightpaints.erp.modules.inventory.domain;

import com.bigbrightpaints.erp.core.domain.VersionedEntity;
import jakarta.persistence.*;

import java.math.BigDecimal;

@Entity
@Table(name = "inventory_adjustment_lines")
public class InventoryAdjustmentLine extends VersionedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "adjustment_id")
    private InventoryAdjustment adjustment;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "finished_good_id")
    private FinishedGood finishedGood;

    @Column(nullable = false)
    private BigDecimal quantity = BigDecimal.ZERO;

    @Column(name = "unit_cost", nullable = false)
    private BigDecimal unitCost = BigDecimal.ZERO;

    @Column(nullable = false)
    private BigDecimal amount = BigDecimal.ZERO;

    private String note;

    public Long getId() {
        return id;
    }

    public InventoryAdjustment getAdjustment() {
        return adjustment;
    }

    public void setAdjustment(InventoryAdjustment adjustment) {
        this.adjustment = adjustment;
    }

    public FinishedGood getFinishedGood() {
        return finishedGood;
    }

    public void setFinishedGood(FinishedGood finishedGood) {
        this.finishedGood = finishedGood;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public void setQuantity(BigDecimal quantity) {
        this.quantity = quantity;
    }

    public BigDecimal getUnitCost() {
        return unitCost;
    }

    public void setUnitCost(BigDecimal unitCost) {
        this.unitCost = unitCost;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }
}
