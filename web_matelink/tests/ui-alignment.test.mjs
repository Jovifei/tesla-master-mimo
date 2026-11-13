import test from 'node:test'
import assert from 'node:assert/strict'
import {normalizeAppearance,daySwipeTarget,calendarDays,civilDay,chargeShade,dailyChargeCells} from '../src/components/presentation-policy.ts'
import {saveSession,clearSession,loadSession,getCars,refreshSession,logout} from '../src/api/matelink.ts'

test('neutral horizontal defaults, only owned appearance preferences',()=>{
 assert.deepEqual(normalizeAppearance({palette:'blue',dayAxis:'up',reducedMotion:1}),{palette:'neutral',dayAxis:'horizontal',reducedMotion:false})
 assert.deepEqual(normalizeAppearance({palette:'cream',dayAxis:'vertical',reducedMotion:true}),{palette:'cream',dayAxis:'vertical',reducedMotion:true})
})
test('orthogonal swipes and short drags never change a date; both chosen axes bound at ends',()=>{
 assert.equal(daySwipeTarget(0,7,-60,2,'horizontal'),1)
 assert.equal(daySwipeTarget(0,7,2,-60,'horizontal'),0)
 assert.equal(daySwipeTarget(0,7,2,-60,'vertical'),1)
 assert.equal(daySwipeTarget(0,7,-60,2,'vertical'),0)
 assert.equal(daySwipeTarget(0,7,55,0,'horizontal'),0)
 assert.equal(daySwipeTarget(6,7,-60,0,'horizontal'),6)
 assert.equal(daySwipeTarget(0,7,-40,0,'horizontal'),0)
 assert.equal(daySwipeTarget(0,7,NaN,0,'horizontal'),0)
})
test('a full seven calendar dates across month/year boundaries, invalid and oversize ranges rejected',()=>{
 assert.equal(calendarDays('2026-12-29','2027-01-04').length,7)
 assert.equal(civilDay('2026-02-31'),null)
 assert.deepEqual(calendarDays('2026-11-14','2026-11-13'),[])
 assert.deepEqual(calendarDays('2024-01-01','2026-01-01'),[])
})
test('daily energy tiles distinguish zero, missing and partial; fixed 60 kWh shade',()=>{
 const cells=dailyChargeCells([{startDate:'2026-11-10',energyAddedKwh:0},{startDate:'2026-11-11',energyAddedKwh:20},{startDate:'2026-11-11',energyAddedKwh:null},{startDate:'2026-11-12',energyAddedKwh:null}], '2026-11-10','2026-11-13')
 assert.equal(cells[0].value,0);assert.equal(cells[1].value,20);assert.equal(cells[1].known,1);assert.equal(cells[1].count,2)
 assert.equal(cells[2].value,null);assert.equal(cells[3].value,null)
 assert.equal(chargeShade(40),2/3);assert.equal(chargeShade(60),1);assert.equal(chargeShade(100),1);assert.equal(chargeShade(0),0)
})
const storage=new Map()
globalThis.localStorage={getItem:k=>storage.get(k)??null,setItem:(k,v)=>storage.set(k,v),removeItem:k=>storage.delete(k)}
const current=(user='synthetic-a')=>({userId:user,accessToken:'fixture-access',refreshToken:'fixture-refresh',expiresAt:Date.now()+120000})
const deferred=()=>{let resolve;const promise=new Promise(r=>{resolve=r});return{promise,resolve}}
const response=data=>new Response(JSON.stringify(data),{status:200,headers:{'content-type':'application/json'}})
test('late authenticated response cannot publish after logout and a different account login',async()=>{
 saveSession(current());const wait=deferred();globalThis.fetch=()=>wait.promise
 const pending=getCars();clearSession();saveSession(current('synthetic-b'))
 wait.resolve(response({cars:[{id:1,name:'A-private'}]}))
 await assert.rejects(pending,e=>e.code==='stale_session');assert.equal(loadSession().userId,'synthetic-b')
})
test('late token refresh never revives a logged-out session or overwrites a newer login',async()=>{
 saveSession(current());const wait=deferred();globalThis.fetch=()=>wait.promise
 const pending=refreshSession();clearSession();saveSession(current('synthetic-b'))
 wait.resolve(response({access_token:'old',refresh_token:'old',expires_in:120,user:{id:'synthetic-a'}}))
 await assert.rejects(pending,e=>e.code==='stale_session');assert.equal(loadSession().userId,'synthetic-b')
})
test('concurrent token refresh is single flight; network failure retains valid stored session',async()=>{
 saveSession(current());const wait=deferred();let calls=0;globalThis.fetch=()=>{calls++;return wait.promise}
 const a=refreshSession(),b=refreshSession();assert.equal(a,b);assert.equal(calls,1)
 wait.resolve(response({access_token:'new',refresh_token:'next',expires_in:120,user:{id:'synthetic-a'}}));await a
 globalThis.fetch=async()=>{throw new Error('offline')}
 await assert.rejects(refreshSession(),e=>e.code==='network_error');assert.equal(loadSession().accessToken,'new')
})
test('aborted history consumer cancels its real request',async()=>{
 saveSession(current());globalThis.fetch=(_url,{signal})=>new Promise((_,reject)=>{signal.addEventListener('abort',()=>reject(new DOMException('abort','AbortError')),{once:true})})
 const c=new AbortController(),pending=getCars(c.signal);c.abort();await assert.rejects(pending,e=>e.code==='request_cancelled')
})
test('logout revokes captured session but does not erase a newer login after late failure',async()=>{
 saveSession(current());const wait=deferred();globalThis.fetch=()=>wait.promise
 const pending=logout();saveSession(current('synthetic-b'));wait.resolve(new Response('{}',{status:500}));await pending
 assert.equal(loadSession().userId,'synthetic-b')
})
