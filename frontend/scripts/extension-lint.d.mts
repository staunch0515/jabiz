import type { Linter } from 'eslint'

/** The configs an extension gets on top of the platform's: JavaScript parsing, its production code, its tests. */
export declare const extensionRules: Linter.Config[]
/** The globs of every source file of an extension. */
export declare const SOURCE_FILES: string[]
/** The globs of an extension's tests (and `test/` helpers), where `@jabiz/admin/testing` may be imported. */
export declare const TEST_FILES: string[]
export declare const NO_ANT_DESIGN: { group: string[]; message: string }
/** The rules for the extension in `dir` (the same for every extension). */
export declare function extensionRulesFor(dir: string): Linter.Config[]
