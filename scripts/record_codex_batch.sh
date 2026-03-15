#!/usr/bin/env bash
set -euo pipefail

FLOWMETRIC_ROOT="/Users/gustavolorena/IdeaProjects/FlowMetric"
SOURCE_LABEL="${3:-${FLOWMETRIC_SOURCE_LABEL:-Codex}}"
AGENT_MODEL="${4:-${FLOWMETRIC_AGENT_MODEL:-${CODEX_MODEL:-}}}"

cd "$FLOWMETRIC_ROOT"
./gradlew -q :shared-core:recordCodexBatch \
  -PflowmetricProjectRoot="$1" \
  -PflowmetricManifestFile="$2" \
  -PflowmetricSourceLabel="$SOURCE_LABEL" \
  -PflowmetricAgentModel="$AGENT_MODEL"
