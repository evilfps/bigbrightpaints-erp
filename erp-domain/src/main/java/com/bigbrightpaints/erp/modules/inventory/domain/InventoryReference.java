package com.bigbrightpaints.erp.modules.inventory.domain;

/**
 * Canonical reference metadata for inventory movements and reservations.
 * <p>
 * Each reference type pins {@code referenceId} to a domain identifier so downstream
 * analytics can hop across inventory, production, sales, and accounting:
 * <ul>
 *     <li>{@link #PRODUCTION_LOG}: {@code referenceId} is the production log code.</li>
 *     <li>{@link #RAW_MATERIAL_PURCHASE}: {@code referenceId} is the purchase receipt reference
 *     (invoice line reference or batch code, depending on the intake flow).</li>
 *     <li>{@link #OPENING_STOCK}: {@code referenceId} is the opening stock batch code.</li>
 *     <li>{@link #SALES_ORDER}: {@code referenceId} is the sales order database identifier.</li>
 *     <li>{@link #MANUFACTURING_ORDER}: {@code referenceId} is the finished good batch public id
 *     for manual manufacturing receipts.</li>
 *     <li>{@link #GOODS_RECEIPT}: {@code referenceId} is the goods receipt number.</li>
 * </ul>
 */
public final class InventoryReference {

    public static final String PRODUCTION_LOG = "PRODUCTION_LOG";
    public static final String RAW_MATERIAL_PURCHASE = "RAW_MATERIAL_PURCHASE";
    public static final String OPENING_STOCK = "OPENING_STOCK";
    public static final String SALES_ORDER = "SALES_ORDER";
    public static final String MANUFACTURING_ORDER = "MANUFACTURING_ORDER";
    public static final String PURCHASE_RETURN = "PURCHASE_RETURN";
    public static final String PACKING_RECORD = "PACKING_RECORD";
    public static final String GOODS_RECEIPT = "GOODS_RECEIPT";

    private InventoryReference() {
    }
}
