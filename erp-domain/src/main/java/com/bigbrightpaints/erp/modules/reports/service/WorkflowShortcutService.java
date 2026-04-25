package com.bigbrightpaints.erp.modules.reports.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bigbrightpaints.erp.modules.reports.dto.WorkflowDraftCapabilityDto;
import com.bigbrightpaints.erp.modules.reports.dto.WorkflowShortcutCatalogDto;
import com.bigbrightpaints.erp.modules.reports.dto.WorkflowShortcutFlowDto;
import com.bigbrightpaints.erp.modules.reports.dto.WorkflowShortcutStepDto;

@Service
public class WorkflowShortcutService {

  private static final String CONNECTED_MODEL_GUIDANCE =
      "Follow connected business flows instead of isolated accounting steps: commercial and"
          + " fulfillment events trigger accounting outcomes, and period controls close only after"
          + " reconciliation evidence is complete.";

  @Transactional(readOnly = true)
  public WorkflowShortcutCatalogDto workflowShortcuts() {
    return new WorkflowShortcutCatalogDto(
        CONNECTED_MODEL_GUIDANCE,
        List.of(orderToInvoiceFlow(), procureToPayFlow(), periodCloseReconciliationFlow()));
  }

  private WorkflowShortcutFlowDto orderToInvoiceFlow() {
    return new WorkflowShortcutFlowDto(
        "ORDER_TO_INVOICE",
        "Order to Invoice",
        "Drive O2C from order capture through dispatch so invoice and accounting markers stay"
            + " linked to the same business order.",
        List.of(
            new WorkflowShortcutStepDto(
                1,
                "Capture order",
                "POST",
                "/api/v1/sales/orders",
                "Creates a tenant-scoped sales order identity."),
            new WorkflowShortcutStepDto(
                2,
                "Confirm order",
                "POST",
                "/api/v1/sales/orders/{id}/confirm",
                "Transitions the order into executable fulfillment state."),
            new WorkflowShortcutStepDto(
                3,
                "Dispatch and accounting trigger",
                "POST",
                "/api/v1/dispatch/confirm",
                "Persists dispatch outcomes and accounting linkage markers."),
            new WorkflowShortcutStepDto(
                4,
                "Read generated invoice",
                "GET",
                "/api/v1/invoices?orderId={salesOrderId}",
                "Returns invoice rows linked to the dispatched order.")),
        false,
        List.of());
  }

  private WorkflowShortcutFlowDto procureToPayFlow() {
    return new WorkflowShortcutFlowDto(
        "PROCURE_TO_PAY",
        "Procure to Pay",
        "Run P2P as one linked supplier flow so purchasing events and AP settlement outcomes stay"
            + " reconciled.",
        List.of(
            new WorkflowShortcutStepDto(
                1,
                "Create supplier",
                "POST",
                "/api/v1/suppliers",
                "Creates the supplier master used by PO, receipt, and AP settlement."),
            new WorkflowShortcutStepDto(
                2,
                "Create purchase order",
                "POST",
                "/api/v1/purchasing/purchase-orders",
                "Captures supplier demand and expected quantities."),
            new WorkflowShortcutStepDto(
                3,
                "Approve purchase order",
                "POST",
                "/api/v1/purchasing/purchase-orders/{id}/approve",
                "Promotes PO to an executable procurement document."),
            new WorkflowShortcutStepDto(
                4,
                "Record goods receipt",
                "POST",
                "/api/v1/purchasing/goods-receipts",
                "Records received stock against the approved PO."),
            new WorkflowShortcutStepDto(
                5,
                "Post supplier invoice",
                "POST",
                "/api/v1/purchasing/raw-material-purchases",
                "Creates payable-side purchase accounting truth."),
            new WorkflowShortcutStepDto(
                6,
                "Settle supplier payable",
                "POST",
                "/api/v1/accounting/suppliers/{supplierId}/auto-settle",
                "Allocates payment against open purchases in canonical AP order.")),
        false,
        List.of());
  }

  private WorkflowShortcutFlowDto periodCloseReconciliationFlow() {
    return new WorkflowShortcutFlowDto(
        "PERIOD_CLOSE_RECONCILIATION",
        "Period Close and Reconciliation",
        "Keep close readiness connected: save reconciliation work as draft, then promote,"
            + " reconcile control balances, and finish maker-checker close approvals.",
        List.of(
            new WorkflowShortcutStepDto(
                1,
                "Save bank reconciliation draft",
                "POST",
                "/api/v1/accounting/reconciliation/bank/sessions",
                "Creates an IN_PROGRESS reconciliation draft artifact."),
            new WorkflowShortcutStepDto(
                2,
                "Resume reconciliation draft",
                "GET",
                "/api/v1/accounting/reconciliation/bank/sessions/{sessionId}",
                "Reads the same pending draft payload and match state."),
            new WorkflowShortcutStepDto(
                3,
                "Promote reconciliation draft",
                "POST",
                "/api/v1/accounting/reconciliation/bank/sessions/{sessionId}/complete",
                "Completes the draft and confirms period bank reconciliation."),
            new WorkflowShortcutStepDto(
                4,
                "Run subledger reconciliation",
                "GET",
                "/api/v1/accounting/reconciliation/subledger",
                "Checks AR/AP control balances against partner ledgers."),
            new WorkflowShortcutStepDto(
                5,
                "Resolve remaining discrepancies",
                "POST",
                "/api/v1/accounting/reconciliation/discrepancies/{discrepancyId}/resolve",
                "Records explicit discrepancy resolution actions."),
            new WorkflowShortcutStepDto(
                6,
                "Request period close",
                "POST",
                "/api/v1/accounting/periods/{periodId}/request-close",
                "Creates maker-side close request with checklist evidence."),
            new WorkflowShortcutStepDto(
                7,
                "Approve period close",
                "POST",
                "/api/v1/accounting/periods/{periodId}/approve-close",
                "Checker approval closes the period on canonical lifecycle owner.")),
        true,
        List.of(
            new WorkflowDraftCapabilityDto(
                "BANK_RECONCILIATION_SESSION",
                "PERIOD_CLOSE_RECONCILIATION",
                "POST",
                "/api/v1/accounting/reconciliation/bank/sessions",
                "GET",
                "/api/v1/accounting/reconciliation/bank/sessions/{sessionId}",
                "POST",
                "/api/v1/accounting/reconciliation/bank/sessions/{sessionId}/complete",
                "IN_PROGRESS",
                "COMPLETED",
                "Draft save and resume remain side-effect free for journals, settlements, and"
                    + " period close transitions until explicit complete/promotion.")));
  }
}
