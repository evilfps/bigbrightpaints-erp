package com.bigbrightpaints.erp.modules.accounting.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

import com.bigbrightpaints.erp.modules.accounting.dto.GstReconciliationDto;
import com.bigbrightpaints.erp.modules.reports.dto.ReconciliationSummaryDto;
import com.bigbrightpaints.erp.modules.reports.dto.TrialBalanceDto;

record AccountingPeriodChecklistDiagnostics(
    ReconciliationSummaryDto inventory,
    ReconciliationService.PeriodReconciliationResult periodReconciliation,
    GstReconciliationDto gstReconciliation,
    long openReconciliationDiscrepancies,
    TrialBalanceDto trialBalance,
    long unpostedDocuments,
    long unlinkedDocuments,
    long unbalancedJournals,
    long uninvoicedReceipts) {

  private static final BigDecimal RECONCILIATION_TOLERANCE = new BigDecimal("0.01");
  private static final List<String> RECONCILIATION_CONTROL_ORDER =
      List.of(
          "inventoryReconciled",
          "arReconciled",
          "apReconciled",
          "gstReconciled",
          "reconciliationDiscrepanciesResolved");

  List<String> unresolvedControlsInPolicyOrder() {
    List<String> unresolved = new ArrayList<>(RECONCILIATION_CONTROL_ORDER.size());
    if (!inventoryControlResolved()) {
      unresolved.add(RECONCILIATION_CONTROL_ORDER.get(0));
    }
    if (!arControlResolved()) {
      unresolved.add(RECONCILIATION_CONTROL_ORDER.get(1));
    }
    if (!apControlResolved()) {
      unresolved.add(RECONCILIATION_CONTROL_ORDER.get(2));
    }
    if (!gstControlResolved()) {
      unresolved.add(RECONCILIATION_CONTROL_ORDER.get(3));
    }
    if (!reconciliationDiscrepanciesResolved()) {
      unresolved.add(RECONCILIATION_CONTROL_ORDER.get(4));
    }
    return List.copyOf(unresolved);
  }

  boolean inventoryControlResolved() {
    return inventory != null && inventory.variance() != null;
  }

  boolean inventoryReconciled() {
    return inventoryControlResolved() && varianceWithinTolerance(inventoryVariance());
  }

  BigDecimal inventoryVariance() {
    return inventory != null ? inventory.variance() : BigDecimal.ZERO;
  }

  boolean arControlResolved() {
    return periodReconciliation != null && periodReconciliation.arVariance() != null;
  }

  boolean arReconciled() {
    return arControlResolved() && periodReconciliation.arReconciled();
  }

  BigDecimal arVariance() {
    return periodReconciliation != null ? periodReconciliation.arVariance() : BigDecimal.ZERO;
  }

  boolean apControlResolved() {
    return periodReconciliation != null && periodReconciliation.apVariance() != null;
  }

  boolean apReconciled() {
    return apControlResolved() && periodReconciliation.apReconciled();
  }

  BigDecimal apVariance() {
    return periodReconciliation != null ? periodReconciliation.apVariance() : BigDecimal.ZERO;
  }

  boolean gstControlResolved() {
    return gstReconciliation != null
        && gstReconciliation.getNetLiability() != null
        && gstReconciliation.getNetLiability().getTotal() != null;
  }

  boolean gstReconciled() {
    return gstControlResolved() && varianceWithinTolerance(gstVariance());
  }

  BigDecimal gstVariance() {
    if (!gstControlResolved()) {
      return BigDecimal.ZERO;
    }
    return gstReconciliation.getNetLiability().getTotal();
  }

  boolean reconciliationDiscrepanciesResolved() {
    return openReconciliationDiscrepancies <= 0;
  }

  boolean trialBalanceBalanced() {
    return trialBalance != null && trialBalance.balanced();
  }

  BigDecimal trialBalanceTotalDebit() {
    return trialBalance == null ? BigDecimal.ZERO : safe(trialBalance.totalDebit());
  }

  BigDecimal trialBalanceTotalCredit() {
    return trialBalance == null ? BigDecimal.ZERO : safe(trialBalance.totalCredit());
  }

  BigDecimal trialBalanceDifference() {
    return trialBalanceTotalDebit().subtract(trialBalanceTotalCredit());
  }

  String formatVariance(BigDecimal variance) {
    return safe(variance).setScale(2, RoundingMode.HALF_UP).toPlainString();
  }

  private boolean varianceWithinTolerance(BigDecimal variance) {
    return variance != null && variance.abs().compareTo(RECONCILIATION_TOLERANCE) <= 0;
  }

  private BigDecimal safe(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }
}
