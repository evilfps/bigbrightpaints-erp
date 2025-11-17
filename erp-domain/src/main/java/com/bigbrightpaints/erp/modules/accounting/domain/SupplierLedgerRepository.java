package com.bigbrightpaints.erp.modules.accounting.domain;

import com.bigbrightpaints.erp.modules.accounting.dto.SupplierBalanceView;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.purchasing.domain.Supplier;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SupplierLedgerRepository extends JpaRepository<SupplierLedgerEntry, Long> {

    List<SupplierLedgerEntry> findByCompanyAndSupplierOrderByEntryDateAsc(Company company, Supplier supplier);

    @Query("select new com.bigbrightpaints.erp.modules.accounting.dto.SupplierBalanceView(e.supplier.id, coalesce(sum(e.credit - e.debit), 0)) " +
            "from SupplierLedgerEntry e where e.company = :company and e.supplier.id in :supplierIds group by e.supplier.id")
    List<SupplierBalanceView> aggregateBalances(@Param("company") Company company,
                                                @Param("supplierIds") Collection<Long> supplierIds);

    @Query("select new com.bigbrightpaints.erp.modules.accounting.dto.SupplierBalanceView(e.supplier.id, coalesce(sum(e.credit - e.debit), 0)) " +
            "from SupplierLedgerEntry e where e.company = :company and e.supplier = :supplier group by e.supplier.id")
    Optional<SupplierBalanceView> aggregateBalance(@Param("company") Company company,
                                                   @Param("supplier") Supplier supplier);
}
