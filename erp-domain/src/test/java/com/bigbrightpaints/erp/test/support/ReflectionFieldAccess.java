package com.bigbrightpaints.erp.test.support;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;

public final class ReflectionFieldAccess {

  private ReflectionFieldAccess() {}

  public static void setField(Object target, String fieldName, Object value) {
    if (target == null) {
      throw new IllegalArgumentException("target must not be null");
    }
    Field field = findField(target.getClass(), fieldName);
    if (field == null) {
      throw new IllegalArgumentException(
          "Field '" + fieldName + "' not found on " + target.getClass().getName());
    }
    try {
      field.setAccessible(true);
      field.set(target, value);
    } catch (IllegalAccessException ex) {
      throw new RuntimeException(
          "Failed to set field '" + fieldName + "' on " + target.getClass().getName(), ex);
    }
  }

  @SuppressWarnings("unchecked")
  public static <T> T invokeMethod(Object target, String methodName, Object... args) {
    if (target == null) {
      throw new IllegalArgumentException("target must not be null");
    }
    Class<?> targetType = target instanceof Class<?> clazz ? clazz : target.getClass();
    Object invocationTarget = target instanceof Class<?> ? null : target;
    Object[] effectiveArgs = args == null ? new Object[0] : args;
    Method method = findMethod(targetType, methodName, effectiveArgs);
    if (method == null) {
      throw new IllegalArgumentException(
          "Method '" + methodName + "' not found on " + targetType.getName());
    }
    try {
      method.setAccessible(true);
      return (T) method.invoke(invocationTarget, effectiveArgs);
    } catch (ReflectiveOperationException ex) {
      throw new RuntimeException(
          "Failed to invoke method '" + methodName + "' on " + targetType.getName(), ex);
    }
  }

  private static Field findField(Class<?> type, String fieldName) {
    Class<?> current = type;
    while (current != null) {
      try {
        return current.getDeclaredField(fieldName);
      } catch (NoSuchFieldException ignored) {
        current = current.getSuperclass();
      }
    }
    return null;
  }

  private static Method findMethod(Class<?> type, String methodName, Object[] args) {
    Class<?> current = type;
    while (current != null) {
      Method method =
          Arrays.stream(current.getDeclaredMethods())
              .filter(candidate -> candidate.getName().equals(methodName))
              .filter(candidate -> parametersMatch(candidate.getParameterTypes(), args))
              .findFirst()
              .orElse(null);
      if (method != null) {
        return method;
      }
      current = current.getSuperclass();
    }
    return null;
  }

  private static boolean parametersMatch(Class<?>[] parameterTypes, Object[] args) {
    if (parameterTypes.length != args.length) {
      return false;
    }
    for (int index = 0; index < parameterTypes.length; index++) {
      Object arg = args[index];
      if (arg == null) {
        continue;
      }
      Class<?> requiredType = wrapPrimitive(parameterTypes[index]);
      if (!requiredType.isAssignableFrom(arg.getClass())) {
        return false;
      }
    }
    return true;
  }

  private static Class<?> wrapPrimitive(Class<?> type) {
    if (!type.isPrimitive()) {
      return type;
    }
    if (type == int.class) {
      return Integer.class;
    }
    if (type == long.class) {
      return Long.class;
    }
    if (type == boolean.class) {
      return Boolean.class;
    }
    if (type == double.class) {
      return Double.class;
    }
    if (type == float.class) {
      return Float.class;
    }
    if (type == short.class) {
      return Short.class;
    }
    if (type == byte.class) {
      return Byte.class;
    }
    if (type == char.class) {
      return Character.class;
    }
    return type;
  }
}
