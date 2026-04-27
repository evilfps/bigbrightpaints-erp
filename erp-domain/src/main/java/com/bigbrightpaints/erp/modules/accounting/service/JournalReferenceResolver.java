package com.bigbrightpaints.erp.modules.accounting.service;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalReferenceMapping;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalReferenceMappingRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;

@Service
public class JournalReferenceResolver {

  private static final Logger logger = LoggerFactory.getLogger(JournalReferenceResolver.class);

  private final JournalEntryRepository journalEntryRepository;
  private final JournalReferenceMappingRepository journalReferenceMappingRepository;

  public JournalReferenceResolver(
      JournalEntryRepository journalEntryRepository,
      JournalReferenceMappingRepository journalReferenceMappingRepository) {
    this.journalEntryRepository = journalEntryRepository;
    this.journalReferenceMappingRepository = journalReferenceMappingRepository;
  }

  public Optional<JournalEntry> findExistingEntry(Company company, String reference) {
    if (company == null || !StringUtils.hasText(reference)) {
      return Optional.empty();
    }
    String trimmed = reference.trim();
    Optional<JournalEntry> direct =
        journalEntryRepository.findByCompanyAndReferenceNumber(company, trimmed);
    if (direct.isPresent()) {
      return direct;
    }
    List<JournalReferenceMapping> legacyMappings =
        journalReferenceMappingRepository.findAllByCompanyAndLegacyReferenceIgnoreCase(
            company, trimmed);
    Optional<JournalEntry> legacyEntry =
        findEntryByMappings(company, legacyMappings, true, "legacy", trimmed);
    if (legacyEntry.isPresent()) {
      return legacyEntry;
    }
    List<JournalReferenceMapping> canonicalMappings =
        journalReferenceMappingRepository.findAllByCompanyAndCanonicalReferenceIgnoreCase(
            company, trimmed);
    Optional<JournalEntry> canonicalEntry =
        findEntryByMappings(company, canonicalMappings, true, "canonical", trimmed);
    if (canonicalEntry.isPresent()) {
      return canonicalEntry;
    }
    return Optional.empty();
  }

  public boolean exists(Company company, String reference) {
    return findExistingEntry(company, reference).isPresent();
  }

  private Optional<JournalEntry> findEntryByMappings(
      Company company,
      List<JournalReferenceMapping> mappings,
      boolean useCanonicalReference,
      String kind,
      String reference) {
    if (mappings == null || mappings.isEmpty()) {
      return Optional.empty();
    }
    if (mappings.size() > 1) {
      logger.warn(
          "Multiple journal reference mappings for {} reference; attempting resolution", kind);
    }
    Comparator<JournalReferenceMapping> comparator =
        Comparator.comparing(
                JournalReferenceMapping::getCreatedAt,
                Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(
                JournalReferenceMapping::getId, Comparator.nullsLast(Comparator.naturalOrder()));
    List<JournalReferenceMapping> ordered =
        mappings.stream().sorted(comparator.reversed()).toList();
    for (JournalReferenceMapping mapping : ordered) {
      String candidate =
          useCanonicalReference ? mapping.getCanonicalReference() : mapping.getLegacyReference();
      if (!StringUtils.hasText(candidate)) {
        continue;
      }
      Optional<JournalEntry> entry =
          journalEntryRepository.findByCompanyAndReferenceNumber(company, candidate.trim());
      if (entry.isPresent()) {
        return entry;
      }
    }
    return Optional.empty();
  }
}
