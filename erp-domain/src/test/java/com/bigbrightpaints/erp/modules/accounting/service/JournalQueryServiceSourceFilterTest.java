package com.bigbrightpaints.erp.modules.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;

import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;

@ExtendWith(MockitoExtension.class)
class JournalQueryServiceSourceFilterTest {

  @Mock private CompanyContextService companyContextService;
  @Mock private JournalEntryRepository journalEntryRepository;
  @Mock private AccountingDtoMapperService accountingDtoMapperService;
  @Mock private AccountResolutionService accountResolutionService;

  private JournalQueryService journalQueryService;
  private Company company;

  @BeforeEach
  void setUp() {
    journalQueryService =
        new JournalQueryService(
            companyContextService,
            journalEntryRepository,
            accountingDtoMapperService,
            accountResolutionService);
    company = new Company();
  }

  @Test
  void listJournalEntries_mapsPackingAliasToFactoryPackingSource() {
    when(companyContextService.requireCurrentCompany()).thenReturn(company);
    JournalEntry entry = new JournalEntry();
    entry.setSourceModule("FACTORY_PACKING");
    entry.setReferenceNumber("PACK-001");
    entry.setEntryDate(LocalDate.of(2026, 4, 1));
    when(
            journalEntryRepository.findByCompanyAndSourceModuleIgnoreCaseOrderByEntryDateDescIdDesc(
                eq(company), eq("FACTORY_PACKING"), any()))
        .thenReturn(new PageImpl<>(List.of(entry)));
    when(accountingDtoMapperService.resolveDisplayReferenceNumber(company, entry)).thenReturn("PACK-001");
    JournalEntryDto dto =
        new JournalEntryDto(
            1L,
            null,
            "PACK-001",
            LocalDate.of(2026, 4, 1),
            "memo",
            "POSTED",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            List.of(),
            null,
            null,
            null,
            null,
            null,
            null);
    when(accountingDtoMapperService.toJournalEntryDto(entry, "PACK-001")).thenReturn(dto);

    List<JournalEntryDto> result = journalQueryService.listJournalEntries(null, null, 0, 20, "PACKING");

    assertThat(result).containsExactly(dto);
    verify(journalEntryRepository)
        .findByCompanyAndSourceModuleIgnoreCaseOrderByEntryDateDescIdDesc(
            eq(company), eq("FACTORY_PACKING"), any());
  }

  @Test
  void normalizeSourceModule_mapsKnownAliases() {
    assertThat(journalQueryService.normalizeSourceModule("PACKING")).isEqualTo("FACTORY_PACKING");
    assertThat(journalQueryService.normalizeSourceModule("cost_allocation"))
        .isEqualTo("FACTORY_COST_VARIANCE");
  }
}
