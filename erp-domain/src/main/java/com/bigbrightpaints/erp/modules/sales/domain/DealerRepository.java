package com.bigbrightpaints.erp.modules.sales.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.company.domain.Company;

import jakarta.persistence.LockModeType;

public interface DealerRepository extends JpaRepository<Dealer, Long> {
  List<Dealer> findByCompanyOrderByNameAsc(Company company);

  List<Dealer> findByCompany(Company company, Pageable pageable);

  List<Dealer> findByCompanyAndStatusIgnoreCaseOrderByNameAsc(Company company, String status);

  List<Dealer> findByCompanyAndStatusIgnoreCase(Company company, String status, Pageable pageable);

  long countByCompanyAndStatusIgnoreCase(Company company, String status);

  Optional<Dealer> findByCompanyAndId(Company company, Long id);

  Optional<Dealer> findByCompanyAndCodeIgnoreCase(Company company, String code);

  Optional<Dealer> findByCompanyAndEmailIgnoreCase(Company company, String email);

  Optional<Dealer> findByCompanyAndReceivableAccount(Company company, Account receivableAccount);

  List<Dealer> findAllByCompanyAndReceivableAccount(Company company, Account receivableAccount);

  List<Dealer> findByCompanyAndReceivableAccountIn(
      Company company, Collection<Account> receivableAccounts);

  Optional<Dealer> findByCompanyAndPortalUserEmail(Company company, String email);

  List<Dealer> findAllByCompanyAndPortalUserId(Company company, Long userId);

  List<Dealer> findAllByCompanyAndPortalUserEmailIgnoreCase(Company company, String email);

  @Query(
      "select d from Dealer d where d.company = :company and (lower(d.name) like lower(concat('%',"
          + " :term, '%')) or lower(d.code) like lower(concat('%', :term, '%'))) order by d.name"
          + " asc")
  List<Dealer> search(
      @Param("company") Company company, @Param("term") String term, Pageable pageable);

  @Query(
      """
      select d
      from Dealer d
      where d.company = :company
        and (:status is null or upper(d.status) = :status)
        and (:region is null or upper(coalesce(d.region, '')) = :region)
        and (lower(d.name) like lower(concat('%', :term, '%'))
          or lower(d.code) like lower(concat('%', :term, '%'))
          or lower(coalesce(d.email, '')) like lower(concat('%', :term, '%'))
          or lower(coalesce(d.companyName, '')) like lower(concat('%', :term, '%')))
      order by d.name asc
      """)
  List<Dealer> searchFiltered(
      @Param("company") Company company,
      @Param("term") String term,
      @Param("status") String status,
      @Param("region") String region,
      Pageable pageable);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select d from Dealer d where d.company = :company and d.id = :id")
  Optional<Dealer> lockByCompanyAndId(@Param("company") Company company, @Param("id") Long id);

  @Query("select d from Dealer d where d.portalUser.id = :userId")
  Optional<Dealer> findByPortalUserId(@Param("userId") Long userId);

  @Query("select d from Dealer d where d.portalUser.email = :email")
  Optional<Dealer> findByPortalUserEmail(@Param("email") String email);
}
