package com.bigbrightpaints.erp.modules.accounting.domain;

/**
 * Standard account types supported by the system.
 * Keeps downstream logic from relying on free-form strings.
 */
public enum AccountType {
    ASSET,
    LIABILITY,
    EQUITY,
    REVENUE,
    EXPENSE,
    COGS;

    public boolean isDebitNormalBalance() {
        return switch (this) {
            case ASSET, EXPENSE, COGS -> true;
            default -> false;
        };
    }

    public boolean isCreditNormalBalance() {
        return !isDebitNormalBalance();
    }
}
