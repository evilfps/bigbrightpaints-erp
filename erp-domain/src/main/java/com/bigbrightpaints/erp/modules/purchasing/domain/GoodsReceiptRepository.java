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

public interface GoodsReceiptRepository extends JpaRepository<GoodsReceipt, Long> {

    @EntityGraph(attributePaths = {"supplier", "purchaseOrder", "lines", "lines.rawMaterial"})
    @Query("select gr from GoodsReceipt gr where gr.company = :company order by gr.receiptDate desc")
    List<GoodsReceipt> findByCompanyWithLinesOrderByReceiptDateDesc(@Param("company") Company company);

    @EntityGraph(attributePaths = {"supplier", "purchaseOrder", "lines", "lines.rawMaterial"})
    @Query("select gr from GoodsReceipt gr where gr.company = :company and gr.supplier = :supplier order by gr.receiptDate desc")
    List<GoodsReceipt> findByCompanyAndSupplierWithLinesOrderByReceiptDateDesc(@Param("company") Company company,
                                                                               @Param("supplier") Supplier supplier);

    Optional<GoodsReceipt> findByCompanyAndId(Company company, Long id);
    Optional<GoodsReceipt> findByCompanyAndReceiptNumberIgnoreCase(Company company, String receiptNumber);

    boolean existsByPurchaseOrder(PurchaseOrder purchaseOrder);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select gr from GoodsReceipt gr where gr.company = :company and gr.id = :id")
    Optional<GoodsReceipt> lockByCompanyAndId(@Param("company") Company company, @Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select gr from GoodsReceipt gr where gr.company = :company and lower(gr.receiptNumber) = lower(:receiptNumber)")
    Optional<GoodsReceipt> lockByCompanyAndReceiptNumberIgnoreCase(@Param("company") Company company,
                                                                   @Param("receiptNumber") String receiptNumber);
}
