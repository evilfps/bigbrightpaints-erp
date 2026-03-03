package com.bigbrightpaints.erp.modules.admin.domain;

import com.bigbrightpaints.erp.modules.admin.dto.ExportApprovalStatus;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExportRequestRepository extends JpaRepository<ExportRequest, Long> {

    Optional<ExportRequest> findByCompanyAndId(Company company, Long id);

    List<ExportRequest> findByCompanyAndStatusOrderByCreatedAtAsc(Company company,
                                                                  ExportApprovalStatus status);
}
