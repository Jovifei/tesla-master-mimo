from pathlib import Path
import json, hashlib, os, shutil
from playwright.sync_api import sync_playwright
ROOT=Path(__file__).resolve().parent
HTML=ROOT/'preview.html'
results=[]
def check(name,ok):
    results.append({'name':name,'pass':bool(ok)})
    assert ok,name
with sync_playwright() as p:
    browser=p.chromium.launch(executable_path=os.environ.get('CHROME_BIN') or shutil.which('chromium') or shutil.which('google-chrome'),headless=True,args=['--no-sandbox'])
    page=browser.new_page(viewport={'width':1280,'height':1000})
    external=[];errors=[]
    page.on('request',lambda r: external.append(r.url) if r.url.startswith(('http:','https:')) else None)
    page.on('pageerror',lambda e:errors.append(str(e)))
    page.set_content(HTML.read_text(),wait_until='load');page.wait_for_selector('[data-tab="home"]')
    check('home car loads',page.locator('.car').evaluate('(x)=>x.complete && x.naturalWidth>0'))
    cdp=page.context.new_cdp_session(page)
    cdp.send('DOM.enable');cdp.send('CSS.enable')
    document=cdp.send('DOM.getDocument')
    heading=cdp.send('DOM.querySelector',{'nodeId':document['root']['nodeId'],'selector':'#heading h1'})
    rendered_fonts=cdp.send('CSS.getPlatformFontsForNode',{'nodeId':heading['nodeId']})['fonts']
    check('actual Chinese glyphs rendered',any('CJK' in f['familyName'] and f['glyphCount']>=2 for f in rendered_fonts))
    page.locator('[data-tab="drives"]').click()
    check('tab uses no fade', 'fade' not in page.locator('#content').get_attribute('class'))
    page.locator('#content [data-go="parked"]').click();page.locator('[data-action="retry"]').click()
    check('loading preserves duration',page.locator('#content').inner_text().find('10h 48m')>=0)
    check('loading is cancellable',page.locator('[data-action="cancel-load"]').count()==1)
    page.locator('[data-action="cancel-load"]').click();check('cancel removes skeleton',page.locator('.skeleton').count()==0)
    page.locator('.receipt [data-go="tpms"]').click()
    expected=['2.73','2.75','2.75','2.78']
    for i in range(4):
        page.locator(f'[data-wheel="{i}"]').click()
        check(f'wheel {i} focus',page.locator('.wheel.active strong').inner_text()==expected[i]+' bar')
        last=page.locator('.plot circle').last.get_attribute('cy'); expected_y=170-(float(expected[i])-2.5)/.5*152
        check(f'wheel {i} graph matches',abs(float(last)-expected_y)<1e-6)
    page.screenshot(path=str(ROOT/'preview-tpms.png'))
    page.locator('.receipt [data-go="mismatch"]').click()
    check('identity mismatch hides fee edit',page.locator('#content [data-go="cost"]').count()==0)
    check('identity mismatch contains no fabricated energy','kWh' not in page.locator('#content').inner_text())
    page.locator('[data-tab="charges"]').click();page.locator('#content [data-go="charge"]').click();page.locator('[data-go="cost"]').click()
    page.locator('#amount').fill('0');page.locator('#cost-form button').click()
    check('valid zero preserved','¥0.00' in page.locator('#actual-cost').inner_text())
    page.locator('[data-go="cost"]').click();page.locator('#amount').fill('');page.locator('#cost-form button').click()
    check('empty is unknown','未记录' in page.locator('#actual-cost').inner_text())
    page.locator('[data-tab="home"]').click();page.locator('#content [data-go="nearby"]').click();page.locator('[data-action="navigation"]').first.click()
    check('overlay opens',page.locator('[role="dialog"]').count()==1)
    page.keyboard.press('Escape');check('escape closes overlay',page.locator('[role="dialog"]').count()==0)
    page.locator('#motion').click();page.locator('[data-action="navigation"]').first.click()
    check('reduced motion global',page.locator('.sheet').evaluate('(x)=>getComputedStyle(x).animationName')=='none')
    page.keyboard.press('Escape');page.locator('#theme').click()
    check('dark theme',page.locator('body').evaluate('(x)=>getComputedStyle(x).getPropertyValue("--ink").trim()')=='#f3f7f8')
    page.locator('[data-tab="home"]').click();page.screenshot(path=str(ROOT/'preview-home-dark.png'))
    page.locator('#theme').click();page.screenshot(path=str(ROOT/'preview-home-light.png'))
    page.locator('.receipt [data-go="mini"]').click()
    check('mini direct authorization action',page.locator('#content [data-go="auth"]').count()==1)
    page.locator('#content [data-go="auth"]').click()
    check('prototype does not request credentials',page.locator('input[type="password"]').count()==0)
    page.set_viewport_size({'width':390,'height':844});page.locator('[data-tab="home"]').click()
    check('390 width no page overflow',page.evaluate('document.documentElement.scrollWidth<=window.innerWidth'))
    page.locator('.receipt [data-go="tpms"]').click()
    check('tpms no horizontal overflow',page.locator('#content').evaluate('(x)=>x.scrollWidth<=x.clientWidth'))
    check('no external requests',not external)
    check('no runtime errors',not errors)
    browser.close()
report={'html_sha256':hashlib.sha256(HTML.read_bytes()).hexdigest(),'rendered_heading_fonts':rendered_fonts,'checks':results,'passed':sum(x['pass'] for x in results),'failed':sum(not x['pass'] for x in results),'network_requests':external,'runtime_errors':errors,'evidence_class':'isolated desktop Chromium on generated offline design prototype; NOT Figma render, device or provider acceptance'}
(ROOT/'preview-test-report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2))
print(json.dumps({k:v for k,v in report.items() if k!='checks'},ensure_ascii=False,indent=2))
