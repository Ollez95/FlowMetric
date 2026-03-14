# BEST_PRACTICES.md

## Best Practices for a Kotlin Desktop App + IntelliJ/Android Studio Plugin

This document defines practical best practices for a project composed of:

- a **desktop application**
- an **Android Studio / IntelliJ plugin**
- a **shared Kotlin core module**

This is **not a mobile app**.  
The architecture and recommendations below are optimized for:

- maintainability
- scalability
- readability
- testability
- clear module boundaries
- SOLID principles
- DRY principles
- pragmatic clean architecture

---

# 1. Core Principles

## 1.1 Prefer clarity over cleverness
- Write code that is easy to read and maintain.
- Avoid premature abstractions.
- Prefer explicit code over surprising code.

## 1.2 Apply SOLID where it adds value
- **Single Responsibility Principle**: each class, file, and component should have one clear responsibility.
- **Open/Closed Principle**: extend behavior through composition and abstractions instead of constantly modifying existing code.
- **Liskov Substitution Principle**: implementations must honor the contract of the abstraction.
- **Interface Segregation Principle**: prefer small, focused interfaces.
- **Dependency Inversion Principle**: depend on abstractions, not concrete implementations.

## 1.3 Use DRY with judgment
- Avoid real duplication.
- Do not abstract too early.
- A good rule: if something repeats 3 or more times, consider extracting it.

## 1.4 Keep it simple
- Prefer the simplest solution that solves the current need.
- Avoid overengineering.

## 1.5 You Aren’t Gonna Need It
- Do not build speculative features.
- Focus on current product scope.

---

# 2. Recommended Project Structure

Use a multi-module structure like:

- `shared-core`
- `desktop-app`
- `android-studio-plugin`

## 2.1 shared-core
Contains:
- domain models
- business rules
- heuristics
- analytics calculations
- shared services
- pure Kotlin logic
- interfaces/contracts

This module should be:
- framework-light
- UI-agnostic
- platform-agnostic where possible
- easy to test

## 2.2 desktop-app
Contains:
- desktop UI
- screens
- dashboard components
- filters
- charts
- app-specific state holders / view models
- desktop-specific integrations

Recommended technology:
- **Compose Multiplatform Desktop**

## 2.3 android-studio-plugin
Contains:
- plugin entry points
- IDE actions
- document/editor listeners
- tool windows
- plugin settings
- plugin-specific services
- integration with IntelliJ Platform APIs

Recommended technology:
- **Kotlin + IntelliJ Platform Plugin SDK**

Do not treat the plugin like a normal Compose app.

---

# 3. Architecture Rules

## 3.1 Dependency direction
- `desktop-app` depends on `shared-core`
- `android-studio-plugin` depends on `shared-core`
- `shared-core` depends on neither of them

## 3.2 Keep shared-core pure
Do not place these in `shared-core` unless truly necessary:
- IntelliJ-specific APIs
- Compose UI code
- desktop-specific filesystem behavior
- plugin-specific state or UI logic

## 3.3 Share logic, not framework assumptions
Share:
- scoring logic
- analytics models
- file change models
- aggregation logic
- use cases
- repository contracts

Do not share:
- IDE-specific event wiring
- UI widgets
- plugin actions
- tool window implementations

---

# 4. Best Practices for shared-core

## 4.1 shared-core should be pure and testable
This module should contain:
- domain models
- rules
- calculations
- use cases
- service interfaces
- pure functions where possible

## 4.2 Keep business logic out of UI modules
Neither the plugin nor the desktop app should contain core business logic if it can live in `shared-core`.

## 4.3 Use explicit models
Prefer explicit models for:
- file changes
- sessions
- confidence scores
- classifications
- analytics summaries

Example concepts:
- `TrackedChange`
- `ChangeSession`
- `OriginEstimate`
- `ConfidenceLevel`
- `ProjectAnalyticsSummary`

## 4.4 Define contracts in shared-core
Repository interfaces and service contracts should live in `shared-core`.
Implementations can live in the desktop or plugin modules.

