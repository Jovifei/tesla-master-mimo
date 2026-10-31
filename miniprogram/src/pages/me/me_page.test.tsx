import React from 'react'
import TestRenderer, { act, type ReactTestInstance, type ReactTestRenderer } from 'react-test-renderer'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'

const platform = vi.hoisted(() => ({ request: vi.fn(), bindingRequest: vi.fn(), login: vi.fn(), modal: vi.fn(), values: new Map<string, unknown>(), failPreferenceWrite: false, failPreferenceRemove: false, failConsentWrite: false, failConsentRemove: false, toast: vi.fn(), navigate: vi.fn(), switchTab: vi.fn(), clipboard: vi.fn(),
  show: (() => {}) as () => void, hide: (() => {}) as () => void, unload: (() => {}) as () => void }))
vi.mock('@tarojs/components', () => ({ View: 'view', Text: 'text', Image: 'image', Button: 'button', Checkbox: 'checkbox', CheckboxGroup: 'checkboxgroup' }))
vi.mock('@tarojs/taro', () => ({ default: {
  request: (options: { url: string }) => options.url.endsWith('/v1/auth/wechat/binding') ? platform.bindingRequest(options) : platform.request(options),
  login: platform.login, showModal: platform.modal, getStorageSync: (key: string) => platform.values.get(key),
  setStorageSync: (key: string, value: unknown) => { if ((key.startsWith('matelink.wechat.recovery.v1.') && platform.failPreferenceWrite) || (key === 'matelink.consent.v1' && platform.failConsentWrite)) throw Error('storage failed'); platform.values.set(key, value) },
  removeStorageSync: (key: string) => { if ((key.startsWith('matelink.wechat.recovery.v1.') && platform.failPreferenceRemove) || (key === 'matelink.consent.v1' && platform.failConsentRemove)) throw Error('storage failed'); platform.values.delete(key) }, showToast: platform.toast,
  navigateTo: platform.navigate, switchTab: platform.switchTab, setClipboardData: platform.clipboard,
}, useDidShow: (fn: () => void) => { platform.show = fn }, useDidHide: (fn: () => void) => { platform.hide = fn },
useUnload: (fn: () => void) => { platform.unload = fn } }))
const key = 'matelink.wechat.session.v1'
const session = (userId: string) => ({ apiOrigin: 'https://api.example.test', accessToken: `fixture-${userId}`, refreshToken: null, expiresAt: null, userId, linkRequired: false })
const respond = (statusCode: number, data: unknown) => ({ statusCode, data, header: {} })
let renderer: ReactTestRenderer | null = null
beforeEach(() => {
  vi.resetModules(); vi.stubEnv('TARO_APP_API_BASE_URL', 'https://api.example.test')
  platform.values.clear(); platform.request.mockReset(); platform.bindingRequest.mockReset().mockResolvedValue(respond(200, { bound: false, binding_version: '0' })); platform.login.mockReset(); platform.modal.mockReset(); platform.toast.mockReset(); platform.navigate.mockReset().mockResolvedValue({}); platform.switchTab.mockReset().mockResolvedValue({}); platform.clipboard.mockReset().mockResolvedValue({})
  platform.failPreferenceWrite = false; platform.failPreferenceRemove = false; platform.failConsentWrite = false; platform.failConsentRemove = false
  platform.values.set(key, session('a'))
})
afterEach(async () => {
  if (renderer) { await act(async () => { platform.unload(); renderer!.unmount() }); renderer = null }
  vi.unstubAllEnvs()
})
async function flush() { await act(async () => { await new Promise(resolve => setTimeout(resolve, 0)) }) }
async function mount() {
  const Page = (await import('./index')).default
  await act(async () => { renderer = TestRenderer.create(React.createElement(Page)); })
  await act(async () => { platform.show() }); await flush()
}
async function logout() {
  const button = renderer!.root.findAllByType('button').find(node => node.children.join('') === '退出登录')!
  await act(async () => { button.props.onClick() }); await flush()
}
const visible = () => JSON.stringify(renderer!.toJSON())

