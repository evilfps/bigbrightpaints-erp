package com.bigbrightpaints.erp.orchestrator.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component
public class DispatchMappingHealthIndicator implements HealthIndicator {

    private final Long debitAccountId;
    private final Long creditAccountId;

    public DispatchMappingHealthIndicator(
            @Value("${erp.dispatch.debit-account-id:0}") Long debitAccountId,
            @Value("${erp.dispatch.credit-account-id:0}") Long creditAccountId) {
        this.debitAccountId = debitAccountId;
        this.creditAccountId = creditAccountId;
    }

    @Override
    public Health health() {
        boolean missingDebit = debitAccountId == null || debitAccountId <= 0;
        boolean missingCredit = creditAccountId == null || creditAccountId <= 0;
        if (missingDebit || missingCredit) {
            return Health.status("WARN")
                    .withDetail("dispatchDebitConfigured", !missingDebit)
                    .withDetail("dispatchCreditConfigured", !missingCredit)
                    .withDetail("message", "Dispatch COGS postings disabled because dispatch debit/credit accounts are not configured")
                    .build();
        }
        return Health.up()
                .withDetail("dispatchDebitAccountId", debitAccountId)
                .withDetail("dispatchCreditAccountId", creditAccountId)
                .build();
    }
}