---

# 5. Best Practices for the Desktop App

## 5.1 Compose Desktop is a good fit
Use Compose Multiplatform Desktop for:
- dashboards
- settings screens
- filters
- charts
- project summary views
- history views

## 5.2 Separate screen state from reusable UI
Organize UI into:
- screen-level composables
- content composables
- reusable components

Example:
- `DashboardScreen`
- `DashboardContent`
- `SummaryCard`
- `ChangedFilesTable`

## 5.3 Keep composables focused
A composable should do one thing well.
Avoid giant composables with too many responsibilities.

## 5.4 State hoisting
Prefer stateless reusable components.
Pass state in and callbacks out.

## 5.5 No business logic in composables
Composables should not:
- calculate analytics
- classify AI vs human changes
- access persistence directly
- query repositories directly unless explicitly scoped and simple

Put those responsibilities in:
- view models
- presenters
- state holders
- use cases

## 5.6 Avoid unnecessary recomposition
- use `remember` correctly
- use `derivedStateOf` when useful
- avoid expensive calculations inside composables
- keep parameters stable when possible

## 5.7 Design a small design system
Create reusable UI primitives:
- cards
- tables
- chips / tags
- filter bars
- dialogs
- spacing tokens
- typography tokens

---

# 6. Best Practices for the IntelliJ / Android Studio Plugin

## 6.1 Treat the plugin as a plugin, not as a generic app
Follow IntelliJ Platform conventions:
- use services where appropriate
- use tool windows for analytics UI inside the IDE
- use actions for commands
- use listeners for editor/document events

## 6.2 Keep plugin code thin
The plugin should mainly:
- listen to IDE events
- adapt them into shared-core models
- persist or forward tracked data
- render plugin-specific UI

The plugin should not contain heavy business logic if it can live in `shared-core`.

## 6.3 Separate IDE integration from domain logic
Good split:
- plugin module: `DocumentListener`, project hooks, tool windows
- shared-core: classification, heuristics, analytics aggregation

## 6.4 Use plugin services carefully
Use services for:
- lifecycle-managed plugin behavior
- event tracking coordination
- settings access
- persistence coordination

Do not create unnecessary global mutable state.

## 6.5 Keep plugin UI pragmatic
For the plugin UI:
- keep it simple
- prefer maintainable plugin-native patterns
- avoid overbuilding the IDE UI early

A desktop dashboard can carry richer analytics views.

## 6.6 Do not block the IDE
Any expensive processing should be:
- asynchronous when appropriate
- batched
- moved out of UI-sensitive code paths

Performance matters a lot inside the IDE.

---

# 7. State Management

## 7.1 Model state explicitly
Prefer:
- `data class`
- `sealed interface`
- `sealed class`

Avoid vague combinations of booleans.

## 7.2 Separate state from events
Keep these distinct:
- persistent UI state
- user actions
- one-off events
- background processing results

## 7.3 Make analytics state explicit
Examples:
- selected project
- time range filter
- changed files list
- AI estimate summary
- selected file details
- confidence filter

---

# 8. Domain Modeling

## 8.1 Prefer domain-specific names
Use names that reflect the product language.

Examples:
- `TrackedProject`
- `FileChangeRecord`
- `ChangeMechanism`
- `OriginEstimate`
- `ConfidenceScore`
- `AnalyticsSnapshot`

## 8.2 Separate concepts clearly
Do not mix:
- origin estimate
- change mechanism
- file stats
- project totals

For example:
- `OriginEstimate` = likely AI / likely human / uncertain
- `ChangeMechanism` = typed / pasted / restored from git / unknown

## 8.3 Keep enums and sealed types meaningful
Avoid vague flags like:
- `isAi`
- `isHuman`

Prefer expressive types.

---

# 9. Persistence Best Practices

## 9.1 Start simple
For MVP, use:
- local JSON
- SQLite
- lightweight local persistence

