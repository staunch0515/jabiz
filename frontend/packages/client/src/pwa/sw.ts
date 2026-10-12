/**
 * The Service Worker script of a jabiz PWA (decision D43 item 6): compiled by the `@jabiz/client/vite` preset into
 * `<base>sw.js`, with its configuration (the shell's files, the cache name, the other SPAs' prefixes) built in.
 * Not part of the browser bundle of the application.
 */
import { startWorker, type SwConfig, type WorkerScope } from './worker'

declare const __JABIZ_SW__: SwConfig

startWorker(globalThis as unknown as WorkerScope, __JABIZ_SW__, caches, fetch.bind(globalThis))
