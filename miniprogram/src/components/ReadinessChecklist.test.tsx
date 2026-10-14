import React from 'react'
import renderer from 'react-test-renderer'
import { expect, it, vi } from 'vitest'
vi.mock('@tarojs/components', () => ({ Text: 'text', View: 'view' }))
import ReadinessChecklist from './ReadinessChecklist'
it('keeps server configuration distinct from vehicle-key confirmation and local session evidence', () => {
  const tree = renderer.create(<ReadinessChecklist sessionReady pairing={{ status: 'pairing_required', virtual_key_url: null, config_synced: true, updated_at: null, error_class: null }} />)
  const text = JSON.stringify(tree.toJSON())
  expect(text).toContain('需要确认'); expect(text).toContain('服务端已配置')
  expect(text).toContain('本机会话可用'); expect(text).not.toContain('已由服务端确认')
  tree.unmount()
})
