import { PaperClipOutlined } from '@ant-design/icons'
import { Button, Image, Typography } from 'antd'
import { useTranslation } from 'react-i18next'
import { previewVariant } from '../api/files'
import { downloadFile, useFileUrl } from './fileContent'
import type { FileFieldMeta } from '../meta/types'

interface Props {
  fileId: string
  field: FileFieldMeta
  /** Width the preview is shown at; the narrowest variant at least this wide is loaded. */
  width: number
}

/** An image as a thumbnail, anything else as a download button. */
export default function FilePreview({ fileId, field, width }: Props) {
  const { t } = useTranslation()
  const isImage = field.image === true
  const { url, failed } = useFileUrl(isImage ? fileId : undefined, isImage ? previewVariant(field.variants, width) : undefined)
  if (!isImage) {
    return (
      <Button
        size="small"
        icon={<PaperClipOutlined />}
        onClick={(event) => {
          event.stopPropagation()
          void downloadFile(fileId)
        }}
      >
        {t('file.download')}
      </Button>
    )
  }
  if (failed) return <Typography.Text type="secondary">{t('file.previewFailed')}</Typography.Text>
  if (!url) return <Typography.Text type="secondary">{t('app.loading')}</Typography.Text>
  return <Image src={url} width={width} alt={t('file.preview')} />
}