it('More mirrors the App section order and opens existing analysis and live readiness routes', async () => {
  await mount()
  const labels = renderer!.root.findAllByProps({ className: 'more-tile-label' }).map(node => node.children.join(''))
  expect(labels).toEqual(['统计概览', '电池健康', '里程统计', '行程历史', '长途旅程', '能耗', '费用分析', '续航分析', '待机能耗', '时间线', '年度报告', '导出数据'])
  expect(visible()).not.toContain('查看数据')
  expect(visible()).not.toContain('查看车辆与数据状态')
  const system = renderer!.root.findAllByProps({ className: 'more-system-label' }).map(node => node.children.join(''))
  expect(system).toEqual(['数据状态', '软件更新', '哨兵历史', '设置', '关于'])
  const tiles = renderer!.root.findAllByType('button').filter(node => node.props.className === 'more-action more-tile')
  const systemRows = renderer!.root.findAllByType('button').filter(node => node.props.className === 'more-action more-system-action')
  const iconName = (node: ReactTestInstance) => node.findAll(child => typeof child.props.className === 'string' && child.props.className.startsWith('visual-icon visual-icon-'))[0].props.className.replace('visual-icon visual-icon-', '')
  expect(tiles.map(iconName)).toEqual(['speed', 'battery', 'distance', 'clock', 'route', 'chart', 'money', 'route', 'energy', 'timeline', 'chart', 'update'])
  expect(systemRows.map(iconName)).toEqual(['shield', 'update', 'shield', 'settings', 'info'])
  await act(async () => { tiles[0].props.onClick(); tiles[3].props.onClick(); tiles[11].props.onClick(); systemRows[0].props.onClick() })
  expect(platform.navigate).toHaveBeenCalledWith({ url: '/pages/analysis/index?kind=stats' })
  expect(platform.navigate).toHaveBeenCalledWith({ url: '/pages/tools/index?kind=export' })
  expect(platform.switchTab).toHaveBeenCalledWith({ url: '/pages/drives/index' })
  expect(platform.switchTab).toHaveBeenCalledWith({ url: '/pages/vehicle/index' })
  expect(platform.values.get('matelink.ui.data-status.v1')).toMatchObject({ userId: 'a', origin: 'https://api.example.test', at: expect.any(Number) })
})

/** Actual page -> actual API logout -> platform transport/storage, no API mocking. */
it('successful logout exposes login and clears the actual local session', async () => {
  platform.request.mockResolvedValueOnce(respond(200, { status: 'logged_out' }))
  await mount(); await logout()
  expect(platform.values.has(key)).toBe(false)
  expect(visible()).toContain('尚未登录')
  expect(renderer!.root.findAllByType('button').some(node => node.children.join('') === '特斯拉账号登录')).toBe(true)
})

it.each(['network', 'http500'])('failed %s logout still synchronizes already-cleared local session', async failure => {
  if (failure === 'network') platform.request.mockRejectedValueOnce(Error('PRIVATE_TRANSPORT_DIAGNOSTIC'))
  else platform.request.mockResolvedValueOnce(respond(500, { message: 'PRIVATE_SERVER_DIAGNOSTIC' }))
  await mount(); await logout()
  expect(platform.values.has(key)).toBe(false)
  expect(visible()).toContain('尚未登录')
  expect(visible()).not.toContain('PRIVATE_')
  expect(renderer!.root.findAllByType('button').some(node => node.children.join('') === '特斯拉账号登录')).toBe(true)
  expect(platform.toast).not.toHaveBeenCalled()
})

it('shows that automatic recovery could resume after restart when logout and local storage both fail', async () => {
  const storage = await import('../../services/session')
  storage.writeWechatRecoveryPreference({ setStorageSync: (name, value) => platform.values.set(name, value) },
    { apiOrigin: 'https://api.example.test', boundUserId: 'a', enabled: true })
  platform.failPreferenceWrite = true; platform.failPreferenceRemove = true
  platform.request.mockRejectedValue(Error('PRIVATE_TRANSPORT_DIAGNOSTIC'))
  await mount(); await logout()
  expect(visible()).toContain('重启后可能重新进入')
  expect(visible()).toContain('退出请求未确认')
  expect(visible()).not.toContain('PRIVATE_TRANSPORT_DIAGNOSTIC')
  expect(platform.values.has(key)).toBe(false)
})

