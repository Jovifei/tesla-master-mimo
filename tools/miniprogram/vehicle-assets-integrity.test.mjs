import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import crypto from 'node:crypto'
import { fileURLToPath } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../miniprogram')
const inventory = JSON.parse(fs.readFileSync(path.join(root, 'vehicle-assets/integrity.json'), 'utf8')).entries
test('all97 committed originals retain their PNG hashes,720x405 dimensions and RGBA alpha format', () => {
  assert.equal(inventory.length, 97)
  assert.equal(new Set(inventory.map(entry => entry.assetKey)).size, 97)
  let bytes = 0
  for (const entry of inventory) {
    assert.match(entry.assetPath, /^\/vehicle-assets-\d+\/images\/[a-z0-9]+_[A-Z0-9]{4}_[A-Z0-9]{4,5}\.png$/)
    const png = fs.readFileSync(path.join(root, 'vehicle-assets', entry.packageName, 'images', entry.file))
    assert.equal(crypto.createHash('sha256').update(png).digest('hex'), entry.sha256)
    assert.equal(png.length, entry.bytes); bytes += png.length
    assert.equal(png.readUInt32BE(16), 720); assert.equal(png.readUInt32BE(20), 405); assert.equal(png[25], 6)
  }
  assert.equal(bytes, 10_287_155)
})
test('resource groups satisfy internal budgets and runtime manifests contain paths rather than image imports', () => {
  const groups = new Map()
  for (const entry of inventory) groups.set(entry.packageName, (groups.get(entry.packageName) || 0) + entry.bytes)
  assert.equal(groups.size, 6)
  for (const bytes of groups.values()) assert.ok(bytes <= 1.75 * 1024 * 1024)
  const source = fs.readFileSync(path.join(root, 'src/domain/vehicle_visual_manifest.ts'), 'utf8')
  assert.doesNotMatch(source, /(?:import|require)\s*\(?[^\n]*\.png/)
  assert.doesNotMatch(source, /[CE]:[\\/]|\d{4}-\d{2}-\d{2}T/)
})
test('native module uses fixed relative require.async loaders and an internal Image event, without eval or credentials', () => {
  const directory = path.join(root, 'vehicle-assets/native/vehicle-asset')
  const loaders = fs.readFileSync(path.join(directory, 'packages.js'), 'utf8'), component = fs.readFileSync(path.join(directory, 'index.js'), 'utf8')
  assert.equal((loaders.match(/require\.async\('\.\.\/\.\.\/vehicle-assets-\d+\/ready\.js'\)/g) || []).length, 6)
  assert.doesNotMatch(component + loaders, /\beval\s*\(|new\s+Function|loadSub[Pp]ackage|accessToken|Authorization|https?:\/\//)
  assert.equal(JSON.parse(fs.readFileSync(path.join(directory, 'index.json'), 'utf8')).component, true)
  assert.match(fs.readFileSync(path.join(directory, 'index.wxml'), 'utf8'), /bindload="_onImageLoad"/)
})
