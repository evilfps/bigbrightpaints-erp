package com.bigbrightpaints.erp.modules.admin.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.company.domain.Company;

public interface SupportTicketRepository extends JpaRepository<SupportTicket, Long> {

  List<SupportTicket> findAllByOrderByCreatedAtDesc();

  List<SupportTicket> findByCompanyOrderByCreatedAtDesc(Company company);

  List<SupportTicket> findByCompanyAndUserIdOrderByCreatedAtDesc(Company company, Long userId);

  Optional<SupportTicket> findByCompanyAndUserIdAndId(Company company, Long userId, Long id);

  Optional<SupportTicket> findByCompanyAndId(Company company, Long id);

  long countByCompanyAndStatus(Company company, SupportTicketStatus status);

  List<SupportTicket> findTop200ByGithubIssueNumberIsNotNullAndStatusInOrderByCreatedAtAsc(
      Collection<SupportTicketStatus> statuses);

  @Query("SELECT u FROM UserAccount u WHERE u.id IN :ids")
  List<UserAccount> findUsersByIdIn(@Param("ids") Set<Long> ids);
}
