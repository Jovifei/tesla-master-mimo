import { expect, it } from 'vitest'
import { sourceLabel } from './data_labels'

it('identifies the Fleet API source instead of labelling it absent', () => {
  expect(sourceLabel('fleet_api')).toBe('Fleet API')
  expect(sourceLabel(null)).toBe('来源未标明')
})
