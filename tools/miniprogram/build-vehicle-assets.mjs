import fs from 'node:fs'
import path from 'node:path'
import crypto from 'node:crypto'
import { fileURLToPath } from 'node:url'

const project = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../miniprogram')
const assets = path.join(project, 'vehicle-assets')
const MAX_GROUP = 1.75 * 1024 * 1024, MAX_MAIN = 2 * 1024 * 1024, MAX_TOTAL = 12 * 1024 * 1024
const sha = data => crypto.createHash('sha256').update(data).digest('hex')
const fail = message => { throw new Error(message) }
const args = process.argv.slice(2), sourceIndex = args.indexOf('--source')
const source = sourceIndex >= 0 ? path.resolve(args[sourceIndex + 1] || fail('Missing source directory')) : null
const pattern = /^(m3|m3h|m3hp|my|myjs|myj|myjp|ms|mx)_([A-Z0-9]{4})_([A-Z0-9]{4,5})\.png$/
const json = value => JSON.stringify(value, null, 2) + '\n'
function write(file, content) { fs.mkdirSync(path.dirname(file), { recursive: true }); fs.writeFileSync(file, content) }
function groupsFor(rows) {
  const groups = []
  for (const row of rows) {
    if (row.bytes > MAX_GROUP) fail(`Image exceeds group budget: ${row.file}`)
    let group = groups[groups.length - 1]
    if (!group || group.bytes + row.bytes > MAX_GROUP) { group = { name: `vehicle-assets-${groups.length + 1}`, bytes: 0, rows: [] }; groups.push(group) }
    group.rows.push(row); group.bytes += row.bytes
  }
  return groups
}
function expected(rows) {
  const groups = groupsFor(rows), runtime = {}, entries = []
  for (const group of groups) for (const row of group.rows) {
    const assetPath = `/${group.name}/images/${row.file}`, assetKey = row.file.slice(0, -4)
    runtime[assetKey] = { assetPath, packageName: group.name }
    entries.push({ ...row, assetKey, assetPath, packageName: group.name })
  }
  return { groups, runtime, entries }
}
function generated(rows) {
  const result = expected(rows)
  const config = result.groups.map(group => ({ root: group.name, name: group.name, pages: ['index'] }))
  const typescript = '// Generated deterministically from the committed original App PNG copies. No image imports.\n' +
    'export const vehicleVisualAssets: Record<string, { assetPath: string; packageName: string }> = ' + json(result.runtime).trimEnd() + '\n'
  const packages = '// Generated public resource subpackages; no user-facing business pages.\nexport const vehicleAssetPackages = ' + json(config).trimEnd() + '\n'
  const nativeManifest = '// Public string paths only; deliberately outside Webpack.\nmodule.exports = ' + json(result.runtime).trimEnd() + '\n'
  const loaders = '// Native require.async is retained in this module scope.\nmodule.exports = {\n' + result.groups.map(group =>
    `  '${group.name}': function () { return require.async('../../${group.name}/ready.js') }`).join(',\n') + '\n}\n'
  return { ...result, config, typescript, packages, nativeManifest, loaders }
}
if (source) {
  const files = fs.readdirSync(source).filter(file => file.endsWith('.png')).sort()
  if (files.length !== 97 || files.some(file => !pattern.test(file))) fail('Expected the exact97 named original App PNGs')
  const rows = files.map(file => { const bytes = fs.readFileSync(path.join(source, file)); return { file, bytes: bytes.length, sha256: sha(bytes) } })
  const result = generated(rows)
  let count = 0
  for (const entry of result.entries) {
    const target = path.join(project, 'vehicle-assets', entry.packageName, 'images', entry.file)
    if (fs.existsSync(target) && sha(fs.readFileSync(target)) !== entry.sha256) fail(`Existing asset differs; refusing overwrite: ${entry.file}`)
    fs.mkdirSync(path.dirname(target), { recursive: true }); fs.copyFileSync(path.join(source, entry.file), target)
    if (sha(fs.readFileSync(target)) !== entry.sha256) fail(`Copy mismatch: ${entry.file}`)
    console.log(`[${++count}/97] ${entry.file}`)
  }
  write(path.join(assets, 'integrity.json'), json({ schema: 1, entries: result.entries }))
  write(path.join(project, 'src/domain/vehicle_visual_manifest.ts'), result.typescript)
  write(path.join(project, 'vehicle-assets.config.ts'), result.packages)
  write(path.join(assets, 'native/vehicle-asset/manifest.js'), result.nativeManifest)
  write(path.join(assets, 'native/vehicle-asset/packages.js'), result.loaders)
  for (const group of result.groups) {
    write(path.join(assets, group.name, 'ready.js'), `module.exports = { packageName: '${group.name}' }\n`)
    write(path.join(project, `src/${group.name}/index.tsx`), "import { View } from '@tarojs/components'\nexport default function VehicleAssetsInternalPage() { return <View /> }\n")
  }
}
const integrity = JSON.parse(fs.readFileSync(path.join(assets, 'integrity.json'), 'utf8'))
if (integrity.schema !== 1 || !Array.isArray(integrity.entries) || integrity.entries.length !== 97) fail('Invalid original image inventory')
const rows = integrity.entries.map(({ file, bytes, sha256 }) => ({ file, bytes, sha256 })).sort((a, b) => a.file < b.file ? -1 : a.file > b.file ? 1 : 0)
if (new Set(rows.map(row => row.file)).size !== 97 || rows.some(row => !pattern.test(row.file))) fail('Duplicate or invalid asset filename')
const result = generated(rows)
if (json(integrity.entries) !== json(result.entries)) fail('Non-deterministic or stale integrity entries')
for (const [file, expectedText] of [
  ['src/domain/vehicle_visual_manifest.ts', result.typescript], ['vehicle-assets.config.ts', result.packages],
  ['vehicle-assets/native/vehicle-asset/manifest.js', result.nativeManifest], ['vehicle-assets/native/vehicle-asset/packages.js', result.loaders],
]) if (fs.readFileSync(path.join(project, file), 'utf8') !== expectedText) fail(`Generated strings are stale: ${file}`)
for (const entry of result.entries) {
  const bytes = fs.readFileSync(path.join(assets, entry.packageName, 'images', entry.file))
  if (bytes.length !== entry.bytes || sha(bytes) !== entry.sha256) fail(`Original PNG bytes changed: ${entry.file}`)
  if (bytes.toString('hex', 0, 8) !== '89504e470d0a1a0a' || bytes.readUInt32BE(16) !== 720 || bytes.readUInt32BE(20) !== 405 || bytes[25] !== 6) fail(`Original dimensions/alpha format changed: ${entry.file}`)
}
const report = { images: result.entries.length, originalPngBytes: rows.reduce((sum, row) => sum + row.bytes, 0),
  groups: result.groups.map(group => ({ name: group.name, images: group.rows.length, originalPngBytes: group.bytes })),
  internalBudgets: { main: MAX_MAIN, group: MAX_GROUP, total: MAX_TOTAL }, platformLimitClaim: false }
