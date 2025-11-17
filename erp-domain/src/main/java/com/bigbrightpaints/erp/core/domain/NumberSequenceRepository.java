package com.bigbrightpaints.erp.core.domain;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface NumberSequenceRepository extends JpaRepository<NumberSequence, Long> {
    Optional<NumberSequence> findByCompanyAndSequenceKey(Company company, String sequenceKey);
}
