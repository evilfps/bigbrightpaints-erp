package com.bigbrightpaints.erp.modules.inventory.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Domain event for inventory movements that require GL posting.
 * Published when inventory physically moves (not just valuation changes).
 */
public record InventoryMovementEvent(
        Long companyId,
        MovementType movementType,
        InventoryValuationChangedEvent.InventoryType inventoryType,
        Long itemId,
        String itemCode,
        String itemName,
        BigDecimal quantity,
        BigDecimal unitCost,
        BigDecimal totalCost,
        Long sourceAccountId,      // Account to credit (e.g., Inventory)
        Long destinationAccountId, // Account to debit (e.g., COGS, WIP)
        Long movementId,           // Stable inventory movement id for idempotency
        String referenceNumber,
        LocalDate movementDate,
        String memo,
        Long relatedEntityId,      // SalesOrder, ProductionLog, Purchase, etc.
        String relatedEntityType,
        Instant timestamp
) {
    public enum MovementType {
        RECEIPT,           // Goods received (purchase, production output)
        ISSUE,             // Goods issued (sales, production input)
        TRANSFER,          // Between locations/warehouses
        ADJUSTMENT_IN,     // Inventory count increase
        ADJUSTMENT_OUT,    // Inventory count decrease
        SCRAP,             // Written off as waste
        RETURN_TO_VENDOR,  // Returned to supplier
        RETURN_FROM_CUSTOMER // Customer return
    }
    
    public static Builder builder() {
        return new Builder();
    }
    
    public static class Builder {
        private Long companyId;
        private MovementType movementType;
        private InventoryValuationChangedEvent.InventoryType inventoryType;
        private Long itemId;
        private String itemCode;
        private String itemName;
        private BigDecimal quantity = BigDecimal.ZERO;
        private BigDecimal unitCost = BigDecimal.ZERO;
        private BigDecimal totalCost = BigDecimal.ZERO;
        private Long sourceAccountId;
        private Long destinationAccountId;
        private Long movementId;
        private String referenceNumber;
        private LocalDate movementDate;
        private String memo;
        private Long relatedEntityId;
        private String relatedEntityType;
        
        public Builder companyId(Long id) { this.companyId = id; return this; }
        public Builder movementType(MovementType type) { this.movementType = type; return this; }
        public Builder inventoryType(InventoryValuationChangedEvent.InventoryType type) { this.inventoryType = type; return this; }
        public Builder itemId(Long id) { this.itemId = id; return this; }
        public Builder itemCode(String code) { this.itemCode = code; return this; }
        public Builder itemName(String name) { this.itemName = name; return this; }
        public Builder quantity(BigDecimal qty) { this.quantity = qty; return this; }
        public Builder unitCost(BigDecimal cost) { this.unitCost = cost; return this; }
        public Builder totalCost(BigDecimal cost) { this.totalCost = cost; return this; }
        public Builder sourceAccountId(Long id) { this.sourceAccountId = id; return this; }
        public Builder destinationAccountId(Long id) { this.destinationAccountId = id; return this; }
        public Builder movementId(Long id) { this.movementId = id; return this; }
        public Builder referenceNumber(String ref) { this.referenceNumber = ref; return this; }
        public Builder movementDate(LocalDate date) { this.movementDate = date; return this; }
        public Builder memo(String memo) { this.memo = memo; return this; }
        public Builder relatedEntityId(Long id) { this.relatedEntityId = id; return this; }
        public Builder relatedEntityType(String type) { this.relatedEntityType = type; return this; }
        
        public InventoryMovementEvent build() {
            if (totalCost == null || totalCost.compareTo(BigDecimal.ZERO) == 0) {
                totalCost = quantity.multiply(unitCost);
            }
            return new InventoryMovementEvent(
                    companyId, movementType, inventoryType, itemId, itemCode, itemName,
                    quantity, unitCost, totalCost, sourceAccountId, destinationAccountId, movementId,
                    referenceNumber, movementDate != null ? movementDate : LocalDate.now(),
                    memo, relatedEntityId, relatedEntityType, Instant.now()
            );
        }
    }
}
