from pathlib import Path
r=Path('web_matelink')
for name,typ,getter in [('ChargesPage','WebCharge','getChargesPage'),('DrivesPage','WebDriveSummary','getDrivesPage')]:
 p=r/f'src/pages/{name}.tsx';s=p.read_text();s="import { usePagedHistory } from './usePagedHistory'\n"+s
 start=s.index(f'  const [rows, setRows] = useState<{typ}[]>([])',s.index(f'export default function {name}'))
 stop=s.index('  const [selected',start) if name=='DrivesPage' else s.index('  const [filter',start)
 s=s[:start]+f'  const {{rows, page, totalPages, loading, error, reload, loadMore}} = usePagedHistory<{typ}>(carId,{getter})\n'+s[stop:]
 a=s.index('  const loadPage =',s.index(f'export default function {name}'));b=s.index('  const summary =',a) if name=='DrivesPage' else s.index('  const visible =',a)
 effect='''  useEffect(() => {
    const controller=new AbortController(); setSelected(null);setStatus(null)
    if(carId!=null)getCarStatus(carId,controller.signal).then(s=>{if(!controller.signal.aborted)setStatus(s)}).catch(()=>{})
    return()=>controller.abort()
  },[carId])

''' if name=='ChargesPage' else '  useEffect(() => { setSelected(null) },[carId])\n\n'
 s=s[:a]+effect+s[b:]
 s=s.replace('onClick={() => carId != null && void loadPage(carId, page + 1, true)}','onClick={() => void loadMore()}')
 s=s.replace('        <h1 style={{ fontSize: 28, fontWeight: 700 }}>','        <button className="apple-btn-secondary" disabled={loading} onClick={() => {void reload();void history.reload()}}>刷新记录</button>\n        <h1 style={{ fontSize: 28, fontWeight: 700 }}>',1)
 p.write_text(s)
p=r/'src/pages/ChargesPage.tsx';s="import { ChargeCalendar } from '../components/ChargeCalendar'\n"+p.read_text();s=s.replace('BarsPanel, ','')
a=s.index('function monthlyEnergy(');b=s.index('function countOf',a);s=s[:a]+s[b:]
s=s.replace('  const monthly = useMemo(() => monthlyEnergy(rows), [rows])','  const dailyRows = useMemo(() => filterCharges(history.charges, filter), [history.charges, filter])').replace('  const monthlyTotal = monthly.reduce((sum, entry) => sum + entry.value, 0)\n','')
a=s.index('      {monthly.length ? (');b=s.index('      {loading && !visible.length',a)
s=s[:a]+'''      <ChargeCalendar rows={dailyRows} />
      <p className="text-secondary" role="status" style={{fontSize:13,lineHeight:1.7}}>
        {rows[0]?.startDate ? `当前已返回的最新记录：${formatTime(rows[0].startDate)}。` : '尚未收到充电历史。'}
        {visible.length !== rows.length ? `筛选隐藏了 ${rows.length-visible.length} 条。` : ''}
        {page < totalPages ? '还有后续分页，可加载更多。' : '当前接口分页已读完，但不证明车辆同步完整。'}
        期间有充电却未显示时，请核对账号、车辆、筛选和服务端同步；不要清除历史。
      </p>

'''+s[b:]
s=s.replace("<div style={{ display: 'grid', gridTemplateColumns: 'minmax(0, 8fr) minmax(0, 4fr)'", "<div className=\"ml-detail-columns\" style={{ display: 'grid', gridTemplateColumns: 'minmax(0, 8fr) minmax(0, 4fr)'")
a=s.index('  const loadDetail =');b=s.index('  // 实时区',a)
s=s[:a]+'''  useEffect(() => {
    const controller=new AbortController();let pending=false
    setDetail(null);setError(null)
    const load=async()=>{
      if(pending || controller.signal.aborted)return
      pending=true
      try{const result=await getChargeDetail(carId,charge.id,controller.signal);if(!controller.signal.aborted){setDetail(result);setError(null)}}
      catch(reason){if(!controller.signal.aborted)setError(reason instanceof ApiError?reason.message:'读取充电详情失败。')}
      finally{pending=false}
    }
    void load()
    const timer=charge.isCharging?window.setInterval(()=>void load(),20_000):null
    return()=>{controller.abort();if(timer!=null)window.clearInterval(timer)}
  },[carId,charge.id,charge.isCharging])

'''+s[b:];s=s.replace('useCallback, ','');p.write_text(s)
p=r/'src/pages/DrivesPage.tsx';s=p.read_text().replace('useCallback, ','');s="import { DayCards } from '../components/DayCards'\nimport { ParkingBetween } from '../components/ParkingBetween'\nimport { calendarDays, civilDay } from '../components/presentation-policy'\n"+s
a=s.index('  const history = useHistory(carId)',s.index('export default function'))+len('  const history = useHistory(carId)')
s=s[:a]+'''
  const [windowDays,setWindowDays]=useState(7)
  const groups=useMemo(()=>{
    const byDay=new Map<string,WebDriveSummary[]>()
    for(const row of rows){const key=civilDay(row.startDate)??'日期未知';byDay.set(key,[...(byDay.get(key)??[]),row])}
    if(!windowDays)return [...byDay].sort((a,b)=>b[0].localeCompare(a[0])).map(([key,items])=>({key,items}))
    const today=new Date(), end=civilDay(today.toISOString())!
    today.setDate(today.getDate()-windowDays+1)
    const start=civilDay(today.toISOString())!
    return calendarDays(start,end).reverse().map(key=>({key,items:byDay.get(key)??[]}))
  },[rows,windowDays])
'''+s[a:]
a=s.index("      <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>\n        {rows.map",s.index('export default function'));b=s.index('        {!rows.length',a);fragment=s[a:b]
fragment=fragment.replace("      <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>\n        {rows.map((drive, index) => {",'''      <div className="ml-day-nav" aria-label="行程日期范围"><button aria-pressed={windowDays===7} onClick={()=>setWindowDays(7)}>近一周</button><button aria-pressed={windowDays===30} onClick={()=>setWindowDays(30)}>近一月</button><button aria-pressed={windowDays===0} onClick={()=>setWindowDays(0)}>全部已加载</button></div>
      <Panel title="按日行程次数"><BarsPanel data={groups.filter(g=>g.key!=='日期未知').slice().reverse().map(g=>({label:g.key.slice(5),value:g.items.length}))} unit="次" color={DATA_COLORS.graphite} height={140}/><p className="text-secondary" style={{fontSize:12}}>按已返回记录统计；空柱不证明当天没有行程。</p></Panel>
      <DayCards key={`${carId}:${windowDays}`} groups={groups}>{group => <>{group.items.map(drive => {
          const index=rows.findIndex(row=>row===drive)''')