it('late successful logout synchronizes a newer local session instead of clearing its UI', async () => {
  const { matelinkApi } = await import('../../services/api')
  let release!: (value: unknown) => void
  platform.request.mockImplementationOnce(() => new Promise(resolve => { release = resolve }))
  await mount(); await logout()
  platform.request.mockResolvedValueOnce(respond(200, { access_token: 'fixture-b', user: { id: 'b' }, link_required: false }))
  await act(async () => { await matelinkApi.exchangeTeslaTicket('fixture-ticket') })
  await act(async () => { release(respond(200, { status: 'logged_out' })) }); await flush()
  expect(platform.values.get(key)).toEqual(session('b'))
  expect(visible()).toContain('会话已建立')
  expect(renderer!.root.findAllByType('button').some(node => node.children.join('') === '特斯拉账号登录')).toBe(false)
  expect(platform.toast).not.toHaveBeenCalled()
})

it('hidden lifecycle invalidates a late logout completion and re-show reads the new session', async () => {
  let release!: (value: unknown) => void
  platform.request.mockImplementationOnce(() => new Promise(resolve => { release = resolve }))
  await mount(); await logout()
  await act(async () => { platform.hide() })
  platform.values.set(key, session('b'))
  await act(async () => { release(respond(200, { status: 'logged_out' })) }); await flush()
  expect(platform.toast).not.toHaveBeenCalled()
  await act(async () => { platform.show() }); await flush()
  expect(platform.values.get(key)).toEqual(session('b'))
  expect(visible()).toContain('会话已建立')
})

const pendingKey = 'matelink.wechat.tesla-auth.v1'
const pending = (transactionId: string) => ({ transactionId, clientProof: `fixture-proof-${transactionId}`, apiOrigin: 'https://api.example.test', expiresAt: null })
const button = (label: string) => renderer!.root.findAllByType('button').find(node => node.children.join('') === label)!
async function consent() {
  await act(async () => { renderer!.root.findByType('checkboxgroup' as React.ElementType).props.onChange({ detail: { value: ['terms', 'privacy'] } }) })
}
it('does not infer current consent from an existing session or restore a withdrawn consent', async () => {
  platform.values.set('matelink.consent.v1', { termsVersion: 'old', privacyVersion: 'old', acceptedAtEpochMs: Date.now() })
  await mount()
  const checked = () => renderer!.root.findAllByType('checkbox' as React.ElementType).map(node => node.props.checked)
  expect(checked()).toEqual([false, false])
  expect(button('重新授权 Tesla').props.disabled).toBe(true)
  await consent()
  expect(checked()).toEqual([true, true])
  expect(button('重新授权 Tesla').props.disabled).toBe(false)
  await act(async () => { renderer!.root.findByType('checkboxgroup' as React.ElementType).props.onChange({ detail: { value: ['terms'] } }) })
  await act(async () => { platform.hide(); platform.show() }); await flush()
  expect(checked()).toEqual([false, false])
})
it('keeps withdrawn consent unchecked and reports a storage failure when the prior record cannot be removed', async () => {
  await mount(); await consent()
  platform.failConsentWrite = true; platform.failConsentRemove = true
  await act(async () => { renderer!.root.findByType('checkboxgroup' as React.ElementType).props.onChange({ detail: { value: ['terms'] } }) })
  await act(async () => { platform.hide(); platform.show() }); await flush()
  expect(renderer!.root.findAllByType('checkbox' as React.ElementType).map(node => node.props.checked)).toEqual([false, false])
  expect(visible()).toContain('本机同意状态未能更新')
})
it('asks for confirmation before binding the current WeChat identity', async () => {
  platform.login.mockResolvedValue({ code: 'synthetic-code' })
  platform.modal.mockResolvedValueOnce({ confirm: false }).mockResolvedValueOnce({ confirm: true })
  platform.request.mockResolvedValue(respond(200, { status: 'bound' }))
  await mount()
  await act(async () => { button('绑定当前微信').props.onClick() }); await flush()
  expect(platform.login).not.toHaveBeenCalled()
  await act(async () => { button('绑定当前微信').props.onClick() }); await flush()
  expect(platform.login).toHaveBeenCalledTimes(1)
  expect(platform.request.mock.calls[0][0]).toMatchObject({ url: 'https://api.example.test/v1/auth/wechat/bind' })
  expect(visible()).toContain('此账号已绑定微信')
})

