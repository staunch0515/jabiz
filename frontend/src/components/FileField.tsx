import { formatBytes, uploadFile, ApiError } from '@jabiz/client'
import { Button, FileUpload } from '@jabiz/ui'
import type { TFunction } from 'i18next'
import type { FileFieldMeta } from '../meta/types'
import FilePreview from './FilePreview'

interface InputProps {
  field: FileFieldMeta
  disabled: boolean
  t: TFunction
  value?: string | null
  onChange?: (value: string | null) => void
  /** The id of the file input, which the field's label points at. */
  id?: string
  'aria-describedby'?: string
  /** The field has a problem shown with it (a server's violation). */
  'aria-invalid'?: boolean
  /** Uploading starts (true) and ends (false): a form is not sent meanwhile. */
  onUploadingChange?: (uploading: boolean) => void
}

/**
 * The input of a `jabiz.file` field (docs/design/14-files.md section 4): uploads under the field's policy and keeps
 * the returned id; shows the current file (images as a preview); removing it empties the field (sent as null). The
 * picker's filter and the size check only save a round trip: the server recognises the type by content and enforces
 * the limit.
 */
export function FileInput({
  field,
  disabled,
  t,
  value,
  onChange,
  id,
  'aria-describedby': describedBy,
  'aria-invalid': invalid,
  onUploadingChange,
}: InputProps) {
  const types = (field.accept ?? []).filter((a) => a.startsWith('.')).join(', ')
  const hint =
    field.maxBytes !== undefined
      ? t('file.accepted', { types: types || '—', max: formatBytes(field.maxBytes) })
      : undefined
  return (
    <div className={'flex flex-col gap-2'}>
      {value ? <FilePreview fileId={value} field={field} width={320} /> : null}
      <div className="flex flex-wrap items-start gap-2">
        <FileUpload
          id={id}
          aria-describedby={describedBy}
          accept={field.accept?.join(',')}
          maxSize={field.maxBytes}
          maxSizeText={field.maxBytes !== undefined ? formatBytes(field.maxBytes) : undefined}
          disabled={disabled}
          label={value ? t('file.replace') : t('file.upload')}
          hint={hint}
          aria-invalid={invalid}
          onUpload={async (file) => {
            onUploadingChange?.(true)
            try {
              const uploaded = await uploadFile(field.policy, file, file.name)
              onChange?.(uploaded.fileId ?? null)
            } catch (e) {
              // The upload field shows the message; the server's own words, not the error's type.
              throw new Error(e instanceof ApiError ? e.display : String(e), { cause: e })
            } finally {
              onUploadingChange?.(false)
            }
          }}
        />
        {value && !disabled ? (
          <Button variant="outline" onClick={() => onChange?.(null)}>
            {t('file.remove')}
          </Button>
        ) : null}
      </div>
    </div>
  )
}

export default FileInput
