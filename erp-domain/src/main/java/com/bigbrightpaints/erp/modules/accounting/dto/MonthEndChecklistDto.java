package com.bigbrightpaints.erp.modules.accounting.dto;

import java.util.List;

public record MonthEndChecklistDto(AccountingPeriodDto period,
                                   List<MonthEndChecklistItemDto> items,
                                   boolean readyToClose) {}
