package com.bigbrightpaints.erp.modules.inventory.domain;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface FinishedGoodBatchRepository extends JpaRepository<FinishedGoodBatch, Long> {

    List<FinishedGoodBatch> findByFinishedGoodOrderByManufacturedAtAsc(FinishedGood finishedGood);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select b from FinishedGoodBatch b
            where b.finishedGood = :finishedGood
              and b.quantityAvailable > 0
            order by case when b.expiryDate is null then 1 else 0 end,
                     b.expiryDate asc,
                     b.manufacturedAt asc
            """)
    List<FinishedGoodBatch> findAllocatableBatches(@Param("finishedGood") FinishedGood finishedGood);
}
