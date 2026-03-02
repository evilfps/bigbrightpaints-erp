package com.bigbrightpaints.erp.modules.hr.domain;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface EmployeeRepository extends JpaRepository<Employee, Long> {
    List<Employee> findByCompanyOrderByFirstNameAsc(Company company);
    Optional<Employee> findByCompanyAndId(Company company, Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from Employee e where e.company = :company and e.id = :id")
    Optional<Employee> lockByCompanyAndId(@Param("company") Company company, @Param("id") Long id);

    // Find by email (for linking to user account)
    Optional<Employee> findByCompanyAndEmail(Company company, String email);

    List<Employee> findByCompanyAndIdIn(Company company, List<Long> ids);

    // Find by employee type and status
    List<Employee> findByCompanyAndEmployeeTypeAndStatus(Company company, Employee.EmployeeType type, String status);

    // Find by payment schedule and status (for payroll)
    List<Employee> findByCompanyAndPaymentScheduleAndStatus(Company company, Employee.PaymentSchedule schedule, String status);

    // Find all active employees
    List<Employee> findByCompanyAndStatusOrderByFirstNameAsc(Company company, String status);

    // Count employees by status
    long countByCompanyAndStatus(Company company, String status);
}
