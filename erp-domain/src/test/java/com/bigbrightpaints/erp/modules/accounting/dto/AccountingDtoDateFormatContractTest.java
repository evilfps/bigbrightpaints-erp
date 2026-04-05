package com.bigbrightpaints.erp.modules.accounting.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.annotation.JsonFormat;

@Tag("critical")
class AccountingDtoDateFormatContractTest {

  private static final Path DTO_ROOT =
      Path.of("src/main/java/com/bigbrightpaints/erp/modules/accounting/dto");
  private static final String DTO_PACKAGE = "com.bigbrightpaints.erp.modules.accounting.dto";

  @Test
  void everyAccountingDtoDateField_hasCanonicalJsonFormatAnnotation() throws Exception {
    List<String> failures = new ArrayList<>();

    for (Class<?> dtoClass : loadTopLevelDtoClasses()) {
      inspectClass(dtoClass, failures);
    }

    assertThat(failures)
        .withFailMessage(
            "Accounting DTO date fields must declare canonical @JsonFormat annotations:%n%s",
            String.join(System.lineSeparator(), failures))
        .isEmpty();
  }

  private void inspectClass(Class<?> dtoClass, List<String> failures) {
    inspectOwnFields(dtoClass, failures);
    for (Class<?> nestedClass : dtoClass.getDeclaredClasses()) {
      inspectClass(nestedClass, failures);
    }
  }

  private void inspectOwnFields(Class<?> dtoClass, List<String> failures) {
    for (Field field : dtoClass.getDeclaredFields()) {
      if (field.isSynthetic() || Modifier.isStatic(field.getModifiers())) {
        continue;
      }
      if (field.getType().equals(LocalDate.class)) {
        assertLocalDateFormat(dtoClass, field, failures);
      } else if (field.getType().equals(Instant.class)) {
        assertInstantFormat(dtoClass, field, failures);
      } else if (field.getType().equals(YearMonth.class)) {
        assertYearMonthFormat(dtoClass, field, failures);
      }
    }
  }

  private void assertLocalDateFormat(Class<?> dtoClass, Field field, List<String> failures) {
    JsonFormat annotation = findJsonFormat(dtoClass, field);
    String fieldName = dtoClass.getName() + "#" + field.getName();
    if (annotation == null) {
      failures.add(fieldName + " is missing @JsonFormat(pattern = \"yyyy-MM-dd\")");
      return;
    }
    if (!"yyyy-MM-dd".equals(annotation.pattern())) {
      failures.add(
          fieldName
              + " must use @JsonFormat(pattern = \"yyyy-MM-dd\") but was pattern=\""
              + annotation.pattern()
              + "\"");
    }
  }

  private void assertInstantFormat(Class<?> dtoClass, Field field, List<String> failures) {
    JsonFormat annotation = findJsonFormat(dtoClass, field);
    String fieldName = dtoClass.getName() + "#" + field.getName();
    if (annotation == null) {
      failures.add(fieldName + " is missing @JsonFormat(shape = JsonFormat.Shape.STRING)");
      return;
    }
    if (annotation.shape() != JsonFormat.Shape.STRING) {
      failures.add(
          fieldName
              + " must use @JsonFormat(shape = JsonFormat.Shape.STRING) but was shape="
              + annotation.shape());
    }
  }

  private void assertYearMonthFormat(Class<?> dtoClass, Field field, List<String> failures) {
    JsonFormat annotation = findJsonFormat(dtoClass, field);
    String fieldName = dtoClass.getName() + "#" + field.getName();
    if (annotation == null) {
      failures.add(fieldName + " is missing @JsonFormat(pattern = \"yyyy-MM\")");
      return;
    }
    if (!"yyyy-MM".equals(annotation.pattern())) {
      failures.add(
          fieldName
              + " must use @JsonFormat(pattern = \"yyyy-MM\") but was pattern=\""
              + annotation.pattern()
              + "\"");
    }
  }

  private JsonFormat findJsonFormat(Class<?> dtoClass, Field field) {
    JsonFormat fieldAnnotation = field.getAnnotation(JsonFormat.class);
    if (fieldAnnotation != null) {
      return fieldAnnotation;
    }

    if (dtoClass.isRecord()) {
      JsonFormat componentAnnotation =
          Arrays.stream(dtoClass.getRecordComponents())
              .filter(component -> component.getName().equals(field.getName()))
              .map(component -> component.getAnnotation(JsonFormat.class))
              .filter(annotation -> annotation != null)
              .findFirst()
              .orElse(null);
      if (componentAnnotation != null) {
        return componentAnnotation;
      }
    }

    try {
      Method accessor = dtoClass.getDeclaredMethod(field.getName());
      return accessor.getAnnotation(JsonFormat.class);
    } catch (NoSuchMethodException ignored) {
      return null;
    }
  }

  private List<Class<?>> loadTopLevelDtoClasses() throws IOException {
    try (Stream<Path> paths = Files.walk(DTO_ROOT)) {
      return paths
          .filter(
              path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".java"))
          .filter(path -> !"package-info.java".equals(path.getFileName().toString()))
          .sorted(Comparator.naturalOrder())
          .map(this::loadClass)
          .collect(Collectors.toList());
    }
  }

  private Class<?> loadClass(Path javaFile) {
    String relativePath = DTO_ROOT.relativize(javaFile).toString().replace(".java", "");
    String className = relativePath.replace('/', '.').replace('\\', '.');
    try {
      return Class.forName(DTO_PACKAGE + "." + className);
    } catch (ClassNotFoundException ex) {
      throw new IllegalStateException("Failed to load DTO class " + className, ex);
    }
  }
}
