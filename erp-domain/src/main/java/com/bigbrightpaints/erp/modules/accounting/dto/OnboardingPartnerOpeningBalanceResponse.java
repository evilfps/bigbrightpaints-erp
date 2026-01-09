package com.bigbrightpaints.erp.modules.accounting.dto;

public record OnboardingPartnerOpeningBalanceResponse(String referenceNumber,
                                                      Long journalEntryId,
                                                      int linesProcessed,
                                                      int linesSkipped) {}
