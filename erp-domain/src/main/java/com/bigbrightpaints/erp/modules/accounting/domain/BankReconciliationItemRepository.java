package com.bigbrightpaints.erp.modules.accounting.domain;

import java.util.Collection;
import java.util.List;
import java.util.Set;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bigbrightpaints.erp.modules.company.domain.Company;

public interface BankReconciliationItemRepository
    extends JpaRepository<BankReconciliationItem, Long> {

  List<BankReconciliationItem> findBySessionOrderByClearedAtAscIdAsc(
      BankReconciliationSession session);

  List<BankReconciliationItem> findBySessionAndJournalLineIdIn(
      BankReconciliationSession session, Collection<Long> journalLineIds);

  @Query(
      """
      select item
      from BankReconciliationItem item
      join fetch item.journalLine line
      join fetch line.journalEntry entry
      where item.session = :session
      order by item.clearedAt asc, item.id asc
      """)
  List<BankReconciliationItem> findDetailedBySession(
      @Param("session") BankReconciliationSession session);

  void deleteBySessionAndJournalLineIdIn(
      BankReconciliationSession session, Collection<Long> journalLineIds);

  @Query(
      """
      select item.journalLine.id
      from BankReconciliationItem item
      where item.session = :session
      """)
  Set<Long> findJournalLineIdsBySession(@Param("session") BankReconciliationSession session);

  @Query(
      """
      select count(item)
      from BankReconciliationItem item
      where item.session = :session
        and item.journalLine.id = :journalLineId
      """)
  long countBySessionAndJournalLineId(
      @Param("session") BankReconciliationSession session,
      @Param("journalLineId") Long journalLineId);

  @Query(
      """
      select item
      from BankReconciliationItem item
      join fetch item.session session
      where item.company = :company
        and item.session.id in :sessionIds
      """)
  List<BankReconciliationItem> findByCompanyAndSessionIds(
      @Param("company") Company company, @Param("sessionIds") Collection<Long> sessionIds);
}
