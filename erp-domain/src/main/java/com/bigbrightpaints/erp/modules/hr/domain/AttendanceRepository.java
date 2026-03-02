package com.bigbrightpaints.erp.modules.hr.domain;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface AttendanceRepository extends JpaRepository<Attendance, Long> {

    Optional<Attendance> findByCompanyAndEmployeeAndAttendanceDate(
            Company company, Employee employee, LocalDate date);

    List<Attendance> findByCompanyAndEmployeeInAndAttendanceDate(
            Company company,
            List<Employee> employees,
            LocalDate attendanceDate);

    List<Attendance> findByCompanyAndAttendanceDateOrderByEmployeeFirstNameAsc(
            Company company, LocalDate date);

    List<Attendance> findByCompanyAndEmployeeAndAttendanceDateBetweenOrderByAttendanceDateAsc(
            Company company, Employee employee, LocalDate startDate, LocalDate endDate);

    // Count attendance by status for a period
    @Query("SELECT COUNT(a) FROM Attendance a WHERE a.company = :company AND a.employee = :employee " +
           "AND a.attendanceDate BETWEEN :startDate AND :endDate AND a.status = :status")
    long countByEmployeeAndDateRangeAndStatus(
            @Param("company") Company company,
            @Param("employee") Employee employee,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate,
            @Param("status") Attendance.AttendanceStatus status);

    // Get all attendance for employees with specific payment schedule in date range
    @Query("SELECT a FROM Attendance a WHERE a.company = :company " +
           "AND a.employee.paymentSchedule = :schedule " +
           "AND a.attendanceDate BETWEEN :startDate AND :endDate " +
           "ORDER BY a.employee.id, a.attendanceDate")
    List<Attendance> findByPaymentScheduleAndDateRange(
            @Param("company") Company company,
            @Param("schedule") Employee.PaymentSchedule schedule,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    // Get attendance for specific employee type
    @Query("SELECT a FROM Attendance a WHERE a.company = :company " +
           "AND a.employee.employeeType = :type " +
           "AND a.attendanceDate BETWEEN :startDate AND :endDate " +
           "ORDER BY a.employee.id, a.attendanceDate")
    List<Attendance> findByEmployeeTypeAndDateRange(
            @Param("company") Company company,
            @Param("type") Employee.EmployeeType type,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    @Query("SELECT a FROM Attendance a JOIN FETCH a.employee "
           + "WHERE a.company = :company "
           + "AND a.employee.employeeType = :type "
           + "AND a.employee.status = :status "
           + "AND a.attendanceDate BETWEEN :startDate AND :endDate")
    List<Attendance> findByEmployeeTypeAndStatusAndDateRange(
            @Param("company") Company company,
            @Param("type") Employee.EmployeeType type,
            @Param("status") String status,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    @Query("SELECT a FROM Attendance a JOIN FETCH a.employee e "
           + "WHERE a.company = :company "
           + "AND e.id IN :employeeIds "
           + "AND a.attendanceDate BETWEEN :startDate AND :endDate "
           + "ORDER BY e.id, a.attendanceDate")
    List<Attendance> findByCompanyAndEmployeeIdsAndDateRange(
            @Param("company") Company company,
            @Param("employeeIds") List<Long> employeeIds,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    // Check if attendance already marked for today
    boolean existsByCompanyAndEmployeeAndAttendanceDate(Company company, Employee employee, LocalDate date);

    // Find attendance by employee and date range (for payroll calculation)
    List<Attendance> findByEmployeeAndAttendanceDateBetween(Employee employee, LocalDate startDate, LocalDate endDate);

    @Query("SELECT a.status, COUNT(a) FROM Attendance a WHERE a.company = :company "
            + "AND a.employee.id = :employeeId "
            + "AND a.attendanceDate BETWEEN :startDate AND :endDate "
            + "GROUP BY a.status")
    List<Object[]> summarizeEmployeeAttendanceByStatus(@Param("company") Company company,
                                                       @Param("employeeId") Long employeeId,
                                                       @Param("startDate") LocalDate startDate,
                                                       @Param("endDate") LocalDate endDate);

    @Query("SELECT a.employee.id, a.employee.firstName, a.employee.lastName, a.employee.department, a.employee.designation, "
            + "SUM(CASE WHEN a.status = com.bigbrightpaints.erp.modules.hr.domain.Attendance.AttendanceStatus.PRESENT THEN 1 ELSE 0 END), "
            + "SUM(CASE WHEN a.status = com.bigbrightpaints.erp.modules.hr.domain.Attendance.AttendanceStatus.HALF_DAY THEN 1 ELSE 0 END), "
            + "SUM(CASE WHEN a.status = com.bigbrightpaints.erp.modules.hr.domain.Attendance.AttendanceStatus.ABSENT THEN 1 ELSE 0 END), "
            + "SUM(CASE WHEN a.status = com.bigbrightpaints.erp.modules.hr.domain.Attendance.AttendanceStatus.LEAVE THEN 1 ELSE 0 END), "
            + "SUM(CASE WHEN a.status = com.bigbrightpaints.erp.modules.hr.domain.Attendance.AttendanceStatus.HOLIDAY THEN 1 ELSE 0 END), "
            + "COALESCE(SUM(a.overtimeHours), 0), COALESCE(SUM(a.doubleOvertimeHours), 0) "
            + "FROM Attendance a WHERE a.company = :company "
            + "AND a.attendanceDate BETWEEN :startDate AND :endDate "
            + "GROUP BY a.employee.id, a.employee.firstName, a.employee.lastName, a.employee.department, a.employee.designation "
            + "ORDER BY a.employee.firstName")
    List<Object[]> summarizeMonthlyAttendance(@Param("company") Company company,
                                              @Param("startDate") LocalDate startDate,
                                              @Param("endDate") LocalDate endDate);
}