## 9.2 Keep storage behind interfaces
Do not let UI code know persistence details.

Use contracts like:
- `TrackedChangeRepository`
- `ProjectAnalyticsRepository`

## 9.3 Persist raw events and derived summaries separately
If possible:
- raw events should remain inspectable
- summaries should be recomputable

That makes heuristics easier to evolve.

---

# 10. Error Handling

## 10.1 Handle errors explicitly
Do not silently swallow failures.

Prefer:
- `Result`
- sealed result models
- domain-specific error types

## 10.2 Fail safely inside the plugin
The plugin must not destabilize the IDE.
If tracking fails:
- log safely
- recover gracefully
- avoid crashing user workflows

---

# 11. Testing Strategy

## 11.1 shared-core gets the most tests
Highest priority:
1. heuristics
2. scoring
3. aggregations
4. use cases
5. mappers

## 11.2 Test plugin adapters selectively
Test:
- event adaptation logic
- persistence wiring
- core plugin services where practical

Do not overtest framework glue unless it is critical.

## 11.3 Test desktop UI where it adds value
Focus on:
- state transformation
- screen logic
- important rendering states

## 11.4 Use fakes when possible
Prefer simple fakes over excessive mocking.

---

# 12. Performance Guidelines

## 12.1 Keep analytics incremental
Do not recompute everything on every small change if avoidable.

## 12.2 Avoid heavy work on the UI thread
This is especially important in the plugin.

## 12.3 Batch file-change processing when useful
If many file events arrive together, group them.

## 12.4 Measure before optimizing
Optimize proven bottlenecks, not imaginary ones.

---

# 13. UI and UX Guidelines

## 13.1 Be honest in wording
Never present AI attribution as exact truth.

Prefer wording like:
- estimated AI-assisted lines
- likely AI-generated changes
- confidence level
- uncertain classification

## 13.2 Expose uncertainty clearly
Where appropriate, show:
- high confidence
- medium confidence
- low confidence

## 13.3 Separate summary and inspection views
A good product flow:
- project overview
- summary metrics
- changed files list
- file/session details
- classification explanation

## 13.4 Keep the first version simple
Do not overload the UI with every possible metric.

---

# 14. Git / History Considerations

## 14.1 Do not confuse restoration with origin
Git operations such as:
- stash apply/pop
- revert
- restore
- checkout
- reset

should usually be treated as:
- restoration / replay activity

not automatic AI generation.

## 14.2 Model origin separately from mechanism
Recommended:
- `OriginEstimate`
- `ChangeMechanism`

This keeps analytics more honest.

---

# 15. Naming Guidelines

## 15.1 Use descriptive names
Prefer:
- `TrackProjectChangesUseCase`
- `CalculateAiContributionUseCase`
- `FlowMetricDashboardState`

Avoid:
- `Manager`
- `Helper`
- `Util`
- `DataThing`
- `Stuff`

## 15.2 Avoid generic wrappers
Only introduce wrappers when they add clear value.

---

# 16. Code Review Checklist

Before merging, ask:

- Is responsibility clear?
- Is business logic in the right module?
- Is any duplication worth extracting?
- Is naming clear and domain-driven?
- Is the code easy to test?
- Is UI separated from logic?
- Is shared-core still framework-light?
- Is the plugin code lightweight and safe?
- Is the desktop UI simple and maintainable?
- Are we being honest about AI estimation?

---

# 17. Anti-Patterns to Avoid

- giant composables
- giant plugin services
- domain logic inside UI
- IntelliJ APIs leaking into shared-core
- Compose assumptions leaking into plugin code
- hardcoded magic values
- overuse of booleans for complex state
- hidden global mutable state
- premature abstractions
- forcing a single UI paradigm across unrelated modules

---

# 18. Final Rule

The best practice is not “use more patterns.”

The best practice is:
- keep responsibilities clear
- keep modules decoupled
- share only what should be shared
- keep business logic testable
- keep UI honest and maintainable
- let the desktop app and plugin each use the approach that fits them best