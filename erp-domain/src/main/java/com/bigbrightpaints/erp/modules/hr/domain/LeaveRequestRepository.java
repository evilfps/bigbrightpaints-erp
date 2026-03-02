package com.bigbrightpaints.erp.modules.hr.domain;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LeaveRequestRepository extends JpaRepository<LeaveRequest, Long> {
    List<LeaveRequest> findByCompanyOrderByCreatedAtDesc(Company company);

    List<LeaveRequest> findByCompanyAndStatusOrderByCreatedAtDesc(Company company, String status);

    Optional<LeaveRequest> findByCompanyAndId(Company company, Long id);

    @Query("select case when count(l) > 0 then true else false end from LeaveRequest l "
            + "where l.company = :company and l.employee.id = :employeeId and l.startDate <= :endDate and l.endDate >= :startDate "
            + "and upper(l.status) not in ('REJECTED', 'CANCELLED')")
    boolean existsOverlappingByEmployeeIdAndDates(@Param("company") Company company,
                                                  @Param("employeeId") Long employeeId,
                                                  @Param("startDate") LocalDate startDate,
                                                  @Param("endDate") LocalDate endDate);

    @Query("select l from LeaveRequest l where l.company = :company and l.employee = :employee "
            + "and upper(l.status) = 'APPROVED' and l.startDate >= :startDate and l.endDate <= :endDate")
    List<LeaveRequest> findApprovedRequestsForEmployeeInPeriod(@Param("company") Company company,
                                                                @Param("employee") Employee employee,
                                                                @Param("startDate") LocalDate startDate,
                                                                @Param("endDate") LocalDate endDate);
}
