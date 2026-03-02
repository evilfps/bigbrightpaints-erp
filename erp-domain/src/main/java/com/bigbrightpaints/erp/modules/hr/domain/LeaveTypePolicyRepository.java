package com.bigbrightpaints.erp.modules.hr.domain;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LeaveTypePolicyRepository extends JpaRepository<LeaveTypePolicy, Long> {

    List<LeaveTypePolicy> findByCompanyAndActiveTrueOrderByDisplayNameAsc(Company company);

    Optional<LeaveTypePolicy> findByCompanyAndLeaveTypeIgnoreCase(Company company, String leaveType);
}
