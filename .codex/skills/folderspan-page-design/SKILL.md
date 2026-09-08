---
name: folderspan-page-design
description: Design, redesign, implement, or review FolderSpan Compose Multiplatform pages using the repository's page guidelines, reference page, responsive window utilities, and shared page-state components. Use for shared page or screen layout, responsive panes, loading/error/empty/content states, and page-level component reuse in FileManager; do not use for unrelated non-UI work or an isolated primitive component that does not affect a page.
---

# FolderSpan Page Design

Build FolderSpan pages from the bundled design guideline and the repository's live reference implementation and components. The bundled guideline is the normative design source for this skill.

## Source routing

Resolve paths from the FileManager repository root and follow the nearest `AGENTS.md` files before editing.

1. For every page design, redesign, implementation, or review, read [references/PAGE_DESIGN_GUIDELINES.md](references/PAGE_DESIGN_GUIDELINES.md) completely before deciding the layout.
2. For a new page, a material page restructure, responsive pane work, or page-state work, inspect `app/shared/src/commonMain/kotlin/com/folderspan/ui/screen/design/PageDesignReference.kt`. Treat it as an architectural example, not a business-specific template to copy verbatim.
3. Before creating loading, error, empty, refresh, pagination, or generic status UI, search `core/src/commonMain/kotlin/com/folderspan/ui/components` with the repository code graph. Read the exact selected component source before using or changing its API.
4. Inspect the target page, its UI state, and its existing callers. Preserve the target feature's behavior and reuse its state containers instead of introducing page-local business logic.

If the reference page and the bundled guideline differ, follow the bundled guideline. Explicit user requirements and repository instructions remain controlling.

## Shared component map

Use this map only for discovery; verify current signatures in source:

- `pagestate/PageState.kt`: `PageViewState`, refresh/append states, `resolvePageViewState`, throwable mapping, and load-more threshold logic.
- `pagestate/PageStateLayout.kt`: mutually exclusive loading/error/empty/content rendering, retry, refresh, and default error presentation.
- `pagestate/PageAppend.kt`: append footer, near-end loading, and lazy-list append helpers.
- `loading/Loading.kt`: standard loading presentation.
- `error/Error.kt`: standard empty, connection, blocked, and general error presentations.
- `status/StatusWidget.kt`: generic centered status presentation with accessibility announcements and an optional action.

Prefer these shared components when their semantics fit. Extend a shared API only when multiple consumers benefit; otherwise compose its slots at the page level.

## Page decisions

- Keep shared page UI in `commonMain` and use Material 3 only.
- Use `MaterialTheme.colorScheme`, `typography`, and `shapes`, plus Material 3 component defaults. Use explicit Compose `Dp` values where page layout needs them; do not reintroduce a custom dimensions `CompositionLocal` or page-private token object.
- Derive responsive structure from `WindowSizeClass`, `WindowPaneMode`, and normalized window capabilities. Do not branch on platform name, device model, browser identity, or orientation booleans.
- Make the Scaffold content, page body, and panes consume all available space. Pass Scaffold content padding through once, and never add top padding to the body itself.
- Use a flat page structure by default. Do not wrap the body, panes, continuous lists, or metadata rows in `Card` or rounded tonal `Surface`; use typography, spacing, and a restrained number of `HorizontalDivider` or `VerticalDivider` elements where separation is needed.
- Connect state only at the page entry. Pass immutable data and callbacks to widgets; do not inject a ViewModel or call services from a page-region widget.
- Preserve accessibility semantics, stable lazy-list keys, scrolling in short windows, and keyboard, pointer, and touch usability.
- Add a third pane only when it has real supporting content and navigation behavior; declaring `WindowPaneMode.ThreePane` alone is not a reason to render an empty pane.

## Implementation workflow

1. Identify the page's state priority, primary actions, list/detail/supporting regions, and window behavior.
2. Search both repository component locations before creating a new widget:
   - `core/src/commonMain/kotlin/com/folderspan/ui/components`
   - `app/shared/src/commonMain/kotlin/com/folderspan/ui/components`
3. Separate the page entry, template, page-region widgets, composed widgets, and basic widgets only as far as reuse and readability justify.
4. Implement the smallest coherent change while preserving unrelated worktree changes.
5. Add or update previews for the relevant width classes and a short landscape window. Add `commonTest` coverage for layout selection and state priority when those behaviors change.
6. Run focused tests first, then the validation required by the nearest repository instructions. If browser tests require unavailable Chrome, distinguish that environment failure from compilation or unit-test failures.
7. When a Markdown file changes, update `md_descriptions_paths.md` in the repository's existing format.

## Review checklist

When reviewing rather than editing, report concrete violations against the live guideline and cite local file locations. Check at minimum:

- shared component reuse and page-state handling;
- full-space body and pane layout with no duplicated top padding;
- flat hierarchy without default card styling;
- Material theme tokens and responsive window utilities;
- state hoisting, callback boundaries, accessibility, scrolling, and preview/test coverage.
