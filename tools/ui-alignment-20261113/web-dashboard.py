from pathlib import Path
p=Path('web_matelink/src/pages/WebDashboard.tsx');s=p.read_text().replace('useMemo, useState','useMemo, useRef, useState')
s=s.replace('const [status, setStatus] = useState<WebCarStatus | null>(null)','const [statusState, setStatusState] = useState<{owner: number; value: WebCarStatus} | null>(null)\n  const status = statusState?.owner === carId ? statusState.value : null\n  const statusRequest = useRef<AbortController | null>(null)')
s=s.replace('const [chargePoints, setChargePoints] = useState<ReadonlyMap<string, readonly WebChargePoint[]>>(new Map())','const [chargePointState, setChargePointState] = useState<{owner: number; values: ReadonlyMap<string, readonly WebChargePoint[]>} | null>(null)\n  const chargePoints = chargePointState?.owner === carId ? chargePointState.values : new Map<string, readonly WebChargePoint[]>()')
s=s.replace('const [liveDetail, setLiveDetail] = useState<ChargeDetail | null>(null)','const [liveState, setLiveState] = useState<{owner: number; value: ChargeDetail} | null>(null)\n  const liveDetail = liveState?.owner === carId ? liveState.value : null')
a=s.index('  const loadStatus = useCallback');z=s.index('  // 动态',a)
s=s[:a]+'''  const loadStatus = useCallback(async () => {
    if (carId == null || statusRequest.current) return
    const controller = new AbortController(); statusRequest.current = controller
    try {
      const next = await getCarStatus(carId, controller.signal)
      if (!controller.signal.aborted) { setStatusState({owner:carId,value:next}); setStatusError(null) }
    } catch { if (!controller.signal.aborted) setStatusError('读取车辆状态失败，请稍后重试。') }
    finally { if(statusRequest.current===controller) statusRequest.current=null }
  }, [carId])
  useEffect(() => {
    statusRequest.current?.abort();statusRequest.current=null;setStatusError(null)
    void loadStatus()
    return () => {statusRequest.current?.abort();statusRequest.current=null}
  }, [loadStatus])

'''+s[z:]
s=s.replace('if (carId == null || !history.charges.length) return','if (carId == null || !history.charges.length) {setChargePointState(null);return}').replace('    let cancelled = false\n    void (async () => {','    const controller = new AbortController()\n    setChargePointState(null)\n    void (async () => {',1).replace('for (const charge of inWindow) {\n        try {','for (const charge of inWindow) {\n        if(controller.signal.aborted) return\n        try {',1).replace('getChargeDetail(carId, charge.id)','getChargeDetail(carId, charge.id, controller.signal)',1).replace('if (!cancelled) setChargePoints(map)','if (!controller.signal.aborted) setChargePointState({owner:carId,values:map})').replace('      cancelled = true\n    }\n  }, [carId, history.charges, windowStart])','      controller.abort()\n    }\n  }, [carId, history.charges, windowStart])',1)
a=s.index('  useEffect(() => {\n    if (carId == null || !liveCharge)');z=s.index('\n  const segments',a)
s=s[:a]+'''  useEffect(() => {
    setLiveState(null)
    if (carId == null || !liveCharge) return
    const controller = new AbortController()
    let busy=false
    const fetchDetail = async () => {
      if (busy || controller.signal.aborted) return
      busy=true
      try {
        const detail = await getChargeDetail(carId, liveCharge.id, controller.signal)
        if (!controller.signal.aborted) setLiveState({owner:carId,value:detail})
      } catch { /* do not promote failed requests to new evidence */ }
      finally { busy=false }
    }
    void fetchDetail()
    const timer = charging ? window.setInterval(() => void fetchDetail(), 20_000) : undefined
    return () => {controller.abort();if(timer)window.clearInterval(timer)}
  }, [carId, liveCharge, charging])
'''+s[z:]
s=s.replace("<div style={{ margin: '-20px -32px 16px' }}>",'<div className="ml-premise">').replace("gridTemplateColumns: 'repeat(auto-fit, minmax(300px, 1fr))'","gridTemplateColumns: 'repeat(auto-fit, minmax(min(100%, 300px), 1fr))'").replace("<div style={{ gridColumn: 'span 2', minWidth: 0 }}>",'<div style={{ minWidth: 0 }}>');p.write_text(s)
