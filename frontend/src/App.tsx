import { useEffect, useState, type FormEvent } from 'react'

type Todo = { id: number; title: string; done: boolean }

async function api<T>(path = '', init?: RequestInit): Promise<T> {
  const res = await fetch(`/api/todos${path}`, {
    headers: { 'Content-Type': 'application/json' },
    ...init,
  })
  if (!res.ok) throw new Error(`HTTP ${res.status}`)
  return res.status === 204 ? (undefined as T) : res.json()
}

export default function App() {
  const [todos, setTodos] = useState<Todo[]>([])
  const [title, setTitle] = useState('')

  const load = () => api<Todo[]>().then(setTodos)
  useEffect(() => { load() }, [])

  const add = async (e: FormEvent) => {
    e.preventDefault()
    if (!title.trim()) return
    await api('', { method: 'POST', body: JSON.stringify({ title }) })
    setTitle('')
    load()
  }

  return (
    <main>
      <h1>Todos</h1>
      <form onSubmit={add}>
        <input value={title} onChange={e => setTitle(e.target.value)} />
        <button>Add</button>
      </form>
      <ul>
        {todos.map(t => (
          <li key={t.id}>
            <label style={{ textDecoration: t.done ? 'line-through' : 'none' }}>
              <input
                type="checkbox"
                checked={t.done}
                onChange={() => api(`/${t.id}/toggle`, { method: 'PUT' }).then(load)}
              />
              {t.title}
            </label>
            <button onClick={() => api(`/${t.id}`, { method: 'DELETE' }).then(load)}>×</button>
          </li>
        ))}
      </ul>
    </main>
  )
}