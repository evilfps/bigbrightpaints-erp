package com.bigbrightpaints.erp.codered;

import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class CR_SwaggerDefaultHardeningIT extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    @Test
    @DisplayName("Default profile does not expose Swagger/OpenAPI anonymously")
    void defaultProfileBlocksAnonymousSwagger() {
        ResponseEntity<String> apiDocs = rest.getForEntity("/v3/api-docs", String.class);
        assertThat(apiDocs.getStatusCode()).isIn(
                HttpStatus.NOT_FOUND,
                HttpStatus.UNAUTHORIZED,
                HttpStatus.FORBIDDEN
        );

        ResponseEntity<String> swaggerUi = rest.getForEntity("/swagger-ui/index.html", String.class);
        assertThat(swaggerUi.getStatusCode()).isIn(
                HttpStatus.NOT_FOUND,
                HttpStatus.UNAUTHORIZED,
                HttpStatus.FORBIDDEN
        );
    }
}
