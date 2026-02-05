package com.bigbrightpaints.erp.modules.accounting.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AccountingPeriodTrialBalanceLineRepository extends JpaRepository<AccountingPeriodTrialBalanceLine, Long> {
    List<AccountingPeriodTrialBalanceLine> findBySnapshotOrderByAccountCodeAsc(AccountingPeriodSnapshot snapshot);
    Optional<AccountingPeriodTrialBalanceLine> findBySnapshotAndAccountId(AccountingPeriodSnapshot snapshot, Long accountId);
    void deleteBySnapshot(AccountingPeriodSnapshot snapshot);
}
