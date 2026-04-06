package com.bigbrightpaints.erp.core.util;

import org.springframework.stereotype.Service;

import com.bigbrightpaints.erp.modules.company.domain.Company;

@Service
public class CompanyScopedLookupService {

  public <T> T require(
      Company company, Long id, CompanyScopedEntityFinder<T> finder, String entityLabel) {
    return finder
        .find(company, id)
        .orElseThrow(() -> new IllegalArgumentException(entityLabel + " not found: id=" + id));
  }
}
