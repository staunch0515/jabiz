import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useId, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useNavigate } from 'react-router'
import {
  Alert,
  AlertDescription,
  ApiError,
  Badge,
  Button,
  Checkbox,
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  EXTENSION_NAMESPACE,
  Input,
  Label,
  notify,
  PageState,
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
  useLanguage,
} from '@jabiz/admin'
import { LoadFailed, Section } from './components'
import { PATH } from './ids'
import {
  adoptTemplate, copyTemplate, createTemplate, i18nText, readCopyableTemplates, readTemplateDiff, TREES_KEY,
  type TreeRow,
} from './trees-api'

const failureText = (error: unknown) => (error instanceof ApiError ? error.display : String(error))

/**
 * A new platform template (F-S3e, `qb.tree.platform.write`): its name in the interface language and who lights it;
 * the map then opens for its nodes. Another administrator reviews it.
 */
export function CreateTemplateButton() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const id = useId()
  const language = useLanguage().slice(0, 2)
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [open, setOpen] = useState(false)
  const [name, setName] = useState('')
  const [subject, setSubject] = useState<'TAKER' | 'SPONSOR'>('TAKER')
  const [missing, setMissing] = useState(false)
  const create = useMutation({
    mutationFn: () => createTemplate(subject, { [language]: name.trim() }),
    onSuccess: (out) => {
      notify.success(t('trees.create.done'))
      void queryClient.invalidateQueries({ queryKey: TREES_KEY })
      navigate(`${PATH.tree(out.treeId)}?edit=1`)
    },
  })
  const submit = (event: FormEvent) => {
    event.preventDefault()
    setMissing(!name.trim())
    if (name.trim()) create.mutate()
  }
  return (
    <>
      <Button type="button" data-testid="tree-create" onClick={() => setOpen(true)}>{t('trees.create.action')}</Button>
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent data-testid="tree-create-dialog">
          <form noValidate onSubmit={submit} className="flex flex-col gap-4">
            <DialogHeader>
              <DialogTitle>{t('trees.create.title')}</DialogTitle>
              <DialogDescription>{t('trees.editor.languageHint')}</DialogDescription>
            </DialogHeader>
            <div className="flex flex-col gap-2">
              <Label htmlFor={`${id}-name`}>{t('trees.create.name')}</Label>
              <Input id={`${id}-name`} value={name} required aria-invalid={missing ? true : undefined}
                aria-describedby={missing ? `${id}-name-error` : undefined} data-testid="tree-create-name"
                onChange={(event) => { setName(event.target.value); setMissing(false) }} />
              {missing && <p id={`${id}-name-error`} className="text-destructive text-sm">{t('trees.create.nameRequired')}</p>}
            </div>
            <div className="flex flex-col gap-2">
              <Label htmlFor={`${id}-subject`}>{t('trees.create.subject')}</Label>
              <Select value={subject} onValueChange={(value) => setSubject(value as 'TAKER' | 'SPONSOR')}>
                <SelectTrigger id={`${id}-subject`} data-testid="tree-create-subject"><SelectValue /></SelectTrigger>
                <SelectContent>
                  <SelectItem value="TAKER">{t('trees.create.subjects.TAKER')}</SelectItem>
                  <SelectItem value="SPONSOR">{t('trees.create.subjects.SPONSOR')}</SelectItem>
                </SelectContent>
              </Select>
            </div>
            {create.isError && (
              <Alert variant="destructive"><AlertDescription>{failureText(create.error)}</AlertDescription></Alert>
            )}
            <DialogFooter>
              <Button type="button" variant="outline" onClick={() => setOpen(false)}>{t('trees.create.cancel')}</Button>
              <Button type="submit" disabled={create.isPending} data-testid="tree-create-submit">{t('trees.create.submit')}</Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>
    </>
  )
}

/**
 * An operator's copy of a platform template lit by Sponsors (F-S3e, `QB_TREE_COPY`, one per template): the templates
 * from `qb.operator.tree-templates`, a copied one linking to its copy.
 */
