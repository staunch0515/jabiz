import { useEffect, type FormEvent, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { SearchIcon } from 'lucide-react'

import { UI_NAMESPACE } from '../i18n'
import { cn } from '../lib/utils'
import { PageHeader, type PageHeaderProps } from './page-header'
import { SegmentedControl, type SegmentedOption } from './segmented-control'
import { Button } from './ui/button'
import { Input } from './ui/input'

/*
 * The three page templates of phase 17zj (Q108 section 3): a list, a detail with its status beside it, and a form
 * with a save bar. Each is a {@link PageHeader} and a body, centred at most as wide as the theme's content tokens
 * (`--content-max`, `--content-form-max`), so that on a wide screen nothing drifts away from the title.
 */

/** The page's head: everything {@link PageHeader} takes. */
type HeaderProps = Omit<PageHeaderProps, 'className'>

/** Centres a page's content at most as wide as `--content-max` (or `--content-form-max` for a form). */
export function PageContent({ form = false, className, children }: {
  form?: boolean
  className?: string
  children: ReactNode
}) {
  return (
    <div
      data-slot="page-content"
      className={cn('mx-auto w-full', form ? 'max-w-(--content-form-max)' : 'max-w-(--content-max)', className)}
    >
      {children}
    </div>
  )
}

/** The search box of a {@link ListPage}'s toolbar. */
export interface ListSearch {
  value: string
  onChange: (value: string) => void
  /** Its accessible name and placeholder; "Search" in the user's language when absent. */
  label?: string
}

/** The status chips of a {@link ListPage}'s toolbar: one of a few states, "all" usually first. */
export interface ListChips<V extends string = string> {
  /** What the chips choose between (their group's name). */
  label: string
  options: SegmentedOption<V>[]
  value: V
  onChange: (value: V) => void
}

export interface ListPageProps<V extends string = string> extends HeaderProps {
  search?: ListSearch
  chips?: ListChips<V>
  /** More of the toolbar, at its end (a filter button, an export). */
  toolbar?: ReactNode
  /** The list: usually a {@link DataTable}, whose own switch shows it as cards or a table (phase 17zc). */
  children: ReactNode
}

/**
 * A list page: the head, a toolbar (search, status chips, more at the end) and the list. Searching and the chips
 * only tell the application what was chosen: it reads the rows.
 */
export function ListPage<V extends string = string>({
  search,
  chips,
  toolbar,
  children,
  ...header
}: ListPageProps<V>) {
  const { t } = useTranslation(UI_NAMESPACE)
  const searchLabel = search?.label ?? t('templates.search')
  const hasToolbar = search !== undefined || chips !== undefined || toolbar !== undefined
  return (
    <PageContent>
      <PageHeader {...header} />
      {hasToolbar && (
        <div data-slot="list-toolbar" className="mb-3 flex flex-wrap items-center gap-2">
          {search && (
            <div className="relative w-full sm:w-64">
              <SearchIcon aria-hidden className="text-muted-foreground absolute top-1/2 left-2.5 size-4 -translate-y-1/2" />
              <Input
                type="search"
                className="pl-8"
                value={search.value}
                onChange={(event) => search.onChange(event.target.value)}
                aria-label={searchLabel}
                placeholder={searchLabel}
                data-testid="list-search"
              />
            </div>
          )}
          {chips && (
            <SegmentedControl
              aria-label={chips.label}
              options={chips.options}
              value={chips.value}
              onChange={chips.onChange}
              data-testid="list-chips"
            />
          )}
          {toolbar && <div className="ml-auto flex flex-wrap items-center gap-2">{toolbar}</div>}
        </div>
      )}
      {children}
    </PageContent>
  )
}

export interface DetailPageProps extends HeaderProps {
  /** The status and the next steps (a {@link Steps}, the main operations): beside the content, or before it on a phone. */
  aside: ReactNode
  /** The aside's landmark name ("Status and next steps"). */
  asideLabel: string
  children: ReactNode
}

/**
 * A detail page: the head, the content, and a column with the record's status and what to do next, fixed on the
 * right of wide screens while the content scrolls, and before the content on narrow ones (it comes first in the
 * document, so a screen reader also reaches it first).
 */
export function DetailPage({ aside, asideLabel, children, ...header }: DetailPageProps) {
  return (
    <PageContent>
      <PageHeader {...header} />
      <div className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_20rem] lg:items-start">
        <aside
          aria-label={asideLabel}
          data-slot="detail-aside"
          className="bg-card text-card-foreground flex flex-col gap-3 rounded-lg border p-4 lg:sticky lg:top-16 lg:order-2"
        >
          {aside}
        </aside>
        <div data-slot="detail-content" className="flex min-w-0 flex-col gap-4 lg:order-1">
          {children}
        </div>
      </div>
    </PageContent>
  )
}

/** Asks before the page is left (closed, reloaded, another address) while {@link dirty}. */
export function useUnsavedChanges(dirty: boolean) {
  useEffect(() => {
    if (!dirty) return
    const warn = (event: BeforeUnloadEvent) => {
      event.preventDefault()
      // Older browsers show the prompt only when a value is set.
      event.returnValue = ''
    }
    window.addEventListener('beforeunload', warn)
    return () => window.removeEventListener('beforeunload', warn)
  }, [dirty])
}

export interface FormPageProps extends HeaderProps {
  /** Whether there are changes not saved: the bar says so, Discard is offered, and leaving the page asks first. */
  dirty: boolean
  /** Saves; also on Enter in a field (the page is a <form>). */
  onSave: () => void
  /** Discards the changes. */
  onDiscard: () => void
  /** While saving, both buttons wait. */
  saving?: boolean
  /** Whether what is filled in can be saved yet (a required choice is made); Save waits otherwise. */
  canSave?: boolean
  saveLabel?: string
  discardLabel?: string
  /** The fields. */
  children: ReactNode
}

/**
 * A form page: the head, one column of fields at most `--content-form-max` wide, and a bar fixed at the bottom with
 * Discard and Save that says whether there are unsaved changes (a status, read out when it changes). While there are,
 * closing or reloading the page asks first; a router's own navigation should ask too (its blocker, with `dirty`).
 */
export function FormPage({
  dirty,
  onSave,
  onDiscard,
  saving = false,
  canSave = true,
  saveLabel,
  discardLabel,
  children,
  ...header
}: FormPageProps) {
  const { t } = useTranslation(UI_NAMESPACE)
  useUnsavedChanges(dirty)
  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (!saving && dirty && canSave) onSave()
  }
  return (
    <PageContent form>
      <PageHeader {...header} />
      <form onSubmit={submit} noValidate data-slot="form-page" className="flex flex-col gap-4">
        {children}
        <div
          data-slot="form-page-bar"
          className="bg-background/95 supports-[backdrop-filter]:bg-background/80 sticky bottom-[var(--bottom-bar-offset,0px)] z-20 -mx-4 flex flex-wrap items-center gap-2 border-t px-4 py-3 backdrop-blur"
        >
          <p role="status" className="text-muted-foreground mr-auto text-sm" data-testid="form-page-state">
            {dirty ? t('templates.unsaved') : t('templates.saved')}
          </p>
          <Button
            type="button"
            variant="outline"
            onClick={onDiscard}
            disabled={!dirty || saving}
            data-testid="form-page-discard"
          >
            {discardLabel ?? t('templates.discard')}
          </Button>
          <Button type="submit" disabled={!dirty || saving || !canSave} data-testid="form-page-save">
            {saveLabel ?? t('templates.save')}
          </Button>
        </div>
      </form>
    </PageContent>
  )
}
