package com.bigbrightpaints.erp.modules.company.domain;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TenantSupportWarningRepository extends JpaRepository<TenantSupportWarning, Long> {
  List<TenantSupportWarning> findByCompany_IdOrderByIssuedAtDesc(Long companyId);
}
