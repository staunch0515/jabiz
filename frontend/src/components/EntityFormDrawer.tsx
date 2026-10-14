import { api, unwrap, ApiError, useAuth } from '@jabiz/client'
import {
  Alert,
  AlertDescription,
  Button,
  cn,
  Controller,
  DateTimePicker,
  Input,
  Label,
  notify,
  Sheet,
  SheetContent,
  SheetFooter,
  SheetHeader,
  SheetTitle,
  Spinner,
  useForm,
} from '@jabiz/ui'
import { CircleAlert } from 'lucide-react'
import { useId, useMemo, useRef, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { actionsFor } from '../meta/actions'
import { changedAttributes, formFieldsOf, wireAttributes, type FormField } from '../meta/entityForm'
import { useDatasets, useProcesses } from '../meta/hooks'
import { enabledCodes, fieldLabel, findField, toFormValue } from '../meta/kinds'
import { childListsOf, referenceSourceOf, useEntityMetas } from '../meta/references'
import { describe, validateField } from '../meta/validation'
import type { DatasetEntry, DictItem, EntityInstance, EntityMeta, Violation } from '../meta/types'
import ChildEntityList from './ChildEntityList'
import EntityField from './EntityField'
import RowActions from './RowActions'

interface Props {
  dataset: DatasetEntry
  entity: EntityMeta
  dictionaries: Record<string, DictItem[]>
  /** The entry to edit; absent to create one. */
  instance?: EntityInstance
  /** Values of a new entry that are fixed (the parent of a child created from its parent's details). */
  preset?: Record<string, unknown>
  /** Shows the entry without offering to change it (the caller may not write through the dataset). */
  readOnly?: boolean
  /**
   * The entry as it was at another point in time: shown read-only, without the actions and children of the current
   * state, which would not match it.
   */
  historical?: boolean
  open: boolean
  onOpenChange(open: boolean): void
  onSaved(): void
}

type Errors = Record<string, Violation[]>
type Values = Record<string, unknown>

/** The form's own inputs beside the entity's fields: when a temporal change takes effect, and why it is made. */
const EFFECTIVE_TIME = '__effectiveTime'
const REASON = '__reason'

/**
 * The generated create / edit form of any entity (docs/design/12-frontend.md section 5), in a sheet from the right.
 * Inputs come from the semantic kinds and hold their values in react-hook-form; the only check is meta/validation.ts
 * (the server's rules, decision D15): a field is checked as it changes, and on saving everything (new entry) or what
 * changed (update). Whatever the server still refuses (400/422 violations) is shown at the same place, a conflict
 * (409) above the fields.
 */
export default function EntityFormDrawer(props: Props) {
  const { open, instance, onOpenChange } = props
  // A new opening, or another entry while open, starts a new form: nothing typed for one entry may be sent for
  // another (a caller may keep the drawer mounted and switch rows). Reset while rendering, React's pattern for state
  // derived from props.
  // What had focus when the sheet was opened (a row's button), read as the opening renders: by the time the sheet
  // mounts, focus may already have left it. Focus returns there when the sheet closes.
  const [shown, setShown] = useState(() => ({
    open,
    id: instance?.id,
    opener: open ? document.activeElement : null,
  }))
  const [session, setSession] = useState(0)
  if (shown.open !== open || shown.id !== instance?.id) {
    setShown({ open, id: instance?.id, opener: open && !shown.open ? document.activeElement : shown.opener })
    if (open) setSession(session + 1)
  }
  // While a save is under way the sheet stays open (Escape, the close button and Cancel alike).
  const busy = useRef(false)
  const close = () => {
    if (!busy.current) onOpenChange(false)
  }
  return (
    <Sheet open={open} onOpenChange={(next) => (next ? onOpenChange(true) : close())}>
      <EntityForm key={session} {...props} busyRef={busy} close={close} opener={shown.opener} />
    </Sheet>
  )
}

/** The sheet's content: one form for one opening of one entry. */
function EntityForm({
  dataset,
  entity,
  dictionaries,
  instance,
  preset,
  readOnly = false,
  historical = false,
  onSaved,
  busyRef,
  close,
  opener,
}: Props & { busyRef: { current: boolean }; close(): void; opener: Element | null }) {
  const { t } = useTranslation()
  const { can } = useAuth()
  const formId = useId()
  const [errors, setErrors] = useState<Errors>({})
  const [general, setGeneral] = useState<string[]>([])
  const [busy, setBusyState] = useState(false)
  const setBusy = (value: boolean) => {
    busyRef.current = value
    setBusyState(value)
  }
  // File fields still uploading: saving now would send the old file (or none).
  const [uploading, setUploading] = useState<ReadonlySet<string>>(new Set())
  const setUploadingField = (name: string, isUploading: boolean) =>
    setUploading((previous) => {
      const next = new Set(previous)
      if (isUploading) next.add(name)
      else next.delete(name)
      return next
    })
  // Date fields holding typed text that is no date: the form is not sent while there are any (the field says why).
  const typedInvalid = useRef(new Set<string>())
  const mode = instance ? 'edit' : 'create'
  const fields = useMemo(() => {
    const base = formFieldsOf(entity, mode, preset, can)
    return readOnly ? base.map((f) => ({ ...f, disabled: true, fixed: false })) : base
  }, [entity, mode, preset, readOnly, can])
  const datasetList = useDatasets().data
  const processList = useProcesses().data
  const datasets = useMemo(() => datasetList ?? [], [datasetList])
  const processes = useMemo(() => processList ?? [], [processList])
  // Reference targets (picked by name) and, for an existing entry, every entity that may list children under it.
  const metas = useEntityMetas([
    ...entity.fields.flatMap((f) => (f.type === 'reference' ? [f.targetEntity] : [])),
    ...(instance && !historical ? datasets.filter((d) => d.isDefault).map((d) => d.entity) : []),
  ])
  const children = useMemo(
    () => (instance && !historical ? childListsOf(entity.entity, datasets, metas) : []),
    [instance, historical, entity.entity, datasets, metas],
  )
  const actions = useMemo(
    () => (historical ? [] : actionsFor(entity.entity, processes)),
    [historical, entity.entity, processes],
  )
  const original = useMemo(() => (instance?.attributes ?? {}) as Values, [instance])
  const codes = useMemo(() => enabledCodes(dictionaries), [dictionaries])
  const temporal = entity.temporal
  const showEffectiveTime = temporal && (dataset.allowScheduled || can('temporal.backdate'))

  const initialValues = useMemo(() => {
    const values: Values = {}
    for (const { field } of fields) {
      // A new entry starts with "no" for yes/no fields: an untouched switch means false, not "not given".
      values[field.name] = instance || field.type !== 'bool' ? toFormValue(field, original[field.name]) : false
      if (preset && field.name in preset) values[field.name] = toFormValue(field, preset[field.name])
    }
    values[EFFECTIVE_TIME] = null
    values[REASON] = ''
    return values
  }, [fields, original, instance, preset])
  const form = useForm<Values>({ defaultValues: initialValues })

  const messageOf = (v: { field: string; ruleCode: string; params: Record<string, unknown> }): Violation => {
    const field = findField(entity, v.field)
    return describe(entity, v, field ? fieldLabel(field, t) : v.field)
  }

  /** Checks one field as it changes, with the same rules as on saving; a server message of that field is replaced. */
  const recheck = (formField: FormField) => {
    const attributes = wireAttributes([formField], form.getValues())
    const raw = formField.field.name in attributes ? attributes[formField.field.name] : undefined
    const violations = validateField(formField.field, raw, { insert: mode === 'create', dictionaries: codes }).map(messageOf)
    setErrors((previous) => {
      const next = { ...previous }
      if (violations.length > 0) next[formField.field.name] = violations
      else delete next[formField.field.name]
      return next
    })
  }

  const showServerViolations = (error: ApiError) => {
    const byField: Errors = {}
    const rest: string[] = []
    for (const v of error.violations) {
      if (v.field && fields.some((f) => f.field.name === v.field)) (byField[v.field] ??= []).push(v)
      else rest.push(v.message)
    }
    if (error.status === 409) rest.unshift(t('form.conflict'))
    if (rest.length === 0 && Object.keys(byField).length === 0) rest.push(error.display)
    setErrors(byField)
    setGeneral(rest)
  }

  const save = async () => {
    const values = form.getValues()
    setGeneral([])
    const attributes = mode === 'create' ? wireAttributes(fields, values) : changedAttributes(fields, original, values)
    // The same check the server runs on what is sent: everything on insert, the changed fields on update.
    const found: Errors = {}
    for (const formField of fields) {
      const { field } = formField
      if (mode === 'edit' && !(field.name in attributes)) continue
      const violations = validateField(field, attributes[field.name], {
        insert: mode === 'create',
        dictionaries: codes,
      }).map(messageOf)
      if (violations.length > 0) found[field.name] = violations
    }
    setErrors(found)
    if (Object.keys(found).length > 0) return
    const effectiveTime = typeof values[EFFECTIVE_TIME] === 'string' && values[EFFECTIVE_TIME] ? values[EFFECTIVE_TIME] : undefined
    if (mode === 'edit' && Object.keys(attributes).length === 0 && !effectiveTime) {
      setGeneral([t('form.nothingChanged')])
      return
    }
    const reasonText = typeof values[REASON] === 'string' ? values[REASON].trim() : ''

    setBusy(true)
    try {
      await unwrap(
        api.POST('/api/datasets/{resourceId}/commit', {
          params: { path: { resourceId: dataset.id } },
          body: {
            reason: reasonText || undefined,
            changes: [
              {
                action: mode === 'create' ? 'INSERT' : 'UPDATE',
                id: instance?.id,
                version: instance?.version,
                attributes,
                effectiveTime,
              },
            ],
          },
        }),
      )
    } catch (e) {
      setBusy(false)
      if (e instanceof ApiError) {
        showServerViolations(e)
        return
      }
      throw e
    }
    setBusy(false)
    notify.success(t('list.saved'))
    onSaved()
  }

  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    // Not the forms of a sheet opened from this one (a child's), whose events reach here through React's tree.
    event.stopPropagation()
    if (readOnly || busy || uploading.size > 0) return
    if (typedInvalid.current.size > 0) {
      event.currentTarget.querySelector<HTMLElement>('[aria-invalid="true"]')?.focus()
      return
    }
    void save()
  }

  const title = t(readOnly ? 'form.viewTitle' : mode === 'create' ? 'form.createTitle' : 'form.editTitle', {
    entity: entity.label,
  })
  const effectiveTimeId = `${formId}-effective-time`
  const reasonId = `${formId}-reason`

  return (
      <SheetContent
        aria-describedby={undefined}
        data-testid="entity-form"
        className={cn('w-full gap-0', children.length > 0 ? 'sm:max-w-3xl' : 'sm:max-w-xl')}
        onCloseAutoFocus={(event) => {
          // Radix returns focus to a trigger only; the drawer is opened from a row's button, which gets it back.
          if (opener instanceof HTMLElement && opener.isConnected) {
            event.preventDefault()
            opener.focus()
          }
        }}
      >
        <SheetHeader className="border-b pr-12">
          <SheetTitle>{title}</SheetTitle>
        </SheetHeader>
        <div className="flex-1 overflow-y-auto p-4">
          <form id={formId} noValidate onSubmit={submit} className="flex flex-col gap-4">
            {general.length > 0 && (
              <Alert variant="destructive" data-testid="form-error">
                <CircleAlert aria-hidden />
                <AlertDescription>{general.join(' ')}</AlertDescription>
              </Alert>
            )}
            {instance && actions.length > 0 && (
              <div className="flex flex-wrap items-center gap-2" data-testid="detail-actions">
                <RowActions actions={actions} id={instance.id} attributes={original} onDone={onSaved} />
              </div>
            )}
            {fields.map((formField) => (
              <Controller
                key={formField.field.name}
                control={form.control}
                name={formField.field.name}
                render={({ field: control }) => (
                  <EntityField
                    field={formField.field}
                    disabled={formField.disabled}
                    dictionaries={dictionaries}
                    violations={errors[formField.field.name]}
                    t={t}
                    value={control.value}
                    onChange={(value) => {
                      control.onChange(value)
                      recheck(formField)
                    }}
                    onTypedInvalid={(invalid) => {
                      if (invalid) typedInvalid.current.add(formField.field.name)
                      else typedInvalid.current.delete(formField.field.name)
                    }}
                    onUploadingChange={(isUploading) => setUploadingField(formField.field.name, isUploading)}
                    referenceDatasetId={referenceSourceOf(formField.field, datasets, metas)?.id}
                    defaultLocale={entity.defaultLocale}
                  />
                )}
              />
            ))}
            {showEffectiveTime && !readOnly && (
              <Controller
                control={form.control}
                name={EFFECTIVE_TIME}
                render={({ field: control }) => (
                  <div className="flex flex-col gap-1.5" data-slot="form-item" data-field={EFFECTIVE_TIME}>
                    <Label htmlFor={effectiveTimeId}>{t('form.effectiveTime')}</Label>
                    <DateTimePicker
                      id={effectiveTimeId}
                      aria-describedby={`${effectiveTimeId}-hint`}
                      value={(control.value as string | null) ?? null}
                      onChange={(value) => control.onChange(value)}
                      onInvalidChange={(invalid) => {
                        if (invalid) typedInvalid.current.add(EFFECTIVE_TIME)
                        else typedInvalid.current.delete(EFFECTIVE_TIME)
                      }}
                      // Without scheduling (only backdating) a change cannot take effect later than now.
                      toDate={dataset.allowScheduled ? undefined : new Date()}
                      clearable
                    />
                    <p id={`${effectiveTimeId}-hint`} className="text-muted-foreground text-sm">
                      {t('form.effectiveTimeHint')}
                    </p>
                  </div>
                )}
              />
            )}
            {temporal && !readOnly && (
              <div className="flex flex-col gap-1.5" data-slot="form-item" data-field={REASON}>
                <Label htmlFor={reasonId}>{t('form.reason')}</Label>
                <Input id={reasonId} {...form.register(REASON)} />
              </div>
            )}
          </form>
          {instance &&
            children.map((child) => (
              <ChildEntityList key={`${child.entity.entity}.${child.field}`} child={child} parentId={String(instance.id)} />
            ))}
        </div>
        {!readOnly && (
          <SheetFooter className="flex-row justify-end border-t">
            <Button variant="outline" disabled={busy} onClick={close}>
              {t('form.cancel')}
            </Button>
            <Button
              type="submit"
              form={formId}
              disabled={busy || uploading.size > 0}
              aria-busy={busy || uploading.size > 0 || undefined}
            >
              {(busy || uploading.size > 0) && <Spinner className="text-current" />}
              {t('form.submit')}
            </Button>
          </SheetFooter>
        )}
      </SheetContent>
  )
}
