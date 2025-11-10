package com.bigbrightpaints.erp.modules.invoice.domain;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface InvoiceRepository extends JpaRepository<Invoice, Long> {

    @EntityGraph(attributePaths = "lines")
    List<Invoice> findByCompanyOrderByIssueDateDesc(Company company);

    @EntityGraph(attributePaths = "lines")
    List<Invoice> findByCompanyAndDealerOrderByIssueDateDesc(Company company, Dealer dealer);

    Optional<Invoice> findBySalesOrderId(Long salesOrderId);

    @EntityGraph(attributePaths = "lines")
    Optional<Invoice> findByCompanyAndId(Company company, Long id);
}
