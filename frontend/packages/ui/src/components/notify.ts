import type { ReactNode } from 'react'
import { toast } from 'sonner'

export interface NotifyOptions {
  /** A second line under the message. */
  description?: ReactNode
  /** Milliseconds before it goes; sonner's default when absent. */
  duration?: number
}

/**
 * Short messages after an action ("Saved.", "The import failed: …"), shown by the application's one `<Toaster />`;
 * replaces antd's `App.useApp().message`. Messages are announced to screen readers (sonner's live region); errors
 * stay longer, for they usually need reading.
 */
export const notify = {
  success: (message: ReactNode, options?: NotifyOptions) => toast.success(message, options),
  info: (message: ReactNode, options?: NotifyOptions) => toast.info(message, options),
  warning: (message: ReactNode, options?: NotifyOptions) => toast.warning(message, options),
  error: (message: ReactNode, options?: NotifyOptions) => toast.error(message, { duration: 8000, ...options }),
  dismiss: (id?: string | number) => toast.dismiss(id),
}
