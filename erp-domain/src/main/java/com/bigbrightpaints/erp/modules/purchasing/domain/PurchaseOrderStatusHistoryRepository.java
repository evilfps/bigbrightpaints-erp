package com.bigbrightpaints.erp.modules.purchasing.domain;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PurchaseOrderStatusHistoryRepository extends JpaRepository<PurchaseOrderStatusHistory, Long> {

    @Query("""
            select history
            from PurchaseOrderStatusHistory history
            where history.company = :company
              and history.purchaseOrder = :purchaseOrder
            order by history.changedAt asc, history.id asc
            """)
    List<PurchaseOrderStatusHistory> findTimeline(@Param("company") Company company,
                                                  @Param("purchaseOrder") PurchaseOrder purchaseOrder);
}
