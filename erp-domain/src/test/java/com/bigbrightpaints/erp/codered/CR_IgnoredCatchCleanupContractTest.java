package com.bigbrightpaints.erp.codered;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("critical")
class CR_IgnoredCatchCleanupContractTest {

  private static final Pattern IGNORED_CATCH_PATTERN =
      Pattern.compile("catch\\s*\\([^\\)]*\\s+ignored\\)");

  @Test
  void productionSourcesDoNotContainIgnoredCatchPatterns() throws IOException {
    try (var paths = Files.walk(Path.of("src/main/java"))) {
      List<String> offenders =
          paths
              .filter(path -> path.toString().endsWith(".java"))
              .filter(this::containsIgnoredCatchPattern)
              .map(path -> path.toString().replace('\\', '/'))
              .sorted()
              .toList();

      assertThat(offenders).isEmpty();
    }
  }

  private boolean containsIgnoredCatchPattern(Path path) {
    try {
      return IGNORED_CATCH_PATTERN.matcher(Files.readString(path, StandardCharsets.UTF_8)).find();
    } catch (IOException ex) {
      throw new IllegalStateException("Unable to read source file: " + path, ex);
    }
  }
}
