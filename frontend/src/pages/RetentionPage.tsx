import {
  Badge,
  Button,
  Card,
  CardContent,
  CardHeader,
  CardTitle,
  Checkbox,
  Combobox,
  DataTable,
  DatePicker,
  DateTimePicker,
  Form,
  FormControl,
  FormField,
  FormItem,
  FormLabel,
  FormMessage,
  Label,
  notify,
  Spinner,
  UI_NAMESPACE,
  useForm,
  useWatch,
  type ColumnDef,
} from '@jabiz/ui'
import { useQuery } from '@tanstack/react-query'
import dayjs from 'dayjs'
import { XIcon } from 'lucide-react'
import { useId, useMemo, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'
import { api, unwrap } from '../api/client'
import { ApiError } from '../api/problem'
import {
  exportData, LEGAL_HOLD_DATASET, LEGAL_HOLD_PLACE, LEGAL_HOLD_RELEASE, type RetentionPolicyStatus,
} from '../api/retention'
import { useAuth } from '../auth/AuthContext'
import AdminPage from '../layout/AdminPage'
import { useDatasets } from '../meta/hooks'
import { paths } from './paths'

interface ExportForm {
  datasets: string[]
  /** An instant (ISO), or null for now. */
  asOf: string | null
  reports: boolean
  /** The first and last day (`YYYY-MM-DD`) the reports were issued in; either may stay open. */
  reportsFrom: string | null
  reportsTo: string | null
}

const EMPTY: ExportForm = { datasets: [], asOf: null, reports: false, reportsFrom: null, reportsTo: null }

/** A titled card of the page. */
function Part({ title, children }: { title: ReactNode; children: ReactNode }) {
  return (
    <Card>
      <CardHeader>
        <CardTitle>
          <h2 className="text-base font-semibold">{title}</h2>
        </CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">{children}</CardContent>
    </Card>
  )
}

/**
 * Retention and archiving (docs/design/21-audit-retention.md sections 3 and 4): what is past its retention and what
 * legal holds keep (retention.read), the legal holds (their generated pages), and the open-format export of datasets
 * with the issued reports (data.export). The server checks every permission again.
 */
export default function RetentionPage() {
  const { t } = useTranslation()
  const { can } = useAuth()

  const report = useQuery({
    queryKey: ['retention'],
    queryFn: async () => unwrap(api.GET('/api/retention')),
    enabled: can('retention.read'),
  })

  const columns = useMemo<ColumnDef<RetentionPolicyStatus, unknown>[]>(() => {
    const right = { className: 'text-right tabular-nums' }
    return [
      { id: 'entity', header: t('retention.entity'), cell: ({ row }) => row.original.entity },
      {
        id: 'keep',
        header: t('retention.keep'),
        cell: ({ row: { original: p } }) => (
          <span className="flex flex-wrap items-center gap-1">
            <span>{p.keep}</span>
            <span className="text-muted-foreground">{t('retention.from', { field: p.from })}</span>
            {p.fromFiscalYearEnd && <Badge variant="secondary">{t('retention.fiscalYearEnd')}</Badge>}
          </span>
        ),
      },
      { id: 'expiredThrough', header: t('retention.expiredThrough'), cell: ({ row }) => row.original.expiredThrough },
      { id: 'entries', header: t('retention.entries'), meta: right, cell: ({ row }) => row.original.entries },
      {
        id: 'expired',
        header: t('retention.expired'),
        meta: right,
        cell: ({ row: { original: p } }) => <span data-testid={`retention-expired-${p.entity}`}>{p.expired}</span>,
      },
      { id: 'held', header: t('retention.held'), meta: right, cell: ({ row }) => row.original.held },
    ]
  }, [t])

  return (
    <AdminPage title={t('retention.title')}>
      {can('retention.read') && (
        <Part title={t('retention.policies')}>
          {report.data && (
            <p className="text-muted-foreground text-sm">
              {t('retention.asOf', { today: report.data.today, month: report.data.fiscalYearEnd })}
            </p>
          )}
          <div data-testid="retention-policies">
            <DataTable
              label={t('retention.policies')}
              columns={columns}
              getRowId={(p) => p.entity ?? ''}
              loading={report.isLoading}
              data={report.data?.policies ?? []}
            />
          </div>
        </Part>
      )}
      {(can('legal.hold.read') || can('legal.hold.write')) && (
        <Part title={t('retention.holds')}>
          <div className="flex flex-wrap gap-4">
            <Link to={paths.dataset(LEGAL_HOLD_DATASET)} className="text-primary hover:underline" data-testid="retention-holds">
              {t('retention.showHolds')}
            </Link>
            {can('legal.hold.write') && (
              <>
                <Link to={paths.process(LEGAL_HOLD_PLACE, 1)} className="text-primary hover:underline">
                  {t('retention.placeHold')}
                </Link>
                <Link to={paths.process(LEGAL_HOLD_RELEASE, 1)} className="text-primary hover:underline">
                  {t('retention.releaseHold')}
                </Link>
              </>
            )}
          </div>
        </Part>
      )}
      {can('data.export') && (
        <Part title={t('retention.export')}>
          <p className="text-muted-foreground text-sm">{t('retention.exportHint')}</p>
          <ExportData />
        </Part>
      )}
    </AdminPage>
  )
}

/** The open-format export of the chosen datasets (POST /api/exports/data), downloaded as a ZIP. */
function ExportData() {
  const { t } = useTranslation()
  const datasets = useDatasets()
  const fromId = useId()
  const toId = useId()
  const form = useForm<ExportForm>({ defaultValues: EMPTY })
  const reports = useWatch({ control: form.control, name: 'reports' })
  const busy = form.formState.isSubmitting
  const options = useMemo(
    () => (datasets.data ?? []).map((d) => ({ value: d.id, label: `${d.label} (${d.id})` })),
    [datasets.data],
  )

  const submit = async (values: ExportForm) => {
    try {
      await exportData({
        datasets: values.datasets,
        asOf: values.asOf ?? undefined,
        reports: values.reports || undefined,
        reportsFrom: values.reports && values.reportsFrom
          ? dayjs(values.reportsFrom).startOf('day').toISOString() : undefined,
        reportsTo: values.reports && values.reportsTo
          ? dayjs(values.reportsTo).add(1, 'day').startOf('day').toISOString() : undefined,
      })
      notify.success(t('retention.exported'))
    } catch (e) {
      notify.error(e instanceof ApiError ? e.display : String(e))
    }
  }

  return (
    <Form {...form}>
      <form noValidate className="flex max-w-2xl flex-col gap-4" onSubmit={form.handleSubmit(submit)}
        data-testid="export-form">
        <FormField
          control={form.control}
          name="datasets"
          rules={{ validate: (chosen) => chosen.length > 0 || t('retention.datasetsRequired') }}
          render={({ field, fieldState }) => (
            <FormItem>
              <FormLabel>{t('retention.datasets')}</FormLabel>
              {field.value.length > 0 && (
                <ul className="flex flex-wrap gap-1" data-testid="export-chosen">
                  {field.value.map((id) => (
                    <li key={id}>
                      <Badge variant="secondary" className="gap-0.5 pr-0.5">
                        {options.find((o) => o.value === id)?.label ?? id}
                        <button
                          type="button"
                          className="hover:bg-accent focus-visible:ring-ring/50 rounded-sm p-0.5 outline-none focus-visible:ring-[3px]"
                          aria-label={t('tags.remove', { ns: UI_NAMESPACE, tag: id })}
                          onClick={() => field.onChange(field.value.filter((other) => other !== id))}
                        >
                          <XIcon aria-hidden />
                        </button>
                      </Badge>
                    </li>
                  ))}
                </ul>
              )}
              <FormControl>
                <Combobox
                  value={null}
                  onChange={(id) => {
                    if (id && !field.value.includes(id)) field.onChange([...field.value, id])
                  }}
                  options={options.filter((o) => !field.value.includes(o.value))}
                  loading={datasets.isLoading}
                  placeholder={t('retention.addDataset')}
                  required
                  invalid={fieldState.invalid}
                  data-testid="export-datasets"
                />
              </FormControl>
              <FormMessage />
            </FormItem>
          )}
        />
        <FormField
          control={form.control}
          name="asOf"
          render={({ field }) => (
            <FormItem>
              <FormLabel>{t('retention.exportAsOf')}</FormLabel>
              <FormControl>
                <DateTimePicker value={field.value} onChange={field.onChange} clearable className="max-w-xs" />
              </FormControl>
            </FormItem>
          )}
        />
        <FormField
          control={form.control}
          name="reports"
          render={({ field }) => (
            <FormItem className="flex flex-row items-center gap-2">
              <FormControl>
                <Checkbox checked={field.value} onCheckedChange={(checked) => field.onChange(checked === true)} />
              </FormControl>
              <FormLabel className="font-normal">{t('retention.withReports')}</FormLabel>
            </FormItem>
          )}
        />
        {reports && (
          <fieldset className="flex flex-wrap items-end gap-3">
            <legend className="mb-2 text-sm font-medium">{t('retention.reportsPeriod')}</legend>
            <FormField
              control={form.control}
              name="reportsFrom"
              render={({ field }) => (
                <div className="flex flex-col gap-2">
                  <Label htmlFor={fromId}>{t('retention.reportsFrom')}</Label>
                  <DatePicker id={fromId} value={field.value} onChange={field.onChange} clearable className="w-44" />
                </div>
              )}
            />
            <FormField
              control={form.control}
              name="reportsTo"
              render={({ field }) => (
                <div className="flex flex-col gap-2">
                  <Label htmlFor={toId}>{t('retention.reportsTo')}</Label>
                  <DatePicker id={toId} value={field.value} onChange={field.onChange} clearable className="w-44" />
                </div>
              )}
            />
          </fieldset>
        )}
        <div>
          <Button type="submit" disabled={busy} data-testid="export-submit">
            {busy && <Spinner className="text-current" />}
            {t('retention.exportNow')}
          </Button>
        </div>
      </form>
    </Form>
  )
}
