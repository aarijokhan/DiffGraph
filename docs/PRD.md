# PRD: Scope Check — AI Change Intelligence for IntelliJ

**Status:** Draft
**Author:** Aarij Khan
**Date:** 2026-04-16

---

## What This Is

An IntelliJ IDEA plugin that analyzes staged git changes using the IDE's program structure interface (PSI) and shows developers the *causal structure* of a changeset — which change triggered which, what the blast radius is, and whether the commit mixes unrelated concerns. Built in Kotlin for the IntelliJ Platform SDK, published to JetBrains Marketplace.

One sentence: **"Understand what your AI coding assistant actually changed before you commit it."**

---

## Problem

AI coding assistants (Claude Code, Cursor, Copilot, Junie) now routinely produce multi-file changesets. A single prompt can touch 5–15 files across multiple modules. Developers face two problems with these changes that existing tools don't solve:

**Problem 1: "I can't tell what the AI actually did."**
Every diff viewer (GitHub, GitKraken, IntelliJ's built-in) shows diffs as a flat alphabetical file list. For a 3-file human commit, this is fine. For an 8-file AI-generated changeset, the flat list hides the *causality* — which change drove which. The developer has to mentally reconstruct: "it changed the model first, which required updating the service, which broke the test, which needed a new fixture." This mental reconstruction takes 10–30 minutes on complex changes and is error-prone.

**Problem 2: "The AI edited files it shouldn't have."**
The most frequently reported frustration with AI coding tools. The developer asks the AI to fix one thing; it touches five unrelated files. Without understanding the dependency graph, the developer can't quickly distinguish "this file was touched because it imports the changed function" (legitimate) from "this file was touched because the AI went on a tangent" (scope creep).

**How developers solve this today:**
- Manually reading each diff file-by-file and mentally tracing cross-file references
- Running `git diff --stat` and guessing which files are related
- Committing before every AI interaction and reverting when things go wrong
- Splitting AI tasks into artificially small scopes to limit blast radius

All of these are workarounds. None show the developer the dependency structure of the change.

**Evidence this is a real problem:**
- The 2025 Stack Overflow Developer Survey found developer trust in AI accuracy dropped from 43% to 33%, primarily due to context and control issues
- Cursor forums have multiple threads about AI "editing the wrong part of the code, removing huge chunks without being asked, or inventing new files"
- The r/ContextEngineering subreddit identifies "architecture amnesia" and "inconsistent patterns" as the top two AI coding failures
- Ars Technica reported two catastrophic incidents (Gemini CLI, Replit) where AI tools made destructive changes the developer didn't understand in time

---

## Users

**Primary:** Developers who use AI coding assistants (Claude Code, Cursor, Copilot, Junie) in IntelliJ-based IDEs and regularly commit multi-file AI-generated changes.

**Secondary:** Any developer working on a team where understanding the structure of a commit matters — code reviewers, tech leads reviewing PRs, developers onboarding to an unfamiliar codebase.

**Not targeting:** Developers who only use AI for single-file edits or inline completions. The plugin's value scales with changeset complexity.

---

## Goals (v1)

1. **Dependency-ordered change view.** When the developer triggers the plugin (keyboard shortcut or pre-commit), show staged changes ordered by dependency — not alphabetically. If a function signature changed in `Engine.kt` and three callers were updated in response, the view shows `Engine.kt` first with the callers grouped below it. The ordering uses IntelliJ's PSI reference resolution to determine which changes are upstream vs downstream.

2. **Scope violation detection.** Analyze whether the staged changes touch multiple unrelated modules. "Unrelated" means: no shared dependency path between the changed files within N hops in the project's import/reference graph. When detected, surface a clear warning: "This commit touches [auth] and [payments] which share no dependencies. Consider splitting."

3. **Blast radius quantification.** For each changed function/class/interface, count how many other locations in the project reference it. Display as: "This function is called by 23 locations across 8 files." Sorted by blast radius descending so the highest-impact changes are visible first.

4. **Orphaned change detection.** When a source file is modified but its corresponding test file is not, flag it: "`UserService.kt` was modified but `UserServiceTest.kt` has no corresponding changes." Uses naming conventions and directory structure to infer test-source pairings.

5. **Tool window UI.** All of the above displayed in a dedicated IntelliJ tool window panel. The panel shows a tree/graph of changes with expandable nodes, inline diff snippets, and color-coded severity indicators (info, warning, critical).

---

## Non-Goals

- **Not a linter or static analysis tool.** Does not check code style, formatting, or correctness. That is the job of detekt, ktlint, ESLint, etc. Scope Check analyzes the *structure* of changes, not the *content*.
- **Not an AI suggestion interceptor.** Does not hook into completion providers or inline suggestion APIs. It operates on staged git changes — after the AI (or the human) has already written the code.
- **Not a code review bot.** Does not produce "review comments" or suggest fixes. It provides structural intelligence that helps the developer make their own review decisions.
- **No LLM dependency.** The plugin is purely deterministic. No API keys, no network calls, no cost per use, no latency from model inference. All analysis uses IntelliJ's built-in PSI and reference resolution.
- **No git history analysis.** v1 analyzes only the current staged diff. Historical trend analysis ("your AI commits have been getting larger") is a v2 feature.
- **No multi-IDE support.** v1 targets IntelliJ IDEA (and by extension all JetBrains IDEs that share the IntelliJ Platform: PyCharm, WebStorm, GoLand, etc.). No VS Code, no Vim.
- **No language-specific logic.** The plugin relies on IntelliJ's PSI, which handles language-specific parsing internally. The plugin itself does not contain Java-specific or Kotlin-specific rules. It works for any language IntelliJ can parse.
- **No custom rule engine.** v1 does not support user-defined scope rules ("module A should never depend on module B"). That is a v2 feature.

---

## Success Criteria

1. A developer with 8 staged files from an AI coding session can, within 10 seconds of triggering the plugin, see which file change caused which other file changes.
2. The plugin correctly identifies scope violations (unrelated modules in the same commit) in at least 80% of test cases drawn from real multi-file AI commits.
3. Published to JetBrains Marketplace with a README, screenshots, and source code on GitHub.
4. At least one real-world demo: the developer's own AI-assisted project (FPL Transfer Strategist) used as the test case, with screenshots showing the plugin analyzing a real Phase 5 or Phase 6 commit.

---

## Proposed Design

### Core Architecture

```
┌─────────────────────────────────────────────┐
│              Scope Check Plugin              │
├─────────────────────────────────────────────┤
│                                             │
│  ┌─────────────┐    ┌──────────────────┐    │
│  │  Git Diff    │───▶│  Change Parser   │    │
│  │  Provider    │    │  (per-file AST   │    │
│  └─────────────┘    │   extraction)    │    │
│                      └──────┬───────────┘    │
│                             │                │
│                      ┌──────▼───────────┐    │
│                      │  Dependency      │    │
│                      │  Graph Builder   │    │
│                      │  (PSI references)│    │
│                      └──────┬───────────┘    │
│                             │                │
│          ┌──────────────────┼────────────┐   │
│          │                  │            │   │
│   ┌──────▼──────┐  ┌───────▼─────┐ ┌────▼─┐│
│   │ Scope       │  │ Blast       │ │Orphan││
│   │ Analyzer    │  │ Radius      │ │Detect││
│   │             │  │ Calculator  │ │      ││
│   └──────┬──────┘  └───────┬─────┘ └────┬─┘│
│          │                 │            │   │
│          └─────────────────┼────────────┘   │
│                            │                │
│                     ┌──────▼───────────┐    │
│                     │   Tool Window    │    │
│                     │   (UI renderer)  │    │
│                     └──────────────────┘    │
│                                             │
└─────────────────────────────────────────────┘
```

### Component Breakdown

**1. Git Diff Provider**
- Reads staged changes from git (`git diff --cached --name-status` and `git diff --cached`)
- Produces a list of changed files with their diff hunks
- Uses IntelliJ's built-in git integration (`GitRepositoryManager`, `GitChangeProvider`)
- Input: none (reads from the current project's git state)
- Output: `List<ChangedFile>` where each contains filepath, change type (add/modify/delete), and diff hunks

**2. Change Parser**
- For each changed file, uses IntelliJ PSI to parse the AST
- Identifies which specific symbols (functions, classes, interfaces, properties) were added, modified, or removed
- Compares pre-change and post-change PSI trees using the diff hunks to narrow scope
- Input: `List<ChangedFile>`
- Output: `List<ChangedSymbol>` where each contains the symbol name, kind (function/class/etc), containing file, and change type

**3. Dependency Graph Builder**
- For each `ChangedSymbol`, uses IntelliJ's `ReferencesSearch` to find all usages across the project
- Builds a directed graph: `ChangedSymbol → [files/symbols that reference it]`
- Identifies which other changed files are *downstream* (they changed because this symbol changed) vs *independent* (they changed for unrelated reasons)
- The heuristic for "downstream": file B is downstream of file A's change if file B contains a reference to a symbol that was modified in file A
- Input: `List<ChangedSymbol>`
- Output: `DependencyGraph` (directed acyclic graph of change causality)

**4. Scope Analyzer**
- Takes the dependency graph and identifies connected components
- If there are multiple disconnected components (groups of files with no shared dependency path), flags a scope violation
- Labels each component with its "module" based on the top-level directory or package
- Input: `DependencyGraph`
- Output: `ScopeReport` containing component count, component labels, and a boolean `hasScopeViolation`

**5. Blast Radius Calculator**
- For each `ChangedSymbol`, counts total usages across the *entire* project (not just changed files)
- Distinguishes between "usages in changed files" (already updated) and "usages in unchanged files" (potentially affected but not updated — higher risk)
- Input: `List<ChangedSymbol>`, `DependencyGraph`
- Output: `List<BlastRadiusEntry>` sorted descending by total usage count

**6. Orphan Detector**
- For each changed source file, looks for a corresponding test file using configurable naming conventions:
    - `Foo.kt` → `FooTest.kt` or `Foo_test.go` or `test_foo.py` etc.
    - Checks both same-directory and `test/` mirror directory
- If the source file was modified but the test file was not (and the test file exists), flags it as orphaned
- If no test file exists at all, flags as "untested" (lower severity)
- Input: `List<ChangedFile>`
- Output: `List<OrphanEntry>` with severity (orphaned vs untested)

**7. Tool Window UI**
- Renders a tree view in IntelliJ's tool window panel
- Root nodes are the dependency graph's connected components (labeled by module/package)
- Under each component: the "root cause" change first (the upstream symbol change), then downstream changes indented below
- Each node shows: filename, changed symbol name, change type icon, blast radius badge
- Scope violation warning banner at the top if multiple components detected
- Orphaned file warnings as inline annotations
- Color coding: green (low risk), yellow (moderate blast radius or orphaned test), red (scope violation or high blast radius with unchanged callers)
- Expandable inline diff snippet per node (clicking a node shows the relevant diff hunk without leaving the panel)

### Interaction Model

**Trigger:** keyboard shortcut (default: `Cmd+Shift+S` / `Ctrl+Shift+S`) or a button in the commit dialog. The plugin runs on the current staged changes.

**Timing:** analysis runs in the background on a separate thread. For a typical 5-10 file changeset, PSI traversal and reference search should complete in 1-3 seconds. A progress indicator shows while analysis runs.

**Integration with commit workflow:** optionally, the plugin can register as a pre-commit check. If scope violations are detected, it shows a confirmation dialog: "This commit touches unrelated modules. Commit anyway?" This is opt-in via plugin settings.

---

## Edge Cases

- **No staged changes:** show an empty state message: "No staged changes. Stage files with `git add` first."
- **Binary files in the diff:** skip them (no PSI available). Show them as "binary file changed" in the tree with no analysis.
- **Renamed/moved files:** treat as a delete + add. The dependency graph builder should detect that references to the old path now point to the new path.
- **Very large changesets (50+ files):** cap analysis at a configurable limit (default: 30 files). Show a warning: "Changeset exceeds analysis limit. Showing the 30 highest-impact changes."
- **Files outside the project root:** skip (e.g., submodule changes, symlinked files outside the project).
- **No git repository:** show an error state: "Scope Check requires a git repository."
- **Language without PSI support:** files in unsupported languages are included in the tree but without dependency analysis. Marked as "no PSI available."

---

## Test Plan

### Unit Tests
- Dependency graph builder correctly identifies A → B → C chains
- Scope analyzer correctly detects 2 disconnected components as a scope violation
- Scope analyzer correctly identifies 1 connected component as no violation
- Blast radius calculator counts usages in changed vs unchanged files separately
- Orphan detector matches `Foo.kt` to `FooTest.kt` across naming conventions
- Orphan detector handles missing test files (flags as "untested", not "orphaned")
- Change parser correctly identifies added/modified/deleted symbols from diff hunks

### Integration Tests
- End-to-end: stage 5 files in a test project, trigger analysis, verify the tool window renders the correct tree structure
- End-to-end: stage files touching 2 unrelated modules, verify scope violation is flagged
- End-to-end: modify a source file without touching its test, verify orphan detection fires

### Manual Acceptance Tests
- Run on the developer's own FPL Transfer Strategist repo, analyzing a real Phase 5 commit
- Run on a medium-sized open-source Kotlin project (e.g., ktor or a JetBrains plugin sample)
- Verify performance: analysis completes in under 3 seconds for a 10-file changeset

---

## Constraints

- **Timeline:** 2–3 weeks of part-time effort (side project alongside main portfolio work)
- **Platform:** IntelliJ Platform SDK 2024.1+ (covers IntelliJ IDEA, PyCharm, WebStorm, GoLand, etc.)
- **Language:** Kotlin (JetBrains' standard for plugin development)
- **Build system:** Gradle with the `intellij` plugin (standard IntelliJ plugin build setup)
- **No external dependencies:** no network calls, no API keys, no LLM. Purely deterministic analysis using IDE-native APIs.
- **JetBrains Marketplace:** must meet Marketplace submission requirements (plugin.xml, description, screenshots, compatibility range)
- **Cost:** $0 to build, $0 to use. No infrastructure. No ongoing costs.

---

## Assumptions

- IntelliJ's `ReferencesSearch` API is performant enough for real-time analysis of changesets up to 30 files (believed to be true based on IntelliJ's own "Find Usages" feature using the same API)
- The PSI tree for changed files can be obtained from the working copy, not just the committed version (believed to be true — PSI operates on the editor state)
- JetBrains Marketplace approval takes 2-5 business days for first submissions
- The developer (Aarij) has basic Kotlin proficiency or can acquire it within the first 2-3 days of plugin development

---

## Out of Scope (For Now)

- **v2: Custom scope rules.** Let users define module boundaries ("src/auth/ should never depend on src/payments/") and flag violations. Currently inferred from the dependency graph.
- **v2: Historical trend analysis.** "Your AI-generated commits have averaged 12 files over the last week, up from 5 last month."
- **v2: PR integration.** Show Scope Check analysis as a comment on GitHub/GitLab PRs.
- **v2: Suggested commit splits.** "This commit has 2 disconnected components. Here's how to split it into 2 focused commits."
- **v2: AI tool attribution.** Detect which changes came from which AI tool (requires hooking into completion events, which is fragile).
