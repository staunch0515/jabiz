import type { Linter } from 'eslint'

export declare const extensionRules: Linter.Config
/** The rules for the extension in `dir` (the same for every extension). */
export declare function extensionRulesFor(dir: string): Linter.Config
