import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'
import vm from 'node:vm'
import { resolveVehicleVisual } from '../domain/vehicle_visual'
const target = () => resolveVehicleVisual({ model: 'Y', exteriorColor: 'PBSB', wheelType: 'Gemini19' })
const host = vi.hoisted(() => ({ component: { ensureAsset: vi.fn(), clearAsset: vi.fn() }, available: true, selections: [] as string[] }))
vi.mock('@tarojs/taro', () => ({ default: {
  nextTick: (callback: () => void) => { callback() },
  getCurrentPages: () => [{ selectComponent: (selector: string) => { host.selections.push(selector); return host.available ? host.component : null } }],
} }))
beforeEach(() => { vi.resetModules(); host.available = true; host.selections.length = 0; host.component.ensureAsset.mockReset(); host.component.clearAsset.mockReset() })
afterEach(() => { vi.useRealTimers() })
it('calls only the native host public asset method and returns its original local path', async () => {
  const { ensureVehicleAsset } = await import('./vehicle_assets'), plan = target()
  host.component.ensureAsset.mockResolvedValue(plan.assetPath)
  await expect(ensureVehicleAsset(plan)).resolves.toBe(plan.assetPath)
  expect(host.component.ensureAsset).toHaveBeenCalledWith(plan.assetKey)
  expect(host.selections).toEqual(['#matelink-vehicle-asset'])
})
it('waits for asynchronous Taro native host attachment within a bounded interval', async () => {
  vi.useFakeTimers(); host.available = false
  const { ensureVehicleAsset } = await import('./vehicle_assets'), plan = target()
  host.component.ensureAsset.mockResolvedValue(plan.assetPath)
  const loading = ensureVehicleAsset(plan)
  await vi.advanceTimersByTimeAsync(90); host.available = true
  await vi.advanceTimersByTimeAsync(30)
  await expect(loading).resolves.toBe(plan.assetPath)
  expect(host.component.ensureAsset).toHaveBeenCalledTimes(1)
})
it('bounds missing hosts and hung image callbacks while allowing explicit retry after a real failure', async () => {
  vi.useFakeTimers(); host.available = false
  const { ensureVehicleAsset, VEHICLE_COMPONENT_WAIT_MS, VEHICLE_ASSET_LOAD_TIMEOUT_MS } = await import('./vehicle_assets')
  const absent = ensureVehicleAsset(target()), rejected = expect(absent).rejects.toMatchObject({ code: 'loader_unavailable' })
  await vi.advanceTimersByTimeAsync(VEHICLE_COMPONENT_WAIT_MS + 60); await rejected
  host.available = true; host.component.ensureAsset.mockImplementation(() => new Promise(() => {}))
  const hanging = ensureVehicleAsset(target()), timedOut = expect(hanging).rejects.toMatchObject({ code: 'load_failed' })
  await vi.advanceTimersByTimeAsync(VEHICLE_ASSET_LOAD_TIMEOUT_MS + 60); await timedOut
  host.component.ensureAsset.mockResolvedValue(target().assetPath)
  await expect(ensureVehicleAsset(target())).resolves.toBe(target().assetPath)
})
it('rejects late consumers, forged remote paths and wrong package metadata without trusting a native result', async () => {
  const { ensureVehicleAsset } = await import('./vehicle_assets')
  let finish: (path: string) => void = () => {}, current = true
  host.component.ensureAsset.mockImplementation(() => new Promise(resolve => { finish = resolve }))
  const pending = ensureVehicleAsset(target(), { isCurrent: () => current })
  await Promise.resolve(); await Promise.resolve(); current = false; finish(target().assetPath!)
  await expect(pending).rejects.toMatchObject({ code: 'stale_request' })
  const before = host.component.ensureAsset.mock.calls.length
  await expect(ensureVehicleAsset({ ...target(), assetPath: 'https://external.test/image.png' })).rejects.toMatchObject({ code: 'invalid_plan' })
  await expect(ensureVehicleAsset({ ...target(), packageName: 'unregistered-package' })).rejects.toMatchObject({ code: 'invalid_plan' })
  expect(host.component.ensureAsset).toHaveBeenCalledTimes(before)
})

