import type { ShellMenuItem } from '@jabiz/ui'
import type { RouteObject } from 'react-router'
import type { AdminExtension, ExtensionMenuItem } from './api'

/** Paths the platform's own pages use; an extension route may not take them over. */
export const PLATFORM_PATHS = ['/login', '/data', '/processes', '/tasks', '/reports', '/documents', '/imports', '/audit', '/integrity', '/retention', '/account'] as const

const PATH = /^\/[A-Za-z0-9._~:@!$&'()*+,;=/-]*$/

function routePaths(routes: RouteObject[] | undefined, prefix = ''): string[] {
  return (routes ?? []).flatMap((route) => {
    // A relative path is relative to its parent; at the top it stays as written, and is reported.
    const own =
      route.path === undefined
        ? prefix
        : route.path.startsWith('/') || prefix === ''
          ? route.path
          : `${prefix}/${route.path}`
    return [...(route.path === undefined ? [] : [own]), ...routePaths(route.children, own)]
  })
}

function menuPaths(items: ExtensionMenuItem[] | undefined): string[] {
  return (items ?? []).flatMap((item) => [...(item.path ? [item.path] : []), ...menuPaths(item.children)])
}

function platformPath(path: string): boolean {
  return PLATFORM_PATHS.some((own) => path === own || path.startsWith(`${own}/`)) || path === '/' || path === '*'
}

/**
 * Everything wrong with an extension, all at once (the platform's "fail at start" rule, CLAUDE.md section 4): routes
 * that are not absolute paths or reuse a platform path, menu entries without a key or label, and a home or menu path
 * that is not absolute.
 */
export function extensionProblems(extension: AdminExtension): string[] {
  const problems: string[] = []
  for (const route of extension.routes ?? []) {
    if (route.index) problems.push('an extension route cannot be the index route; use "home"')
    else if (route.path === undefined) problems.push('an extension route needs a path')
  }
  const seen = new Set<string>()
  for (const path of routePaths(extension.routes)) {
    if (!path.startsWith('/') || !PATH.test(path)) problems.push(`route "${path}" is not an absolute path`)
    else if (platformPath(path)) problems.push(`route "${path}" is a path of the platform`)
    if (seen.has(path)) problems.push(`route "${path}" is declared twice`)
    seen.add(path)
  }
  const keys = new Set<string>()
  const visit = (items: ExtensionMenuItem[] | undefined) => {
    for (const item of items ?? []) {
      if (!item.key || !item.label) problems.push('a menu entry needs a key and a label')
      if (keys.has(item.key)) problems.push(`menu key "${item.key}" is declared twice`)
      keys.add(item.key)
      visit(item.children)
    }
  }
  visit(extension.menu)
  for (const path of [...menuPaths(extension.menu), ...(extension.home ? [extension.home] : [])]) {
    if (!path.startsWith('/')) problems.push(`"${path}" is not an absolute path`)
  }
  return problems
}

/** The extension, checked: a broken extension stops the application with every problem named. */
export function checkedExtension(extension: AdminExtension): AdminExtension {
  const problems = extensionProblems(extension)
  if (problems.length > 0) {
    throw new Error(`The admin extension is invalid:\n- ${problems.join('\n- ')}`)
  }
  return extension
}

/** Menu entries the user may see, as entries of the shell's menu, labelled by `label`. Empty groups are left out. */
export function extensionMenu(
  items: ExtensionMenuItem[] | undefined,
  can: (permission: string) => boolean,
  label: (key: string) => string,
): ShellMenuItem[] {
  return (items ?? []).flatMap((item): ShellMenuItem[] => {
    if (item.permission && !can(item.permission)) return []
    const children = item.children ? extensionMenu(item.children, can, label) : undefined
    if (item.children && children!.length === 0 && !item.path) return []
    return [{
      key: `ext:${item.key}`,
      label: label(item.label),
      path: item.path ?? `/ext/${item.key}`,
      icon: item.icon,
      children: children && children.length > 0 ? children : undefined,
    }]
  })
}

/** Where a signed-in user lands. */
export function homePath(extension: AdminExtension): string {
  return extension.home ?? '/data'
}
