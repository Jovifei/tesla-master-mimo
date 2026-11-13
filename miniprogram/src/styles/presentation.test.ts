import { resolve } from 'node:path'
import { compile, Logger } from 'sass'
import { parse } from 'postcss'
import { expect, it } from 'vitest'

it('compiles supported reduced-motion selectors for manual and system preferences', () => {
  const css = compile(resolve(process.cwd(), 'src/app.scss'), { logger: Logger.silent }).css
  const sheet = parse(css)
  const manual = new Set<string>(), system = new Set<string>()
  sheet.walkRules(rule => {
    const motion = (property: string, value: string) => rule.nodes.some(node => node.type === 'decl' && node.prop === property && node.value === value && node.important)
    if (!motion('animation', 'none') || !motion('transition', 'none') || !motion('scroll-behavior', 'auto')) return
    expect(rule.selector).not.toMatch(/(^|[\s,>+~])\*(?=$|[\s,>+~.:#\[])/)
    const media = rule.parent?.type === 'atrule' && rule.parent.name === 'media' && rule.parent.params.includes('prefers-reduced-motion')
    for (const selector of rule.selectors) (media ? system : manual).add(selector.trim())
  })
  const targets = ['.card', '.loading-beacon', '.day-stack-body', '.history-record', '.dashboard-hero', '.dashboard-info-card',
    '.dashboard-hud', '.dashboard-history-entry', '.dashboard-current-charge', '.dashboard-partial-hero']
  for (const target of targets) {
    expect(manual.has(`.reduce-motion ${target}`)).toBe(true)
    expect(system.has(`.page ${target}`)).toBe(true)
  }
})
