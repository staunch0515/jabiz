import { EyeOutlined } from '@ant-design/icons'
import { App, Button, Space, Typography } from 'antd'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { api, ApiError, unwrap } from '../api/client'

interface Props {
  datasetId: string
  id: unknown
  field: string
  /** The masked form every read shows. */
  masked: string
}

/**
 * A masked value with a button that shows it in plain text (docs/design/10-security.md section 13.1). Offered to
 * holders of the field's permission only; each click asks the server again, which checks the permission and records
 * the display. The plain value lives in this component's state only and is gone when the page is left.
 */
export default function MaskedValue({ datasetId, id, field, masked }: Props) {
  const { t } = useTranslation()
  const { message } = App.useApp()
  const [plain, setPlain] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)

  if (plain !== null) {
    return (
      <Typography.Text copyable data-testid={`revealed-${field}`}>
        {plain}
      </Typography.Text>
    )
  }
  const reveal = async () => {
    setLoading(true)
    try {
      const answer = await unwrap(
        api.POST('/api/datasets/{resourceId}/reveal', {
          params: { path: { resourceId: datasetId } },
          body: { id, field },
        }),
      )
      setPlain(answer.value === null || answer.value === undefined ? '' : String(answer.value))
    } catch (e) {
      message.error(e instanceof ApiError ? e.display : String(e))
    } finally {
      setLoading(false)
    }
  }
  return (
    <Space size={4}>
      <span>{masked}</span>
      <Button
        size="small"
        type="link"
        icon={<EyeOutlined />}
        loading={loading}
        onClick={() => void reveal()}
        aria-label={t('masked.reveal')}
        data-testid={`reveal-${field}`}
      >
        {t('masked.reveal')}
      </Button>
    </Space>
  )
}
