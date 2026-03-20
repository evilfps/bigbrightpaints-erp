package com.bigbrightpaints.erp.core.config;

import io.swagger.v3.oas.models.Operation;
import java.util.List;
import java.util.Locale;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiTaggingConfig {
    @Bean
    public OpenApiCustomizer moduleTagCustomizer() {
        return openApi -> {
            if (openApi.getPaths() == null) {
                return;
            }
            openApi.getPaths().forEach((path, item) -> {
                String tag = resolveTag(path);
                if (tag == null) {
                    return;
                }
                item.readOperations().forEach(operation -> applyTag(operation, tag));
            });
        };
    }

    private void applyTag(Operation operation, String tag) {
        if (operation.getTags() == null || operation.getTags().isEmpty()) {
            operation.setTags(List.of(tag));
            return;
        }
        if (!operation.getTags().contains(tag)) {
            operation.addTagsItem(tag);
        }
    }

    private String resolveTag(String path) {
        String normalized = path.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("/api/v1/admin")
                || normalized.startsWith("/api/v1/auth")
                || normalized.startsWith("/api/v1/companies")
                || normalized.startsWith("/api/v1/multi-company")
                || normalized.startsWith("/api/v1/orchestrator")
                || normalized.startsWith("/api/v1/portal")
                || normalized.startsWith("/api/v1/demo")
                || normalized.startsWith("/api/integration")) {
            return "ADMIN";
        }
        if (normalized.startsWith("/api/v1/accounting")
                || normalized.startsWith("/api/v1/reports")
                || normalized.startsWith("/api/v1/purchasing")
                || normalized.startsWith("/api/v1/inventory")
                || normalized.startsWith("/api/v1/dispatch")
                || normalized.startsWith("/api/v1/raw-materials")
                || normalized.startsWith("/api/v1/raw-material-batches")
                || normalized.startsWith("/api/v1/finished-goods")
                || normalized.startsWith("/api/v1/packaging")
                || normalized.startsWith("/api/v1/hr")
                || normalized.startsWith("/api/v1/payroll")
                || normalized.startsWith("/api/v1/suppliers")) {
            return "ACCOUNTING";
        }
        if (normalized.startsWith("/api/v1/factory")
                || normalized.startsWith("/api/v1/production")) {
            return "FACTORY_PRODUCTION";
        }
        if (normalized.startsWith("/api/v1/sales")
                || normalized.startsWith("/api/v1/invoices")
                || normalized.startsWith("/api/v1/credit/override-requests")) {
            return "SALES";
        }
        if (normalized.startsWith("/api/v1/dealers")
                || normalized.startsWith("/api/v1/dealer-portal")) {
            return "DEALERS";
        }
        return null;
    }
}
