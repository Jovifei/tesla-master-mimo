from pathlib import Path
r=Path('web_matelink')
p=r/'src/App.tsx';s=p.read_text();anchor='function Shell({ children }: { children: ReactNode }) {\n  const navigate = useNavigate()';assert anchor in s
s=s.replace(anchor,anchor+'''\n  const [navigationOpen,setNavigationOpen]=useState(false)
  const location=useLocation()
  useEffect(()=>setNavigationOpen(false),[location.pathname])''')
s=s.replace('<header className="ml-header">','<header className={`ml-header${navigationOpen ? \' is-open\' : \'\'}`} onKeyDown={e=>{if(e.key===\'Escape\')setNavigationOpen(false)}}>')
s=s.replace('        <nav style=', '''        <button className="ml-mobile-menu" aria-expanded={navigationOpen} aria-controls="site-navigation" onClick={()=>setNavigationOpen(v=>!v)}>{navigationOpen?'收起导航':'导航'}</button>
        <nav id="site-navigation" aria-label="主导航" style=''')
s=s.replace("<div style={{ marginLeft: 'auto', display: 'flex', alignItems: 'center', gap: 6 }}>","<div className=\"ml-header-session\" style={{ marginLeft: 'auto', display: 'flex', alignItems: 'center', gap: 6 }}>")
s=s.replace("justifyContent: 'center', color: '#fff'","justifyContent: 'center', color: 'var(--apple-on-action)'")
p.write_text(s)
for name in ['DrivesPage','ChargesPage']:
 p=r/f'src/pages/{name}.tsx';s=p.read_text()
 a=s.index('        <button className="apple-btn-secondary" disabled={loading} onClick={() => {void reload();void history.reload()}}>刷新记录</button>')
 b=s.index('</h1>',a)+len('</h1>');block=s[a:b];lines=block.splitlines();assert len(lines)==2
 s=s[:a]+'\n'.join(reversed(lines))+s[b:];p.write_text(s)
p=r/'src/pages/ChargesPage.tsx';s=p.read_text()
s=s.replace('      <ChargeCalendar rows={dailyRows} />','      <div className="ml-charge-layout"><ChargeCalendar rows={dailyRows} /><div className="ml-charge-records">')
anchor='''        </Panel>
      )}
      {loading && visible.length'''
assert s.count(anchor)==1
s=s.replace(anchor,'''        </Panel>
      )}
      </div></div>
      {loading && visible.length''')
anchor='''        <Panel title="充电记录" action={<span style={{ fontSize: 12, color: V3.label3 }}>逐列参数 · 点击任意一行查看单次充电详情</span>}>
          <DataTable'''
assert anchor in s
s=s.replace(anchor,'''        <Panel title="充电记录" action={<span style={{ fontSize: 12, color: V3.label3 }}>点选记录查看详情</span>}>
          <div className="ml-charge-list">
            {visible.map(charge => <button key={charge.id} className="ml-charge-record" onClick={()=>setSelected(charge)}>
              <div className="ml-charge-record-top"><strong>{charge.address??'地点尚未返回'}</strong><strong className="tabular">{num(charge.energyAddedKwh,1)} <small>kWh</small></strong></div>
              <div className="ml-charge-record-sub"><span>{formatTime(charge.startDate)}</span><span>{charge.chargeMode==='dc'?'直流':charge.chargeMode==='ac'?'交流':'类型待确认'}</span></div>
              <div className="ml-charge-record-bottom"><span>{fmtDuration(charge.durationMinutes)}</span><BatteryPill from={charge.startBatteryLevel} to={charge.endBatteryLevel}/><span>{charge.cost==null?'费用未知':`${num(charge.cost,2)} ${charge.currency??'元'}`}</span></div>
            </button>)}
            {!visible.length ? <p className="ml-empty">没有符合当前条件的已返回记录。</p>:null}
          </div>
          <details className="ml-record-parameters"><summary>完整参数表</summary>
          <DataTable''')
s=s.replace('''          />
        </Panel>
      )}
      </div></div>''','''          />
          </details>
        </Panel>
      )}
      </div></div>''')
