package com.bigbrightpaints.erp.modules.accounting.controller;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.modules.production.service.ProductionCatalogService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AccountingCatalogControllerIdempotencyHeaderTest {

    @Test
    void importCatalog_appliesLegacyHeaderWhenPrimaryMissing() {
        ProductionCatalogService productionCatalogService = mock(ProductionCatalogService.class);
        when(productionCatalogService.importCatalog(any(), any())).thenReturn(null);
        AccountingCatalogController controller = new AccountingCatalogController(productionCatalogService);

        MultipartFile file = csvFile();
        controller.importCatalog(file, null, "legacy-001");

        ArgumentCaptor<MultipartFile> fileCaptor = ArgumentCaptor.forClass(MultipartFile.class);
        verify(productionCatalogService).importCatalog(fileCaptor.capture(), eq("legacy-001"));
        assertThat(fileCaptor.getValue().getOriginalFilename()).isEqualTo("catalog.csv");
    }

    @Test
    void importCatalog_rejectsPrimaryLegacyHeaderMismatch() {
        ProductionCatalogService productionCatalogService = mock(ProductionCatalogService.class);
        AccountingCatalogController controller = new AccountingCatalogController(productionCatalogService);

        assertThatThrownBy(() -> controller.importCatalog(csvFile(), "hdr-001", "legacy-001"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Idempotency key header mismatch");
    }

    private MultipartFile csvFile() {
        return new MockMultipartFile(
                "file",
                "catalog.csv",
                "text/csv",
                "sku,name\nSKU-1,Test".getBytes()
        );
    }
}
