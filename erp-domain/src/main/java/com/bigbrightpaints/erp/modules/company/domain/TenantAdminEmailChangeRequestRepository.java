package com.bigbrightpaints.erp.modules.company.domain;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TenantAdminEmailChangeRequestRepository
    extends JpaRepository<TenantAdminEmailChangeRequest, Long> {
  Optional<TenantAdminEmailChangeRequest>
      findFirstByCompanyIdAndAdminUserIdAndConsumedFalseOrderByIdDesc(
          Long companyId, Long adminUserId);
}
