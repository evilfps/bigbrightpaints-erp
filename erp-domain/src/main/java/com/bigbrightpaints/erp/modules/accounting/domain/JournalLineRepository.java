package com.bigbrightpaints.erp.modules.accounting.domain;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface JournalLineRepository extends JpaRepository<JournalLine, Long> {

    @Query("""
            select line from JournalLine line
            join fetch line.journalEntry entry
            where entry.company = :company
              and line.account.id = :accountId
              and entry.entryDate between :start and :end
            order by entry.entryDate asc, entry.referenceNumber asc, line.id asc
            """)
    List<JournalLine> findLinesForAccountBetween(@Param("company") Company company,
                                                 @Param("accountId") Long accountId,
                                                 @Param("start") LocalDate start,
                                                 @Param("end") LocalDate end);
}

