package com.bigbrightpaints.erp.modules.accounting.controller;

import com.bigbrightpaints.erp.modules.accounting.dto.OpeningBalanceImportResponse;
import com.bigbrightpaints.erp.modules.accounting.service.OpeningBalanceImportService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OpeningBalanceImportControllerTest {

    @Mock
    private OpeningBalanceImportService openingBalanceImportService;

    @Test
    void importOpeningBalances_delegatesToServiceWithMultipartFile() {
        OpeningBalanceImportController controller = new OpeningBalanceImportController(openingBalanceImportService);
        MockMultipartFile file = csvFile();
        OpeningBalanceImportResponse response = new OpeningBalanceImportResponse(2, 1, List.of());
        when(openingBalanceImportService.importOpeningBalances(file)).thenReturn(response);

        controller.importOpeningBalances(file);

        verify(openingBalanceImportService).importOpeningBalances(file);
    }

    private MockMultipartFile csvFile() {
        return new MockMultipartFile(
                "file",
                "opening-balances.csv",
                "text/csv",
                "account_code,account_name,account_type,debit_amount,credit_amount,narration\n"
                        .getBytes(StandardCharsets.UTF_8)
        );
    }
}
