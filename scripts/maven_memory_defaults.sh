#!/usr/bin/env bash

bbp_ensure_maven_memory_defaults() {
  if [[ -n "${MAVEN_OPTS:-}" ]]; then
    return 0
  fi

  local xmx="${BBP_MAVEN_XMX:-1536m}"
  local metaspace="${BBP_MAVEN_MAX_METASPACE:-512m}"
  export MAVEN_OPTS="-Xmx${xmx} -XX:MaxMetaspaceSize=${metaspace} -XX:+UseG1GC"
}
