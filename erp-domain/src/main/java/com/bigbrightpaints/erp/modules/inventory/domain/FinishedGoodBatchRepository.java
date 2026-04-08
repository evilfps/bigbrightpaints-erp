package com.bigbrightpaints.erp.modules.inventory.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bigbrightpaints.erp.modules.company.domain.Company;

import jakarta.persistence.LockModeType;

public interface FinishedGoodBatchRepository extends JpaRepository<FinishedGoodBatch, Long> {

  List<FinishedGoodBatch> findByFinishedGoodOrderByManufacturedAtAsc(FinishedGood finishedGood);

  List<FinishedGoodBatch> findByFinishedGoodIn(Collection<FinishedGood> finishedGoods);

  List<FinishedGoodBatch> findByFinishedGood_ValuationAccountId(Long valuationAccountId);

  @Query(
      """
      select b from FinishedGoodBatch b
      where b.finishedGood.company = :company
        and b.finishedGood.valuationAccountId = :valuationAccountId
      """)
  List<FinishedGoodBatch> findByCompanyAndValuationAccountId(
      @Param("company") Company company, @Param("valuationAccountId") Long valuationAccountId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      select b from FinishedGoodBatch b
      where b.finishedGood = :finishedGood
        and b.quantityAvailable > 0
      order by case when b.expiryDate is null then 1 else 0 end,
               b.expiryDate asc,
               b.manufacturedAt asc,
               b.id asc
      """)
  List<FinishedGoodBatch> findAllocatableBatches(@Param("finishedGood") FinishedGood finishedGood);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      select b from FinishedGoodBatch b
      where b.finishedGood = :finishedGood
        and b.quantityAvailable > 0
      order by b.manufacturedAt asc, b.id asc
      """)
  List<FinishedGoodBatch> findAllocatableBatchesFIFO(
      @Param("finishedGood") FinishedGood finishedGood);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      select b from FinishedGoodBatch b
      where b.finishedGood = :finishedGood
        and b.quantityAvailable > 0
      order by b.manufacturedAt desc, b.id desc
      """)
  List<FinishedGoodBatch> findAllocatableBatchesLIFO(
      @Param("finishedGood") FinishedGood finishedGood);

  @Query(
      """
      select sum(b.quantityTotal * b.unitCost) / sum(b.quantityTotal)
      from FinishedGoodBatch b
      where b.finishedGood = :finishedGood
        and b.quantityTotal > 0
      """)
  BigDecimal calculateWeightedAverageCost(@Param("finishedGood") FinishedGood finishedGood);

  @Query(
      """
      select coalesce(sum(b.quantityAvailable * b.unitCost), 0)
      from FinishedGoodBatch b
      where b.finishedGood.company = :company
      """)
  BigDecimal sumAvailableValueByCompany(@Param("company") Company company);

  // Bulk packaging support
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select b from FinishedGoodBatch b where b.id = :id")
  java.util.Optional<FinishedGoodBatch> lockById(@Param("id") Long id);

  @Query(
      """
      select b from FinishedGoodBatch b
      where b.finishedGood.company = :company
        and b.expiryDate is not null
        and b.expiryDate >= :today
        and b.expiryDate <= :cutoff
        and b.quantityAvailable > 0
      order by b.expiryDate asc, b.manufacturedAt asc, b.id asc
      """)
  List<FinishedGoodBatch> findExpiringSoonByCompany(
      @Param("company") Company company,
      @Param("today") LocalDate today,
      @Param("cutoff") LocalDate cutoff);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      select b from FinishedGoodBatch b
      where b.finishedGood.company = :company
        and b.id = :id
      """)
  java.util.Optional<FinishedGoodBatch> lockByCompanyAndId(
      @Param("company") Company company, @Param("id") Long id);

  @EntityGraph(attributePaths = "finishedGood")
  java.util.Optional<FinishedGoodBatch> findByFinishedGood_CompanyAndId(Company company, Long id);

  // Used to ensure idempotency when creating batches (e.g. Tally sync, fixture seeding)
  boolean existsByFinishedGoodAndBatchCodeIgnoreCase(FinishedGood finishedGood, String batchCode);

  java.util.Optional<FinishedGoodBatch> findByFinishedGoodAndBatchCode(
      FinishedGood finishedGood, String batchCode);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select b from FinishedGoodBatch b where b.finishedGood = :finishedGood and b.batchCode ="
          + " :batchCode")
  java.util.Optional<FinishedGoodBatch> lockByFinishedGoodAndBatchCode(
      @Param("finishedGood") FinishedGood finishedGood, @Param("batchCode") String batchCode);
}
