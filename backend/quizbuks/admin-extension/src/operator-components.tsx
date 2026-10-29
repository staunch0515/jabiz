import { useTranslation } from 'react-i18next'
import { EXTENSION_NAMESPACE, Input, Label, Textarea } from '@jabiz/admin'

/** The fields of an operator, as both the new one's dialog and the operator's page edit them. */
export function OperatorFields({ id, name, contactName, contactEmail, note, onChange }: {
  id: string
  name: string
  contactName: string
  contactEmail: string
  note: string
  onChange: (field: 'name' | 'contactName' | 'contactEmail' | 'note', value: string) => void
}) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  return (
    <>
      <div className="flex flex-col gap-2">
        <Label htmlFor={`${id}-name`}>{t('operator.fields.name')}</Label>
        <Input id={`${id}-name`} value={name} required maxLength={200} autoComplete="off"
          data-testid="operator-name" onChange={(event) => onChange('name', event.target.value)} />
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

