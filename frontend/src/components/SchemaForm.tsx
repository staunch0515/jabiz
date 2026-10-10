import {
  Button,
  cn,
  Combobox,
  Controller,
  DatePicker,
  DateTimePicker,
  DecimalInput,
  Input,
  Label,
  Spinner,
  Switch,
  TagsInput,
  Textarea,
  UI_SCOPE,
  useFieldArray,
  useForm,
  useWatch,
  type Control,
} from '@jabiz/ui'
import { Plus, Trash2 } from 'lucide-react'
import { createContext, useContext, useId, useMemo, useState, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { checkInputs, initialInputValues, type InputNode, type InputProblem } from '../meta/processForm'

type Values = Record<string, unknown>

interface FormContext {
  control: Control<Values>
  /** The problems to show: after the first submission, recomputed as the user types. */
  problems: Map<string, InputProblem>
  /** The prefix of the fields' ids (one form's ids differ from another's). */
  idPrefix: string
  /** Top-level inputs filled in by the row an action was started on: shown, not changed. */
  fixed: ReadonlySet<string>
}

const SchemaFormContext = createContext<FormContext | null>(null)

function useSchemaForm(): FormContext {
  const context = useContext(SchemaFormContext)
  if (!context) throw new Error('a SchemaForm field outside SchemaForm')
  return context
}

/** An id for a field path usable in HTML and in `aria-*` references. */
const fieldId = (prefix: string, path: string) => `${prefix}-${path.replace(/[^A-Za-z0-9_-]/g, '-')}`

/** The label, the control and the problem of one input. */
function FieldShell({
  node,
  path,
  inline = false,
  children,
}: {
  node: InputNode
  path: string
  inline?: boolean
  children: (props: { id: string; invalid: boolean; describedBy?: string }) => ReactNode
}) {
  const { t } = useTranslation()
  const { problems, idPrefix } = useSchemaForm()
  const id = fieldId(idPrefix, path)
  const problem = problems.get(path)
  const errorId = `${id}-error`
  const label = (
    <Label htmlFor={id}>
      {node.name}
      {node.required && (
        <>
          <span aria-hidden className="text-destructive">
            *
          </span>
          <span className="sr-only">({t('process.required')})</span>
        </>
      )}
    </Label>
  )
  const control = children({ id, invalid: problem !== undefined, describedBy: problem ? errorId : undefined })
  return (
    <div className="flex flex-col gap-1.5" data-field={path}>
      {inline ? (
        <div className="flex items-center gap-2">
          {control}
          {label}
        </div>
      ) : (
        <>
          {label}
          {control}
        </>
      )}
      {problem && (
        <p id={errorId} className="text-destructive text-sm" data-rule-code={problem.ruleCode}>
          {problem.message}
        </p>
      )}
    </div>
  )
}

/** One input of the schema: a control by its kind, a group for an object, repeatable items for a list. */
function SchemaField({ node, path }: { node: InputNode; path: string }) {
  const { t } = useTranslation()
  const { control, fixed } = useSchemaForm()
  // Only top-level inputs are filled in from the row (16 section 3).
  const readOnly = fixed.has(path)

  if (node.kind === 'object') {
    return (
      <fieldset className="flex flex-col gap-4 rounded-md border p-4" data-field={path}>
        <legend className="px-1 text-sm font-medium">{node.name}</legend>
        {(node.children ?? []).map((child) => (
          <SchemaField key={child.name} node={child} path={`${path}.${child.name}`} />
        ))}
      </fieldset>
    )
  }
  if (node.kind === 'list') return <SchemaList node={node} path={path} />

  return (
    <Controller
      control={control}
      name={path}
      render={({ field }) => (
        <FieldShell node={node} path={path} inline={node.kind === 'boolean'}>
          {({ id, invalid, describedBy }) => {
            const common = {
              id,
              'aria-invalid': invalid || undefined,
              'aria-describedby': describedBy,
              'aria-required': node.required || undefined,
            }
            const text = (field.value as string | undefined) ?? ''
            switch (node.kind) {
              case 'password':
                return (
                  <Input {...common} type="password" autoComplete="new-password" value={text} readOnly={readOnly}
                    onChange={field.onChange} onBlur={field.onBlur} />
                )
              case 'datetime':
                return (
                  <DateTimePicker {...common} value={text || null} onChange={(v) => field.onChange(v ?? '')}
                    readOnly={readOnly} clearable={!readOnly} />
                )
              case 'date':
                return (
                  <DatePicker {...common} value={text || null} onChange={(v) => field.onChange(v ?? '')}
                    readOnly={readOnly} clearable={!readOnly} />
                )
              case 'decimal':
                return (
                  <DecimalInput {...common} value={text} onChange={field.onChange} onBlur={field.onBlur}
                    readOnly={readOnly} />
                )
              case 'integer':
              case 'number':
                return (
                  <Input {...common} type="number" step={node.kind === 'integer' ? 1 : 'any'} value={text}
                    readOnly={readOnly} onChange={field.onChange} onBlur={field.onBlur} />
                )
              case 'boolean':
                return (
                  <Switch {...common} checked={field.value === true} disabled={readOnly}
                    onCheckedChange={(checked) => field.onChange(checked)} />
                )
              case 'enum':
                return (
                  <Combobox
                    id={id}
                    aria-describedby={describedBy}
                    invalid={invalid}
                    required={node.required}
                    value={text || null}
                    onChange={(v) => field.onChange(v ?? '')}
                    options={(node.options ?? []).map((v) => ({ value: v, label: v }))}
                    clearable={!node.required && !readOnly}
                    disabled={readOnly}
                  />
                )
              case 'tags':
                return (
                  <TagsInput {...common} value={(field.value as string[] | undefined) ?? []} onChange={field.onChange}
                    readOnly={readOnly} />
                )
              case 'json':
                return (
                  <Textarea {...common} className="font-mono" placeholder={t('process.json')} value={text}
                    readOnly={readOnly} onChange={field.onChange} onBlur={field.onBlur} />
                )
              default:
                return (
                  <Input {...common} type="text" value={text} readOnly={readOnly} onChange={field.onChange}
                    onBlur={field.onBlur} />
                )
            }
          }}
        </FieldShell>
      )}
    />
  )
}

/** A list of objects: one group per item, removable, and a button adding one. */
function SchemaList({ node, path }: { node: InputNode; path: string }) {
  const { t } = useTranslation()
  const { control } = useSchemaForm()
  const { fields, append, remove } = useFieldArray({ control, name: path as never })
  return (
    <fieldset className="flex flex-col gap-3 rounded-md border p-4" data-field={path}>
      <legend className="px-1 text-sm font-medium">{node.name}</legend>
      {fields.map((item, index) => (
        <div
          key={item.id}
          role="group"
          aria-label={`${node.name} ${t('process.item', { no: index + 1 })}`}
          className="bg-muted/40 flex flex-wrap items-start gap-4 rounded-md border p-3"
        >
          {(node.children ?? []).map((child) => (
            <div key={child.name} className="min-w-48 flex-1">
              <SchemaField node={child} path={`${path}.${index}.${child.name}`} />
            </div>
          ))}
          <Button
            variant="ghost"
            size="icon-sm"
            className="mt-6"
            aria-label={t('process.removeItem', { no: index + 1, list: node.name })}
            onClick={() => remove(index)}
          >
            <Trash2 aria-hidden />
          </Button>
        </div>
      ))}
      <Button variant="outline" size="sm" className="self-start" onClick={() => append(initialInputValues(node.children ?? []))}>
        <Plus aria-hidden />
        {t('process.addItem')}
      </Button>
    </fieldset>
  )
}

export interface SchemaFormProps {
  nodes: InputNode[]
  /** Top-level inputs filled in by the row an action was started on (16 section 3): shown read-only. */
  preset?: Record<string, string>
  submitLabel: string
  /** The submit button's test id. */
  submitTestId?: string
  /** The form's values once the required inputs are filled in and patterns match; convert with toProcessInput. */
  onSubmit: (values: Values) => Promise<unknown> | void
  className?: string
}

/**
 * A form generated from a JSON Schema (process inputs; template parameters and import parameters from phase 15c):
 * one control per property by its kind, a group per nested object, removable items for a list of objects. It checks
 * only that required inputs are filled in and that text matches the schema's pattern (`checkInputs`), on submitting
 * and, after that, as the user types; the server judges the rest.
 */
export default function SchemaForm({ nodes, preset, submitLabel, submitTestId, onSubmit, className }: SchemaFormProps) {
  const idPrefix = useId()
  const defaultValues = useMemo(() => initialInputValues(nodes, preset), [nodes, preset])
  const form = useForm<Values>({ defaultValues })
  const values = useWatch({ control: form.control }) as Values
  const [submitted, setSubmitted] = useState(false)
  const problems = useMemo(
    () => new Map(submitted ? checkInputs(nodes, values).map((p) => [p.path, p]) : []),
    [submitted, nodes, values],
  )
  const fixed = useMemo(() => new Set(Object.keys(preset ?? {})), [preset])
  const busy = form.formState.isSubmitting

  return (
    <SchemaFormContext.Provider value={{ control: form.control, problems, idPrefix, fixed }}>
      <form
        noValidate
        className={cn(UI_SCOPE, 'flex flex-col gap-4', className)}
        onSubmit={form.handleSubmit(async (current) => {
          setSubmitted(true)
          const found = checkInputs(nodes, current)
          if (found.length > 0) {
            document.getElementById(fieldId(idPrefix, found[0].path))?.focus()
            return
          }
          await onSubmit(current)
        })}
      >
        {nodes.map((node) => (
          <SchemaField key={node.name} node={node} path={node.name} />
        ))}
        <div>
          <Button type="submit" disabled={busy} aria-busy={busy || undefined} data-testid={submitTestId}>
            {busy && <Spinner className="text-current" />}
            {submitLabel}
          </Button>
        </div>
      </form>
    </SchemaFormContext.Provider>
  )
}
