import {
  Alert,
  AlertDescription,
  AlertTitle,
  Button,
  Card,
  CardContent,
  CardHeader,
  CardTitle,
  Combobox,
  ConfirmDialog,
  DataTable,
  DecimalInput,
  FileUpload,
  Input,
  Label,
  notify,
  PageState,
  Spinner,
  Steps,
  Switch,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
  Textarea,
  type ColumnDef,
} from '@jabiz/ui'
import { CircleCheck, History, TriangleAlert } from 'lucide-react'
import { useId, useMemo, useRef, useState, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useSearchParams } from 'react-router'
import { api, unwrap } from '../api/client'
import { uploadFile } from '../api/files'
import {
  commitImport, inspectFile, previewImport, type ImportInspection, type ImportMapping, type ImportOptions,
  type ImportReport,
} from '../api/imports'
import { ApiError } from '../api/problem'
import SchemaForm from '../components/SchemaForm'
import AdminPage from '../layout/AdminPage'
import { useImportCatalog } from '../meta/hooks'
import { InvalidJson, inputNodes, toProcessInput, type JsonSchema } from '../meta/processForm'
import ImportReportView, { IssueTable } from './ImportReportView'
import { paths } from './paths'

const DELIMITERS = [',', ';', '\t', '|']

type Sample = NonNullable<ImportInspection['sample']>[number]

/** A labelled control of the file's layout. */
function LayoutField({ id, label, children }: { id: string; label: ReactNode; children: ReactNode }) {
  return (
    <div className="flex flex-col gap-1.5">
      <Label htmlFor={id}>{label}</Label>
      {children}
    </div>
  )
}

/** The section of a step: a card headed by the step's name. */
function StepCard({ title, children }: { title: ReactNode; children: ReactNode }) {
  return (
    <Card>
      <CardHeader>
        <CardTitle>
          <h2 className="text-base">{title}</h2>
        </CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">{children}</CardContent>
    </Card>
  )
}

/**
 * Importing a file (docs/design/20-imports.md): upload it, map its columns to the import's fields (adjusting a CSV or
 * Excel layout and using saved mappings), give the parameters, preview - every row is run and rolled back - and
 * commit, which imports everything or, when anything is wrong, nothing. The server checks everything again.
 */
