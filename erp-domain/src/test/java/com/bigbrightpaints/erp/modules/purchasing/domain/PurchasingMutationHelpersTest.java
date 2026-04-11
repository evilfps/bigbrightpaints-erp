package com.bigbrightpaints.erp.modules.purchasing.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class PurchasingMutationHelpersTest {

  @Test
  void goodsReceipt_setLinesAddLineAndStatusMapping_coverHelperBranches() {
    GoodsReceipt receipt = new GoodsReceipt();
    GoodsReceiptLine line = new GoodsReceiptLine();

    receipt.setLines(null);
    receipt.addLine(null);
    assertThat(receipt.getLines()).isEmpty();

    List<GoodsReceiptLine> source = new ArrayList<>(List.of(line));
    receipt.setLines(source);
    source.clear();
    receipt.addLine(line);
    receipt.setStatus((String) null);
    assertThat(receipt.getStatusEnum()).isEqualTo(GoodsReceiptStatus.RECEIVED);
    receipt.setStatus("partially_received");
    assertThat(receipt.getStatusEnum()).isEqualTo(GoodsReceiptStatus.PARTIAL);
    receipt.setStatus("received");
    assertThat(receipt.getStatusEnum()).isEqualTo(GoodsReceiptStatus.RECEIVED);
    assertThat(receipt.getLines()).hasSize(2);
  }

  @Test
  void purchaseOrder_setLinesAddLineAndStatusMapping_coverHelperBranches() {
    PurchaseOrder order = new PurchaseOrder();
    PurchaseOrderLine line = new PurchaseOrderLine();

    order.setLines(null);
    order.addLine(null);
    assertThat(order.getLines()).isEmpty();

    List<PurchaseOrderLine> source = new ArrayList<>(List.of(line));
    order.setLines(source);
    source.clear();
    order.addLine(line);
    order.setStatus((String) null);
    assertThat(order.getStatusEnum()).isEqualTo(PurchaseOrderStatus.DRAFT);
    order.setStatus("open");
    assertThat(order.getStatusEnum()).isEqualTo(PurchaseOrderStatus.APPROVED);
    order.setStatus("partial");
    assertThat(order.getStatusEnum()).isEqualTo(PurchaseOrderStatus.PARTIALLY_RECEIVED);
    order.setStatus("cancelled");
    assertThat(order.getStatusEnum()).isEqualTo(PurchaseOrderStatus.VOID);
    assertThat(order.getLines()).hasSize(2);
  }

  @Test
  void rawMaterialPurchase_setLinesAndAddLine_handleNullAndCopyInputs() {
    RawMaterialPurchase purchase = new RawMaterialPurchase();
    RawMaterialPurchaseLine line = new RawMaterialPurchaseLine();

    purchase.setLines(null);
    purchase.addLine(null);
    assertThat(purchase.getLines()).isEmpty();

    List<RawMaterialPurchaseLine> source = new ArrayList<>(List.of(line));
    purchase.setLines(source);
    source.clear();
    purchase.addLine(line);

    assertThat(purchase.getLines()).hasSize(2);
  }
}
