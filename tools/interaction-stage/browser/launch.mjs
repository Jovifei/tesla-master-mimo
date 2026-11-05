/** Disposable local browser only. A reachable DevTools endpoint, not a marker file,
 * proves readiness. No Owner profile, remote service or application dependency is used. */
import fs from 'node:fs'
import path from 'node:path'
import os from 'node:os'
import net from 'node:net'
import { spawn, execFileSync } from 'node:child_process'
import { setTimeout as sleep } from 'node:timers/promises'

async function reservePort() {
  const socket = net.createServer()
  await new Promise((resolve, reject) => { socket.once('error', reject); socket.listen(0, '127.0.0.1', resolve) })
  const port = socket.address().port
  await new Promise((resolve, reject) => socket.close(error => error ? reject(error) : resolve()))
  return port
}

export async function launchIsolatedBrowser({ chrome, evidence, bootTimeoutMs = 60_000 }) {
  if (!chrome || !Number.isFinite(bootTimeoutMs) || bootTimeoutMs <= 0 || bootTimeoutMs > 60_000)
    throw new Error('An executable and bounded browser startup deadline are required')
  fs.mkdirSync(evidence, { recursive: true })
  const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'matelink-visual-'))
  const port = await reservePort(), endpoint = `http://127.0.0.1:${port}`
  const logPath = path.join(evidence, 'chromium.log')
  const logFd = fs.openSync(logPath, 'w')
  const args = ['--headless=new', '--no-sandbox', '--disable-gpu', '--disable-dev-shm-usage',
    '--no-first-run', '--no-default-browser-check', '--disable-background-networking',
    '--disable-component-update', '--disable-sync', '--password-store=basic', '--hide-scrollbars',
    '--remote-debugging-address=127.0.0.1', `--remote-debugging-port=${port}`,
    `--user-data-dir=${profile}`, 'about:blank']
  let child, spawnError, closed = false
  const close = async () => {
    if (closed) return
    closed = true
    if (child && child.exitCode === null && child.signalCode === null) {
      child.kill('SIGTERM')
      for (let i = 0; i < 20 && child.exitCode === null && child.signalCode === null; i++) await sleep(50)
      if (child.exitCode === null && child.signalCode === null) child.kill('SIGKILL')
      for (let i = 0; i < 20 && child.exitCode === null && child.signalCode === null; i++) await sleep(50)
    }
    fs.rmSync(profile, { recursive: true, force: true, maxRetries: 3, retryDelay: 50 })
  }
  try {
    let version
    try { version = execFileSync(chrome, ['--version'], { encoding: 'utf8', timeout: 5000 }).trim() }
    catch (error) { version = `version probe unavailable: ${error.code || error.name}` }
    child = spawn(chrome, args, { stdio: ['ignore', logFd, logFd] })
    child.once('error', error => { spawnError = error })
    fs.closeSync(logFd)
    const started = Date.now(), deadline = started + bootTimeoutMs
    let lastFailure = 'not yet reachable'
    while (Date.now() < deadline) {
      if (spawnError) throw new Error(`Browser spawn failed: ${spawnError.code || spawnError.message}`)
      if (child.exitCode !== null || child.signalCode !== null)
        throw new Error(`Browser exited before readiness: code=${child.exitCode} signal=${child.signalCode}`)
      try {
        const response = await fetch(`${endpoint}/json/version`, { signal: AbortSignal.timeout(Math.min(800, Math.max(1, deadline - Date.now()))) })
        if (!response.ok) throw new Error(`DevTools HTTP ${response.status}`)
        const data = await response.json()
        const websocket = new URL(data.webSocketDebuggerUrl)
        if (websocket.protocol !== 'ws:' || !['127.0.0.1', 'localhost'].includes(websocket.hostname) || Number(websocket.port) !== port)
          throw new Error('DevTools endpoint did not belong to the isolated loopback browser')
        fs.writeFileSync(path.join(evidence, 'browser-launch.json'), JSON.stringify({
          executable: path.basename(chrome), version, browser: data.Browser,
          protocol: data['Protocol-Version'], startupMs: Date.now() - started,
          deadlineMs: bootTimeoutMs, freshProfile: true, loopbackOnly: true,
        }, null, 2))
        return { endpoint, close }
      } catch (error) { lastFailure = error.message }
      await sleep(100)
    }
    throw new Error(`Browser startup exceeded ${bootTimeoutMs}ms: ${lastFailure}`)
  } catch (error) {
    try { fs.closeSync(logFd) } catch {}
    await close()
    const diagnostic = fs.existsSync(logPath) ? fs.readFileSync(logPath, 'utf8').slice(-3000) : ''
    throw new Error(`${error.message}\nIsolated browser diagnostics:\n${diagnostic || '(no output)'}`, { cause: error })
  }
}