/** Execute the shipped unbundled controller at the native require.async boundary.
 * No wx.loadSubpackage, auth/session API or HTTP method is mocked or used. */
function nativeFixture() {
  const directory = path.resolve(process.cwd(), 'vehicle-assets/native/vehicle-asset'), calls: string[] = [], waiting: Array<{ resolve: (value: unknown) => void; reject: (error: unknown) => void; name: string }> = []
  const cache = new Map<string, unknown>(); let definition: any
  function load(file: string): any {
    if (cache.has(file)) return cache.get(file)
    const module = { exports: {} as unknown }
    const nativeRequire = Object.assign((request: string) => load(path.resolve(path.dirname(file), request)), {
      async: (request: string) => {
        const absolute = path.resolve(path.dirname(file), request); calls.push(absolute)
        return new Promise((resolve, reject) => waiting.push({ resolve, reject, name: path.basename(path.dirname(absolute)) }))
      },
    })
    new vm.Script(readFileSync(file, 'utf8'), { filename: file }).runInNewContext({ module, exports: module.exports, require: nativeRequire,
      Component: (value: unknown) => { definition = value }, Promise, Object, Number, Error, setTimeout, clearTimeout })
    cache.set(file, module.exports); return module.exports
  }
  load(path.join(directory, 'index.js'))
  const component: any = { data: { ...definition.data }, events: [] as unknown[], setData(value: object) { Object.assign(this.data, value) }, triggerEvent(name: string, value: object) { this.events.push([name, value]) } }
  Object.assign(component, definition.methods); definition.lifetimes.created.call(component); definition.lifetimes.attached.call(component)
  const image = (width = 720, height = 405) => component._onImageLoad({ currentTarget: { dataset: { generation: component.data.generation, assetKey: component.data.assetKey } }, detail: { width, height } })
  const flush = async () => { for (let i = 0; i < 6; i++) await Promise.resolve() }
  return { component, calls, waiting, image, flush, detach: () => definition.lifetimes.detached.call(component) }
}
it('native module loads only the matching package, deduplicates it and resolves only after real-sized Image load', async () => {
  const fixture = nativeFixture(), plan = target()
  const loading = fixture.component.ensureAsset(plan.assetKey), duplicate = fixture.component.ensureAsset(plan.assetKey)
  expect(duplicate).toBe(loading); await fixture.flush(); expect(fixture.calls).toHaveLength(1)
  expect(fixture.calls[0]).toBe(path.resolve(process.cwd(), `vehicle-assets/${plan.packageName}/ready.js`))
  fixture.waiting[0].resolve({ packageName: plan.packageName }); await fixture.flush()
  expect(fixture.component.data.src).toBe(plan.assetPath)
  fixture.image(); await expect(loading).resolves.toBe(plan.assetPath)
  await expect(fixture.component.ensureAsset(plan.assetKey)).resolves.toBe(plan.assetPath)
  expect(fixture.calls).toHaveLength(1); fixture.detach()
})
it('native load failure retries and a detached old generation never paints or resolves a new image', async () => {
  const fixture = nativeFixture(), plan = target(), first = fixture.component.ensureAsset(plan.assetKey)
  const failed = expect(first).rejects.toMatchObject({ code: 'load_failed' }); await fixture.flush()
  fixture.waiting[0].reject(new Error('do not reflect private native error text')); await fixture.flush(); await failed
  const retry = fixture.component.ensureAsset(plan.assetKey), stale = expect(retry).rejects.toMatchObject({ code: 'stale_request' })
  await fixture.flush(); expect(fixture.calls).toHaveLength(2); fixture.detach()
  fixture.waiting[1].resolve({ packageName: plan.packageName }); await fixture.flush(); await stale
  expect(fixture.component.data.src).toBe('')
})
