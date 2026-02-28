package com.bigbrightpaints.erp.modules.inventory.domain;

import com.bigbrightpaints.erp.core.util.CompanyTime;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import com.bigbrightpaints.erp.core.domain.VersionedEntity;

@Entity
@Table(name = "finished_goods", uniqueConstraints = @UniqueConstraint(columnNames = {"company_id", "product_code"}))
public class FinishedGood extends VersionedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false)
    private UUID publicId;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id")
    private Company company;

    @Column(name = "product_code", nullable = false)
    private String productCode;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String unit = "UNIT";

    @Column(name = "current_stock", nullable = false)
    private BigDecimal currentStock = BigDecimal.ZERO;

    @Column(name = "reserved_stock", nullable = false)
    private BigDecimal reservedStock = BigDecimal.ZERO;

    @Column(name = "costing_method", nullable = false)
    private String costingMethod = "FIFO";

    @Column(name = "valuation_account_id")
    private Long valuationAccountId;

    @Column(name = "cogs_account_id")
    private Long cogsAccountId;

    @Column(name = "revenue_account_id")
    private Long revenueAccountId;

    @Column(name = "discount_account_id")
    private Long discountAccountId;

    @Column(name = "tax_account_id")
    private Long taxAccountId;

    @Column(name = "low_stock_threshold", nullable = false)
    private BigDecimal lowStockThreshold = new BigDecimal("100");

    @Enumerated(EnumType.STRING)
    @Column(name = "inventory_type", nullable = false)
    private InventoryType inventoryType = InventoryType.STANDARD;

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
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = CompanyTime.now(company);
    }

    public Long getId() { return id; }
    public UUID getPublicId() { return publicId; }
    public Company getCompany() { return company; }
    public void setCompany(Company company) { this.company = company; }
    public String getProductCode() { return productCode; }
    public void setProductCode(String productCode) { this.productCode = productCode; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getUnit() { return unit; }
    public void setUnit(String unit) { this.unit = unit; }
    public BigDecimal getCurrentStock() { return currentStock; }
    public void setCurrentStock(BigDecimal currentStock) {
        if (currentStock == null) {
            this.currentStock = BigDecimal.ZERO;
        } else if (currentStock.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Stock cannot be negative for product " + productCode);
        } else {
            this.currentStock = currentStock;
        }
    }
    public BigDecimal getReservedStock() { return reservedStock; }
    public void setReservedStock(BigDecimal reservedStock) {
        if (reservedStock == null) {
            this.reservedStock = BigDecimal.ZERO;
        } else if (reservedStock.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Reserved stock cannot be negative for product " + productCode);
        } else {
            this.reservedStock = reservedStock;
        }
    }
    public String getCostingMethod() { return costingMethod; }
    public void setCostingMethod(String costingMethod) { this.costingMethod = costingMethod; }
    public Long getValuationAccountId() { return valuationAccountId; }
    public void setValuationAccountId(Long valuationAccountId) { this.valuationAccountId = valuationAccountId; }
    public Long getCogsAccountId() { return cogsAccountId; }
    public void setCogsAccountId(Long cogsAccountId) { this.cogsAccountId = cogsAccountId; }
    public Long getRevenueAccountId() { return revenueAccountId; }
    public void setRevenueAccountId(Long revenueAccountId) { this.revenueAccountId = revenueAccountId; }
    public Long getDiscountAccountId() { return discountAccountId; }
    public void setDiscountAccountId(Long discountAccountId) { this.discountAccountId = discountAccountId; }
    public Long getTaxAccountId() { return taxAccountId; }
    public void setTaxAccountId(Long taxAccountId) { this.taxAccountId = taxAccountId; }
    public BigDecimal getLowStockThreshold() { return lowStockThreshold; }
    public void setLowStockThreshold(BigDecimal lowStockThreshold) {
        if (lowStockThreshold == null) {
            this.lowStockThreshold = new BigDecimal("100");
        } else if (lowStockThreshold.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Low stock threshold cannot be negative for product " + productCode);
        } else {
            this.lowStockThreshold = lowStockThreshold;
        }
    }
    public InventoryType getInventoryType() { return inventoryType; }
    public void setInventoryType(InventoryType inventoryType) { this.inventoryType = inventoryType; }

    @Transient
    public void adjustStock(BigDecimal quantityDelta, String operation) {
        BigDecimal newStock = (currentStock == null ? BigDecimal.ZERO : currentStock).add(quantityDelta);
        if (newStock.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalStateException(String.format(
                    "Insufficient stock for %s. Available: %s, Delta: %s, Operation: %s",
                    productCode, currentStock, quantityDelta, operation));
        }
        setCurrentStock(newStock);
    }
}
