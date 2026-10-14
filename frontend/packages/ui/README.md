# @jabiz/ui

The components of jabiz frontends (decision D34): shadcn/ui's components, the platform's composite components
(`DataTable`, `ConfirmDialog`, `notify`, `DatePicker` / `DateTimePicker` (typeable), `DecimalInput` / `MoneyInput`,
`ThemeToggle`, `PageHeader`, `AppShell` / `ShellNav`, `Combobox`, `FilterBar`, `FileUpload`, `Timeline`,
`DescriptionList`, `TagsInput`, `CopyButton`, `PageState`, `Spinner`, `Steps`, `SegmentedControl`), the content views
(`MarkdownView`, which never renders raw HTML, and `FilePreview` with `useFileUrl` / `downloadFile`, which fetch files
with the session; phase 16n), react-hook-form's form state re-exported
(`useForm`, `useFieldArray`, `useWatch`, `Controller`; `src/form-state.ts`), the sign-in and account building blocks
(`src/auth/`: `AuthFrame`, `SignInForm`, `SignUpForm`, `MailLinkPage`, `StepUpProvider`, `ChangePasswordForm`, … —
decision D43; they know no router and take navigation as callbacks) and the theme (`@jabiz/ui/theme.css`). It builds on
`@jabiz/client` and is used by the admin frontend, its extensions (through `@jabiz/admin`) and applications' own
single-page applications.

## Rules

- **One copy of every shadcn component, here.** Applications and extensions never generate or copy shadcn
  components; a component they need is added to this package first.
- **Tokens only.** Components use Tailwind utilities and the theme's tokens (`bg-primary`, `text-muted-foreground`,
  …), never fixed colours. `src/styles/theme.test.ts` checks the tokens' contrast (WCAG 2.2 AA) in both appearances.
- **Texts** go through i18next, namespace `ui` (`src/i18n.ts`) and, for the building blocks of `src/auth/`, `auth`
  (`src/auth/messages.ts`), in every platform language.
- **Accessibility**: every composite component has a Vitest test with `expectAccessible` (axe, light and dark).

## The shadcn sources and updating them

`src/components/ui/*` are shadcn/ui's "new-york" components for Tailwind CSS v4 and React 19, with these changes:

| Change | Where |
| --- | --- |
| Imports are relative (`../../lib/utils`), not `@/…` | all |
| Collapsed with `offcanvas`, the sidebar container is `inert` | sidebar |
| Fixed texts ("Close", "Toggle sidebar", "Previous page", …) come from the `ui` namespace | dialog, sheet, sidebar, pagination, breadcrumb |
| The overlay is the `overlay` token, not `bg-black/50`; the destructive button's text is `destructive-foreground` | dialog, sheet, alert dialog, button, badge |
| `Button` defaults to `type="button"` | button |
| `DropdownMenu` is not modal by default (a modal menu leaves the page `aria-hidden` but focusable: axe `aria-hidden-focus`) | dropdown-menu |
| The sidebar remembers its state in `localStorage` (guarded), not a cookie; its rail is hidden from assistive technology (the header's trigger does the same) | sidebar |
| Days of neighbouring months keep 4.5:1 text contrast | calendar |
| `success` and `warning` variants | badge, alert |
| Tooltip and legend payload types for Recharts 3 | chart |

The first versions were written by hand from the shadcn registry's sources (the registry was not reachable from the
build environment). To take a component or an update from upstream, run the CLI **in this directory only**
(`components.json` is here):

```sh
cd frontend/packages/ui && pnpm dlx shadcn@<version> add <component> --overwrite
```

then re-apply the changes in the table (git diff shows them), rewrite `@/` imports to relative ones, and run
`pnpm lint`, `pnpm typecheck` and `pnpm test` in `frontend/`.

## The preflight

`theme.css` imports Tailwind's preflight (its CSS reset) globally into the layer `base`, sets the page's background
and text from the tokens on `body`, and turns native controls dark under `.dark`. Until phase 15d it was scoped to
`.jabiz-ui` while Ant Design pages remained; `UI_SCOPE` is still exported (for extensions that name it) but has no
styles and is not needed. An application's own SPA gets the same reset on every element of its pages.

## Using it in an application's own SPA

The SPA depends on `@jabiz/client` and `@jabiz/ui` with `link:` paths into the platform's `frontend/packages`,
imports the theme and adds this package to Tailwind's sources:

```css
@import '@jabiz/ui/theme.css';
@source '<path>/frontend/packages/ui/src';
/* brand colours: override tokens here */
:root { --primary: #…; }
```

`pnpm app:check <dir>` (in `frontend/`) verifies that the SPA's React, Tailwind, TanStack Query, i18next and this
package's runtime dependencies are the platform's versions.
