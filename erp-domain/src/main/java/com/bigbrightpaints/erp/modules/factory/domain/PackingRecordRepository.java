package com.bigbrightpaints.erp.modules.factory.domain;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PackingRecordRepository extends JpaRepository<PackingRecord, Long> {
    List<PackingRecord> findByCompanyAndProductionLogOrderByPackedDateAscIdAsc(Company company, ProductionLog productionLog);
    List<PackingRecord> findByCompanyAndPackedDateBetween(Company company,
                                                          java.time.LocalDate start,
                                                          java.time.LocalDate end);
}