it('shows the server binding and local failure when binding succeeds but preference storage fails', async () => {
  const storage = await import('../../services/session')
  storage.writeWechatRecoveryPreference({ setStorageSync: (name, value) => platform.values.set(name, value) },
    { apiOrigin: 'https://api.example.test', boundUserId: 'a', enabled: true })
  platform.failPreferenceWrite = true; platform.failPreferenceRemove = true
  platform.bindingRequest.mockResolvedValueOnce(respond(200, { bound: false, binding_version: '0' }))
    .mockResolvedValueOnce(respond(200, { bound: false, binding_version: '0' }))
    .mockResolvedValueOnce(respond(200, { bound: true, binding_version: '1' }))
  platform.login.mockResolvedValue({ code: 'fresh-code' })
  platform.modal.mockResolvedValue({ confirm: true })
  platform.request.mockResolvedValue(respond(200, { status: 'bound' }))
  await mount()
  await act(async () => { button('绑定当前微信').props.onClick() }); await flush()
  expect(visible()).toContain('本机快捷进入设置无法保存')
  expect(visible()).toContain('此账号已绑定微信')
  expect(visible()).not.toContain('本机已启用快捷进入')
  expect(button('解绑微信')).toBeDefined()
})

it('shows the server unbinding and local failure while retaining a Tesla session', async () => {
  const storage = await import('../../services/session')
  storage.writeWechatRecoveryPreference({ setStorageSync: (name, value) => platform.values.set(name, value) },
    { apiOrigin: 'https://api.example.test', boundUserId: 'a', enabled: true })
  platform.failPreferenceWrite = true; platform.failPreferenceRemove = true
  platform.bindingRequest.mockResolvedValueOnce(respond(200, { bound: true, binding_version: '0' }))
    .mockResolvedValueOnce(respond(200, { bound: false, binding_version: '1' }))
  platform.modal.mockResolvedValue({ confirm: true })
  platform.request.mockResolvedValue(respond(200, { status: 'unbound' }))
  await mount()
  await act(async () => { button('解绑微信').props.onClick() }); await flush()
  expect(visible()).toContain('本机快捷进入设置无法保存')
  expect(visible()).toContain('此账号尚未绑定微信')
  expect(visible()).not.toContain('本机已启用快捷进入')
  expect(platform.values.get(key)).toEqual(session('a'))
})

it('does not apply a delayed binding status from an account that has been replaced', async () => {
  platform.failPreferenceWrite = true
  let release!: (value: unknown) => void
  platform.bindingRequest.mockResolvedValueOnce(respond(200, { bound: false, binding_version: '0' }))
    .mockResolvedValueOnce(respond(200, { bound: false, binding_version: '0' }))
    .mockImplementationOnce(() => new Promise(resolve => { release = resolve }))
    .mockResolvedValue(respond(200, { bound: false, binding_version: '0' }))
  platform.login.mockResolvedValue({ code: 'fresh-code' })
  platform.modal.mockResolvedValue({ confirm: true })
  platform.request.mockResolvedValue(respond(200, { status: 'bound' }))
  await mount()
  await act(async () => { button('绑定当前微信').props.onClick() })
  await vi.waitFor(() => expect(platform.bindingRequest).toHaveBeenCalledTimes(3))
  await act(async () => { platform.hide(); platform.values.set(key, session('b')); platform.show() }); await flush()
  await act(async () => { release(respond(200, { bound: true, binding_version: '1' })) }); await flush()
  expect(visible()).toContain('此账号尚未绑定微信')
  expect(visible()).not.toContain('此账号已绑定微信')
})

