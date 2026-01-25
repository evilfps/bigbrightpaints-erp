package com.bigbrightpaints.erp.modules.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.bigbrightpaints.erp.core.service.NumberSequenceService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BatchNumberServiceTest {

    @Mock
    private NumberSequenceService numberSequenceService;

    private BatchNumberService batchNumberService;

    @BeforeEach
    void setup() {
        batchNumberService = new BatchNumberService(numberSequenceService);
    }

    @Test
    void nextFinishedGoodBatchCodeUsesPackedDate() {
        Company company = new Company();
        company.setCode("ACME");
        company.setTimezone("UTC");
        FinishedGood finishedGood = new FinishedGood();
        finishedGood.setCompany(company);
        finishedGood.setProductCode("fg- 01");
        when(numberSequenceService.nextValue(any(), any())).thenReturn(7L);

        String code = batchNumberService.nextFinishedGoodBatchCode(finishedGood, LocalDate.of(2025, 1, 15));

        assertThat(code).isEqualTo("ACME-FG-01-202501-007");
    }

    @Test
    void nextRawMaterialBatchCodeFallsBackToId() {
        Company company = new Company();
        company.setCode("BBP");
        company.setTimezone("UTC");
        RawMaterial material = new RawMaterial();
        material.setCompany(company);
        material.setSku("   ");
        when(numberSequenceService.nextValue(any(), any())).thenReturn(1L);

        String code = batchNumberService.nextRawMaterialBatchCode(material);

        String period = YearMonth.now(ZoneId.of("UTC")).toString().replace("-", "");
        assertThat(code).isEqualTo("RM-ITEM-" + period + "-001");
    }

    @Test
    void nextPackagingSlipNumberUsesCompanyCode() {
        Company company = new Company();
        company.setCode("BBP");
        when(numberSequenceService.nextValue(any(), any())).thenReturn(12L);

        String code = batchNumberService.nextPackagingSlipNumber(company);

        assertThat(code).isEqualTo("BBP-PS-012");
    }
}
