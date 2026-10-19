import type { Plugin } from 'vite'

/** The markers of test code (Vitest, vitest-axe, axe-core) found in the code of one chunk. */
export declare function testCodeIn(code: string): string[]
/** One line per chunk of a Rollup bundle that holds test code. */
export declare function bundleProblems(chunks: Record<string, { type: string; code?: string }>): string[]
/** One line per `assets/*.js` file of a built directory that holds test code. */
export declare function builtProblems(outDir: string): string[]
/** Fails a production build whose JavaScript holds test code. */
export declare function noTestCodeInBundle(): Plugin
export declare const BUNDLE_ADVICE: string
