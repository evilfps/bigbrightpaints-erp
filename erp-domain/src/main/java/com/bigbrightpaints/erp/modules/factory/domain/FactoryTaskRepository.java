package com.bigbrightpaints.erp.modules.factory.domain;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FactoryTaskRepository extends JpaRepository<FactoryTask, Long> {
    List<FactoryTask> findByCompanyOrderByCreatedAtDesc(Company company);
    Optional<FactoryTask> findByCompanyAndId(Company company, Long id);
}
