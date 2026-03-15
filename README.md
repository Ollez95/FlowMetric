# FlowMetric

FlowMetric is a local-only developer analytics MVP for a single selected project. It estimates whether saved code changes were likely AI-assisted or likely non-AI using edit-pattern heuristics. It does not attempt exact attribution.

FlowMetric can also record explicit `AI_PATCH` events when an agent is instructed to report its own edits. Those events are more precise than heuristics because they carry the exact before/after patch for the edited file.

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

### Exact Codex patch recording
If you want FlowMetric to know that Codex changed exact lines, use the local recorder after a Codex edit:

```bash
scripts/record_codex_edit.sh <project-root> <absolute-file-path> <before-snapshot-file>
```

You can still pass the model explicitly on each command if you want it stored on the event without relying on one global default:

```bash
scripts/record_codex_edit.sh <project-root> <absolute-file-path> <before-snapshot-file> Codex gpt-5-codex
```

If you want one command for multiple edited files, create a tab-separated manifest where each line is:

```text
<absolute-file-path>\t<before-snapshot-file>
```

Then run:

```bash
scripts/record_codex_batch.sh <project-root> <manifest-file>
```

If you want approval reuse in Codex, do not combine `mktemp`, `printf`, and the recorder call into one shell one-liner. Create the manifest first, then run `scripts/record_codex_batch.sh` directly.

For a faster setup in another local project, use the desktop app's `Install tracking` button after selecting that project. FlowMetric will install a small proxy script plus an `AGENTS.md` snippet into the target repo.

Example:

```bash
tmp_before="$(mktemp)"
cp /Users/gustavolorena/IdeaProjects/FlowMetric/shared-core/src/main/kotlin/com/flowmetric/shared/model/Models.kt "$tmp_before"
# let Codex edit the file
scripts/record_codex_edit.sh \
  /Users/gustavolorena/IdeaProjects/FlowMetric \
  /Users/gustavolorena/IdeaProjects/FlowMetric/shared-core/src/main/kotlin/com/flowmetric/shared/model/Models.kt \
  "$tmp_before"
```

Batch example:

```bash
manifest="$(mktemp)"
printf '%s\t%s\n' \
  /Users/gustavolorena/IdeaProjects/FlowMetric/shared-core/src/main/kotlin/com/flowmetric/shared/model/Models.kt \
  "$tmp_before" > "$manifest"
scripts/record_codex_batch.sh /Users/gustavolorena/IdeaProjects/FlowMetric "$manifest"
```

That appends an `AI_PATCH` event to `.flowmetric/events.json` using the exact diff between the saved snapshot and the current file on disk.

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
