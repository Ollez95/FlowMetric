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

## UX guidelines
- Confidence must always be visible beside estimated classifications.
- Favor transparency over polish when tradeoffs conflict.
- The desktop UI should make it obvious what data was observed versus what FlowMetric inferred.

## Validation
- Prioritize unit tests for heuristic scoring and aggregation.
- If build verification is blocked by local tooling, document the blocker clearly in `README.md`.
