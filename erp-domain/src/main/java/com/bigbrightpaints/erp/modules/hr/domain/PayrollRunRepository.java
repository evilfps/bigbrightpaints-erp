package com.bigbrightpaints.erp.modules.hr.domain;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.Collection;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface PayrollRunRepository extends JpaRepository<PayrollRun, Long> {
    
    List<PayrollRun> findByCompanyOrderByCreatedAtDesc(Company company);
    
    List<PayrollRun> findByCompanyAndStatusOrderByCreatedAtDesc(Company company, PayrollRun.PayrollStatus status);
    
    List<PayrollRun> findByCompanyAndRunTypeOrderByCreatedAtDesc(Company company, PayrollRun.RunType runType);
    
    Optional<PayrollRun> findByCompanyAndId(Company company, Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT pr FROM PayrollRun pr WHERE pr.company = :company AND pr.id = :id")
    Optional<PayrollRun> lockByCompanyAndId(@Param("company") Company company, @Param("id") Long id);
    
    Optional<PayrollRun> findByCompanyAndRunNumber(Company company, String runNumber);
    
    @Query("SELECT pr FROM PayrollRun pr WHERE pr.company = :company " +
           "AND pr.periodStart <= :date AND pr.periodEnd >= :date")
    List<PayrollRun> findByCompanyAndPeriodContaining(@Param("company") Company company, 
                                                       @Param("date") LocalDate date);
    
    @Query("SELECT pr FROM PayrollRun pr WHERE pr.company = :company " +
           "AND pr.runType = :runType AND pr.status = :status " +
           "ORDER BY pr.periodEnd DESC")
    List<PayrollRun> findByCompanyAndRunTypeAndStatus(@Param("company") Company company,
                                                       @Param("runType") PayrollRun.RunType runType,
                                                       @Param("status") PayrollRun.PayrollStatus status);
    
    @Query("SELECT COUNT(pr) FROM PayrollRun pr WHERE pr.company = :company " +
           "AND YEAR(pr.periodStart) = :year")
    long countByCompanyAndYear(@Param("company") Company company, @Param("year") int year);

    @Query("SELECT COUNT(pr) FROM PayrollRun pr WHERE pr.company = :company " +
           "AND pr.periodStart >= :start AND pr.periodEnd <= :end " +
           "AND pr.status IN :statuses")
    long countByCompanyAndPeriodBetweenAndStatusIn(@Param("company") Company company,
                                                   @Param("start") LocalDate start,
                                                   @Param("end") LocalDate end,
                                                   @Param("statuses") Collection<PayrollRun.PayrollStatus> statuses);

    @Query("SELECT COUNT(pr) FROM PayrollRun pr WHERE pr.company = :company " +
           "AND pr.periodStart >= :start AND pr.periodEnd <= :end " +
           "AND pr.status IN :statuses " +
           "AND pr.journalEntryId IS NULL AND pr.journalEntry IS NULL")
    long countByCompanyAndPeriodBetweenAndStatusInAndJournalMissing(@Param("company") Company company,
                                                                    @Param("start") LocalDate start,
                                                                    @Param("end") LocalDate end,
                                                                    @Param("statuses") Collection<PayrollRun.PayrollStatus> statuses);

    // For backward compatibility with existing code
    default List<PayrollRun> findByCompanyOrderByRunDateDesc(Company company) {
        return findByCompanyOrderByCreatedAtDesc(company);
    }
    
    Optional<PayrollRun> findByCompanyAndIdempotencyKey(Company company, String idempotencyKey);
}
