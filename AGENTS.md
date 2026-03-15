# FlowMetric Agent Guide

## Product constraints
- Keep all user-facing wording honest: use `estimated AI-generated`, `likely AI-assisted`, or `mixed / unclear`.
- Do not present heuristic output as proof or exact attribution.
- Stay local-first. Do not add cloud sync, auth, telemetry upload, or team features in MVP work.

## Architecture expectations
- `shared-core`: domain models, heuristics, analytics, and persistence helpers that are reusable by both app surfaces.
- `android-studio-plugin`: capture local editor save events for one selected project root and persist raw events locally.
- `desktop-app`: read persisted events and present analytics, trends, filters, and review flows.

## Implementation guidelines
- Prefer additive, raw event storage. Derived metrics should be recomputable.
- Keep heuristics explainable. New signals should include short notes or explicit signal names.
- If revert/undo is discussed, prefer safe review and future-ready architecture over destructive automation.
- Default to JSON persistence for MVP unless a concrete SQLite need appears.
- When Codex edits a tracked project file in this repo, prefer explicit attribution over heuristics:
  1. Save a temp snapshot of the file before editing.
  2. Apply the edit.
  3. Run `scripts/record_codex_edit.sh <project-root> <absolute-file-path> <before-snapshot-file>`.
  4. If multiple files were edited, you may instead create a tab-separated manifest of `<absolute-file-path>\t<before-snapshot-file>` entries and run `scripts/record_codex_batch.sh <project-root> <manifest-file>`.
  This records a `CODEX_PATCH` event with the exact patch payload in `.flowmetric/events.json`.

## UX guidelines
- Confidence must always be visible beside estimated classifications.
- Favor transparency over polish when tradeoffs conflict.
- The desktop UI should make it obvious what data was observed versus what FlowMetric inferred.

## Validation
- Prioritize unit tests for heuristic scoring and aggregation.
- If build verification is blocked by local tooling, document the blocker clearly in `README.md`.

## FlowMetric Codex Tracking
- When Codex edits a tracked project file, record the exact patch so FlowMetric can attribute those lines to Codex.
- Before Codex edits a file, save a temp snapshot:
  `tmp_before="$(mktemp)"; cp /absolute/path/to/file "$tmp_before"`
- After Codex finishes editing, run:
  `scripts/record_codex_edit.sh /absolute/path/to/project-root /absolute/path/to/file "$tmp_before" Codex`
- For multiple files, create a tab-separated manifest of `<absolute-file-path>\t<before-snapshot-file>` rows and run:
  `scripts/record_codex_batch.sh /absolute/path/to/project-root /absolute/path/to/manifest.tsv Codex`
- Keep the file paths absolute so FlowMetric can resolve the patch correctly.
