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
            "from SupplierLedgerEntry e where e.company = :company and e.supplier.id in :supplierIds " +
            "and e.entryDate between :start and :end group by e.supplier.id")
    List<SupplierBalanceView> aggregateBalancesBetween(@Param("company") Company company,
                                                       @Param("supplierIds") Collection<Long> supplierIds,
                                                       @Param("start") java.time.LocalDate start,
                                                       @Param("end") java.time.LocalDate end);

    @Query("select new com.bigbrightpaints.erp.modules.accounting.dto.SupplierBalanceView(e.supplier.id, coalesce(sum(e.credit - e.debit), 0)) " +
            "from SupplierLedgerEntry e where e.company = :company and e.supplier.id in :supplierIds " +
            "and e.entryDate <= :end group by e.supplier.id")
    List<SupplierBalanceView> aggregateBalancesUpTo(@Param("company") Company company,
                                                    @Param("supplierIds") Collection<Long> supplierIds,
                                                    @Param("end") java.time.LocalDate end);

    @Query("select new com.bigbrightpaints.erp.modules.accounting.dto.SupplierBalanceView(e.supplier.id, coalesce(sum(e.credit - e.debit), 0)) " +
            "from SupplierLedgerEntry e where e.company = :company and e.supplier = :supplier group by e.supplier.id")
    Optional<SupplierBalanceView> aggregateBalance(@Param("company") Company company,
                                                   @Param("supplier") Supplier supplier);

    List<SupplierLedgerEntry> findByCompanyAndSupplierAndEntryDateBetweenOrderByEntryDateAsc(Company company,
                                                                                            Supplier supplier,
                                                                                            java.time.LocalDate start,
                                                                                            java.time.LocalDate end);

    List<SupplierLedgerEntry> findByCompanyAndSupplierAndEntryDateBeforeOrderByEntryDateAsc(Company company,
                                                                                           Supplier supplier,
                                                                                           java.time.LocalDate before);
}