it('does not query binding again after unbinding revokes the current WeChat session', async () => {
  platform.values.set(key, { ...session('a'), source: 'wechat' })
  platform.failPreferenceWrite = true; platform.failPreferenceRemove = true
  platform.bindingRequest.mockResolvedValue(respond(200, { bound: true, binding_version: '0' }))
  platform.modal.mockResolvedValue({ confirm: true })
  platform.request.mockResolvedValue(respond(200, { status: 'unbound' }))
  await mount()
  await act(async () => { button('解绑微信').props.onClick() }); await flush()
  expect(platform.values.has(key)).toBe(false)
  expect(visible()).toContain('尚未登录')
  expect(visible()).toContain('本机快捷进入设置无法保存')
  expect(platform.bindingRequest).toHaveBeenCalledTimes(1)
})

it('pins the binding version before confirmation and does not override a later unbind', async () => {
  platform.bindingRequest.mockResolvedValue(respond(200, { bound: false, binding_version: '12' }))
  let release!: (value: unknown) => void
  platform.modal.mockImplementation(() => new Promise(resolve => { release = resolve }))
  platform.login.mockResolvedValue({ code: 'fresh-code' })
  platform.request.mockResolvedValue(respond(409, { error: 'wechat_bind_expired' }))
  await mount()
  await act(async () => { button('绑定当前微信').props.onClick() }); await flush()
  expect(platform.bindingRequest).toHaveBeenCalledTimes(2)
  expect(platform.login).not.toHaveBeenCalled()
  await act(async () => { release({ confirm: true }) }); await flush()
  expect(platform.request.mock.calls[0][0].data).toMatchObject({ code: 'fresh-code', binding_version: '12' })
  expect(visible()).toContain('绑定操作已取消，请重新确认')
})

it('offers explicit WeChat entry after logout while Tesla remains the primary entry', async () => {
  platform.values.delete(key)
  await mount(); await consent()
  platform.login.mockResolvedValue({ code: 'manual-code' })
  platform.request.mockResolvedValue(respond(200, { access_token: 'manual-access', user: { id: 'a' } }))
  await act(async () => { button('使用已绑定微信进入').props.onClick() }); await flush()
  expect(platform.request.mock.calls[0][0].url).toBe('https://api.example.test/v1/auth/wechat/restore')
  expect(visible()).toContain('会话已建立')
})
it('shows the restored account after the More page is hidden and shown during recovery', async () => {
  platform.values.delete(key)
  const storage = await import('../../services/session')
  storage.writeWechatRecoveryPreference({ setStorageSync: (name, value) => platform.values.set(name, value) },
    { apiOrigin: 'https://api.example.test', boundUserId: 'a', enabled: true })
  platform.login.mockResolvedValue({ code: 'manual-code' })
  let release!: (value: unknown) => void
  platform.request.mockImplementation(() => new Promise(resolve => { release = resolve }))
  await mount()
  expect(visible()).toContain('正在恢复会话')
  await act(async () => { platform.hide(); platform.show() })
  await act(async () => { release(respond(200, { access_token: 'restored', user: { id: 'a' } })) }); await flush()
  expect(visible()).toContain('会话已建立')
  expect(platform.login).toHaveBeenCalledTimes(1)
})

it('disables WeChat binding while another Tesla authorization remains pending', async () => {
  platform.values.set(pendingKey, pending('a'))
  platform.request.mockResolvedValue(respond(200, { status: 'pending' }))
  await mount()
  expect(button('绑定当前微信').props.disabled).toBe(true)
})

it('opens the Tesla authorization page after a same-account token refresh completes', async () => {
  const { refreshSession } = await import('../../services/api')
  platform.values.set(key, { ...session('a'), refreshToken: 'refresh-a' })
  let release!: (value: unknown) => void
  platform.request.mockImplementation(({ url }: { url: string }) => url.endsWith('/v1/session/refresh')
    ? new Promise(resolve => { release = resolve })
    : Promise.resolve(respond(200, { authorization_url: 'https://auth.tesla.com/oauth2/v3/authorize?state=fixture', transaction_id: 'txn', client_proof: 'proof' })))
  await mount()
  await consent()
  const refreshing = refreshSession()
  await act(async () => { button('重新授权 Tesla').props.onClick() })
  expect(platform.navigate).not.toHaveBeenCalled()
  await act(async () => { release(respond(200, { access_token: 'rotated-a', refresh_token: 'new-refresh' })); await refreshing }); await flush()
  expect(platform.navigate).toHaveBeenCalledWith({ url: expect.stringContaining('/pages/auth/index?url=') })
})

