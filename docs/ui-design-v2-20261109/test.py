"""Exercise the actual offline V2 DOM in Chromium; no production access."""
from pathlib import Path
import json, os, re
from playwright.sync_api import sync_playwright
ROOT=Path(__file__).resolve().parent
out=ROOT/'rendered';out.mkdir(exist_ok=True)
checks=[];errors=[];requests=[]
def check(name,ok):
    checks.append({'name':name,'passed':bool(ok)})
    assert ok,name
with sync_playwright() as pw:
    b=pw.chromium.launch(headless=True)
    p=b.new_page(viewport={'width':1380,'height':1000},device_scale_factor=1)
    p.on('pageerror',lambda e:errors.append(str(e)))
    p.on('request',lambda r: requests.append(r.url) if r.url.startswith(('https:','http:')) else None)
    p.goto((ROOT/'preview.html').as_uri());p.wait_for_function('!!window.MateLinkReview')
    check('default palette is warm milk white',p.eval_on_selector('#palette','e=>e.value')=='cream')
    required=['已锁','未插枪','空调关闭','哨兵已开启','里程','位置','最近行程','出发点 A','目的地 B','能耗','时长','极速','车内','车外','胎压','附近充电','近 7 天电量变化']
    check('homepage preserves all owner-required information',all(s in p.locator('#view').inner_text() for s in required))
    check('unplugged is status, not a false charge command',p.locator('.statusitem').nth(1).is_disabled())
    p.locator('[data-control="解锁车辆"]').click();check('vehicle action requires explicit demonstration confirmation',p.get_by_role('dialog').count()==1 and '不会向车辆发送' in p.get_by_role('dialog').inner_text())
    p.keyboard.press('Escape');check('escape closes modal',p.get_by_role('dialog').count()==0)
    check('focus returns to initiating control',p.evaluate("document.activeElement.dataset.control==='解锁车辆'"))
    keys=p.evaluate('Object.keys(MateLinkReview.pages)')
    for key in keys:
        p.evaluate('(id)=>MateLinkReview.navigate(id)',key)
        check('page renders '+key,p.locator('#view h1').count()>0 and len(p.locator('#view').inner_text())>20)
        check('no horizontal viewport overflow '+key,p.eval_on_selector('#view','e=>e.scrollWidth<=e.clientWidth+1'))
        p.locator('.phone').screenshot(path=str(out/(key+'.png')))
    # Shared content must be identical, not separate visual implementations.
    for key in ['home','drives','charges','more','stats','chargeStats','battery','mileage','history','longTrips','efficiency','cost','range','standby','annual','data','sentry','settings','about','tpms','temperature']:
        p.select_option('#platform','app');p.evaluate('(id)=>MateLinkReview.navigate(id)',key)
        app=p.locator('#view').inner_text()
        p.select_option('#platform','mini');mini=p.locator('#view').inner_text()
        mini=re.sub(r'^MateLink 小程序\s*••• ◉\s*','',mini)
        check('App/Mini common page parity '+key,app==mini)
    p.select_option('#platform','app');p.select_option('#daymode','accordion')
    check('today initially expanded, yesterday separate',p.locator('.daycontent').count()==1 and p.locator('.daygroup').count()==3)
    p.locator('[data-dayfold="1"]').click();check('yesterday can expand without losing today',p.locator('.daycontent').count()==2)
    check('each expanded date retains drive and parked history',p.locator('.daycontent .parkrow').count()==2 and p.locator('.daycontent .tripentry').count()==4)
    p.locator('[data-dayfold="0"]').click();check('date can collapse',p.locator('.daycontent').count()==1)
    p.locator('.phone').screenshot(path=str(out/'dates-accordion.png'))
    p.select_option('#daymode','stack');p.evaluate('document.querySelector("#view").scrollTop=0')
    check('stack has two passive back cards',p.locator('.stackback').count()==2)
    p.locator('[data-day="1"]').first.click();check('stack date switches by explicit button',p.evaluate('MateLinkReview.state.day')==1)
    p.locator('#daypager').dispatch_event('pointerdown',{'clientX':200,'clientY':100})
    p.locator('#daypager').dispatch_event('pointerup',{'clientX':100,'clientY':106})
    check('horizontal date gesture moves to older date',p.evaluate('MateLinkReview.state.day')==2)
    p.locator('#daypager').dispatch_event('pointerdown',{'clientX':100,'clientY':100})
    p.locator('#daypager').dispatch_event('pointerup',{'clientX':105,'clientY':220})
    check('vertical scrolling does not switch date',p.evaluate('MateLinkReview.state.day')==2)
    p.locator('.phone').screenshot(path=str(out/'dates-stack.png'))
    p.select_option('#chartmode','cells');check('calendar alternative retains exact numeric counts',p.locator('[data-cell]').count()==7 and p.locator('[data-cell="0"]').inner_text().startswith('4'))
    p.select_option('#daymode','strip');check('strip has no fake depth cards',p.locator('.stackback').count()==0)
    p.evaluate("MateLinkReview.navigate('home')");p.locator('[data-station]').first.click();check('station navigation is a single contextual sheet',p.get_by_role('dialog').count()==1 and '目标：示例超充站 A' in p.get_by_role('dialog').inner_text())
    p.keyboard.press('Escape')
    p.evaluate("MateLinkReview.navigate('chargeDetail')");p.locator('[data-action="bill"]').click();p.locator('[data-action="save-bill"]').click()
    check('empty payment does not become zero','空值不算零' in p.locator('#billerror').inner_text())
    p.locator('#bill').fill('0');p.locator('[data-action="save-bill"]').click();check('valid zero payment survives',p.locator('#billvalue').inner_text()=='0.00 元')
    p.evaluate("MateLinkReview.navigate('tpms')");paths=[]
    for i in range(4):
        p.locator('[data-select-wheel]').nth(i).click();paths.append(p.locator('.chart .series').get_attribute('d'))
    check('four independent wheel trend examples',len(set(paths))==4)
    check('unknown history is not a fabricated temperature chart',p.evaluate("(()=>{MateLinkReview.navigate('temperature');return !document.querySelector('#view .chart')})()"))
    p.select_option('#reduced','true');p.select_option('#daymode','accordion');p.locator('[data-dayfold="0"]').click()
    check('manual reduced motion stops animations',p.evaluate("[...document.querySelectorAll('#view *')].every(e=>getComputedStyle(e).animationName==='none')"))
    p.select_option('#reduced','false');p.emulate_media(reduced_motion='reduce')
    check('system reduced motion also stops animations',p.evaluate("[...document.querySelectorAll('#view *')].every(e=>getComputedStyle(e).animationName==='none')"))
    p.emulate_media(reduced_motion='no-preference')
    # Visual-review regressions: native field meaning and above-fold login action.
    for key,unit in [('stats','km'),('chargeStats','kWh'),('mileage','km'),('efficiency','kWh/100km'),('cost','元'),('range','%'),('standby','百分点/天')]:
        p.evaluate('(id)=>MateLinkReview.navigate(id)',key)
        check('metric-specific chart unit '+key,p.locator('#analysis-chart').get_attribute('data-unit')==unit)
    p.evaluate("MateLinkReview.navigate('standby')")
    check('standby chart retains negative SOC change',float(p.locator('#analysis-chart').get_attribute('data-min'))<0)
    p.evaluate("MateLinkReview.navigate('range')")
    check('range percentage uses appropriate scale',p.locator('#analysis-chart').get_attribute('data-max')=='100')
    p.evaluate("MateLinkReview.navigate('charges')")
    check('charging fourth summary retains original average price meaning','平均电价' in p.locator('#view').inner_text() and '元/kWh' in p.locator('#view').inner_text())
    for width in [393,320]:
        p.set_viewport_size({'width':width,'height':1100})
        for mode in ['minimal','vehicle']:
            p.select_option('#loginmode',mode)
            p.evaluate('document.querySelector("#view").scrollTop=0')
            check('login primary action above fold '+str(width)+' '+mode,p.eval_on_selector('[data-action="login"]',"e=>{const r=e.getBoundingClientRect(),v=document.querySelector('#view').getBoundingClientRect();return r.top>=v.top&&r.bottom<=v.bottom}"))
            check('auth does not expose confusing inactive bottom tabs '+str(width)+' '+mode,p.eval_on_selector('#tabs',"e=>getComputedStyle(e).display==='none'"))
    p.set_viewport_size({'width':1380,'height':1000})
    # Inspect actual Chinese font use, not just declared family.
    p.evaluate("MateLinkReview.navigate('home')")
    cdp=p.context.new_cdp_session(p);cdp.send('DOM.enable');cdp.send('CSS.enable')
    doc=cdp.send('DOM.getDocument');nid=cdp.send('DOM.querySelector',{'nodeId':doc['root']['nodeId'],'selector':'#view h1'})['nodeId']
    fonts=cdp.send('CSS.getPlatformFontsForNode',{'nodeId':nid})['fonts']
    check('actual CJK glyphs rendered',any(f['glyphCount']>0 and any(x in f['familyName'] for x in ['Noto Sans CJK','PingFang','Microsoft YaHei']) for f in fonts))
    for pal in ['cream','ice','white']:
        p.select_option('#palette',pal);p.evaluate("MateLinkReview.navigate('home')")
        p.locator('.phone').screenshot(path=str(out/('palette-'+pal+'.png')))
    p.select_option('#palette','cream')
    for mode in ['minimal','vehicle']:
        p.select_option('#loginmode',mode);p.evaluate('document.querySelector("#view").scrollTop=0');p.locator('.phone').screenshot(path=str(out/('login-'+mode+'.png')))
    p.select_option('#platform','mini');p.evaluate("MateLinkReview.navigate('home')");p.locator('.phone').screenshot(path=str(out/'mini-home.png'))
    p.select_option('#platform','app');p.select_option('#chartmode','bars');p.select_option('#daymode','accordion')
    for key in ['home','drives','charges','more']:
        p.evaluate('(id)=>MateLinkReview.navigate(id)',key)
        style=p.add_style_tag(content='.phone{height:auto}.scroll{height:auto;overflow:visible}.nav{position:relative}.homebar{display:none}')
        p.locator('.phone').screenshot(path=str(out/(key+'-full.png')));style.evaluate('e=>e.remove()')
    p.set_viewport_size({'width':320,'height':950})
    for key in keys:
        p.evaluate('(id)=>MateLinkReview.navigate(id)',key)
        check('320px fit '+key,p.eval_on_selector('#view','e=>e.scrollWidth<=e.clientWidth+1'))
    check('no JavaScript runtime errors',not errors)
    check('zero external network requests',not requests)
    b.close()
report={'scope':'offline V2 prototype only; not Figma/native/production acceptance','passed':sum(x['passed'] for x in checks),'failed':sum(not x['passed'] for x in checks),'checks':checks,'errors':errors,'external_requests':requests,'actual_fonts':fonts,'page_count':len(keys),'figma_updated':False}
(ROOT/'test-report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps({k:v for k,v in report.items() if k!='checks'},ensure_ascii=False))
