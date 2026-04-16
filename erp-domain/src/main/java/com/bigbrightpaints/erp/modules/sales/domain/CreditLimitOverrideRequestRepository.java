package com.bigbrightpaints.erp.modules.sales.domain;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlip;

public interface CreditLimitOverrideRequestRepository
    extends JpaRepository<CreditLimitOverrideRequest, Long> {
  List<CreditLimitOverrideRequest> findByCompanyOrderByCreatedAtDesc(Company company);

  List<CreditLimitOverrideRequest> findByCompanyAndStatusOrderByCreatedAtDesc(
      Company company, String status);

  @Query(
      """
      select count(request)
      from CreditLimitOverrideRequest request
      where request.company = :company
        and upper(trim(request.status)) = 'PENDING'
      """)
  long countPendingByCompany(@Param("company") Company company);

  @Query(
      """
      select request
      from CreditLimitOverrideRequest request
      where request.company = :company
        and upper(trim(request.status)) = 'PENDING'
      order by request.createdAt desc
      """)
  List<CreditLimitOverrideRequest> findPendingByCompanyOrderByCreatedAtDesc(
      @Param("company") Company company);

  Optional<CreditLimitOverrideRequest> findByCompanyAndId(Company company, Long id);

  Optional<CreditLimitOverrideRequest> findByCompanyAndPackagingSlipAndStatus(
      Company company, PackagingSlip packagingSlip, String status);

  @Query(
      """
      select request
      from CreditLimitOverrideRequest request
      where request.company = :company
        and request.dealer = :dealer
        and upper(trim(request.status)) = 'APPROVED'
      order by request.createdAt desc
      """)
  List<CreditLimitOverrideRequest> findApprovedByCompanyAndDealerOrderByCreatedAtDesc(
      @Param("company") Company company, @Param("dealer") Dealer dealer);
}
