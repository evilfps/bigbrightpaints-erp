package com.bigbrightpaints.erp.modules.accounting.service;

import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalReferenceMapping;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalReferenceMappingRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class JournalReferenceResolver {

    private static final Logger logger = LoggerFactory.getLogger(JournalReferenceResolver.class);

    private final JournalEntryRepository journalEntryRepository;
    private final JournalReferenceMappingRepository journalReferenceMappingRepository;

    public JournalReferenceResolver(JournalEntryRepository journalEntryRepository,
                                    JournalReferenceMappingRepository journalReferenceMappingRepository) {
        this.journalEntryRepository = journalEntryRepository;
        this.journalReferenceMappingRepository = journalReferenceMappingRepository;
    }

    public Optional<JournalEntry> findExistingEntry(Company company, String reference) {
        if (company == null || !StringUtils.hasText(reference)) {
            return Optional.empty();
        }
        String trimmed = reference.trim();
        Optional<JournalEntry> direct = journalEntryRepository.findByCompanyAndReferenceNumber(company, trimmed);
        if (direct.isPresent()) {
            return direct;
        }
        List<JournalReferenceMapping> legacyMappings = journalReferenceMappingRepository
                .findAllByCompanyAndLegacyReferenceIgnoreCase(company, trimmed);
        Optional<JournalReferenceMapping> legacy = selectMapping(legacyMappings, "legacy", trimmed);
        if (legacy.isPresent()) {
            String canonical = legacy.get().getCanonicalReference();
            if (StringUtils.hasText(canonical) && !canonical.equalsIgnoreCase(trimmed)) {
                Optional<JournalEntry> canonicalEntry = journalEntryRepository
                        .findByCompanyAndReferenceNumber(company, canonical);
                if (canonicalEntry.isPresent()) {
                    return canonicalEntry;
                }
            }
        }
        List<JournalReferenceMapping> canonicalMappings = journalReferenceMappingRepository
                .findAllByCompanyAndCanonicalReferenceIgnoreCase(company, trimmed);
        Optional<JournalReferenceMapping> canonical = selectMapping(canonicalMappings, "canonical", trimmed);
        if (canonical.isPresent()) {
            String legacyRef = canonical.get().getLegacyReference();
            if (StringUtils.hasText(legacyRef) && !legacyRef.equalsIgnoreCase(trimmed)) {
                Optional<JournalEntry> legacyEntry = journalEntryRepository
                        .findByCompanyAndReferenceNumber(company, legacyRef);
                if (legacyEntry.isPresent()) {
                    return legacyEntry;
                }
            }
        }
        return Optional.empty();
    }

    public boolean exists(Company company, String reference) {
        return findExistingEntry(company, reference).isPresent();
    }

    private Optional<JournalReferenceMapping> selectMapping(List<JournalReferenceMapping> mappings,
                                                            String kind,
                                                            String reference) {
        if (mappings == null || mappings.isEmpty()) {
            return Optional.empty();
        }
        if (mappings.size() == 1) {
            return Optional.of(mappings.get(0));
        }
        Comparator<JournalReferenceMapping> comparator = Comparator
                .comparing(JournalReferenceMapping::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(JournalReferenceMapping::getId, Comparator.nullsLast(Comparator.naturalOrder()));
        JournalReferenceMapping selected = mappings.stream().max(comparator).orElse(mappings.get(0));
        logger.warn("Multiple journal reference mappings for {} reference '{}'; using id={} createdAt={}",
                kind, reference, selected.getId(), selected.getCreatedAt());
        return Optional.of(selected);
    }
}
