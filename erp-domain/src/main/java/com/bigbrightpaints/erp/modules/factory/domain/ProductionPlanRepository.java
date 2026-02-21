package com.bigbrightpaints.erp.modules.factory.domain;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ProductionPlanRepository extends JpaRepository<ProductionPlan, Long> {
    List<ProductionPlan> findByCompanyOrderByPlannedDateDesc(Company company);
    Optional<ProductionPlan> findByCompanyAndId(Company company, Long id);
    Optional<ProductionPlan> findByCompanyAndPlanNumber(Company company, String planNumber);

    @Modifying
    @Query(value = """
            INSERT INTO production_plans (
                company_id,
                plan_number,
                product_name,
                quantity,
                planned_date,
                notes
            )
            VALUES (
                :companyId,
                :planNumber,
                :productName,
                :quantity,
                :plannedDate,
                :notes
            )
            ON CONFLICT (company_id, plan_number) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("companyId") Long companyId,
                       @Param("planNumber") String planNumber,
                       @Param("productName") String productName,
                       @Param("quantity") Double quantity,
                       @Param("plannedDate") java.time.LocalDate plannedDate,
                       @Param("notes") String notes);
}
