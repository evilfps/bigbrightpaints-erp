package com.bigbrightpaints.erp.modules.accounting.service;

import org.springframework.beans.factory.ObjectProvider;

import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLineRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.PeriodCloseRequestRepository;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountingPeriodDto;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountingPeriodReopenRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.PeriodCloseRequestActionRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.PeriodCloseRequestDto;
import com.bigbrightpaints.erp.modules.accounting.dto.PeriodStatusChangeRequest;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;

final class AccountingPeriodCloseWorkflow {

  private final AccountingPeriodCloseRequestWorkflow requestWorkflow;
  private final AccountingPeriodStatusWorkflow statusWorkflow;

  AccountingPeriodCloseWorkflow(
      AccountingPeriodRepository accountingPeriodRepository,
      CompanyContextService companyContextService,
      JournalEntryRepository journalEntryRepository,
      JournalLineRepository journalLineRepository,
      AccountRepository accountRepository,
      CompanyClock companyClock,
      PeriodCloseRequestRepository periodCloseRequestRepository,
      ObjectProvider<JournalEntryService> journalEntryServiceProvider,
      PeriodCloseHook periodCloseHook,
      AccountingPeriodSnapshotService snapshotService,
      AccountingPeriodLifecycleService lifecycleService,
      AccountingPeriodChecklistService checklistService) {
    this.statusWorkflow =
        new AccountingPeriodStatusWorkflow(
            accountingPeriodRepository,
            companyContextService,
            journalEntryRepository,
            journalLineRepository,
            accountRepository,
            companyClock,
            journalEntryServiceProvider,
            periodCloseHook,
            snapshotService,
            lifecycleService,
            checklistService);
    this.requestWorkflow =
        new AccountingPeriodCloseRequestWorkflow(
            accountingPeriodRepository,
            companyContextService,
            periodCloseRequestRepository,
            statusWorkflow);
  }

  PeriodCloseRequestDto requestPeriodClose(
      Long periodId,
      PeriodCloseRequestActionRequest request,
      AccountingComplianceAuditService accountingComplianceAuditService) {
    return requestWorkflow.requestPeriodClose(periodId, request, accountingComplianceAuditService);
  }

  AccountingPeriodDto approvePeriodClose(
      Long periodId,
      PeriodCloseRequestActionRequest request,
      AccountingComplianceAuditService accountingComplianceAuditService) {
    return requestWorkflow.approvePeriodClose(periodId, request, accountingComplianceAuditService);
  }

  PeriodCloseRequestDto rejectPeriodClose(
      Long periodId,
      PeriodCloseRequestActionRequest request,
      AccountingComplianceAuditService accountingComplianceAuditService) {
    return requestWorkflow.rejectPeriodClose(periodId, request, accountingComplianceAuditService);
  }

  AccountingPeriodDto lockPeriod(
      Long periodId,
      PeriodStatusChangeRequest request,
      AccountingComplianceAuditService accountingComplianceAuditService) {
    return statusWorkflow.lockPeriod(periodId, request, accountingComplianceAuditService);
  }

  AccountingPeriodDto reopenPeriod(
      Long periodId,
      AccountingPeriodReopenRequest request,
      AccountingComplianceAuditService accountingComplianceAuditService) {
    return statusWorkflow.reopenPeriod(periodId, request, accountingComplianceAuditService);
  }
}
