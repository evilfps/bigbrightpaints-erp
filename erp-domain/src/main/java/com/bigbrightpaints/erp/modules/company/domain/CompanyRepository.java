package com.bigbrightpaints.erp.modules.company.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CompanyRepository extends JpaRepository<Company, Long> {
    Optional<Company> findByCodeIgnoreCase(String code);
}