s=s.replace("{car?.name ?? ''} · 实时","{car?.name ?? ''} · {snapshot ? '缓存快照' : '最近返回状态'}")
a=s.index('  // 实时区：');b=s.index('  const current =',a)
s=s[:a]+'''  // Current status never substitutes for historical curve samples; one cancellable request at a time.
  useEffect(() => {
    setLiveStatus(null)
    if (!charge.isCharging) return
    const controller=new AbortController();let pending=false
    const fetchStatus = async () => {
      if(pending || controller.signal.aborted)return
      pending=true
      try { const next=await getCarStatus(carId,controller.signal);if(!controller.signal.aborted)setLiveStatus(next) }
      catch { /* retain only this scope's last returned status */ }
      finally {pending=false}
    }
    void fetchStatus()
    const timer=window.setInterval(()=>void fetchStatus(),20_000)
    return()=>{controller.abort();window.clearInterval(timer)}
  },[carId,charge.id,charge.isCharging])

'''+s[b:]
s=s.replace('<Panel title="实时充电"','<Panel title="最近充电状态"')
p.write_text(s)
p=r/'src/presentation.css';s=p.read_text()+'''
/* Small screens prioritize the vehicle content, not a four-line permanent menu. */
.ml-mobile-menu { display:none; }
.ml-charge-layout {display:grid;grid-template-columns:minmax(280px,440px) minmax(0,1fr);gap:24px;align-items:start;}
.ml-charge-records {min-width:0;display:grid;gap:16px;}
.ml-charge-list {display:grid;gap:10px;}
.ml-charge-record {display:block;width:100%;padding:16px;text-align:left;background:var(--apple-surface-secondary);border:1px solid var(--apple-separator);border-radius:14px;color:var(--apple-label-primary);cursor:pointer;}
.ml-charge-record:hover {border-color:var(--apple-tint);}
.ml-charge-record-top,.ml-charge-record-sub,.ml-charge-record-bottom {display:flex;align-items:center;justify-content:space-between;gap:12px;flex-wrap:wrap;}
.ml-charge-record-top>strong {font-size:16px;}.ml-charge-record-top>strong:last-child {font-size:22px;}.ml-charge-record-top small {font-size:12px;font-weight:500;color:var(--apple-label-secondary);}
.ml-charge-record-sub {font-size:12px;color:var(--apple-label-secondary);margin:8px 0 12px;}
.ml-charge-record-bottom {font-size:13px;color:var(--apple-label-secondary);}
.ml-record-parameters {margin-top:16px;border-top:1px solid var(--apple-separator);padding-top:12px;font-size:13px;min-width:0;}
.ml-record-parameters>summary {cursor:pointer;min-height:44px;display:flex;align-items:center;}
@media(max-width:1000px){.ml-charge-layout{grid-template-columns:minmax(0,1fr)}.ml-charge-calendar{max-width:560px;}}
@media(max-width:900px){
  .ml-header{min-height:64px;position:sticky;top:0;padding:8px 16px;gap:12px;justify-content:space-between;}
  .ml-mobile-menu{display:block;border:1px solid var(--apple-separator);border-radius:10px;background:var(--apple-surface-secondary);padding:8px 14px;font-size:14px;color:var(--apple-label-primary);}
  .ml-header nav,.ml-header .ml-header-session{display:none!important;}
  .ml-header.is-open nav{display:flex!important;gap:4px!important;width:100%;padding:8px 0;}
  .ml-header.is-open .ml-header-session{display:flex!important;}
}
''';p.write_text(s)
p=r/'tools/ui-browser-acceptance.mjs';s=p.read_text();anchor=" await record('real trip page uses seven calendar cards and preserves parking',async()=>{";assert anchor in s
s=s.replace(anchor,''' await record('mobile navigation is compact, expandable, keyboard dismissible and route-closing',async()=>{
  await go('/drives');assert.ok((await page.locator('.ml-header').boundingBox()).height<=90)
  const menu=page.getByRole('button',{name:'导航',exact:true});await menu.click();assert.equal(await page.locator('#site-navigation').isVisible(),true)
  await page.keyboard.press('Escape');assert.equal(await page.locator('#site-navigation').isVisible(),false)
  await menu.click();await page.getByRole('link',{name:'设置',exact:true}).click();await page.waitForURL('**/me');assert.equal(await page.locator('#site-navigation').isVisible(),false)
 })()
'''+anchor)
p.write_text(s)
