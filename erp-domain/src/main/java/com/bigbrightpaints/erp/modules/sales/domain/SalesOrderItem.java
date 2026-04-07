package com.bigbrightpaints.erp.modules.sales.domain;

import java.math.BigDecimal;
import java.time.Instant;

import com.bigbrightpaints.erp.core.domain.VersionedEntity;
import com.bigbrightpaints.erp.core.util.CompanyTime;
import com.bigbrightpaints.erp.modules.company.domain.Company;

import jakarta.persistence.*;

@Entity
@Table(name = "sales_order_items")
public class SalesOrderItem extends VersionedEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(optional = false, fetch = FetchType.LAZY)
  @JoinColumn(name = "sales_order_id")
  private SalesOrder salesOrder;

  @Column(name = "product_code", nullable = false)
  private String productCode;

  @Column(name = "finished_good_id")
  private Long finishedGoodId;

  private String description;

  @Column(nullable = false)
  private BigDecimal quantity;

  @Column(name = "unit_price", nullable = false)
  private BigDecimal unitPrice;

  @Column(name = "line_subtotal", nullable = false)
  private BigDecimal lineSubtotal = BigDecimal.ZERO;

  @Column(name = "line_total", nullable = false)
  private BigDecimal lineTotal = BigDecimal.ZERO;

  @Column(name = "gst_rate", nullable = false)
  private BigDecimal gstRate = BigDecimal.ZERO;

  @Column(name = "gst_amount", nullable = false)
  private BigDecimal gstAmount = BigDecimal.ZERO;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @PrePersist
  public void prePersist() {
    if (createdAt == null) {
      Company company = salesOrder != null ? salesOrder.getCompany() : null;
      createdAt = CompanyTime.now(company);
    }
  }

  public Long getId() {
    return id;
  }

  public SalesOrder getSalesOrder() {
    return salesOrder;
  }

  public void setSalesOrder(SalesOrder salesOrder) {
    this.salesOrder = salesOrder;
  }

  public String getProductCode() {
    return productCode;
  }

  public void setProductCode(String productCode) {
    this.productCode = productCode;
  }

  public Long getFinishedGoodId() {
    return finishedGoodId;
  }

  public void setFinishedGoodId(Long finishedGoodId) {
    this.finishedGoodId = finishedGoodId;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public BigDecimal getQuantity() {
    return quantity;
  }

  public void setQuantity(BigDecimal quantity) {
    this.quantity = quantity;
  }

  public BigDecimal getUnitPrice() {
    return unitPrice;
  }

  public void setUnitPrice(BigDecimal unitPrice) {
    this.unitPrice = unitPrice;
  }

  public BigDecimal getLineSubtotal() {
    return lineSubtotal;
  }

  public void setLineSubtotal(BigDecimal lineSubtotal) {
    this.lineSubtotal = lineSubtotal;
  }

  public BigDecimal getLineTotal() {
    return lineTotal;
  }

  public void setLineTotal(BigDecimal lineTotal) {
    this.lineTotal = lineTotal;
  }

  public BigDecimal getGstRate() {
    return gstRate;
  }

  public void setGstRate(BigDecimal gstRate) {
    this.gstRate = gstRate;
  }

  public BigDecimal getGstAmount() {
    return gstAmount;
  }

  public void setGstAmount(BigDecimal gstAmount) {
    this.gstAmount = gstAmount;
  }
}
