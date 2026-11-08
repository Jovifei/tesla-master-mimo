import test from 'node:test'
import assert from 'node:assert/strict'
import { normalizeStatus } from '../src/api/matelink.ts'
import { VEHICLE_COMMAND_CATALOG, commandErrorMessage, needsCommandReauthorization, offeredCommands } from '../src/api/vehicle-commands.ts'
import { amapMarkerUrl, siteDisplayParking, siteDisplayPrice, siteStallsText } from '../src/api/nearby.ts'

const baseStatus = (overrides = {}) => normalizeStatus({ status: { state: 'online', ...overrides } })

test('catalog holds the 16 App/mini commands with unlock as the only confirmation command', () => {
  assert.equal(VEHICLE_COMMAND_CATALOG.length, 16)
  assert.deepEqual(VEHICLE_COMMAND_CATALOG.filter(c => c.needsConfirmation).map(c => c.name), ['unlock'])
})

test('offeredCommands follows the adjudicated gating parity', () => {
  // Authorization not evaluated -> full catalog (App behavior too).
  assert.equal(offeredCommands(baseStatus()).length, 16)
  // Evaluated and ready -> full catalog.
  assert.equal(offeredCommands(baseStatus({ command_authorization: { ready: true, missing_scopes: [] } })).length, 16)
  // Evaluated and not ready -> nothing, reauthorization needed.
  const notReady = baseStatus({ command_authorization: { ready: false, missing_scopes: ['vehicle_cmds'] } })
  assert.equal(offeredCommands(notReady).length, 0)
  assert.equal(needsCommandReauthorization(notReady), true)
  assert.deepEqual(notReady.commandAuthorization.missingScopes, ['vehicle_cmds'])
  // No status -> nothing.
  assert.equal(offeredCommands(null).length, 0)
  assert.equal(needsCommandReauthorization(null), false)
})

test('commandErrorMessage covers the backend error classes', () => {
  assert.equal(commandErrorMessage('commands_disabled'), '车辆控制尚未开启。')
  assert.equal(commandErrorMessage('rate_limited'), '今日命令次数已达上限，请明天再试。')
  assert.equal(commandErrorMessage('something_new'), '命令执行失败，请稍后重试。')
  assert.equal(commandErrorMessage(null), '命令执行失败，请稍后重试。')
})

test('normalizeStatus reads driver temp and command authorization', () => {
  const status = normalizeStatus({
    status: {
      climate_details: { driver_temp_setting: 22.5 },
      command_authorization: { ready: true, missing_scopes: null },
    },
    units: { unit_of_temperature: 'C' },
  })
  assert.equal(status.driverTempC, 22.5)
  assert.equal(status.commandAuthorization.ready, true)
  assert.equal(normalizeStatus({ status: {} }).commandAuthorization, null)
})

test('nearby derivations fall back by site type like the App', () => {
  const superSite = { name: 'Tesla 超级充电站', type: null, priceText: null, parkingPolicy: null, availableStalls: 3, totalStalls: 8, latitude: 30.1, longitude: 120.2 }
  assert.equal(siteDisplayPrice(superSite), '¥1.25/度 (分时)')
  assert.equal(siteDisplayParking(superSite), '充电免费停车2小时')
  assert.equal(siteStallsText(superSite), '3/8')
  const destination = { name: '商场充电', type: 'destination', priceText: null, parkingPolicy: null, availableStalls: null, totalStalls: null, latitude: null, longitude: null }
  assert.equal(siteDisplayPrice(destination), '¥1.15/度')
  assert.equal(siteDisplayParking(destination), '前2小时免费停车')
  assert.equal(siteStallsText(destination), null)
  const explicit = { ...destination, priceText: '¥0.9/度', parkingPolicy: '免费' }
  assert.equal(siteDisplayPrice(explicit), '¥0.9/度')
  assert.equal(siteDisplayParking(explicit), '免费')
})

test('amapMarkerUrl builds a gaode marker link only with coordinates', () => {
  const url = amapMarkerUrl({ name: 'A 站', latitude: 30.25, longitude: 120.16 })
  assert.ok(url.startsWith('https://uri.amap.com/marker?position=120.16,30.25'))
  assert.ok(url.includes('coordinate=gaode'))
  assert.equal(amapMarkerUrl({ name: 'B', latitude: null, longitude: 120 }), null)
})
