package com.bigbrightpaints.erp.modules.sales.domain;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import jakarta.persistence.LockModeType;
import java.util.Optional;

public interface OrderSequenceRepository extends JpaRepository<OrderSequence, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<OrderSequence> findByCompanyAndFiscalYear(Company company, Integer fiscalYear);
}
