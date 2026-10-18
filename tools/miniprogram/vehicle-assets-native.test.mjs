import { test } from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import vm from 'node:vm'
import { createRequire } from 'node:module'

const directory = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../miniprogram/vehicle-assets/native/vehicle-asset')
const require = createRequire(import.meta.url)
const manifest = require(path.join(directory, 'manifest.js'))
const key = 'myj_PX02_WY19P', otherKey = 'myj_PR01_WY20A', packageName = manifest[key].packageName
const flush = async () => { for (let i = 0; i < 12; i++) await Promise.resolve() }
function deferred() { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no }); return { promise, resolve, reject } }
function environment(loader) {
  let definition, now = 0, serial = 0
  const timers = new Map()
  vm.runInNewContext(fs.readFileSync(path.join(directory, 'index.js'), 'utf8'), {
    Component(value) { definition = value }, Promise,
    Date: { now: () => now },
    require(name) { return name === './manifest.js' ? manifest : { [packageName]: loader } },
    setTimeout(fn, duration) { const id = ++serial; timers.set(id, { fn, at: now + duration }); return id },
    clearTimeout(id) { timers.delete(id) },
  }, { filename: 'original-native-vehicle-asset.js' })
  const host = () => {
    const component = { data: { ...definition.data }, setData(patch) { Object.assign(this.data, patch) }, triggerEvent() {} }
    for (const [name, method] of Object.entries(definition.methods)) component[name] = method.bind(component)
    definition.lifetimes.created.call(component); definition.lifetimes.attached.call(component)
    return component
  }
  const advance = async duration => {
    const target = now + duration
    for (;;) {
      const next = [...timers].filter(([, timer]) => timer.at <= target).sort((a, b) => a[1].at - b[1].at || a[0] - b[0])[0]
      if (!next) break
      now = next[1].at; timers.delete(next[0]); next[1].fn(); await flush()
    }
    now = target; await flush()
  }
  const loaded = component => component._onImageLoad({ currentTarget: { dataset: { generation: component.data.generation, assetKey: component.data.assetKey } }, detail: { width: 720, height: 405 } })
  return { host, advance, loaded }
}

test('a hung native require.async times out and an explicit retry calls the real loader again', async () => {
  let calls = 0
  const env = environment(() => { calls++; return calls === 1 ? new Promise(() => {}) : Promise.resolve({ packageName }) })
  const component = env.host(), first = component.ensureAsset(key)
  const failed = first.then(() => 'unexpected-success', error => error.code)
  await flush(); await env.advance(20000)
  assert.equal(await failed, 'load_failed')
  const retry = component.ensureAsset(key)
  await flush()
  assert.equal(calls, 2)
  assert.equal(component.data.src, manifest[key].assetPath)
  env.loaded(component)
  assert.equal(await retry, manifest[key].assetPath)
})

test('an expired package resolving late cannot mark a newer request loaded or remove its deduplication record', async () => {
  const old = deferred(), current = deferred()
  let calls = 0
  const env = environment(() => (++calls === 1 ? old.promise : current.promise))
  const firstHost = env.host(), first = firstHost.ensureAsset(key).catch(error => error.code)
  await flush(); await env.advance(20000); assert.equal(await first, 'load_failed')
  const retry = firstHost.ensureAsset(key)
  await flush(); old.resolve({ packageName }); await flush()
  const secondHost = env.host(), shared = secondHost.ensureAsset(otherKey)
  await flush()
  assert.equal(calls, 2)
  assert.equal(firstHost.data.src, '')
  assert.equal(secondHost.data.src, '')
  current.resolve({ packageName }); await flush()
  env.loaded(firstHost); env.loaded(secondHost)
  assert.equal(await retry, manifest[key].assetPath)
  assert.equal(await shared, manifest[otherKey].assetPath)
})

test('clearing a native image rejects its old consumer and ignores a late image event', async () => {
  const env = environment(() => Promise.resolve({ packageName })), component = env.host()
  const first = component.ensureAsset(key).catch(error => error.code)
  await flush()
  const stale = { currentTarget: { dataset: { generation: component.data.generation, assetKey: key } }, detail: { width: 720, height: 405 } }
  const next = component.ensureAsset(otherKey)
  await flush(); component._onImageLoad(stale)
  assert.equal(await first, 'stale_request')
  assert.equal(component._lastLoaded, undefined)
  env.loaded(component)
  assert.equal(await next, manifest[otherKey].assetPath)
})
