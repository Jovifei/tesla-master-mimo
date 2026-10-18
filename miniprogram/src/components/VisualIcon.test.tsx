import React from 'react'
import renderer from 'react-test-renderer'
import { expect, it, vi } from 'vitest'
import analytics from './icons/material/analytics.png'
import attachMoney from './icons/material/attach_money.png'
import bolt from './icons/material/bolt.png'
import history from './icons/material/history.png'
import timeline from './icons/material/timeline.png'
import update from './icons/material/update.png'
import verifiedUser from './icons/material/verified_user.png'

vi.mock('@tarojs/components', () => ({ View: 'view', Image: 'image' }))
import VisualIcon, { type VisualIconName } from './VisualIcon'

it.each<[VisualIconName, string]>([
  ['shield', verifiedUser], ['chart', analytics], ['clock', history],
  ['energy', bolt], ['money', attachMoney], ['timeline', timeline], ['update', update],
])('%s renders its local App-matched Material asset, not a font character', (name, expectedSrc) => {
  const tree = renderer.create(<VisualIcon name={name} />)
  const image = tree.root.findByType('image')
  expect(image.props.src).toBe(expectedSrc)
  expect(image.props.mode).toBe('aspectFit')
  expect(tree.root.findAllByType('text')).toHaveLength(0)
  tree.unmount()
})
