package com.bigbrightpaints.erp.modules.auth.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class AuthSecretStorageBackfillRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AuthSecretStorageBackfillRunner.class);

    public AuthSecretStorageBackfillRunner() {
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("Auth secret storage backfill is retired; digest-only token storage is now mandatory");
    }
}
