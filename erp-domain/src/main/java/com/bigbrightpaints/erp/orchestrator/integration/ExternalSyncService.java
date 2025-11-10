package com.bigbrightpaints.erp.orchestrator.integration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;

@Service
public class ExternalSyncService {

    private static final Logger log = LoggerFactory.getLogger(ExternalSyncService.class);

    @Retryable(maxAttempts = 3, backoff = @Backoff(delay = 1000, multiplier = 2.0))
    public void sendCostingSnapshot(String companyId) {
        log.info("Sending costing snapshot for company {}", companyId);
    }

    @Retryable(maxAttempts = 3, backoff = @Backoff(delay = 1500, multiplier = 2.0))
    public void exportAccountingData(String companyId) {
        log.info("Exporting accounting data for company {}", companyId);
    }
}