it('returns a WeChat-restored account to Tesla login after unbinding', async () => {
  platform.values.set(key, { ...session('a'), source: 'wechat' })
  platform.bindingRequest.mockResolvedValue(respond(200, { bound: true, binding_version: '0' }))
  platform.modal.mockResolvedValue({ confirm: true })
  platform.request.mockResolvedValue(respond(200, { status: 'unbound' }))
  await mount()
  await act(async () => { button('解绑微信').props.onClick() }); await flush()
  expect(platform.values.has(key)).toBe(false)
  expect(visible()).toContain('尚未登录')
})

it('does not delete a replacement account when the delete confirmation was opened for another', async () => {
  let release!: (value: unknown) => void
  platform.modal.mockImplementation(() => new Promise(resolve => { release = resolve }))
  await mount()
  await act(async () => { button('注销账号').props.onClick() })
  platform.values.set(key, session('b'))
  await act(async () => { release({ confirm: true }) }); await flush()
  expect(platform.request).not.toHaveBeenCalled()
  expect(platform.values.get(key)).toEqual(session('b'))
})

it('does not let a stale binding status hide a successful binding', async () => {
  let release!: (value: unknown) => void
  platform.bindingRequest.mockImplementationOnce(() => new Promise(resolve => { release = resolve })).mockResolvedValue(respond(200, { bound: false, binding_version: '0' }))
  platform.login.mockResolvedValue({ code: 'fresh-code' })
  platform.modal.mockResolvedValue({ confirm: true })
  platform.request.mockResolvedValue(respond(200, { status: 'bound' }))
  await mount()
  await act(async () => { button('绑定当前微信').props.onClick() }); await flush()
  expect(visible()).toContain('此账号已绑定微信')
  await act(async () => { release(respond(200, { bound: false, binding_version: '0' })) }); await flush()
  expect(visible()).toContain('此账号已绑定微信')
})

