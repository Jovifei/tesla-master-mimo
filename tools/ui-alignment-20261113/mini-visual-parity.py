from pathlib import Path
r=Path('miniprogram/src')
p=r/'components/PresentationControls.tsx';s=p.read_text()
s=s.replace("import { activityLevel,", "import { chargeCellPaint, activityLevel,")
a=s.index('        <Text className="section-title">{dayCaption(active.key)}</Text>');b=s.index('      <View key={active.key}',a)
s=s[:a]+'''        <View className="day-stack-heading-row">
          <Text className="section-title">{dayCaption(active.key)}</Text>
          <View className="day-stack-arrows">
            <Button disabled={index === 0} onClick={() => selectIndex(index - 1)} ariaLabel="较新日期">{appearance.dayAxis === 'horizontal' ? '‹' : '↑'}</Button>
            <Button disabled={index === groups.length - 1} onClick={() => selectIndex(index + 1)} ariaLabel="较旧日期">{appearance.dayAxis === 'horizontal' ? '›' : '↓'}</Button>
          </View>
        </View>
        <Text className="muted">{appearance.dayAxis === 'horizontal' ? '左右' : '上下'}滑动日期抬头切换 · 正文正常滚动</Text>
      </View>
'''+s[b:]
s=s.replace('  const pageSize = 28','  const appearance = useAppearance()\n  const pageSize = 28',1)
s=s.replace('        ariaLabel={`${cell.key}，${cell.display}`} onClick={() => setSelected(cell.key)}>', '''        style={daily ? { '--charge-cell-bg': chargeCellPaint(cell.value, appearance.palette).background,
          '--charge-cell-ink': chargeCellPaint(cell.value, appearance.palette).foreground,
          '--charge-cell-bg-dark': chargeCellPaint(cell.value, appearance.palette, true).background,
          '--charge-cell-ink-dark': chargeCellPaint(cell.value, appearance.palette, true).foreground } : undefined}
        ariaLabel={`${cell.key}，${cell.display}`} onClick={() => setSelected(cell.key)}>''')
p.write_text(s)
p=r/'domain/ui_presentation.ts';s=p.read_text()+'''
/** Matches Android ChargeActivityGrid: surfaceVariant -> primary, 60 kWh at 80% mix. */
export function chargeCellPaint(value: number | null, palette: Palette = 'neutral', dark = false): { background: string; foreground: string } {
  const ratio = value != null && Number.isFinite(value) && value > 0 ? Math.min(value / 60, 1) * 0.8 : 0
  const from = dark ? [36,42,49] : palette === 'cream' ? [245,243,237] : [243,243,245]
  const to = dark ? [234,234,236] : [29,29,31]
  const rgb = from.map((v,i) => Math.round(v+(to[i]-v)*ratio))
  const linear = rgb.map(v => { const x=v/255; return x<=0.04045?x/12.92:((x+0.055)/1.055)**2.4 })
  const luminance = linear[0]*.2126+linear[1]*.7152+linear[2]*.0722
  return { background: '#'+rgb.map(v=>v.toString(16).padStart(2,'0')).join(''), foreground: luminance>.179?'#000000':'#ffffff' }
}
''';p.write_text(s)
p=r/'styles/presentation.scss';s=p.read_text()+'''
/* Exact semantic surfaces from Android Color.kt / Theme.kt, not a separate mini redesign. */
:root, page, .appearance-neutral, .appearance-cream {
  --apple-label-secondary: #626268; --apple-surface-secondary: #f3f3f5;
  --apple-surface-tertiary: #ececef; --apple-separator: #e5e5ea;
  --day-rear: #ffffff; --day-rear-selected: #f3f3f5;
}
.appearance-cream { --apple-surface-secondary: #f5f3ed; --apple-surface-tertiary: #ebe7de; --apple-separator: #e4e0d6; --day-rear: #fffefa; --day-rear-selected: #f3f0e8; }
.day-stack-rear { background: var(--day-rear); }
.day-stack-rear.is-selected { background: var(--day-rear-selected); }
.day-stack-header { min-height: 192rpx; box-sizing: border-box; padding: 16rpx 8rpx 16rpx 32rpx; border-bottom: 1px solid var(--apple-separator); }
.day-stack-heading-row { display:flex; align-items:center; gap: 8rpx; }
.day-stack-heading-row > .section-title { flex:1; min-width:0; font-size:32rpx; font-weight:600; }
.day-stack-arrows { display:flex; flex-shrink:0; }
.day-stack-arrows button { width:96rpx; height:96rpx; margin:0; padding:0; border:0; background:transparent; color:var(--apple-label-primary); font-size:40rpx; line-height:96rpx; }
.day-stack-arrows button::after { border:0; }
.day-stack-arrows button[disabled] { opacity:.38; }
.charge-activity-cell[style] { background:var(--charge-cell-bg); color:var(--charge-cell-ink); }
@media (prefers-color-scheme: dark) {
  :root, page, .appearance-neutral, .appearance-cream { --apple-label-secondary:#b7bdc6; --apple-surface-secondary:#242a31; --apple-surface-tertiary:#282d34; --apple-separator:#323840; --day-rear:#191d22; --day-rear-selected:#20252b; }
  .charge-activity-cell[style] { background:var(--charge-cell-bg-dark); color:var(--charge-cell-ink-dark); }
}
''';p.write_text(s)
p=r/'components/PresentationControls.test.tsx';s=p.read_text().replace('import { activityLevel,','import { chargeCellPaint, activityLevel,')+'''
it('matches App charge tile fixed-scale palette and readable contrast in both themes', () => {
  expect(chargeCellPaint(0)).toEqual({ background:'#f3f3f5',foreground:'#000000' })
  expect(chargeCellPaint(null,'cream')).toEqual({ background:'#f5f3ed',foreground:'#000000' })
  expect(chargeCellPaint(60)).toEqual({ background:'#48484a',foreground:'#ffffff' })
  expect(chargeCellPaint(120)).toEqual(chargeCellPaint(60))
  expect(chargeCellPaint(40).background).toBe('#818183')
  expect(chargeCellPaint(0,'neutral',true)).toEqual({background:'#242a31',foreground:'#ffffff'})
  expect(chargeCellPaint(60,'neutral',true)).toEqual({background:'#c2c4c7',foreground:'#000000'})
})
it('keeps date and two controls in the same App-aligned header row', async () => {
  await act(async () => { tree=TestRenderer.create(render()) })
  const row=tree!.root.findByProps({className:'day-stack-heading-row'})
  expect(row.findByProps({className:'section-title'}).children.join('')).toContain('2026-11-10')
  expect(row.findAllByType('button').map(n=>n.props.ariaLabel)).toEqual(['较新日期','较旧日期'])
})
''';p.write_text(s)
