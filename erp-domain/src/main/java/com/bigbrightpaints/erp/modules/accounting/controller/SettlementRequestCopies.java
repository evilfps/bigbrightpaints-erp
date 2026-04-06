package com.bigbrightpaints.erp.modules.accounting.controller;

import com.bigbrightpaints.erp.modules.accounting.dto.AutoSettlementRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.DealerReceiptRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.DealerReceiptSplitRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.PartnerSettlementRequest;

final class SettlementRequestCopies {

  private SettlementRequestCopies() {}

  static DealerReceiptRequest dealerReceipt(DealerReceiptRequest request, String idempotencyKey) {
    return new DealerReceiptRequest(
        request.dealerId(),
        request.cashAccountId(),
        request.amount(),
        request.referenceNumber(),
        request.memo(),
        idempotencyKey,
        request.allocations());
  }

  static DealerReceiptSplitRequest dealerReceiptSplit(
      DealerReceiptSplitRequest request, String idempotencyKey) {
    return new DealerReceiptSplitRequest(
        request.dealerId(),
        request.incomingLines(),
        request.referenceNumber(),
        request.memo(),
        idempotencyKey);
  }

  static PartnerSettlementRequest partnerSettlement(
      PartnerSettlementRequest request, String idempotencyKey) {
    return new PartnerSettlementRequest(
        request.partnerType(),
        request.partnerId(),
        request.cashAccountId(),
        request.discountAccountId(),
        request.writeOffAccountId(),
        request.fxGainAccountId(),
        request.fxLossAccountId(),
        request.amount(),
        request.unappliedAmountApplication(),
        request.settlementDate(),
        request.referenceNumber(),
        request.memo(),
        idempotencyKey,
        request.adminOverride(),
        request.allocations(),
        request.payments());
  }

  static AutoSettlementRequest autoSettlement(
      AutoSettlementRequest request, String idempotencyKey) {
    return new AutoSettlementRequest(
        request.cashAccountId(),
        request.amount(),
        request.referenceNumber(),
        request.memo(),
        idempotencyKey);
  }
}
