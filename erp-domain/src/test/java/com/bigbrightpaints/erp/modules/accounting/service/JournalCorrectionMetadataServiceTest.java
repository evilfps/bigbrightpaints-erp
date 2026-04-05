package com.bigbrightpaints.erp.modules.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.bigbrightpaints.erp.modules.accounting.domain.JournalCorrectionType;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;

@ExtendWith(MockitoExtension.class)
class JournalCorrectionMetadataServiceTest {

  @Mock private JournalEntryRepository journalEntryRepository;

  private JournalCorrectionMetadataService journalCorrectionMetadataService;
  private Company company;

  @BeforeEach
  void setUp() {
    journalCorrectionMetadataService =
        new JournalCorrectionMetadataService(journalEntryRepository);
    company = new Company();
  }

  @Test
  void syncReversalMetadata_savesCanonicalMetadataWhenEntryDrifts() {
    JournalEntry entry = new JournalEntry();
    when(journalEntryRepository.findByCompanyAndId(company, 41L)).thenReturn(Optional.of(entry));

    journalCorrectionMetadataService.syncReversalMetadata(
        company, 41L, "PURCHASE_RETURN", "PURCHASING_RETURN", "PI-41");

    assertThat(entry.getCorrectionType()).isEqualTo(JournalCorrectionType.REVERSAL);
    assertThat(entry.getCorrectionReason()).isEqualTo("PURCHASE_RETURN");
    assertThat(entry.getSourceModule()).isEqualTo("PURCHASING_RETURN");
    assertThat(entry.getSourceReference()).isEqualTo("PI-41");
    verify(journalEntryRepository).save(entry);
  }

  @Test
  void syncReversalMetadata_skipsSaveWhenMetadataAlreadyMatches() {
    JournalEntry entry = new JournalEntry();
    entry.setCorrectionType(JournalCorrectionType.REVERSAL);
    entry.setCorrectionReason("SALES_RETURN");
    entry.setSourceModule("SALES_RETURN");
    entry.setSourceReference("INV-9");
    when(journalEntryRepository.findByCompanyAndId(company, 9L)).thenReturn(Optional.of(entry));

    journalCorrectionMetadataService.syncReversalMetadata(
        company, 9L, "SALES_RETURN", "SALES_RETURN", "INV-9");

    verify(journalEntryRepository, never()).save(entry);
  }

  @Test
  void syncReversalMetadata_noopsWhenEntryDoesNotExist() {
    when(journalEntryRepository.findByCompanyAndId(company, 77L)).thenReturn(Optional.empty());

    journalCorrectionMetadataService.syncReversalMetadata(
        company, 77L, "SALES_RETURN", "SALES_RETURN", "INV-77");

    verify(journalEntryRepository, never()).save(org.mockito.ArgumentMatchers.any());
  }
}
