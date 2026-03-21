package com.bigbrightpaints.erp.modules.reports.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

@Tag("critical")
class ReportControllerRouteContractIT extends AbstractIntegrationTest {

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @Test
    void reportController_exposes_only_canonical_report_paths() {
        Map<String, Set<String>> patternsByMethod = new LinkedHashMap<>();
        handlerMapping.getHandlerMethods().forEach((mapping, handlerMethod) -> {
            if (!ReportController.class.equals(handlerMethod.getBeanType())) {
                return;
            }
            patternsByMethod.computeIfAbsent(handlerMethod.getMethod().getName(), ignored -> new TreeSet<>())
                    .addAll(extractPatterns(mapping));
        });

        assertThat(patternsByMethod)
                .containsEntry("agedDebtors", Set.of("/api/v1/reports/aged-debtors"))
                .containsEntry("balanceSheetHierarchy", Set.of("/api/v1/reports/balance-sheet/hierarchy"))
                .containsEntry("incomeStatementHierarchy", Set.of("/api/v1/reports/income-statement/hierarchy"))
                .containsEntry("agedReceivables", Set.of("/api/v1/reports/aging/receivables"))
                .containsEntry("dealerAging", Set.of("/api/v1/reports/aging/dealer/{dealerId}"))
                .containsEntry("dealerAgingDetailed", Set.of("/api/v1/reports/aging/dealer/{dealerId}/detailed"))
                .containsEntry("dealerDso", Set.of("/api/v1/reports/dso/dealer/{dealerId}"));

        Set<String> allPatterns = patternsByMethod.values().stream()
                .flatMap(Set::stream)
                .collect(Collectors.toCollection(TreeSet::new));

        assertThat(allPatterns)
                .noneMatch(path -> path.startsWith("/api/v1/accounting/reports/"));
    }

    private Set<String> extractPatterns(RequestMappingInfo mapping) {
        if (mapping.getPathPatternsCondition() != null) {
            return mapping.getPathPatternsCondition().getPatternValues();
        }
        if (mapping.getPatternsCondition() != null) {
            return new TreeSet<>(mapping.getPatternsCondition().getPatterns());
        }
        return Set.of();
    }
}
