import { createHash } from 'node:crypto'
import { readFileSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import { bytesOf } from '../content/bank'
import { planImport, readPackage, type ImportPlanned, type PlanContext, type PlannedStep } from './package'

/*
 * F-LAUNCH Q1 (plan test item 16): the server's PackagePlan (Java) plans every package as this page does. Both sides
 * plan the shared cases (package-plan-cases.json, written by the server's PackagePlanCasesWriterTest from its
 * PackageFixtures) and compare a summary of each plan to package-plan-expected.json. This page's code is the
 * reference: `PACKAGE_PLAN_CASES_UPDATE=1 pnpm vitest run package.plan.cases` rewrites the expected file after a
 * change to the plan; then PackagePlanCasesTest must pass as it is, or the Java is changed to match.
 *
 * The summary has to be made the same way in Java (PackagePlanCases.summary): a document is its question count, its
 * bytes in a request and a digest (SHA-256 of its JSON with the keys sorted); every other part is written out.
 */

const CASES = resolve(__dirname, 'package-plan-cases.json')
const EXPECTED = resolve(__dirname, 'package-plan-expected.json')

type Json = null | boolean | number | string | Json[] | { [key: string]: Json }

interface PlanCase {
  name: string
  package?: unknown
  packageRef?: string
  context: Omit<PlanContext, 'sharedVideos'> & { sharedVideos: string[] }
}

/** JSON with every object's keys sorted (UTF-16 order) and undefined values left out, as JSON.stringify leaves them. */
function canonical(value: unknown): string {
  return JSON.stringify(value, (_key, v: unknown) => {
    if (v === null || typeof v !== 'object' || Array.isArray(v)) return v
    const sorted: Record<string, unknown> = {}
    for (const key of Object.keys(v).sort()) sorted[key] = (v as Record<string, unknown>)[key]
    return sorted
  })
}

const digest = (value: unknown): string => createHash('sha256').update(canonical(value), 'utf8').digest('hex')

function documentSummary(document: Record<string, unknown>): Json {
  return {
    questions: Array.isArray(document.questions) ? document.questions.length : 0,
    bytes: bytesOf(document),
    digest: digest(document),
  }
}

function stepSummary(step: PlannedStep): Json {
  switch (step.kind) {
    case 'profile':
      return { kind: step.kind, key: step.key }
    case 'tree':
      return {
        kind: step.kind, key: step.key, treeKey: step.tree.key,
        tree: digest({
          key: step.tree.key, name: step.tree.name, description: step.tree.description ?? null,
          nodes: step.tree.nodes.map((n) => ({ key: n.key, name: n.name, description: n.description ?? null,
            condition: n.condition })),
        }),
      }
    case 'content':
      return {
        kind: step.kind, key: step.key, quiz: step.quiz, label: step.label, title: step.title, check: step.check,
        into: step.into, topics: step.topics.topics, customTopics: step.topics.customTopics, clears: step.clears,
        document: documentSummary(step.document),
      }
    case 'clear':
      return {
        kind: step.kind, key: step.key, quiz: step.quiz, label: step.label, title: step.title, clear: step.clear,
        clears: step.clears, quizStep: step.quizStep, content: step.content,
      }
    case 'batch':
      return {
        kind: step.kind, key: step.key, quiz: step.quiz, label: step.label, title: step.title, check: step.check,
        batch: step.batch, batches: step.batches, quizStep: step.quizStep, clears: step.clears,
        document: documentSummary(step.document),
      }
    case 'version':
      return {
        kind: step.kind, key: step.key, quiz: step.quiz, label: step.label, title: step.title,
        versionKey: step.versionKey, quizStep: step.quizStep,
      }
    case 'publication': {
      const p = step.publication
      return {
        kind: step.kind, key: step.key, index: step.index, name: step.name, quizStep: step.quizStep,
        versionStep: step.versionStep, node: step.node as Json,
        publication: digest({
          quiz: p.quiz, version: p.version, settings: p.settings, node: p.node ?? null, topics: p.topics ?? null,
          sharedVideo: p.sharedVideo ?? null,
        }),
      }
    }
  }
}

function planSummary(planned: ImportPlanned): Json {
  return {
    mode: planned.mode,
    batchSize: planned.batchSize,
    batchBytes: planned.batchBytes,
    makes: planned.makes as unknown as Json,
    sameTitles: planned.sameTitles,
    treesSkipped: planned.treesSkipped,
    treesKept: planned.treesKept,
    unbound: planned.unbound,
    videosAgain: planned.videosAgain,
    tooLarge: planned.tooLarge,
    oversized: planned.oversized,
    orphans: planned.orphans,
    steps: planned.steps.map(stepSummary),
  }
}

function summaryOf(planCase: PlanCase, packages: Record<string, unknown>): Json {
  const value = planCase.packageRef !== undefined ? packages[planCase.packageRef] : planCase.package
  const read = readPackage(value)
  if ('problem' in read) return { problem: read.problem, places: read.places ?? [] }
  const context: PlanContext = { ...planCase.context, sharedVideos: new Set(planCase.context.sharedVideos) }
  return { plan: planSummary(planImport(read.document, context)) }
}

describe('the package plan on the shared cases', () => {
  const file = JSON.parse(readFileSync(CASES, 'utf8')) as { packages: Record<string, unknown>; cases: PlanCase[] }
  const summaries = file.cases.map((c) => ({ name: c.name, summary: summaryOf(c, file.packages) }))

  if (process.env.PACKAGE_PLAN_CASES_UPDATE === '1') {
    writeFileSync(EXPECTED, JSON.stringify({
      about: 'F-LAUNCH Q1: the plans of package-plan-cases.json, written by package.plan.cases.test.ts (the reference).',
      cases: summaries,
    }, null, 2) + '\n')
  }

  const expected = JSON.parse(readFileSync(EXPECTED, 'utf8')) as { cases: { name: string; summary: Json }[] }

  it('has an expectation for every case, in order', () => {
    expect(expected.cases.map((c) => c.name)).toEqual(summaries.map((c) => c.name))
  })

  it.each(summaries.map((s, i) => [s.name, i] as const))('plans %s as expected', (_name, i) => {
    expect(summaries[i].summary).toEqual(expected.cases[i].summary)
  })

  it('reaches every kind of step and every problem', () => {
    const kinds = new Set<string>()
    const problems = new Set<string>()
    for (const { summary } of summaries) {
      const s = summary as { plan?: { steps: { kind: string }[] }; problem?: string }
      s.plan?.steps.forEach((step) => kinds.add(step.kind))
      if (s.problem) problems.add(s.problem)
    }
    expect([...kinds].sort()).toEqual(['batch', 'clear', 'content', 'profile', 'publication', 'tree', 'version'])
    expect([...problems].sort()).toEqual(['newerVersion', 'notPackage', 'shape'])
  })
})
