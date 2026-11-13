import React from 'react'
import TestRenderer, { act } from 'react-test-renderer'
import { describe, expect, it, vi } from 'vitest'
import type { Drive } from '../../services/types'
import AppDriveMonthChart from './AppDriveMonthChart'

vi.mock('@tarojs/components', () => ({ ScrollView: 'scroll-view', Text: 'text', View: 'view' }))

function renderChart(partial: boolean) {
  const now = new Date()
  const date = (offset: number) => new Date(now.getFullYear(), now.getMonth() + offset, 15, 12).toISOString()
  const rows = [{ startDate: date(-2) }, { startDate: date(0) }] as Drive[]
  let rendered!: TestRenderer.ReactTestRenderer
  act(() => { rendered = TestRenderer.create(<AppDriveMonthChart rows={rows} partial={partial} />) })
  return rendered
}

describe('monthly drive chart coverage', () => {
  it('shows exact counts and real zeroes for complete history', () => {
    const chart = renderChart(false)
    const values = chart.root.findAllByProps({ className: 'app-drive-month-value' })
    expect(values.map(value => value.children.join(''))).toEqual(['1', '0', '1'])
    expect(JSON.stringify(chart.toJSON())).not.toContain('数据未完整加载')
  })

  it('distinguishes unknown months and lower-bound counts for partial history', () => {
    const chart = renderChart(true)
    const values = chart.root.findAllByProps({ className: 'app-drive-month-value' })
    expect(values.map(value => value.children.join(''))).toEqual(['≥1', '—', '≥1'])
    const columns = chart.root.findAllByProps({ className: 'app-drive-month-column' })
    expect(columns[0].props.ariaLabel).toContain('至少 1 次')
    expect(columns[1].props.ariaLabel).toContain('未知')
    expect(JSON.stringify(chart.toJSON())).toContain('数据未完整加载')
  })
})
