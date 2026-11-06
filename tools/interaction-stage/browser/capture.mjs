/** Isolated Chromium/CDP acceptance of actual React components, not native WeChat evidence.
 * Uses Node built-ins only and never connects to a provider or an Owner browser profile.
 */
import fs from 'node:fs'
import path from 'node:path'
import http from 'node:http'
import { execFileSync } from 'node:child_process'
import { launchIsolatedBrowser } from './launch.mjs'
import { fileURLToPath } from 'node:url'
import { createHash } from 'node:crypto'
import assert from 'node:assert/strict'
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'../../..')
const bundle=path.join(root,'visual-browser'), evidence=path.join(root,'visual-browser-evidence')
fs.mkdirSync(evidence,{recursive:true})
let chrome=process.env.CHROME_BIN
if(!chrome)for(const name of ['google-chrome','google-chrome-stable','chromium','chromium-browser']){
  try{chrome=execFileSync('which',[name],{encoding:'utf8'}).trim();if(chrome)break}catch{}
}
if(!chrome)throw new Error('No isolated Chromium executable; browser rendering cannot be claimed')
const sleep=ms=>new Promise(resolve=>setTimeout(resolve,ms))
const mime={'.html':'text/html; charset=utf-8','.js':'text/javascript; charset=utf-8','.css':'text/css; charset=utf-8','.png':'image/png'}
const server=http.createServer((req,res)=>{
  const pathname=decodeURIComponent(new URL(req.url,'http://127.0.0.1').pathname)
  const file=path.resolve(bundle,'.'+(pathname==='/'?'/index.html':pathname))
  if(!file.startsWith(bundle+path.sep)||!fs.existsSync(file)||!fs.statSync(file).isFile()){res.writeHead(404);res.end();return}
  res.setHeader('Content-Type',mime[path.extname(file)]||'application/octet-stream');res.setHeader('Cache-Control','no-store')
  fs.createReadStream(file).pipe(res)
})
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve))
const origin=`http://127.0.0.1:${server.address().port}`
let browser, ws;const pending=new Map();let next=1;const exceptions=[],outside=[];const captures=[]; const chineseFontEvidence=[]
try{
  browser=await launchIsolatedBrowser({chrome,evidence})
  const page=await(await fetch(`${browser.endpoint}/json/new?about:blank`,{method:'PUT',signal:AbortSignal.timeout(10_000)})).json()
  ws=new WebSocket(page.webSocketDebuggerUrl)
  await new Promise((resolve,reject)=>{ws.addEventListener('open',resolve,{once:true});ws.addEventListener('error',reject,{once:true})})
  ws.addEventListener('message',event=>{
    const message=JSON.parse(String(event.data))
    if(message.id){const waiter=pending.get(message.id);if(waiter){clearTimeout(waiter.timer);pending.delete(message.id);message.error?waiter.reject(new Error(JSON.stringify(message.error))):waiter.resolve(message.result)}}
    else if(message.method==='Runtime.exceptionThrown')exceptions.push(message.params.exceptionDetails.text+':'+(message.params.exceptionDetails.exception?.description||''))
    else if(message.method==='Network.requestWillBeSent'){
      const url=message.params.request.url
      if(/^https?:/.test(url)&&!url.startsWith(origin+'/'))outside.push(url)
    }
  })
  const send=(method,params={})=>new Promise((resolve,reject)=>{
    const id=next++,timer=setTimeout(()=>{pending.delete(id);reject(new Error(`CDP timeout ${method}`))},20000)
    pending.set(id,{resolve,reject,timer});ws.send(JSON.stringify({id,method,params}))
  })
  const evaluate=async expression=>{
    const result=await send('Runtime.evaluate',{expression,returnByValue:true,awaitPromise:true})
    if(result.exceptionDetails)throw new Error(result.exceptionDetails.exception?.description||result.exceptionDetails.text)
    return result.result?.value
  }
  await send('Page.enable');await send('Runtime.enable');await send('Network.enable')
  await send('DOM.enable');await send('CSS.enable')
  // Successful PNG capture and document.fonts.ready do not prove readable Chinese.
  // Query the actual rendered heading glyphs, not an installed-font guess.
  const verifyChineseGlyphs=async(scene,theme,width)=>{
    const document=await send('DOM.getDocument')
    const node=await send('DOM.querySelector',{nodeId:document.root.nodeId,selector:'.fixture-surface h1'})
    assert(node.nodeId>0,'Missing visible Chinese scene heading')
    const {fonts}=await send('CSS.getPlatformFontsForNode',{nodeId:node.nodeId})
    assert(fonts.some(font=>/Noto Sans CJK/.test(font.familyName)&&font.glyphCount>0),
      `Chinese text lacks rendered CJK glyphs: ${scene} ${JSON.stringify(fonts)}`)
    chineseFontEvidence.push({scene,theme,width,fonts})
  }
  const waitFor=async expression=>{for(let i=0;i<100;i++){if(await evaluate(expression))return;await sleep(50)}throw new Error(`DOM condition not met: ${expression}`)}
  const scenes=['vehicle','vehicle-pending','tesla','auth-pending','wechat','tpms','tpms-warning','route','openings','parking','empty','loading']
  const save=async(name)=>{
    const result=await send('Page.captureScreenshot',{format:'png',captureBeyondViewport:true})
    const bytes=Buffer.from(result.data,'base64');assert(bytes.length>1000)
    assert.equal(bytes.subarray(1,4).toString(),'PNG')
    const sha256=createHash('sha256').update(bytes).digest('hex')
    fs.writeFileSync(path.join(evidence,name+'.png'),bytes)
    captures.push({name,sha256,width:bytes.readUInt32BE(16),height:bytes.readUInt32BE(20),bytes:bytes.length})
  }
  const verifyWheels=async()=>{
    const bounds=await evaluate(`(()=>{const rect=s=>{const r=document.querySelector(s).getBoundingClientRect();return {left:r.left,right:r.right,top:r.top,bottom:r.bottom}};return {car:rect('.tpms-position-board .interaction-vehicle-top'),fl:rect('[data-wheel="fl"]'),fr:rect('[data-wheel="fr"]'),rl:rect('[data-wheel="rl"]'),rr:rect('[data-wheel="rr"]')}})()`)
    assert(bounds.fl.right<=bounds.car.left&&bounds.rl.right<=bounds.car.left,'left wheel controls overlap car')
    assert(bounds.fr.left>=bounds.car.right&&bounds.rr.left>=bounds.car.right,'right wheel controls overlap car')
    assert(bounds.fl.bottom<bounds.rl.top&&bounds.fr.bottom<bounds.rr.top,'front/rear wheel controls overlap')
    assert(await evaluate("[...document.querySelectorAll('.tpms-position-wheel')].every(e=>getComputedStyle(e).borderTopWidth==='1px')"),'wheel status boundaries must survive the generic button reset')
  }
  for(const theme of ['light','dark'])for(const width of [320,412])for(const scene of scenes){
    await send('Emulation.setDeviceMetricsOverride',{width,height:780,deviceScaleFactor:1,mobile:true})
    await send('Emulation.setEmulatedMedia',{features:[{name:'prefers-color-scheme',value:theme},{name:'prefers-reduced-motion',value:'no-preference'}]})
    await send('Page.navigate',{url:`${origin}/?scene=${scene}`})
    await waitFor(`window.__visualFixture?.ready===true && document.querySelector('[data-scene="${scene}"]')!==null && [...document.images].every(i=>i.complete&&i.naturalWidth>0)`)
    await evaluate('document.fonts.ready')
    await verifyChineseGlyphs(scene,theme,width)
    if(scene==='openings'){
      await evaluate("document.querySelector('.interaction-disclosure').click()")
      await waitFor("document.querySelector('.opening-position-board')!==null")
      assert(await evaluate("document.querySelector('[data-opening=\"windows\"]')===null && document.body.innerText.includes('车窗：状态未知')"))
    }
    if(scene==='route'){
      await evaluate("document.querySelector('[data-endpoint=\"B\"]').click()")
      await waitFor("document.querySelector('[data-endpoint=\"B\"]').classList.contains('route-endpoint-selected')")
      assert(await evaluate("document.body.innerText.includes('非实际轨迹')"))
    }
    if(scene==='tpms'){
      await verifyWheels()
      assert(await evaluate("document.querySelector('[data-wheel=\"fl\"]').innerText.includes('0.00') && document.querySelector('[data-wheel=\"rr\"]').dataset.state==='unknown'"))
      await evaluate("document.querySelector('[data-wheel=\"fl\"]').click()")
      await waitFor("document.querySelector('[data-wheel=\"fl\"]').dataset.selected==='true'")
      await evaluate("document.querySelector('[data-wheel=\"fl\"]').click()")
      await waitFor("document.querySelector('[data-wheel=\"fl\"]').dataset.selected==='false'")
    }
    if(scene==='tpms-warning'){
      await verifyWheels()
      assert(await evaluate("document.querySelector('[data-wheel=\"fl\"]').dataset.alert==='low' && document.querySelector('[data-wheel=\"fr\"]').dataset.alert==='high' && document.querySelector('[data-wheel=\"rl\"]').innerText.includes('设置范围内') && document.querySelector('[data-wheel=\"rr\"]').dataset.state==='unknown'"))
    }
    if(scene==='parking')assert(await evaluate("document.querySelectorAll('.recorded-charge-row').length===2 && document.body.innerText.includes('0.00 kWh') && document.body.innerText.includes('结束未记录')"))
    if(scene==='parking'||scene==='openings')assert(await evaluate("[...document.querySelectorAll('.recorded-charge-select,.interaction-disclosure')].every(e=>getComputedStyle(e).borderTopWidth==='0px')"),'unexpected browser-native control border')
    assert(await evaluate('document.documentElement.scrollWidth<=innerWidth'),'horizontal overflow')
    await save(`mini-${theme}-${width}-${scene}`)
    if((scene==='tpms'||scene==='tpms-warning')&&width===320){
      await evaluate("document.querySelectorAll('.tpms-position-name,.tpms-position-value,.tpms-position-status').forEach(e=>e.style.fontSize=parseFloat(getComputedStyle(e).fontSize)*1.8+'px')")
      await verifyWheels();await save(`mini-${theme}-${width}-${scene}-large-text`)
      await evaluate('window.__visualFixture.clear()')
      await waitFor("[...document.querySelectorAll('[data-wheel]')].every(e=>e.dataset.state==='unknown')")
      assert(!(await evaluate("document.querySelector('.tpms-vehicle-visual').innerText.includes('0.00')")))
    }
    if(scene==='auth-pending'){
      await evaluate('window.__visualFixture.foreground(false)')
      await waitFor("document.querySelector('[data-motion]').dataset.motion==='false'")
      assert.equal(await evaluate("document.querySelector('[data-phase]').dataset.phase"),'connecting')
      await send('Emulation.setEmulatedMedia',{features:[{name:'prefers-color-scheme',value:theme},{name:'prefers-reduced-motion',value:'reduce'}]})
      await evaluate('window.__visualFixture.foreground(true)')
      assert.equal(await evaluate("getComputedStyle(document.querySelector('.connection-track')).animationName"),'none')
    }
  }
  assert.equal(captures.length,52);assert.equal(exceptions.length,0,exceptions.join('\n'));assert.equal(outside.length,0,'Unexpected external requests')
  assert(new Set(captures.map(c=>c.sha256)).size>=35,'duplicate/blank scenes')
  fs.copyFileSync(path.join(bundle,'SOURCE.txt'),path.join(evidence,'SOURCE.txt'))
  fs.writeFileSync(path.join(evidence,'manifest.json'),JSON.stringify({kind:'actual React components with test-only DOM adapter; not native WeChat',passed:true,externalRequests:0,exceptionCount:0,chineseFontEvidence,captures},null,2))
  console.log(`PASS ${captures.length} actual browser renders; clicks, unknown/zero, scope reset, motion lifecycle and narrow/large-text layout`)
}finally{
  if(ws)ws.close()
  server.close()
  if(browser)await browser.close()
  for(const value of pending.values()){clearTimeout(value.timer);value.reject(new Error('browser closing'))}

}
