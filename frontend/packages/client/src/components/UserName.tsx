import { useQuery } from '@tanstack/react-query'
import { api, unwrap } from '../api/client'

/** Most ids one request asks for: the server's limit (`UserNames.MAX_IDS`). */
const MAX_IDS = 200

type Waiting = { resolve: (name: string | null) => void; reject: (error: unknown) => void }

const waiting = new Map<string, Waiting[]>()
let timer: ReturnType<typeof setTimeout> | undefined

/**
 * The shown name of one user, asked together with the other names a page asks for at the same moment: a register
 * of a hundred rows makes one request, not a hundred.
 */
function loadName(id: string): Promise<string | null> {
  return new Promise((resolve, reject) => {
    waiting.set(id, [...(waiting.get(id) ?? []), { resolve, reject }])
    timer ??= setTimeout(flush, 0)
  })
}

async function flush() {
  timer = undefined
  const batch = new Map(waiting)
  waiting.clear()
  const ids = [...batch.keys()]
  for (let i = 0; i < ids.length; i += MAX_IDS) {
    const chunk = ids.slice(i, i + MAX_IDS)
    try {
      const answer = await unwrap(api.GET('/api/users/names', { params: { query: { ids: chunk } } }))
      const names = answer.names ?? {}
      for (const id of chunk) batch.get(id)?.forEach((w) => w.resolve(names[id] ?? null))
    } catch (error) {
      for (const id of chunk) batch.get(id)?.forEach((w) => w.reject(error))
    }
  }
}

/**
 * The display name of the user `id` (docs/design/10-security.md section 14): null while unknown, for an actor that is
 * no user and for a user without a display name.
 */
export function useUserName(id: string | null | undefined): string | null {
  const query = useQuery({
    queryKey: ['user-name', id],
    enabled: !!id,
    staleTime: Infinity,
    retry: false,
    // A failed request leaves the id in place and is asked again by the next page that shows it; a found "no name"
    // (an actor that is no user) stays known.
    queryFn: () => loadName(id!),
  })
  return query.data ?? null
}

export interface UserNameProps {
  /** A user id as data keeps it (who prepared, approved or changed something). */
  id: string | null | undefined
}

/**
 * Who a user id is: their display name; the id itself for an actor that is no user (the system), a user without a
 * display name, or while the name is being asked for.
 */
export default function UserName({ id }: UserNameProps) {
  const name = useUserName(id)
  if (!id) return null
  return <span title={name ? id : undefined}>{name ?? id}</span>
}
