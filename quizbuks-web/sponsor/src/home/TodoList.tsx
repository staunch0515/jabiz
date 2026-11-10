import { Badge, Card, CardContent, CardHeader } from '@jabiz/ui'
import { ArrowRight, CircleCheck } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { SPONSOR } from '../messages'
import { Link } from '../qb/router'
import { type TodoItem } from './todo'
import './messages'

/**
 * The home's "to do" list (F-F3b): one row for each thing that needs the Sponsor, with how many and a link straight to
 * the page that handles it; an item with nothing to do is not shown, and when the list is known to be empty it says so.
 */
export function TodoList({ items, ready }: { items: readonly TodoItem[]; ready: boolean }) {
  const { t } = useTranslation(SPONSOR)
  if (items.length === 0 && !ready) return null
  return (
    <Card data-testid="todo">
      <CardHeader>
        <h2 className="text-base leading-none font-semibold">{t('home.todo.title')}</h2>
      </CardHeader>
      <CardContent>
        {items.length === 0 ? (
          <p className="text-muted-foreground flex items-center gap-2 text-sm" data-testid="todo-empty">
            <CircleCheck aria-hidden className="text-success size-5 shrink-0" />
            {t('home.todo.empty')}
          </p>
        ) : (
          <ul className="flex flex-col divide-y" aria-label={t('home.todo.label')}>
            {items.map((item) => (
              <li key={item.key}>
                <Link to={item.path} data-testid={`todo-${item.key}`} data-count={item.count}
                  className="hover:bg-accent focus-visible:ring-ring flex items-center gap-3 rounded-md px-1 py-3 outline-none focus-visible:ring-2">
                  <Badge variant="secondary" className="min-w-8 justify-center tabular-nums" data-testid="todo-count">
                    {item.count}
                  </Badge>
                  <span className="min-w-0 flex-1">{t(`home.todo.items.${item.key}`)}</span>
                  <ArrowRight aria-hidden className="text-muted-foreground size-4 shrink-0" />
                </Link>
              </li>
            ))}
          </ul>
        )}
      </CardContent>
    </Card>
  )
}
