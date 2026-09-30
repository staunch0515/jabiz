import { DrawerForm, ProFormDateTimePicker, ProFormText } from '@ant-design/pro-components'
import { Alert, App, Form, Space } from 'antd'
import dayjs, { type Dayjs } from 'dayjs'
import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { api, unwrap } from '../api/client'
import { ApiError } from '../api/problem'
import { useAuth } from '../auth/AuthContext'
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

/**
 * The generated create / edit form of any entity (docs/design/12-frontend.md section 5). Inputs come from the
 * semantic kinds; the client check reports the server's rule codes before anything is sent, and whatever the server
 * still refuses (400/422 violations) is shown at the same place.
 */
export default function EntityFormDrawer({
  dataset,
  entity,
  dictionaries,
  instance,
  preset,
  readOnly = false,
  historical = false,
  open,
  onOpenChange,
  onSaved,
}: Props) {
  const { t } = useTranslation()
  const { message } = App.useApp()
  const { can } = useAuth()
  const [form] = Form.useForm()
  const [errors, setErrors] = useState<Errors>({})
  const [general, setGeneral] = useState<string[]>([])
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
  const original = useMemo(() => (instance?.attributes ?? {}) as Record<string, unknown>, [instance])
  const codes = useMemo(() => enabledCodes(dictionaries), [dictionaries])
  const temporal = entity.temporal
  const showEffectiveTime = temporal && (dataset.allowScheduled || can('temporal.backdate'))

  const initialValues = useMemo(() => {
    const values: Record<string, unknown> = {}
    for (const { field } of fields) {
      // A new entry starts with "no" for yes/no fields: an untouched switch means false, not "not given".
      values[field.name] = instance || field.type !== 'bool' ? toFormValue(field, original[field.name]) : false
      if (preset && field.name in preset) values[field.name] = toFormValue(field, preset[field.name])
    }
    return values
  }, [fields, original, instance, preset])

  const messageOf = (v: { field: string; ruleCode: string; params: Record<string, unknown> }): Violation => {
    const field = findField(entity, v.field)
    return describe(entity, v, field ? fieldLabel(field, t) : v.field)
  }

  const checkField = (formField: FormField, allValues: Record<string, unknown>) => {
    const attributes = wireAttributes([formField], allValues)
    const raw = formField.field.name in attributes ? attributes[formField.field.name] : undefined
    return validateField(formField.field, raw, { insert: mode === 'create', dictionaries: codes }).map(messageOf)
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

  const submit = async (values: Record<string, unknown>) => {
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
    if (Object.keys(found).length > 0) return false
    if (mode === 'edit' && Object.keys(attributes).length === 0 && !values.__effectiveTime) {
      setGeneral([t('form.nothingChanged')])
      return false
    }

    const effectiveTime = values.__effectiveTime ? (values.__effectiveTime as Dayjs).toISOString() : undefined
    const reason = typeof values.__reason === 'string' && values.__reason.trim() ? values.__reason.trim() : undefined
    try {
      await unwrap(
        api.POST('/api/datasets/{resourceId}/commit', {
          params: { path: { resourceId: dataset.id } },
          body: {
            reason,
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
      if (e instanceof ApiError) {
        showServerViolations(e)
        return false
      }
      throw e
    }
    message.success(t('list.saved'))
    onSaved()
    return true
  }

  return (
    <DrawerForm
      form={form}
      name="entity"
      title={t(readOnly ? 'form.viewTitle' : mode === 'create' ? 'form.createTitle' : 'form.editTitle', {
        entity: entity.label,
      })}
      open={open}
      onOpenChange={(next) => {
        if (!next) {
          setErrors({})
          setGeneral([])
        }
        onOpenChange(next)
      }}
      initialValues={initialValues}
      // Dates reach onFinish as Dayjs, converted to ISO-8601 with offset by the adapter (toWireValue, effective time).
      // ProForm's default formats them as local text without a zone, which the server rejects.
      dateFormatter={false}
      drawerProps={{ destroyOnHidden: true, width: children.length > 0 ? 720 : 560 }}
      submitter={readOnly ? false : { searchConfig: { submitText: t('form.submit'), resetText: t('form.cancel') } }}
      onValuesChange={(changed: Record<string, unknown>, all: Record<string, unknown>) => {
        // Checked while typing, with the same rules as on submit; a server message of that field is replaced.
        setErrors((previous) => {
          const next = { ...previous }
          for (const name of Object.keys(changed)) {
            const formField = fields.find((f) => f.field.name === name)
            if (!formField) continue
            const violations = checkField(formField, all)
            if (violations.length > 0) next[name] = violations
            else delete next[name]
          }
          return next
        })
      }}
      onFinish={submit}
    >
      {general.length > 0 && (
        <Alert type="error" showIcon message={general.join(' ')} style={{ marginBottom: 16 }} data-testid="form-error" />
      )}
      {instance && actions.length > 0 && (
        <Space size="middle" style={{ marginBottom: 16 }} data-testid="detail-actions">
          <RowActions actions={actions} id={instance.id} attributes={original} onDone={onSaved} />
        </Space>
      )}
      {fields.map((formField) => (
        <EntityField
          key={formField.field.name}
          field={formField.field}
          disabled={formField.disabled}
          dictionaries={dictionaries}
          violations={errors[formField.field.name]}
          t={t}
          referenceDatasetId={referenceSourceOf(formField.field, datasets, metas)?.id}
          defaultLocale={entity.defaultLocale}
        />
      ))}
      {showEffectiveTime && !readOnly && (
        <ProFormDateTimePicker
          name="__effectiveTime"
          label={t('form.effectiveTime')}
          tooltip={t('form.effectiveTimeHint')}
          fieldProps={{ style: { width: '100%' }, disabledDate: dataset.allowScheduled ? undefined : (d) => d.isAfter(dayjs()) }}
        />
      )}
      {temporal && !readOnly && <ProFormText name="__reason" label={t('form.reason')} />}
      {instance &&
        children.map((child) => (
          <ChildEntityList key={`${child.entity.entity}.${child.field}`} child={child} parentId={String(instance.id)} />
        ))}
    </DrawerForm>
  )
}
