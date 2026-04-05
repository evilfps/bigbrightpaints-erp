package com.bigbrightpaints.erp.core.auditaccess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.bigbrightpaints.erp.modules.accounting.dto.AccountingTransactionAuditDetailDto;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountingTransactionAuditListItemDto;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingAuditTrailService;
import com.bigbrightpaints.erp.shared.dto.PageResponse;

@Tag("critical")
class AccountingTransactionAuditReadAdapterTest {

  @Test
  void delegatesListAndDetailQueriesToAccountingAuditTrailService() {
    AccountingAuditTrailService accountingAuditTrailService =
        mock(AccountingAuditTrailService.class);
    AccountingTransactionAuditReadAdapter adapter =
        new AccountingTransactionAuditReadAdapter(accountingAuditTrailService);
    PageResponse<AccountingTransactionAuditListItemDto> expectedPage =
        PageResponse.of(List.of(), 0, 0, 50);
    AccountingTransactionAuditDetailDto expectedDetail = null;
    when(accountingAuditTrailService.listTransactions(
            LocalDate.of(2026, 3, 1),
            LocalDate.of(2026, 3, 31),
            "ACCOUNTING",
            "POSTED",
            "JE-17",
            0,
            50))
        .thenReturn(expectedPage);
    when(accountingAuditTrailService.transactionDetail(17L)).thenReturn(expectedDetail);

    assertThat(
            adapter.listTransactions(
                LocalDate.of(2026, 3, 1),
                LocalDate.of(2026, 3, 31),
                "ACCOUNTING",
                "POSTED",
                "JE-17",
                0,
                50))
        .isEqualTo(expectedPage);
    assertThat(adapter.transactionDetail(17L)).isNull();
    verify(accountingAuditTrailService)
        .listTransactions(
            LocalDate.of(2026, 3, 1),
            LocalDate.of(2026, 3, 31),
            "ACCOUNTING",
            "POSTED",
            "JE-17",
            0,
            50);
    verify(accountingAuditTrailService).transactionDetail(17L);
  }
}
