package com.bigbrightpaints.erp.modules.accounting.dto;

import java.util.List;

public record OnboardingAccountSuggestionsResponse(
        CompanyDefaultAccountsResponse defaults,
        List<AccountDto> inventoryCandidates,
        List<AccountDto> cogsCandidates,
        List<AccountDto> revenueCandidates,
        List<AccountDto> taxCandidates,
        List<AccountDto> wipCandidates,
        List<AccountDto> semiFinishedCandidates,
        List<AccountDto> discountCandidates
) {}