export default function ImportPage() {
  const { t } = useTranslation()
  const [searchParams] = useSearchParams()
  const id = searchParams.get('id') ?? ''
  const catalog = useImportCatalog()
  const entry = catalog.data?.find((i) => i.id === id)
  const nodes = useMemo(() => (entry?.params ? inputNodes(entry.params as JsonSchema) : []), [entry])
  const formId = useId()
  const controlId = useId()
  // What submitting the parameters' form is for: a preview, or (after the confirmation) the commit.
  const nextRun = useRef<'preview' | 'commit'>('preview')

  const [file, setFile] = useState<{ fileId: string; name: string } | null>(null)
  const [inspection, setInspection] = useState<ImportInspection | null>(null)
  const [options, setOptions] = useState<ImportOptions>({})
  const [columns, setColumns] = useState<Record<string, string | undefined>>({})
  const [constants, setConstants] = useState<Record<string, string>>({})
  const [report, setReport] = useState<ImportReport | null>(null)
  const [outcome, setOutcome] = useState<{ committed: boolean; report: ImportReport } | null>(null)
  const [notes, setNotes] = useState('')
  const [busy, setBusy] = useState(false)
  const [saved, setSaved] = useState<{ name?: string; mapping?: ImportMapping }[]>([])
  const [chosenSaved, setChosenSaved] = useState<string | null>(null)
  const [mappingName, setMappingName] = useState('')

  const sampleColumns = useMemo<ColumnDef<Sample, unknown>[]>(
    () => [
      { id: '#', header: '#', meta: { className: 'w-28' }, cell: ({ row }) => row.original.location },
      ...(inspection?.columns ?? []).map(
        (c): ColumnDef<Sample, unknown> => ({ id: `column-${c}`, header: c, cell: ({ row }) => row.original.cells?.[c] ?? '' }),
      ),
    ],
    [inspection],
  )

  const fail = (e: unknown) => notify.error(e instanceof ApiError ? e.display : String(e))

  const inspect = async (fileId: string, adjusted: ImportOptions) => {
    const result = await inspectFile(id, fileId, adjusted)
    setInspection(result)
    setColumns((current) => ({ ...(result.suggested ?? {}), ...current }))
    setReport(null)
  }

  const loadSaved = async () => {
    try {
      setSaved(await unwrap(api.GET('/api/imports/{importId}/mappings', { params: { path: { importId: id } } })))
    } catch {
      setSaved([])
    }
  }

  const upload = async (blob: File) => {
    if (!entry?.filePolicy) return
    setBusy(true)
    try {
      const uploaded = await uploadFile(entry.filePolicy, blob, blob.name)
      setFile({ fileId: uploaded.fileId!, name: blob.name })
      setColumns({})
      setConstants({})
      setOutcome(null)
      await inspect(uploaded.fileId!, options)
      await loadSaved()
    } catch (e) {
      fail(e)
    } finally {
      setBusy(false)
    }
  }

  const mapping = (): ImportMapping => ({
    columns: Object.fromEntries(Object.entries(columns).filter(([, column]) => column)) as Record<string, string>,
    constants: Object.fromEntries(Object.entries(constants).filter(([, value]) => value !== '')),
    options,
  })

  /** Previews or commits with the parameters' values (none when the import takes no parameters). */
  const run = async (commit: boolean, values?: Record<string, unknown>) => {
    if (!file) return
    setBusy(true)
    try {
      const params = nodes.length === 0 ? undefined : toProcessInput(nodes, values ?? {})
      if (commit) {
        const result = await commitImport(id, file.fileId, mapping(), params, notes || undefined)
        setOutcome(result)
        setReport(result.report)
      } else {
        setReport(await previewImport(id, file.fileId, mapping(), params))
      }
    } catch (e) {
      if (e instanceof InvalidJson) notify.error(t('process.invalidJson'))
      else fail(e)
    } finally {
      setBusy(false)
    }
  }

  /**
   * Runs through the parameters' form when there is one, so that its checks come first (a required parameter left
   * empty is shown at its field, and nothing is sent).
   */
  const start = (commit: boolean) => {
    nextRun.current = commit ? 'commit' : 'preview'
    if (nodes.length === 0) {
      void run(commit)
      return
    }
    ;(document.getElementById(formId) as HTMLFormElement | null)?.requestSubmit()
  }

  const saveMapping = async () => {
    if (!mappingName.trim()) return
    try {
      await unwrap(api.PUT('/api/imports/{importId}/mappings/{name}', {
        params: { path: { importId: id, name: mappingName.trim() } },
        body: { mapping: mapping() },
      }))
      notify.success(t('imports.mappingSaved'))
      await loadSaved()
    } catch (e) {
      fail(e)
    }
  }

  const applySaved = async (name: string | null) => {
    setChosenSaved(name)
    const found = saved.find((m) => m.name === name)?.mapping
    if (!name || !found || !file) return
    const adjusted = found.options ?? {}
    setOptions(adjusted)
    setColumns(found.columns ?? {})
    setConstants(found.constants ?? {})
    setMappingName(name)
    try {
      await inspect(file.fileId, adjusted)
    } catch (e) {
      fail(e)
    }
  }

  if (catalog.isLoading) return <PageState kind="loading" />
  if (!entry) return <PageState kind="notFound" titleAs="h1" title={id} />
  const format = entry.format
  const step = outcome ? 3 : report ? 2 : file ? 1 : 0
  const extensions = (entry.extensions ?? []).join(' ')
  const fieldIds = (name: string) => `${controlId}-${name.replace(/[^A-Za-z0-9_-]/g, '-')}`

  return (
    <AdminPage
      title={entry.title}
      breadcrumb={[{ label: t('nav.imports'), to: '/imports' }, { label: entry.title }]}
      actions={
        <Button variant="outline" asChild>
          <Link to={paths.importRuns(id)} data-testid="import-runs-link">
            <History aria-hidden />
            {t('imports.runs.title')}
          </Link>
        </Button>
      }
    >
      <Steps
        current={step}
        items={[
          { title: t('imports.steps.upload') },
          { title: t('imports.steps.map') },
          { title: t('imports.steps.preview') },
          { title: t('imports.steps.done') },
        ]}
      />

      <StepCard title={t('imports.steps.upload')}>
        <FileUpload
          variant="dropzone"
          accept={(entry.extensions ?? []).join(',')}
          label={file ? file.name : t('imports.drop')}
          hint={extensions || undefined}
          disabled={busy}
          onUpload={upload}
          data-testid="import-upload"
        />
      </StepCard>

      {file && inspection && (
        <StepCard title={t('imports.steps.map')}>
          {format?.adjustable && (
            <div className="flex flex-wrap items-end gap-4" data-testid="import-layout">
              {format.kind === 'csv' && (
                <>
                  <LayoutField id={fieldIds('delimiter')} label={t('imports.layout.delimiter')}>
                    <Combobox
                      id={fieldIds('delimiter')}
                      className="w-28"
                      value={options.delimiter ?? format.delimiter ?? null}
                      onChange={(value) => setOptions({ ...options, delimiter: value ?? undefined })}
                      options={DELIMITERS.map((d) => ({ value: d, label: d === '\t' ? 'TAB' : d }))}
                      data-testid="import-delimiter"
                    />
                  </LayoutField>
                  <LayoutField id={fieldIds('skip')} label={t('imports.layout.skipLines')}>
                    <DecimalInput
                      id={fieldIds('skip')}
                      className="w-24"
                      scale={0}
                      allowNegative={false}
                      value={String(options.skipLines ?? format.skipLines ?? '')}
                      onChange={(value) => setOptions({ ...options, skipLines: value === '' ? 0 : Number(value) })}
                      data-testid="import-skip"
                    />
                  </LayoutField>
                  <LayoutField id={fieldIds('charset')} label={t('imports.layout.charset')}>
                    <Combobox
                      id={fieldIds('charset')}
                      className="w-40"
                      value={options.charset ?? format.charset ?? null}
                      onChange={(value) => setOptions({ ...options, charset: value ?? undefined })}
                      options={(format.charsets ?? []).map((c) => ({ value: c, label: c }))}
                    />
                  </LayoutField>
                </>
              )}
              {format.kind === 'xlsx' && (
                <>
                  <LayoutField id={fieldIds('sheet')} label={t('imports.layout.sheet')}>
                    <Input
                      id={fieldIds('sheet')}
                      className="w-40"
                      value={options.sheet ?? format.sheet ?? ''}
                      onChange={(e) => setOptions({ ...options, sheet: e.target.value || undefined })}
                    />
                  </LayoutField>
                  <LayoutField id={fieldIds('header-row')} label={t('imports.layout.headerRow')}>
                    <DecimalInput
                      id={fieldIds('header-row')}
                      className="w-24"
                      scale={0}
                      allowNegative={false}
                      value={String(options.headerRow ?? format.headerRow ?? '')}
                      onChange={(value) => setOptions({ ...options, headerRow: value === '' ? 1 : Number(value) })}
                    />
                  </LayoutField>
                </>
              )}
              <div className="flex h-9 items-center gap-2">
                <Switch
                  id={fieldIds('header')}
                  checked={options.header ?? format.header ?? true}
                  onCheckedChange={(value) => setOptions({ ...options, header: value })}
                />
                <Label htmlFor={fieldIds('header')}>{t('imports.layout.header')}</Label>
              </div>
              <Button variant="outline" onClick={() => void inspect(file.fileId, options).catch(fail)} data-testid="import-reread">
                {t('imports.layout.reread')}
              </Button>
            </div>
          )}
          {(inspection.issues ?? []).length > 0 && <IssueTable entry={entry} issues={inspection.issues ?? []} />}
          {saved.length > 0 && (
            <LayoutField id={fieldIds('saved')} label={t('imports.savedMappings')}>
              <Combobox
                id={fieldIds('saved')}
                className="w-56"
                placeholder={t('imports.choose')}
                value={chosenSaved}
                onChange={(name) => void applySaved(name)}
                options={saved.map((m) => ({ value: m.name ?? '', label: m.name ?? '' }))}
                data-testid="import-saved"
              />
            </LayoutField>
          )}
          {/* A plain table: its controls keep their state and focus while the user types into them. */}
          <Table data-testid="import-mapping" aria-label={t('imports.mapping')}>
            <TableHeader>
              <TableRow>
                <TableHead scope="col">{t('imports.field')}</TableHead>
                <TableHead scope="col">{t('imports.column')}</TableHead>
                <TableHead scope="col">{t('imports.constant')}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {(entry.fields ?? []).map((field) => (
                <TableRow key={field.name}>
                  <TableCell>
                    {field.label}
                    {field.required && (
                      <>
                        <span aria-hidden className="text-destructive">
                          {' '}*
                        </span>
                        <span className="sr-only">({t('process.required')})</span>
                      </>
                    )}
                  </TableCell>
                  <TableCell>
                    <Combobox
                      aria-label={t('imports.columnOf', { field: field.label })}
                      className="min-w-48"
                      clearable
                      value={columns[field.name!] ?? null}
                      onChange={(value) => setColumns({ ...columns, [field.name!]: value ?? undefined })}
                      options={(inspection.columns ?? []).map((c) => ({ value: c, label: c }))}
                      data-testid={`import-column-${field.name}`}
                    />
                  </TableCell>
                  <TableCell>
                    <Input
                      aria-label={t('imports.constantOf', { field: field.label })}
                      className="min-w-40"
                      value={constants[field.name!] ?? ''}
                      onChange={(e) => setConstants({ ...constants, [field.name!]: e.target.value })}
                      data-testid={`import-constant-${field.name}`}
                    />
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
          {entry.mappings && (
            <div className="flex flex-wrap items-center gap-2">
              <Input
                className="w-56"
                aria-label={t('imports.mappingName')}
                placeholder={t('imports.mappingName')}
                value={mappingName}
                onChange={(e) => setMappingName(e.target.value)}
                data-testid="import-mapping-name"
              />
              <Button variant="outline" onClick={() => void saveMapping()} disabled={!mappingName.trim()}>
                {t('imports.saveMapping')}
              </Button>
            </div>
          )}
          <p className="text-muted-foreground text-sm">{t('imports.sample', { count: inspection.records ?? 0 })}</p>
          <div data-testid="import-sample">
            <DataTable
              label={t('imports.sampleTable')}
              columns={sampleColumns}
              getRowId={(sample) => String(sample.number)}
              data={inspection.sample ?? []}
            />
          </div>
          {nodes.length > 0 && (
            <fieldset className="flex flex-col gap-3 rounded-md border p-4">
              <legend className="px-1 text-sm font-medium">{t('imports.parameters')}</legend>
              <SchemaForm
                id={formId}
                hideSubmit
                nodes={nodes}
                onSubmit={(values) => run(nextRun.current === 'commit', values)}
              />
            </fieldset>
          )}
          <div>
            <Button
              type={nodes.length > 0 ? 'submit' : 'button'}
              form={nodes.length > 0 ? formId : undefined}
              disabled={busy || Boolean(outcome?.committed)}
              aria-busy={busy || undefined}
              onClick={(event) => {
                nextRun.current = 'preview'
                if (nodes.length === 0) {
                  event.preventDefault()
                  void run(false)
                }
              }}
              data-testid="import-preview"
            >
              {busy && <Spinner className="text-current" />}
              {t('imports.preview')}
            </Button>
          </div>
        </StepCard>
      )}

      {report && (
        <StepCard title={outcome ? t('imports.steps.done') : t('imports.steps.preview')}>
          {outcome?.committed && (
            <Alert variant="success">
              <CircleCheck aria-hidden />
              <AlertTitle>{t('imports.committed')}</AlertTitle>
              <AlertDescription className="flex flex-wrap items-center gap-x-3">
                <code className="font-mono text-xs">{report.runId}</code>
                <Link to={paths.importRuns(id)} className="text-primary underline-offset-4 hover:underline">
                  {t('imports.runs.title')}
                </Link>
              </AlertDescription>
            </Alert>
          )}
          <ImportReportView entry={entry} report={report} />
          {!outcome?.committed && (
            <div className="flex flex-col gap-3">
              <Textarea
                rows={2}
                maxLength={4000}
                aria-label={t('imports.notes')}
                placeholder={t('imports.notes')}
                value={notes}
                onChange={(e) => setNotes(e.target.value)}
                data-testid="import-notes"
              />
              {!report.accepted && (
                <Alert variant="warning">
                  <TriangleAlert aria-hidden />
                  <AlertTitle>{t('imports.fixFirst')}</AlertTitle>
                </Alert>
              )}
              <div>
                <ConfirmDialog
                  title={t('imports.commitConfirm')}
                  confirmLabel={t('imports.commit')}
                  cancelLabel={t('reports.cancel')}
                  destructive
                  onConfirm={() => start(true)}
                  trigger={
                    <Button
                      variant="destructive"
                      disabled={busy || !report.accepted}
                      aria-busy={busy || undefined}
                      data-testid="import-commit"
                    >
                      {busy && <Spinner className="text-current" />}
                      {t('imports.commit')}
                    </Button>
                  }
                />
              </div>
            </div>
          )}
        </StepCard>
      )}
    </AdminPage>
  )
}
