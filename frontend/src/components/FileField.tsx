import { UploadOutlined } from '@ant-design/icons'
import { ProForm } from '@ant-design/pro-components'
import { Button, Space, Typography, Upload } from 'antd'
import type { TFunction } from 'i18next'
import { useState } from 'react'
import { formatBytes, uploadFile } from '../api/files'
import { ApiError } from '../api/problem'
import type { FileFieldMeta } from '../meta/types'
import FilePreview from './FilePreview'

interface InputProps {
  field: FileFieldMeta
  disabled: boolean
  t: TFunction
  value?: string | null
  onChange?: (value: string | null) => void
}

/**
 * The input of a `jabiz.file` field (docs/design/14-files.md section 4): uploads under the field's policy and keeps
 * the returned id; shows the current file (images as a preview). The picker's filter and the size check only save a
 * round trip: the server recognises the type by content and enforces the limit.
 */
export function FileInput({ field, disabled, t, value, onChange }: InputProps) {
  const [uploading, setUploading] = useState(false)
  const [error, setError] = useState<string>()
  const types = (field.accept ?? []).filter((a) => a.startsWith('.')).join(', ')
  const hint =
    field.maxBytes !== undefined
      ? t('file.accepted', { types: types || '—', max: formatBytes(field.maxBytes) })
      : undefined
  return (
    <Space direction="vertical" size="small">
      {value ? <FilePreview fileId={value} field={field} width={320} /> : null}
      <Space>
        <Upload
          accept={field.accept?.join(',')}
          showUploadList={false}
          disabled={disabled || uploading}
          beforeUpload={(file) => {
            setError(undefined)
            if (field.maxBytes !== undefined && file.size > field.maxBytes) {
              setError(t('file.tooLarge', { max: formatBytes(field.maxBytes) }))
              return Upload.LIST_IGNORE
            }
            return true
          }}
          customRequest={({ file, onSuccess, onError }) => {
            const blob = file as File
            setUploading(true)
            uploadFile(field.policy, blob, blob.name)
              .then((uploaded) => {
                onChange?.(uploaded.fileId ?? null)
                onSuccess?.(uploaded)
              })
              .catch((e: unknown) => {
                setError(e instanceof ApiError ? e.display : String(e))
                onError?.(e as Error)
              })
              .finally(() => setUploading(false))
          }}
        >
          <Button icon={<UploadOutlined />} loading={uploading} disabled={disabled}>
            {value ? t('file.replace') : t('file.upload')}
          </Button>
        </Upload>
        {value && !disabled ? (
          <Button
            onClick={() => {
              setError(undefined)
              onChange?.(null)
            }}
          >
            {t('file.remove')}
          </Button>
        ) : null}
      </Space>
      {hint ? <Typography.Text type="secondary">{hint}</Typography.Text> : null}
      {error ? (
        <Typography.Text type="danger" role="alert">
          {error}
        </Typography.Text>
      ) : null}
    </Space>
  )
}

interface Props {
  field: FileFieldMeta
  label: string
  disabled: boolean
  formItemProps: Record<string, unknown>
  t: TFunction
}

/** The form item of a file field. */
export default function FileField({ field, label, disabled, formItemProps, t }: Props) {
  return (
    <ProForm.Item name={field.name} label={label} {...formItemProps}>
      <FileInput field={field} disabled={disabled} t={t} />
    </ProForm.Item>
  )
}
