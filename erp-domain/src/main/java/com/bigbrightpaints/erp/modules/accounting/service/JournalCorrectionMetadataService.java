package com.bigbrightpaints.erp.modules.accounting.service;

import java.util.Objects;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.bigbrightpaints.erp.modules.accounting.domain.JournalCorrectionType;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;

@Service
public class JournalCorrectionMetadataService {

  private final JournalEntryRepository journalEntryRepository;

  public JournalCorrectionMetadataService(JournalEntryRepository journalEntryRepository) {
    this.journalEntryRepository = journalEntryRepository;
  }

  public Optional<JournalEntry> findByCompanyAndId(Company company, Long journalEntryId) {
    if (company == null || journalEntryId == null) {
      return Optional.empty();
    }
    return journalEntryRepository.findByCompanyAndId(company, journalEntryId);
  }

  public void syncReversalMetadata(
      Company company,
      Long journalEntryId,
      String correctionReason,
      String sourceModule,
      String sourceReference) {
    findByCompanyAndId(company, journalEntryId)
        .ifPresent(
            entry -> syncReversalMetadata(entry, correctionReason, sourceModule, sourceReference));
  }

  public void syncReversalMetadata(
      JournalEntry entry, String correctionReason, String sourceModule, String sourceReference) {
    if (entry == null) {
      return;
    }
    boolean changed = false;
    if (entry.getCorrectionType() != JournalCorrectionType.REVERSAL) {
      entry.setCorrectionType(JournalCorrectionType.REVERSAL);
      changed = true;
    }
    if (!Objects.equals(correctionReason, entry.getCorrectionReason())) {
      entry.setCorrectionReason(correctionReason);
      changed = true;
    }
    if (!Objects.equals(sourceModule, entry.getSourceModule())) {
      entry.setSourceModule(sourceModule);
      changed = true;
    }
    if (!Objects.equals(sourceReference, entry.getSourceReference())) {
      entry.setSourceReference(sourceReference);
      changed = true;
    }
    if (changed) {
      journalEntryRepository.save(entry);
    }
  }
}
