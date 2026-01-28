package com.bigbrightpaints.erp.modules.accounting.domain;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface JournalReferenceMappingRepository extends JpaRepository<JournalReferenceMapping, Long> {
    Optional<JournalReferenceMapping> findByCompanyAndLegacyReferenceIgnoreCase(Company company, String legacyReference);
    Optional<JournalReferenceMapping> findByCompanyAndCanonicalReferenceIgnoreCase(Company company, String canonicalReference);
    List<JournalReferenceMapping> findAllByCompanyAndLegacyReferenceIgnoreCase(Company company, String legacyReference);
    List<JournalReferenceMapping> findAllByCompanyAndCanonicalReferenceIgnoreCase(Company company, String canonicalReference);

    @Modifying
    @Query(value = """
            INSERT INTO journal_reference_mappings (
                company_id,
                legacy_reference,
                canonical_reference,
                entity_type,
                created_at
            )
            VALUES (:companyId, :legacyReference, :canonicalReference, :entityType, NOW())
            ON CONFLICT (company_id, legacy_reference) DO NOTHING
            """, nativeQuery = true)
    int reserveManualReference(@Param("companyId") Long companyId,
                               @Param("legacyReference") String legacyReference,
                               @Param("canonicalReference") String canonicalReference,
                               @Param("entityType") String entityType);
}
