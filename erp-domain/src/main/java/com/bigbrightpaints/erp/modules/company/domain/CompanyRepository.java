package com.bigbrightpaints.erp.modules.company.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface CompanyRepository extends JpaRepository<Company, Long> {
  interface CompanyCodeProjection {
    Long getId();

    String getCode();
  }

  Optional<Company> findByCodeIgnoreCase(String code);

  @Query("select c.id from Company c where lower(c.code) = lower(:code)")
  Optional<Long> findIdByCodeIgnoreCase(@Param("code") String code);

  @Query("select c.id as id, c.code as code from Company c where c.id in :ids")
  List<CompanyCodeProjection> findCompanyCodesByIdIn(@Param("ids") Collection<Long> ids);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select c from Company c where c.id = :id")
  Optional<Company> lockById(@Param("id") Long id);
}
