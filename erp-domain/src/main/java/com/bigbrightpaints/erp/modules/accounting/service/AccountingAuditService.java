package com.bigbrightpaints.erp.modules.accounting.service;

import org.springframework.stereotype.Service;

@Service
public class AccountingAuditService {

  private final AccountingCoreSupport accountingCoreSupport;

  public AccountingAuditService(AccountingCoreSupport accountingCoreSupport) {
    this.accountingCoreSupport = accountingCoreSupport;
  }

  AccountingCoreSupport coreSupport() {
    return accountingCoreSupport;
  }
}