fragment=fragment.replace('            <button\n              key={drive.id}','''            <div key={drive.id}>
            {index>0 && carId!=null ? <ParkingBetween key={`${carId}:${drive.id}:${rows[index-1].id}`} carId={carId} older={drive} newer={rows[index-1]} /> : null}
            <button''')
fragment=fragment.replace('            </button>\n          )','            </button></div>\n          )').replace('        })}\n','        })}</>}</DayCards>\n      <div>\n');s=s[:a]+fragment+s[b:]
s=s.replace('  const [error, setError] = useState<string | null>(null)\n\n  useEffect','  const [error, setError] = useState<string | null>(null)\n  const [fullCurves,setFullCurves]=useState(false)\n\n  useEffect',1)
s=s.replace("emptyReason: insufficient('power', '功率') ?? (countOf(rows, 'power') < points.length * 0.5 ? '功率采样稀疏，仅显示已有采样点' : undefined)","emptyReason: insufficient('power', '功率')")
s=s.replace('        <StatTile label="车外平均温度" value={num(current.outsideTempAvgC, 1)} unit="°C" accent={TILE_COLORS.purple} />','')
a=s.index('          <Panel title="采样曲线">');b=s.index('          {hasTemp ?',a)
s=s[:a]+'''          <Panel title="速度"><div className="ml-day-nav"><span>平均 {num(current.speedAvgKmh)} km/h</span><span>最高 {num(current.speedMaxKmh)} km/h</span></div><StackChart data={rows} series={[series[1]]} syncKey={`drive-speed-${drive.id}`} /></Panel>
          <Panel title="功率"><p className="text-secondary" style={{fontSize:13,marginBottom:12}}>峰值 {maxPowerKw==null?'—':fmtNum(maxPowerKw,1)} kW · 仅使用实际采样</p><StackChart data={rows} series={[series[0]]} syncKey={`drive-power-${drive.id}`} /></Panel>
          <p className="text-secondary" style={{fontSize:12}}>孤立的点表示少量实际采样；断开部分缺少观测，不补画连续曲线。</p>
          <details open={fullCurves} className="ml-disclosure" id="full-curves" onToggle={e=>setFullCurves(e.currentTarget.open)}><summary>详细曲线</summary><div>
          <StackChart data={rows} series={[series[2]]} syncKey={`drive-soc-${drive.id}`} />
'''+s[b:]
a=s.index('          {hasSpeedSamples ?');b=s.index('        </>',a);s=s[:b]+'          </div></details>\n'+s[b:]
a=s.index('          返回列表\n');b=s.index('        </button>',a)+len('        </button');s=s[:b]+'''<button className="apple-btn-secondary" style={{float:'right'}} onClick={()=>{setFullCurves(true);requestAnimationFrame(()=>document.getElementById('full-curves')?.scrollIntoView({block:'start'}))}}>详细曲线</button>'''+s[b:]
s=s.replace('        <StatTile label="平均速度" value={num(current.speedAvgKmh)} unit="km/h" />\n','').replace('        <StatTile label="最高速度" value={num(current.speedMaxKmh)} unit="km/h" caption="本段行程记录值" />\n','').replace('        {maxPowerKw != null ? <StatTile label="最大功率" value={fmtNum(maxPowerKw, 1)} unit="kW" accent={TILE_COLORS.orange} caption="逐点采样峰值" /> : null}\n','')
a=s.index('      {status?.tpms ?');b=s.index('      {parked ?',a);s=s[:a]+s[b:]
s=s.replace('WebCarStatus, ','').replace('getCarStatus, ','').replace('  const [status, setStatus] = useState<WebCarStatus | null>(null)\n','').replace('    getCarStatus(carId).then(result => { if (live) setStatus(result) }).catch(() => {})\n','')
s=s.replace('    let live = true\n    getDriveDetail(carId, drive.id)','    let live = true\n    const controller=new AbortController();setDetail(null);setParked(null);setError(null)\n    getDriveDetail(carId, drive.id,controller.signal)').replace('getParkedBetween(carId, drive.id, newerDriveId)','getParkedBetween(carId, drive.id, newerDriveId,controller.signal)').replace('      live = false\n','      live = false\n      controller.abort()\n');p.write_text(s)