export function CopyTemplateButton() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const id = useId()
  const language = useLanguage()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [open, setOpen] = useState(false)
  const [chosen, setChosen] = useState<string>('')
  const templates = useQuery({ queryKey: [...TREES_KEY, 'copyable'], queryFn: readCopyableTemplates, enabled: open })
  const copy = useMutation({
    mutationFn: () => copyTemplate(chosen),
    onSuccess: (out) => {
      notify.success(t('trees.copy.done'))
      void queryClient.invalidateQueries({ queryKey: TREES_KEY })
      navigate(`${PATH.tree(out.treeId)}?edit=1`)
    },
  })
  const rows = templates.data ?? []
  const copied = rows.find((row) => row.treeId === chosen)?.copyTreeId ?? null
  return (
    <>
      <Button type="button" variant="outline" data-testid="tree-copy" onClick={() => setOpen(true)}>{t('trees.copy.action')}</Button>
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent data-testid="tree-copy-dialog">
          <DialogHeader>
            <DialogTitle>{t('trees.copy.title')}</DialogTitle>
            <DialogDescription>{t('trees.copy.hint')}</DialogDescription>
          </DialogHeader>
          {templates.isPending ? <PageState kind="loading" />
            : templates.isError ? <LoadFailed error={templates.error} onRetry={() => void templates.refetch()} />
            : rows.length === 0 ? <p className="text-muted-foreground text-sm" data-testid="tree-copy-none">{t('trees.copy.none')}</p>
            : (
              <div className="flex flex-col gap-2">
                <Label htmlFor={`${id}-template`}>{t('trees.copy.template')}</Label>
                <Select value={chosen} onValueChange={setChosen}>
                  <SelectTrigger id={`${id}-template`} data-testid="tree-copy-template"><SelectValue /></SelectTrigger>
                  <SelectContent>
                    {rows.map((row) => (
                      <SelectItem key={row.treeId} value={row.treeId}>
                        {(i18nText(row.name, language) || row.treeId)
                          + (row.copyTreeId ? ` (${t('trees.copy.copied')})` : '')}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
                {copied && (
                  <Link to={PATH.tree(copied)} className="text-primary text-sm underline-offset-4 hover:underline"
                    data-testid="tree-copy-open">{t('trees.copy.open')}</Link>
                )}
              </div>
            )}
          {copy.isError && <Alert variant="destructive"><AlertDescription>{failureText(copy.error)}</AlertDescription></Alert>}
          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => setOpen(false)}>{t('trees.copy.cancel')}</Button>
            <Button type="button" disabled={!chosen || !!copied || copy.isPending} data-testid="tree-copy-submit"
              onClick={() => copy.mutate()}>{t('trees.copy.submit')}</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  )
}

/**
 * The template's changes for an operator's copy (F-S3e, `qb.operator.tree-template-diff`): each node the copy has
 * otherwise, with a box to take it (`QB_TREE_ADOPT` takes the chosen ones into the copy's draft, as the template has
 * them, or out of it when the template has them no more).
 */
export function AdoptSection({ tree }: { tree: TreeRow }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const id = useId()
  const language = useLanguage()
  const queryClient = useQueryClient()
  const [picked, setPicked] = useState<Set<string>>(new Set())
  const diff = useQuery({ queryKey: [...TREES_KEY, 'template-diff', tree.treeId, tree.revision],
    queryFn: () => readTemplateDiff(tree.treeId) })
  const adopt = useMutation({
    mutationFn: () => adoptTemplate(tree.treeId, tree.revision, [...picked]),
    onSuccess: () => {
      notify.success(t('trees.adopt.done'))
      setPicked(new Set())
      void queryClient.invalidateQueries({ queryKey: TREES_KEY })
    },
  })
  const rows = (diff.data ?? []).filter((row) => row.change !== 'SAME')
  const first = diff.data?.[0]
  return (
    <Section title={t('trees.adopt.title')} testId="tree-adopt">
      <p className="text-muted-foreground text-sm">{t('trees.adopt.hint')}</p>
      {first && (
        <p className="text-sm" data-testid="tree-adopt-versions">
          {t('trees.adopt.versions', { template: first.templateVersionNo ?? '—', taken: first.takenVersionNo ?? '—' })}
        </p>
      )}
      {diff.isPending ? <PageState kind="loading" />
        : diff.isError ? <LoadFailed error={diff.error} onRetry={() => void diff.refetch()} />
        : rows.length === 0 ? <p className="text-muted-foreground text-sm" data-testid="tree-adopt-none">{t('trees.adopt.none')}</p>
        : (
          <ul className="flex flex-col gap-2">
            {rows.map((row) => {
              const name = i18nText(row.name, language) || row.nodeId
              return (
                <li key={row.nodeId} className="flex flex-wrap items-center gap-2" data-testid="tree-adopt-node"
                  data-change={row.change}>
                  <Checkbox id={`${id}-${row.nodeId}`} checked={picked.has(row.nodeId)} aria-label={t('trees.adopt.pick', { name })}
                    onCheckedChange={(on) => setPicked((now) => {
                      const next = new Set(now)
                      if (on === true) next.add(row.nodeId)
                      else next.delete(row.nodeId)
                      return next
                    })} />
                  <Label htmlFor={`${id}-${row.nodeId}`} className="font-normal">{name}</Label>
                  <Badge variant={row.change === 'NOT_IN_TEMPLATE' ? 'destructive' : 'secondary'}>
                    {t(`trees.adopt.changes.${row.change}`, { defaultValue: row.change })}
                  </Badge>
                </li>
              )
            })}
          </ul>
        )}
      {adopt.isError && (
        <Alert variant="destructive"><AlertDescription>{t('trees.adopt.failed')}: {failureText(adopt.error)}</AlertDescription></Alert>
      )}
      {rows.length > 0 && (
        <div>
          <Button type="button" disabled={picked.size === 0 || adopt.isPending} data-testid="tree-adopt-submit"
            onClick={() => adopt.mutate()}>{t('trees.adopt.submit')}</Button>
        </div>
      )}
    </Section>
  )
}
