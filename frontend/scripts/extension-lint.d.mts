import type { Linter } from 'eslint'

export declare const extensionRules: Linter.Config
export declare const ANT_DESIGN_EXEMPT: string[]
export declare function extensionRulesFor(dir: string): Linter.Config
