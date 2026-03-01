package com.bigbrightpaints.erp.modules.purchasing.domain;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, Long> {

    @EntityGraph(attributePaths = {"supplier", "lines", "lines.rawMaterial"})
    @Query("select po from PurchaseOrder po where po.company = :company order by po.orderDate desc")
    List<PurchaseOrder> findByCompanyWithLinesOrderByOrderDateDesc(@Param("company") Company company);

    @EntityGraph(attributePaths = {"supplier", "lines", "lines.rawMaterial"})
    @Query("select po from PurchaseOrder po where po.company = :company and po.supplier = :supplier order by po.orderDate desc")
    List<PurchaseOrder> findByCompanyAndSupplierWithLinesOrderByOrderDateDesc(@Param("company") Company company,
                                                                              @Param("supplier") Supplier supplier);

    Optional<PurchaseOrder> findByCompanyAndId(Company company, Long id);
    Optional<PurchaseOrder> findByCompanyAndOrderNumberIgnoreCase(Company company, String orderNumber);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select distinct po
            from PurchaseOrder po
            left join fetch po.lines lines
            left join fetch lines.rawMaterial
            where po.company = :company
              and po.id = :id
            """)
    Optional<PurchaseOrder> lockByCompanyAndId(@Param("company") Company company, @Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select po from PurchaseOrder po where po.company = :company and lower(po.orderNumber) = lower(:orderNumber)")
    Optional<PurchaseOrder> lockByCompanyAndOrderNumberIgnoreCase(@Param("company") Company company,
                                                                  @Param("orderNumber") String orderNumber);
}
