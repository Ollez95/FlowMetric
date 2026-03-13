# FlowMetric

FlowMetric is a local-only developer analytics MVP for a single selected project. It estimates whether saved code changes were likely AI-assisted or likely non-AI using edit-pattern heuristics. It does not attempt exact attribution.

## Critical review

### Main risks
- False certainty: heuristic output can be mistaken for proof unless the UI and data model keep observed events separate from estimates.
- Signal quality: save-time diffs are a useful MVP signal, but they miss unsaved typing cadence and clipboard provenance, so confidence must stay conservative.
- Edit granularity: a plugin that only looks at file saves will undercount iterative typing sessions and can overclassify large formatted saves as AI-like.
- LOC bias: large generated files can dominate totals; metrics should be framed as estimated changed-line contribution, not source ownership.
- Revert complexity: reliable undo requires durable diffs and careful file safety. v1 should stop at inspection and future-ready session architecture.
- Build/runtime coupling: the IntelliJ plugin and desktop app must share the same persisted schema to avoid version drift.

### Product stance
- Every metric is presented as `estimated`.
- Confidence is part of the primary model, not decorative UI.
- Raw change events are stored so heuristics can be improved later without losing history.

## Proposed architecture

### Modules
- `shared-core`: domain types, heuristics, analytics aggregation, JSON persistence helpers.
- `android-studio-plugin`: project selection, save listeners, session grouping, raw event persistence to `.flowmetric/events.json`.
- `desktop-app`: Compose Desktop analytics UI, local project chooser, filters, trend and file review screens.

### Data flow
1. User selects a tracked root inside Android Studio.
2. Plugin captures file-save deltas and groups them into sessions.
3. Shared heuristics assign an estimated AI/non-AI classification and confidence.
4. Events are stored locally under the tracked project.
5. Desktop app reads the same data and computes dashboard metrics on demand.

## Data model

Observed event fields:
- `projectId`
- `projectPath`
- `filePath`
- `timestampEpochMillis`
- `sessionId`
- `insertedLines`
- `deletedLines`
- `source`
- `fileExtension`
- `languageHint`
- `latestContentHash`

Derived estimate fields:
- `classification`
- `confidence`
- `confidenceScore`
- `estimatedAiLines`
- `estimatedNonAiLines`
- `matchedSignals`
- `notes`

Aggregates:
- dashboard totals
- per-file estimates
- per-session summaries
- daily trend points

## Heuristic scoring approach

Current v1 signals:
- large insertion with low delete ratio
- paste-like burst shortly after the prior event
- bulk insertion followed by small cleanup edits
- repeated structured edits inside one session
- gradual small edits
- delete-heavy refinement

Scoring model:
- additive AI and non-AI signal weights
- convert weighted share into `estimated AI-generated`, `estimated non-AI`, or `mixed / unclear`
- map signal count and consistency to `low`, `medium`, or `high` confidence

Limitations:
- no clipboard access
- no network or model provenance
- save-based tracking only
- estimates changed-line contribution, not authorship

## UI structure

Main screen:
- selected project header
- summary cards for total LOC, estimated AI LOC, estimated non-AI LOC, estimated AI %
- AI vs non-AI comparison bar
- date-range and confidence filters
- trend section by day
- changed file list with classification, confidence, changed lines, and latest timestamp
- detail/review panel prepared for future revert support

## Repository layout

```text
FlowMetric/
├── shared-core/
├── android-studio-plugin/
├── desktop-app/
├── AGENTS.md
└── README.md
```

## Setup

### Requirements
- JDK 17
- Gradle 8.12+ or Gradle wrapper files
- Android Studio / IntelliJ platform for plugin development

### Run targets
- Desktop app: `./gradlew :desktop-app:run`
- Shared tests: `./gradlew :shared-core:test`
- Plugin sandbox: `./gradlew :android-studio-plugin:runIde`

## Current MVP status

Implemented:
- multi-module Gradle structure
- shared domain models and heuristic scorer
- JSON event store under `.flowmetric/events.json`
- IntelliJ plugin save listener and tracked-root selection action
- Compose Desktop analytics dashboard with filters, trends, file review, and estimated contribution cards
- repo `AGENTS.md`

Deferred for later:
- true revert/undo
- deeper editor telemetry beyond save-time deltas
- SQLite persistence
- richer charts and session diff inspection

## Known limitation in this workspace

The local `gradle` binary currently fails to initialize its native platform library on this machine, so build verification may require fixing the local Gradle installation or adding a working wrapper jar before commands like `./gradlew` can run successfully.
