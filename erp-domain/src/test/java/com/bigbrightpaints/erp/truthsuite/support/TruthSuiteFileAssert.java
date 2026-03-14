package com.bigbrightpaints.erp.truthsuite.support;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class TruthSuiteFileAssert {

    private static final Path ERP_DOMAIN_ROOT = locateErpDomainRoot();

    private TruthSuiteFileAssert() {
    }

    public static Path resolve(String relativePath) {
        return ERP_DOMAIN_ROOT.resolve(relativePath).normalize();
    }

    public static String read(String relativePath) {
        Path path = resolve(relativePath);
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            fail("Unable to read source evidence file: " + path + " (" + ex.getMessage() + ")");
            return "";
        }
    }

    public static void assertContains(String relativePath, String... snippets) {
        String content = read(relativePath);
        for (String snippet : snippets) {
            assertTrue(
                    content.contains(snippet),
                    () -> "Expected snippet not found in " + relativePath + ": " + snippet);
        }
    }

    public static void assertContainsInOrder(String relativePath, String... snippets) {
        String content = read(relativePath);
        int cursor = -1;
        for (String snippet : snippets) {
            int index = content.indexOf(snippet, cursor + 1);
            assertTrue(index >= 0, () -> "Expected ordered snippet not found in " + relativePath + ": " + snippet);
            cursor = index;
        }
    }

    private static Path locateErpDomainRoot() {
        Path cwd = Path.of("").toAbsolutePath().normalize();
        if (Files.exists(cwd.resolve("src/main/java"))) {
            return cwd;
        }
        Path fromRepoRoot = cwd.resolve("erp-domain");
        if (Files.exists(fromRepoRoot.resolve("src/main/java"))) {
            return fromRepoRoot;
        }
        throw new IllegalStateException("Unable to locate erp-domain root from: " + cwd);
    }
}
