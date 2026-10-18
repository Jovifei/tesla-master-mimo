import { expect, it } from 'vitest'
import { mapCoordinate } from './map_coordinate'
it('matches App one-time map transform without converting already-GCJ or foreign points', () => {
  const beijing = mapCoordinate(39.9, 116.4)!
  expect(beijing.latitude).toBeCloseTo(39.901403529849404, 8)
  expect(beijing.longitude).toBeCloseTo(116.40624278491117, 8)
  expect(mapCoordinate(beijing.latitude, beijing.longitude, true)).toEqual(beijing)
  expect(mapCoordinate(48.8566, 2.3522)).toEqual({ latitude: 48.8566, longitude: 2.3522 })
  expect(mapCoordinate(0, 0)).toBeNull()
  expect(mapCoordinate(NaN, 116)).toBeNull()
})
