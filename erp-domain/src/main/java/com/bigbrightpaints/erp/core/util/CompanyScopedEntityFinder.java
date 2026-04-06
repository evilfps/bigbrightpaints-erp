package com.bigbrightpaints.erp.core.util;

import java.util.Optional;

import com.bigbrightpaints.erp.modules.company.domain.Company;

@FunctionalInterface
public interface CompanyScopedEntityFinder<T> {

  Optional<T> find(Company company, Long id);
}
