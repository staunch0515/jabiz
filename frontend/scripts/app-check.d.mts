export interface ProjectVersions {
  declared: Record<string, string>
  resolved: Map<string, string>
}

export interface PlatformVersions {
  shared: Map<string, string | undefined>
  ui: Map<string, string | undefined>
}

export declare const SHARED: string[]
export declare function bareVersion(version: string): string
export declare function pnpmImporterVersions(lockText: string, importer?: string): Map<string, string>
export declare function npmLockVersions(lockText: string): Map<string, string>
export declare function projectVersions(dir: string): ProjectVersions
export declare function platformVersions(frontendRoot: string): PlatformVersions
export declare function appCheckProblems(app: ProjectVersions, platform: PlatformVersions): string[]
export declare function checkApp(dir: string, frontendRoot: string): string[]
