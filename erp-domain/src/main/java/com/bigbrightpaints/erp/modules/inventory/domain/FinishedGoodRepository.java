package com.bigbrightpaints.erp.modules.inventory.domain;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface FinishedGoodRepository extends JpaRepository<FinishedGood, Long> {
    List<FinishedGood> findByCompanyOrderByProductCodeAsc(Company company);
    Optional<FinishedGood> findByCompanyAndId(Company company, Long id);
    Optional<FinishedGood> findByCompanyAndProductCode(Company company, String productCode);
    Optional<FinishedGood> findByCompanyAndProductCodeIgnoreCase(Company company, String productCode);
    List<FinishedGood> findByCompanyAndProductCodeIn(Company company, Collection<String> productCodes);
    @Query("select fg from FinishedGood fg where fg.company = :company and lower(fg.productCode) in :productCodes")
    List<FinishedGood> findByCompanyAndProductCodeInIgnoreCase(@Param("company") Company company,
                                                               @Param("productCodes") Collection<String> productCodes);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select fg from FinishedGood fg where fg.company = :company and fg.id = :id")
    Optional<FinishedGood> lockByCompanyAndId(@Param("company") Company company, @Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select fg from FinishedGood fg where fg.company = :company and fg.id in :ids order by fg.id")
    List<FinishedGood> lockByCompanyAndIdInOrderById(@Param("company") Company company, @Param("ids") Collection<Long> ids);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select fg from FinishedGood fg where fg.company = :company and fg.productCode = :productCode")
    Optional<FinishedGood> lockByCompanyAndProductCode(@Param("company") Company company,
                                                       @Param("productCode") String productCode);
}
