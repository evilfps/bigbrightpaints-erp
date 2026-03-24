package com.bigbrightpaints.erp.modules.production.domain;

import com.bigbrightpaints.erp.core.util.CompanyTime;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import com.bigbrightpaints.erp.core.domain.VersionedEntity;

@Entity
@Table(name = "production_products",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_product_company_sku", columnNames = {"company_id", "sku_code"}),
                @UniqueConstraint(name = "uq_product_brand_name", columnNames = {"brand_id", "product_name"})
        })
public class ProductionProduct extends VersionedEntity {

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

    @Column(name = "product_name", nullable = false)
    private String productName;

    @Column(nullable = false)
    private String category;

    @Column(name = "default_colour")
    private String defaultColour;

    @Column(name = "size_label")
    private String sizeLabel;

    @Column(name = "unit_of_measure")
    private String unitOfMeasure;

    @Column(name = "sku_code", nullable = false)
    private String skuCode;

    @Column(name = "variant_group_id")
    private UUID variantGroupId;

    @Column(name = "product_family_name")
    private String productFamilyName;

    @Column(name = "hsn_code")
    private String hsnCode;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @ElementCollection
    @CollectionTable(name = "production_product_colors", joinColumns = @JoinColumn(name = "product_id"))
    @Column(name = "color", nullable = false)
    private Set<String> colors = new LinkedHashSet<>();

    @ElementCollection
    @CollectionTable(name = "production_product_sizes", joinColumns = @JoinColumn(name = "product_id"))
    @Column(name = "size_label", nullable = false)
    private Set<String> sizes = new LinkedHashSet<>();

    @ElementCollection
    @CollectionTable(name = "production_product_carton_sizes", joinColumns = @JoinColumn(name = "product_id"))
    @MapKeyColumn(name = "size_label")
    @Column(name = "pieces_per_carton", nullable = false)
    private Map<String, Integer> cartonSizes = new LinkedHashMap<>();

    @Column(name = "base_price", nullable = false)
    private BigDecimal basePrice = BigDecimal.ZERO;

    @Column(name = "gst_rate", nullable = false)
    private BigDecimal gstRate = BigDecimal.ZERO;

    @Column(name = "min_discount_percent", nullable = false)
    private BigDecimal minDiscountPercent = BigDecimal.ZERO;

    @Column(name = "min_selling_price", nullable = false)
    private BigDecimal minSellingPrice = BigDecimal.ZERO;

    @JdbcTypeCode(SqlTypes.JSON) // map JSONB metadata via Hibernate 6 JSON support
    @Column(name = "metadata", columnDefinition = "jsonb")
    private Map<String, Object> metadata = new HashMap<>();

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

    public String getProductName() {
        return productName;
    }

    public void setProductName(String productName) {
        this.productName = productName;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getDefaultColour() {
        return defaultColour;
    }

    public void setDefaultColour(String defaultColour) {
        this.defaultColour = defaultColour;
    }

    public String getSizeLabel() {
        return sizeLabel;
    }

    public void setSizeLabel(String sizeLabel) {
        this.sizeLabel = sizeLabel;
    }

    public String getUnitOfMeasure() {
        return unitOfMeasure;
    }

    public void setUnitOfMeasure(String unitOfMeasure) {
        this.unitOfMeasure = unitOfMeasure;
    }

    public String getSkuCode() {
        return skuCode;
    }

    public void setSkuCode(String skuCode) {
        this.skuCode = skuCode;
    }

    public UUID getVariantGroupId() {
        return variantGroupId;
    }

    public void setVariantGroupId(UUID variantGroupId) {
        this.variantGroupId = variantGroupId;
    }

    public String getProductFamilyName() {
        return productFamilyName;
    }

    public void setProductFamilyName(String productFamilyName) {
        this.productFamilyName = productFamilyName;
    }

    public String getHsnCode() {
        return hsnCode;
    }

    public void setHsnCode(String hsnCode) {
        this.hsnCode = hsnCode;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public Set<String> getColors() {
        return colors;
    }

    public void setColors(Set<String> colors) {
        this.colors = colors;
    }

    public Set<String> getSizes() {
        return sizes;
    }

    public void setSizes(Set<String> sizes) {
        this.sizes = sizes;
    }

    public Map<String, Integer> getCartonSizes() {
        return cartonSizes;
    }

    public void setCartonSizes(Map<String, Integer> cartonSizes) {
        this.cartonSizes = cartonSizes;
    }

    public BigDecimal getBasePrice() {
        return basePrice;
    }

    public void setBasePrice(BigDecimal basePrice) {
        this.basePrice = basePrice;
    }

    public BigDecimal getGstRate() {
        return gstRate;
    }

    public void setGstRate(BigDecimal gstRate) {
        this.gstRate = gstRate;
    }

    public BigDecimal getMinDiscountPercent() {
        return minDiscountPercent;
    }

    public void setMinDiscountPercent(BigDecimal minDiscountPercent) {
        this.minDiscountPercent = minDiscountPercent;
    }

    public BigDecimal getMinSellingPrice() {
        return minSellingPrice;
    }

    public void setMinSellingPrice(BigDecimal minSellingPrice) {
        this.minSellingPrice = minSellingPrice;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }
}
