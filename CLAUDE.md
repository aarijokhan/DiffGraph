# DiffGraph: AI Context

## Project Context
DiffGraph is an IntelliJ IDEA plugin written in Kotlin that analyzes staged git changes using PSI and shows developers the causal dependency structure of a changeset. It detects scope violations, quantifies blast radius, and finds orphaned test files.

## Tech Stack
- **Language**: Kotlin 1.9.25
- **Platform**: IntelliJ Platform SDK 2024.2.5
- **Build**: IntelliJ Platform Gradle Plugin 2.3.0
- **JDK**: 21
- **Gradle**: 8.12.1
- **Critical dependency**: `bundledPlugin("com.intellij.java")` required for PSI reference resolution

## Commands
- `./gradlew build` — compile, run tests
- `./gradlew runIde` — launch sandbox IDE with plugin installed
- `./gradlew test` — run tests only
- `./gradlew verifyPlugin` — marketplace compatibility check
- `./gradlew buildPlugin` — produce distributable zip in build/distributions/

## Project Structure
```
src/main/kotlin/com/aarijokhan/diffgraph/
  actions/             — IDE action entry points (keyboard shortcuts, menu items)
  analysis/            — core analysis logic (pure, no UI, no side effects) [to create]
  model/               — data classes [to create]
  ui/                  — tool window rendering [to create]
src/main/resources/META-INF/
  plugin.xml          — plugin configuration, action registration, tool window registration
src/test/kotlin/      — tests
```

## Conventions
- **Analysis logic** in `analysis/` must be pure: takes data in, returns data out. No UI, no side effects. Unit-testable without starting IDE.
- **UI code** in `ui/` only reads from model objects. No analysis logic in UI classes.
- **PSI access** must happen inside `ReadAction.compute {}` or `runReadAction {}` from background threads.
- **Long operations** use `ApplicationManager.getApplication().executeOnPooledThread {}` for >100ms tasks.
- **Actions** should be thin — get data from IDE, delegate to analysis, pass results to UI.
- **Data classes** in `model/` are plain Kotlin data classes, not tied to IntelliJ API.

## Gotchas
- `ChangeListManager.allChanges` returns all uncommitted changes, not just staged. For staged-only, use `GitRepositoryManager` and compare HEAD against index.
- `ReferencesSearch.search()` must run inside read action. Can be slow on large projects — cap analysis at 30 changed files.
- `plugin.xml` must declare all actions, tool windows, extensions. Forgetting registration is the #1 cause of "my code exists but nothing happens."
- Sandbox IDE (./gradlew runIde) is a separate IntelliJ instance. Changes inside sandbox don't affect real projects.
- PSI element references become invalid after PSI tree modifications. Don't cache PsiElements across user actions.
- Kotlin PSI classes (KtNamedFunction, KtClass, etc.) require `bundledPlugin("org.jetbrains.kotlin")` for direct use. For now, `com.intellij.java` covers Java and general PSI.

## Workflow Rules
- Run `./gradlew build` before marking any task done.
- Write tests for all analysis logic. UI and actions tested manually via runIde.
- Commit after each completed sub-task, not at end of session.
- When modifying plugin.xml, always verify registration by running runIde and checking Tools menu.

## Key APIs
```kotlin
ChangeListManager.getInstance(project).allChanges
GitRepositoryManager.getInstance(project).repositories
ReferencesSearch.search(element).findAll()
PsiManager.getInstance(project).findFile(virtualFile)
PsiTreeUtil.getParentOfType(element, PsiNamedElement::class.java)
Messages.showInfoMessage(project, message, title)
ToolWindowManager.getInstance(project)
ApplicationManager.getApplication().runReadAction<T> {}
ApplicationManager.getApplication().executeOnPooledThread { }
```
