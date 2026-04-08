package com.bigbrightpaints.erp.modules.factory.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bigbrightpaints.erp.modules.company.domain.Company;

import jakarta.persistence.LockModeType;

public interface ProductionLogRepository extends JpaRepository<ProductionLog, Long> {
  long countByCompany(Company company);

  List<ProductionLog> findTop25ByCompanyOrderByProducedAtDesc(Company company);

  Optional<ProductionLog> findByCompanyAndId(Company company, Long id);

  List<ProductionLog> findByCompanyAndProducedAtBetween(
      Company company, Instant start, Instant end);

  List<ProductionLog> findByCompanyAndProduct_IdAndStatusOrderByProducedAtDesc(
      Company company, Long productId, ProductionLogStatus status);

  List<ProductionLog> findByCompanyAndStatusOrderByProducedAtAsc(
      Company company, ProductionLogStatus status);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT pl FROM ProductionLog pl WHERE pl.company = :company AND pl.id = :id")
  Optional<ProductionLog> lockByCompanyAndId(
      @Param("company") Company company, @Param("id") Long id);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "UPDATE ProductionLog pl SET pl.totalPackedQuantity = pl.totalPackedQuantity + :quantity, "
          + "pl.wastageQuantity = pl.mixedQuantity - (pl.totalPackedQuantity + :quantity), "
          + "pl.updatedAt = CURRENT_TIMESTAMP "
          + "WHERE pl.id = :id AND (pl.totalPackedQuantity + :quantity) <= pl.mixedQuantity")
  int incrementPackedQuantityAtomic(@Param("id") Long id, @Param("quantity") BigDecimal quantity);

  Optional<ProductionLog> findTopByCompanyAndProductionCodeStartingWithOrderByProductionCodeDesc(
      Company company, String prefix);

  List<ProductionLog> findByCompanyAndStatusInOrderByProducedAtAsc(
      Company company, Collection<ProductionLogStatus> statuses);

  @Query(
      "SELECT pl FROM ProductionLog pl WHERE pl.company = :company "
          + "AND pl.status = 'FULLY_PACKED' "
          + "AND pl.producedAt >= :startDate "
          + "AND pl.producedAt < :endDate "
          + "ORDER BY pl.producedAt ASC")
  List<ProductionLog> findFullyPackedBatchesByMonth(
      @Param("company") Company company,
      @Param("startDate") Instant startDate,
      @Param("endDate") Instant endDate);
}
