package com.bigbrightpaints.erp.modules.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Modifier;

import org.junit.jupiter.api.Test;

import com.bigbrightpaints.erp.modules.accounting.dto.JournalCreationRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.ManualJournalRequest;

class JournalCreationOwnershipContractTest {

  @Test
  void accountingCoreEngineCoreHidesLegacyPublicJournalCreationEntryPoints() throws Exception {
    Class<?> accountingCoreEngineCore = JournalEntryService.class.getSuperclass();

    assertThat(accountingCoreEngineCore.getSimpleName()).isEqualTo("AccountingCoreEngineCore");

    assertThat(
            Modifier.isProtected(
                accountingCoreEngineCore
                    .getDeclaredMethod("createStandardJournal", JournalCreationRequest.class)
                    .getModifiers()))
        .isTrue();
    assertThat(
            Modifier.isProtected(
                accountingCoreEngineCore
                    .getDeclaredMethod("createManualJournal", ManualJournalRequest.class)
                    .getModifiers()))
        .isTrue();
    assertThat(
            Modifier.isProtected(
                accountingCoreEngineCore
                    .getDeclaredMethod("createJournalEntry", JournalEntryRequest.class)
                    .getModifiers()))
        .isTrue();
  }

  @Test
  void journalEntryServiceRemainsThePublicJournalCreationOwner() throws Exception {
    Class<?> journalEntryService = JournalEntryService.class;

    assertThat(
            Modifier.isPublic(
                journalEntryService
                    .getDeclaredMethod("createStandardJournal", JournalCreationRequest.class)
                    .getModifiers()))
        .isTrue();
    assertThat(
            Modifier.isPublic(
                journalEntryService
                    .getDeclaredMethod("createJournalEntry", JournalEntryRequest.class)
                    .getModifiers()))
        .isTrue();
  }
}
