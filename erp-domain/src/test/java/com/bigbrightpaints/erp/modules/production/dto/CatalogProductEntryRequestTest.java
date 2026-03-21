package com.bigbrightpaints.erp.modules.production.dto;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("critical")
class CatalogProductEntryRequestTest {

    @Test
    void setMetadata_copiesValues_andFallsBackToEmptyMap() {
        CatalogProductEntryRequest request = new CatalogProductEntryRequest();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("productType", "decorative");

        request.setMetadata(metadata);
        metadata.put("productType", "industrial");

        assertThat(request.getMetadata()).containsEntry("productType", "decorative");

        request.setMetadata(null);

        assertThat(request.getMetadata()).isEmpty();
    }
}
