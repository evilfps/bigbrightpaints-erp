package com.bigbrightpaints.erp.truthsuite.suites;

/**
 * Authoritative suite entry marker for gate-reconciliation.
 * Selection is enforced by Maven profile includes and reconciliation tag filters.
 */
public final class TS_GateReconciliationSuite {

    public static final String GATE = "gate-reconciliation";

    private TS_GateReconciliationSuite() {
    }
}
