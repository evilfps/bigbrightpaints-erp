package com.bigbrightpaints.erp.modules.accounting.service;

import com.bigbrightpaints.erp.modules.accounting.dto.AuditDigestResponse;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

@Service
public class AccountingAuditService {

    private final AccountingCoreService accountingCoreService;

    public AccountingAuditService(AccountingCoreService accountingCoreService) {
        this.accountingCoreService = accountingCoreService;
    }

    public AuditDigestResponse auditDigest(LocalDate from, LocalDate to) {
        return accountingCoreService.auditDigest(from, to);
    }

    public String auditDigestCsv(LocalDate from, LocalDate to) {
        return accountingCoreService.auditDigestCsv(from, to);
    }
}