function walk(directory, prefix = '') {
  return fs.readdirSync(directory, { withFileTypes: true }).flatMap(entry => entry.isDirectory() ? walk(path.join(directory, entry.name), prefix + entry.name + '/')
    : [{ name: prefix + entry.name, bytes: fs.readFileSync(path.join(directory, entry.name)) }])
}
if (args.includes('--check-dist')) {
  const dist = path.join(project, 'dist'), all = walk(dist), app = JSON.parse(fs.readFileSync(path.join(dist, 'app.json'), 'utf8'))
  const definitions = app.subpackages ?? app.subPackages ?? []
  for (const pkg of result.config) if (!definitions.some(entry => entry.root === pkg.root && entry.name === pkg.name && json(entry.pages) === json(pkg.pages))) fail(`Missing runtime subpackage: ${pkg.name}`)
  for (const entry of result.entries) {
    const found = all.find(file => file.name === entry.assetPath.slice(1))
    if (!found || found.bytes.length !== entry.bytes || sha(found.bytes) !== entry.sha256) fail(`Built PNG mismatch: ${entry.file}`)
  }
  const groups = result.groups.map(group => ({ name: group.name, bytes: all.filter(file => file.name.startsWith(group.name + '/')).reduce((sum, file) => sum + file.bytes.length, 0) }))
  const main = all.filter(file => !result.groups.some(group => file.name.startsWith(group.name + '/'))).reduce((sum, file) => sum + file.bytes.length, 0)
  const total = all.reduce((sum, file) => sum + file.bytes.length, 0)
  if (main > MAX_MAIN || total > MAX_TOTAL || groups.some(group => group.bytes > MAX_GROUP)) fail('Built package exceeds an internal conservative budget')
  const canonicalFiles = [...all].sort((a, b) => a.name < b.name ? -1 : a.name > b.name ? 1 : 0)
    .map(file => `${file.name}\0${file.bytes.length}\0${sha(file.bytes)}`).join('\n')
  report.built = { main, groups, total, originalPngHashesMatch: true,
    compiledFiles: all.length, contentSha256: sha(canonicalFiles) }
}
console.log(JSON.stringify(report, null, 2))
