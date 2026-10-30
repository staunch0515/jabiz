import { useTranslation } from 'react-i18next'
import {
  EXTENSION_NAMESPACE,
  Input,
  Label,
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
  Textarea,
} from '@jabiz/admin'
import { useLabels } from './hooks'
import { DICTIONARY } from './ids'
import { OPERATOR_LANGUAGES } from './operator-api'

/** The select's value for no language (a select's item cannot have an empty value). */
const NO_LANGUAGE = 'none'

/** The fields of an operator, as both the new one's dialog and the operator's page edit them. */
export function OperatorFields({ id, name, language, contactName, contactEmail, note, onChange }: {
  id: string
  name: string
  /** '' for none */
  language: string
  contactName: string
  contactEmail: string
  note: string
  onChange: (field: 'name' | 'language' | 'contactName' | 'contactEmail' | 'note', value: string) => void
}) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const languages = useLabels(DICTIONARY.contentLanguage)
  return (
    <>
      <div className="flex flex-col gap-2">
        <Label htmlFor={`${id}-name`}>{t('operator.fields.name')}</Label>
        <Input id={`${id}-name`} value={name} required maxLength={200} autoComplete="off"
          data-testid="operator-name" onChange={(event) => onChange('name', event.target.value)} />
      </div>
      <div className="flex flex-col gap-2">
        <Label htmlFor={`${id}-language`}>{t('operator.fields.language')}</Label>
        <Select value={language || NO_LANGUAGE}
          onValueChange={(value) => onChange('language', value === NO_LANGUAGE ? '' : value)}>
          <SelectTrigger id={`${id}-language`} className="w-fit" aria-describedby={`${id}-language-hint`}
            data-testid="operator-language">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value={NO_LANGUAGE}>{t('operator.fields.noLanguage')}</SelectItem>
            {OPERATOR_LANGUAGES.map((code) => <SelectItem key={code} value={code}>{languages(code)}</SelectItem>)}
          </SelectContent>
        </Select>
        <p id={`${id}-language-hint`} className="text-muted-foreground text-sm">{t('operator.fields.languageHint')}</p>
      </div>
      <div className="flex flex-col gap-2">
        <Label htmlFor={`${id}-contact`}>{t('operator.fields.contactName')}</Label>
        <Input id={`${id}-contact`} value={contactName} required maxLength={100} autoComplete="off"
          data-testid="operator-contact" onChange={(event) => onChange('contactName', event.target.value)} />
      </div>
      <div className="flex flex-col gap-2">
        <Label htmlFor={`${id}-email`}>{t('operator.fields.contactEmail')}</Label>
        <Input id={`${id}-email`} type="email" value={contactEmail} required maxLength={254} autoComplete="off"
          data-testid="operator-email" onChange={(event) => onChange('contactEmail', event.target.value)} />
      </div>
      <div className="flex flex-col gap-2">
        <Label htmlFor={`${id}-note`}>{t('operator.fields.note')}</Label>
        <Textarea id={`${id}-note`} value={note} maxLength={2000} aria-describedby={`${id}-note-hint`}
          data-testid="operator-note" onChange={(event) => onChange('note', event.target.value)} />
        <p id={`${id}-note-hint`} className="text-muted-foreground text-sm">{t('operator.fields.noteHint')}</p>
      </div>
    </>
  )
}

