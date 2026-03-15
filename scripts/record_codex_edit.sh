#!/usr/bin/env bash
set -euo pipefail

FLOWMETRIC_ROOT="/Users/gustavolorena/IdeaProjects/FlowMetric"
SOURCE_LABEL="${4:-${FLOWMETRIC_SOURCE_LABEL:-Codex}}"
AGENT_MODEL="${5:-${FLOWMETRIC_AGENT_MODEL:-${CODEX_MODEL:-}}}"

cd "$FLOWMETRIC_ROOT"
./gradlew -q :shared-core:recordCodexEdit \
  -PflowmetricProjectRoot="$1" \
  -PflowmetricFilePath="$2" \
  -PflowmetricBeforeFile="$3" \
  -PflowmetricSourceLabel="$SOURCE_LABEL" \
  -PflowmetricAgentModel="$AGENT_MODEL"
