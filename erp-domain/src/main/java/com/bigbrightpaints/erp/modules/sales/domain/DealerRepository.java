package com.bigbrightpaints.erp.modules.sales.domain;

import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface DealerRepository extends JpaRepository<Dealer, Long> {
    List<Dealer> findByCompanyOrderByNameAsc(Company company);
    Optional<Dealer> findByCompanyAndId(Company company, Long id);
    Optional<Dealer> findByCompanyAndCodeIgnoreCase(Company company, String code);
    Optional<Dealer> findByCompanyAndReceivableAccount(Company company, Account receivableAccount);
    List<Dealer> findAllByCompanyAndReceivableAccount(Company company, Account receivableAccount);
    @Query("select d from Dealer d where d.company = :company and d.receivableAccount.id in :accountIds")
    List<Dealer> findAllByCompanyAndReceivableAccountIdIn(@Param("company") Company company,
                                                          @Param("accountIds") List<Long> accountIds);
    Optional<Dealer> findByCompanyAndPortalUserEmail(Company company, String email);

    @Query("select d from Dealer d where d.company = :company and (lower(d.name) like lower(concat('%', :term, '%')) or lower(d.code) like lower(concat('%', :term, '%'))) order by d.name asc")
    List<Dealer> search(@Param("company") Company company, @Param("term") String term, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Dealer d where d.company = :company and d.id = :id")
    Optional<Dealer> lockByCompanyAndId(@Param("company") Company company, @Param("id") Long id);

    @Query("select d from Dealer d where d.portalUser.id = :userId")
    Optional<Dealer> findByPortalUserId(@Param("userId") Long userId);

    @Query("select d from Dealer d where d.portalUser.email = :email")
    Optional<Dealer> findByPortalUserEmail(@Param("email") String email);
}
