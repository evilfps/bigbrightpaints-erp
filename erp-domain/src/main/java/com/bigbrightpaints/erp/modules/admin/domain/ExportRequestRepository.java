package com.bigbrightpaints.erp.modules.admin.domain;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.bigbrightpaints.erp.modules.admin.dto.ExportApprovalStatus;
import com.bigbrightpaints.erp.modules.company.domain.Company;

public interface ExportRequestRepository extends JpaRepository<ExportRequest, Long> {

  Optional<ExportRequest> findByCompanyAndId(Company company, Long id);

  List<ExportRequest> findByCompanyAndStatusOrderByCreatedAtAsc(
      Company company, ExportApprovalStatus status);

  long countByCompanyAndStatus(Company company, ExportApprovalStatus status);
}
