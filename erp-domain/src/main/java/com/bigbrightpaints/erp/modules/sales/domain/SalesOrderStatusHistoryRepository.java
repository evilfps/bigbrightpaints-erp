package com.bigbrightpaints.erp.modules.sales.domain;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bigbrightpaints.erp.modules.company.domain.Company;

public interface SalesOrderStatusHistoryRepository
    extends JpaRepository<SalesOrderStatusHistory, Long> {

  @Query(
      """
      select history
      from SalesOrderStatusHistory history
      where history.company = :company
        and history.salesOrder = :salesOrder
      order by history.changedAt asc, history.id asc
      """)
  List<SalesOrderStatusHistory> findTimeline(
      @Param("company") Company company, @Param("salesOrder") SalesOrder salesOrder);

  boolean existsByCompanyAndSalesOrderAndReasonCodeIn(
      Company company, SalesOrder salesOrder, Collection<String> reasonCodes);
}