it.each(['绑定当前微信', '解绑微信', '注销账号'])('ignores a stale %s confirmation after hide and show', async label => {
  if (label === '解绑微信') platform.bindingRequest.mockResolvedValue(respond(200, { bound: true, binding_version: '0' }))
  let release!: (value: unknown) => void
  platform.modal.mockImplementation(() => new Promise(resolve => { release = resolve }))
  await mount()
  await act(async () => { button(label).props.onClick() })
  await act(async () => { platform.hide(); platform.show() }); await flush()
  await act(async () => { release({ confirm: true }) }); await flush()
  expect(platform.login).not.toHaveBeenCalled()
  expect(platform.request).not.toHaveBeenCalled()
})
it('anonymous primary login requires personal consent and starts Tesla directly without WeChat', async () => {
  platform.values.delete(key)
  await mount()
  expect(visible()).not.toContain('微信登录')
  expect(button('特斯拉账号登录').props.disabled).toBe(true)
  await act(async () => { button('特斯拉账号登录').props.onClick() }); await flush()
  expect(platform.request).not.toHaveBeenCalled()
  await consent()
  platform.request.mockResolvedValueOnce(respond(200, { authorization_url: 'https://auth.tesla.com/oauth2/v3/authorize?state=fixture', transaction_id: 'a', client_proof: 'fixture-proof-a' }))
  await act(async () => { button('特斯拉账号登录').props.onClick() }); await flush()
  expect(platform.request.mock.calls[0][0].url).toBe('https://api.example.test/v1/auth/tesla/start')
  expect(platform.request.mock.calls[0][0].header.Authorization).toBeUndefined()
  expect(platform.navigate).toHaveBeenCalledWith({ url: expect.stringContaining('/pages/auth/index?url=') })
  expect(platform.values.get(pendingKey)).toEqual(pending('a'))
})
it('resume checks ready then claims using only owned proof and renders its new session', async () => {
  platform.values.delete(key); platform.values.set(pendingKey, pending('a'))
  platform.request.mockResolvedValueOnce(respond(200, { status: 'ready' }))
    .mockResolvedValueOnce(respond(200, { access_token: 'fixture-b', user: { id: 'b' } }))
  await mount()
  expect(platform.request.mock.calls.map(call => call[0].url)).toEqual([
    'https://api.example.test/v1/auth/miniprogram/status', 'https://api.example.test/v1/auth/miniprogram/claim'])
  expect(platform.request.mock.calls[1][0].data).toEqual({ transaction_id: 'a', client_proof: 'fixture-proof-a' })
  expect(visible()).toContain('会话已建立')
  expect(platform.toast).toHaveBeenCalledTimes(1)
})
it('consumed authorization asks for a new Tesla login without discarding pending proof', async () => {
  platform.values.delete(key); platform.values.set(pendingKey, pending('a'))
  platform.request.mockResolvedValueOnce(respond(200, { status: 'consumed' }))
  await mount()
  expect(visible()).toContain('本次授权已使用')
  expect(platform.values.get(pendingKey)).toEqual(pending('a'))
  expect(platform.request).toHaveBeenCalledTimes(1)
})
it('cancel calls Tesla transaction endpoint and removes only current pending authorization', async () => {
  platform.values.delete(key); platform.values.set(pendingKey, pending('a'))
  platform.request.mockResolvedValueOnce(respond(200, { status: 'pending' })).mockResolvedValueOnce(respond(200, { status: 'cancelled' }))
  await mount()
  await act(async () => { button('取消本次授权').props.onClick() }); await flush()
  expect(platform.request.mock.calls[1][0].url).toBe('https://api.example.test/v1/auth/miniprogram/cancel')
  expect(platform.values.has(pendingKey)).toBe(false)
})
it('hidden page does not claim late ready status', async () => {
  platform.values.delete(key); platform.values.set(pendingKey, pending('a'))
  let release!: (value: unknown) => void
  platform.request.mockImplementationOnce(() => new Promise(resolve => { release = resolve }))
  await mount(); await act(async () => { platform.hide() })
  await act(async () => { release(respond(200, { status: 'ready' })) }); await flush()
  expect(platform.request).toHaveBeenCalledTimes(1)
  expect(platform.toast).not.toHaveBeenCalled()
})

it('failed native navigation copies official Tesla URL after personally accepted consent', async () => {
  platform.values.delete(key)
  await mount(); await consent()
  const official = 'https://auth.tesla.com/oauth2/v3/authorize?state=fixture'
  platform.request.mockResolvedValueOnce(respond(200, { authorization_url: official, transaction_id: 'a', client_proof: 'fixture-proof-a' }))
  platform.navigate.mockRejectedValueOnce(Error('PRIVATE_NATIVE_NAVIGATION'))
  await act(async () => { button('特斯拉账号登录').props.onClick() }); await flush()
  expect(platform.clipboard).toHaveBeenCalledWith({ data: official })
  expect(visible()).not.toContain('PRIVATE_')
})
it('late cancellation cannot hide a replacement transaction or report its cancellation', async () => {
  platform.values.delete(key); platform.values.set(pendingKey, pending('a'))
  platform.request.mockResolvedValueOnce(respond(200, { status: 'pending' }))
  await mount()
  let release!: (value: unknown) => void
  platform.request.mockImplementationOnce(() => new Promise(resolve => { release = resolve }))
  await act(async () => { button('取消本次授权').props.onClick() })
  platform.values.set(pendingKey, pending('b'))
  await act(async () => { release(respond(200, { status: 'cancelled' })) }); await flush()
  expect(platform.values.get(pendingKey)).toEqual(pending('b'))
  expect(visible()).toContain('等待特斯拉账号授权')
  expect(platform.toast).not.toHaveBeenCalled()
})
