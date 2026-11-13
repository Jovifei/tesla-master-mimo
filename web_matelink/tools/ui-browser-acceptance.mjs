// Built product with synthetic API only. No live account, provider or vehicle access.
import assert from 'node:assert/strict'
import {mkdir,writeFile} from 'node:fs/promises'
import {pathToFileURL} from 'node:url'
const {chromium}=await import(pathToFileURL(process.env.PLAYWRIGHT_MODULE).href)
const dir=process.env.EVIDENCE_DIR??'evidence/ui-alignment/browser'
await mkdir(dir,{recursive:true})
const checks=[],errors=[],external=[],requests=[]
const record=(name,fn)=>async()=>{try{await fn();checks.push({name,pass:true})}catch(e){checks.push({name,pass:false,error:String(e)})}}
const browser=await chromium.launch({headless:true})
const context=await browser.newContext({viewport:{width:393,height:852},timezoneId:'Asia/Shanghai',locale:'zh-CN',colorScheme:'light',reducedMotion:'no-preference'})
await context.addInitScript(()=>{
 const OriginalDate=Date;const fixed=OriginalDate.parse('2026-11-13T03:00:00Z')
 window.Date=class extends OriginalDate{constructor(...args){super(...(args.length?args:[fixed]))}static now(){return fixed}}
 localStorage.setItem('matelink-web-session-v1',JSON.stringify({userId:'synthetic-ui',accessToken:'isolated-fixture-not-a-token',refreshToken:'isolated-fixture-not-a-token',expiresAt:fixed+86400000}))
})
const day=(d,time)=>`2026-11-${String(d).padStart(2,'0')}T${time}:00+08:00`
const drive=(id,d,h)=>({drive_id:id,start_date:day(d,`${h}:00`),end_date:day(d,`${h}:30`),start_address:'合成起点广场',end_address:'合成终点园区',duration_min:30,odometer_details:{odometer_distance:12.3},battery_details:{start_battery_level:78,end_battery_level:75},speed_max:72,speed_avg:24.6,speed_unit:'km/h',source:'teslamate_archive',energy_consumed_net:1.9,consumption_net:154.47,drive_details:[0,5,15,30].map((m,i)=>({date:day(d,`${h}:${String(m).padStart(2,'0')}`),speed:[0,32,72,0][i],power:[0,12,-3,0][i],battery_level:78-i,elevation:10+i}))})
const drives=[drive(5,13,'09'),drive(4,13,'08'),drive(3,12,'08'),drive(2,11,'08')]
const charges=[{charge_id:3,start_date:day(12,'20:00'),end_date:day(12,'21:00'),address:'合成充电站',duration_min:60,charge_energy_added:40,cost:0,currency:'CNY',charge_type:'ac',battery_details:{start_battery_level:20,end_battery_level:80},is_charging:false},{charge_id:2,start_date:day(11,'20:00'),end_date:day(11,'21:00'),address:'合成充电站二',duration_min:60,charge_energy_added:0,cost:0,currency:'CNY',battery_details:{start_battery_level:80,end_battery_level:80}},{charge_id:1,start_date:day(10,'20:00'),end_date:day(10,'21:00'),duration_min:60,charge_energy_added:null,cost:null}]
await context.route('**/*',async route=>{
 const u=new URL(route.request().url())
 if(u.origin!=='http://127.0.0.1:4173'){external.push(u.origin);return route.abort()}
 if(!u.pathname.startsWith('/api/')&&!u.pathname.startsWith('/v1/'))return route.continue()
 requests.push({method:route.request().method(),path:u.pathname})
 let body={};let status=200
 if(u.pathname==='/api/v1/cars')body={cars:[{car_id:7,vehicle_uid:'synthetic-7',display_name:'合成车辆',model:'Model 3'}]}
 else if(u.pathname.endsWith('/status'))body={status:{state:'asleep',battery_details:{battery_level:72,rated_battery_range:360,est_battery_range:330},car_status:{locked:true,sentry_mode:false},climate_details:{inside_temp:21,outside_temp:15},charging_details:{charging_state:'Disconnected'},odometer:12000},observed_at:day(13,'09:30'),source:'telemetry_cache'}
 else if(u.pathname.endsWith('/drives'))body={drives,meta:{page:1,total_pages:1},units:{unit_of_length:'km'}}
 else if(/\/drives\/\d+$/.test(u.pathname))body={drive:drives.find(d=>d.drive_id===Number(u.pathname.split('/').at(-1)))}
 else if(u.pathname.endsWith('/charges'))body={charges,meta:{page:1,total_pages:1}}
 else if(/\/charges\/\d+$/.test(u.pathname)){const c=charges.find(c=>c.charge_id===Number(u.pathname.split('/').at(-1)));body={charge:{...c,charge_details:[]}}}
 else if(u.pathname.includes('/parked/')){const ids=u.pathname.split('/').slice(-2).map(Number),a=drives.find(d=>d.drive_id===ids[0]),b=drives.find(d=>d.drive_id===ids[1]);body={older_drive_id:ids[0],newer_drive_id:ids[1],start_date:a?.end_date,end_date:b?.start_date,start_battery_level:75,end_battery_level:78,address:'合成驻车区域'}}
 else if(u.pathname==='/v1/session/logout')body={ok:true}
 else {status=404;body={error:'isolated_fixture_not_implemented'}}
 return route.fulfill({status,contentType:'application/json',body:JSON.stringify(body)})
})
const page=await context.newPage();page.on('pageerror',e=>errors.push(e.message))
async function go(path){await page.goto('http://127.0.0.1:4173'+path);await page.waitForLoadState('networkidle')}
async function overflow(){return page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1)}
try{
 await record('mobile navigation is compact, expandable, keyboard dismissible and route-closing',async()=>{
  await go('/drives');assert.ok((await page.locator('.ml-header').boundingBox()).height<=90)
  const menu=page.getByRole('button',{name:'导航',exact:true});await menu.click();assert.equal(await page.locator('#site-navigation').isVisible(),true)
  await page.keyboard.press('Escape');assert.equal(await page.locator('#site-navigation').isVisible(),false)
  await menu.click();await page.getByRole('link',{name:'设置',exact:true}).click();await page.waitForURL('**/me');assert.equal(await page.locator('#site-navigation').isVisible(),false)
 })()
 await record('real trip page uses seven calendar cards and preserves parking',async()=>{
  await go('/drives');await page.locator('.ml-day-header h2').waitFor();assert.match(await page.locator('.ml-day-header h2').innerText(),/2026-11-13/)
  assert.equal(await page.locator('.ml-day-rear').count(),6);assert.ok((await page.locator('.ml-day-content').innerText()).includes('驻车'))
  assert.equal(await page.locator('.ml-day-stack').getAttribute('class'),'ml-day-stack axis-horizontal')
  await page.screenshot({path:dir+'/trips-neutral-393.png',fullPage:true})
 })()
 await record('rear card raises, repeated click collapses',async()=>{
  const yesterday=page.locator('.ml-day-rear').filter({hasText:'2026-11-12'});await yesterday.click();assert.match(await page.locator('.ml-day-header h2').innerText(),/2026-11-12/)
  await yesterday.click();assert.match(await page.locator('.ml-day-header h2').innerText(),/2026-11-13/)
 })()
 await record('horizontal drag changes date; vertical drag does not',async()=>{
  const h=page.locator('.ml-day-header');await h.scrollIntoViewIfNeeded();const b=await h.boundingBox();await page.mouse.move(b.x+b.width-25,b.y+24);await page.mouse.down();await page.mouse.move(b.x+25,b.y+24,{steps:8});await page.mouse.up();assert.match(await h.innerText(),/2026-11-12/)
  await page.mouse.move(b.x+60,b.y+10);await page.mouse.down();await page.mouse.move(b.x+62,b.y+75,{steps:8});await page.mouse.up();assert.match(await h.innerText(),/2026-11-12/)
 })()
 await record('trip detail keeps rich endpoints and top full curves with only two main curves',async()=>{
  await page.locator('.ml-day-content button.apple-card').first().click();await page.getByRole('button',{name:'详细曲线',exact:true}).waitFor()
  assert.ok((await page.locator('main').innerText()).includes('合成起点广场'))
  assert.equal(await page.locator('#full-curves').getAttribute('open'),null)
  await page.getByRole('button',{name:'详细曲线',exact:true}).click();assert.notEqual(await page.locator('#full-curves').getAttribute('open'),null)
 })()
 await record('charge grid has every day, genuine zero and missing separately',async()=>{
  await go('/charges');const grid=page.getByRole('region',{name:'按日充入电量'});await grid.waitFor();assert.equal(await grid.locator('.ml-charge-cell').count(),30)
  await grid.getByRole('button',{name:/2026-11-12 40.00 kWh/}).click();assert.match(await page.locator('.ml-calendar-caption').innerText(),/40.00 kWh/)
  assert.equal(await grid.getByRole('button',{name:/2026-11-11 0.00 kWh/}).count(),1)
  assert.equal(await grid.getByRole('button',{name:/2026-11-10 尚未读到/}).count(),1)
  await page.screenshot({path:dir+'/charges-neutral-393.png',fullPage:true})
 })()
 await record('cream preference and optional vertical navigation persist across page reload',async()=>{
  await go('/me');await page.getByLabel('A · 奶白').check();await page.getByLabel('上下滑动').check();await page.reload();assert.equal(await page.locator('html').getAttribute('data-palette'),'cream')
  await go('/drives');assert.match(await page.locator('.ml-day-stack').getAttribute('class'),/axis-vertical/)
  const h=page.locator('.ml-day-header');await h.scrollIntoViewIfNeeded();const b=await h.boundingBox();await page.mouse.move(b.x+30,b.y+75);await page.mouse.down();await page.mouse.move(b.x+30,b.y+10,{steps:8});await page.mouse.up();assert.match(await h.innerText(),/2026-11-12/)
  await page.screenshot({path:dir+'/trips-cream-393.png',fullPage:true})
 })()
 await record('reduced motion disables card animations',async()=>{await go('/me');await page.getByLabel('减少动画').check();await go('/drives');assert.equal(await page.locator('.ml-day-content').evaluate(e=>getComputedStyle(e).animationName),'none')})()
 for(const width of [320,393,1440])for(const path of ['/','/drives','/charges','/me','/data-info'])await record(`no horizontal overflow ${path} at ${width}`,async()=>{
  await page.setViewportSize({width,height:900});await go(path);assert.ok(await overflow(),`overflow ${path} ${width}`)
  if(width===1440||path==='/me'||path==='/data-info')await page.screenshot({path:dir+`/${path.slice(1)||'dashboard'}-${width}.png`,fullPage:true})
 })()
 await record('logout cancellation keeps session and confirmed logout opens minimal login',async()=>{
  await page.setViewportSize({width:393,height:852});await go('/me');page.once('dialog',d=>d.dismiss());await page.getByRole('button',{name:'退出登录',exact:true}).first().click();assert.ok(await page.evaluate(()=>localStorage.getItem('matelink-web-session-v1')))
  page.once('dialog',d=>d.accept());await page.getByRole('button',{name:'退出登录',exact:true}).first().click();await page.waitForURL('**/login');assert.equal(await page.evaluate(()=>localStorage.getItem('matelink-web-session-v1')),null)
  assert.equal(await page.locator('details[open]').count(),0);const box=await page.getByRole('button',{name:'使用 Tesla 账号登录',exact:true}).boundingBox();assert.ok(box.y+box.height<852)
  await page.screenshot({path:dir+'/login-393.png',fullPage:true})
 })()
 await record('no external traffic, vehicle commands or uncaught browser exceptions',async()=>{assert.deepEqual(external,[]);assert.deepEqual(errors,[]);assert.ok(!requests.some(r=>r.method==='POST'&&r.path!=='/v1/session/logout'))})()
}finally{
 await writeFile(dir+'/report.json',JSON.stringify({evidence:'synthetic built-product browser; not device/provider acceptance',checks,errors,external,requests},null,2));await browser.close()
}
console.log(JSON.stringify({passed:checks.filter(c=>c.pass).length,failed:checks.filter(c=>!c.pass)},null,2))
if(checks.some(c=>!c.pass))process.exitCode=1
