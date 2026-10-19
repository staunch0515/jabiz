import type { Linter } from 'eslint'

/** The configs an extension gets on top of the platform's: its pages, and its tests. */
export declare const extensionRules: Linter.Config[]
/** The globs of an extension's tests, where `@jabiz/admin/testing` may be imported. */
export declare const TEST_FILES: string[]
export declare const NO_ANT_DESIGN: { group: string[]; message: string }
/** The rules for the extension in `dir` (the same for every extension). */
export declare function extensionRulesFor(dir: string): Linter.Config[]
