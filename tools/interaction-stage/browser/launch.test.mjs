import { test } from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import os from 'node:os'
import { launchIsolatedBrowser } from './launch.mjs'

async function fixture(body, check) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'matelink-launch-test-'))
  const chrome = path.join(root, 'synthetic-browser.cjs'), evidence = path.join(root, 'evidence')
  fs.writeFileSync(chrome, '#!/usr/bin/env node\nif(process.argv.includes("--version")){console.log("Synthetic test browser");process.exit(0)}\n' + body, { mode: 0o700 })
  try { await check({ chrome, evidence, root }) } finally { fs.rmSync(root, { recursive: true, force: true }) }
}

test('readiness requires actual loopback DevTools without a profile marker', async () => {
  await fixture(`
const http=require('node:http'),fs=require('node:fs'),path=require('node:path');
const arg=p=>process.argv.find(x=>x.startsWith(p)).slice(p.length);
const port=Number(arg('--remote-debugging-port=')),profile=arg('--user-data-dir=');
fs.writeFileSync(path.join(__dirname,'profile.txt'),profile);
const server=http.createServer((req,res)=>{res.setHeader('Content-Type','application/json');res.end(JSON.stringify({Browser:'Synthetic/1','Protocol-Version':'1.3',webSocketDebuggerUrl:'ws://127.0.0.1:'+port+'/devtools/browser/synthetic'}))});
server.listen(port,'127.0.0.1');
`, async ({ chrome, evidence, root }) => {
    const browser = await launchIsolatedBrowser({ chrome, evidence, bootTimeoutMs: 3000 })
    const profile = fs.readFileSync(path.join(root, 'profile.txt'), 'utf8')
    assert.equal(fs.existsSync(path.join(profile, 'DevToolsActivePort')), false)
    assert.equal(JSON.parse(fs.readFileSync(path.join(evidence, 'browser-launch.json'))).freshProfile, true)
    await browser.close()
    assert.equal(fs.existsSync(profile), false)
    await assert.rejects(fetch(browser.endpoint, { signal: AbortSignal.timeout(1000) }))
  })
})

test('early process failure returns diagnostic output and cannot become a pass', async () => {
  await fixture('console.error("synthetic launch failure");process.exit(17)', async ({ chrome, evidence }) => {
    await assert.rejects(launchIsolatedBrowser({ chrome, evidence, bootTimeoutMs: 3000 }), /code=17[\s\S]*synthetic launch failure/)
    assert.equal(fs.existsSync(path.join(evidence, 'browser-launch.json')), false)
  })
})

test('a hung executable is bounded and its fresh profile is removed', async () => {
  await fixture(`const fs=require('node:fs'),path=require('node:path');fs.writeFileSync(path.join(__dirname,'profile.txt'),process.argv.find(x=>x.startsWith('--user-data-dir=')).slice(16));setInterval(()=>{},1000)`, async ({ chrome, evidence, root }) => {
    await assert.rejects(launchIsolatedBrowser({ chrome, evidence, bootTimeoutMs: 500 }), /startup exceeded/)
    assert.equal(fs.existsSync(fs.readFileSync(path.join(root, 'profile.txt'), 'utf8')), false)
    assert.equal(fs.existsSync(path.join(evidence, 'browser-launch.json')), false)
  })
})

test('missing browser and unbounded deadlines are rejected', async () => {
  await assert.rejects(launchIsolatedBrowser({ chrome: '', evidence: '.', bootTimeoutMs: 1000 }), /bounded/)
  await assert.rejects(launchIsolatedBrowser({ chrome: 'unused', evidence: '.', bootTimeoutMs: 60001 }), /bounded/)
})
