from pathlib import Path
import re, hashlib, difflib
root=Path('miniprogram/src')
original={}
expected={'app.config.ts': 'b2ce077aad11d9755717f4ee1366cef65bcf00a540b92ed5bdc35f6333acea00', 'app.scss': 'f3f9e826d3425999159ed75e9a5250a781643faf17be5199a4a2e85dbba2f3ad', 'app.ts': '726475ad1d4988fe535a5c9780493dbdbbf31ec73b978806235ab38d28f0bfd7', 'pages/analysis/index.tsx': 'd6f18123dbc8e44b47450e301f05630d2145fcb9f7315189efc096fbf6a6abe9', 'pages/auth/index.tsx': '861b453a4c39a8b0817c682c1478b92d871adaa338d6cad2c5a300f6337cb792', 'pages/charges/index.tsx': 'ae56fd54416c563f80dad2d33e1dff8aad54e74a0ea5392fffaba63f91df91ce', 'pages/drives/index.tsx': 'eb8017944d94dac3226a4a7829d6c85389e91e1196360c77b2de53335e82ed1f', 'pages/insights/index.tsx': 'f79e16600f38c97c8ab15d4add98ed65fbbb14493822ba7f66ce6288bb02b114', 'pages/legal/index.tsx': 'e8a4d4b6ddb075cf835c48a6530d9bd22d86fba83fe572d79eeebf4f96bb951b', 'pages/me/index.tsx': '02fc5256ac79d20235cf612ae0f5bb195ac21052222b8545cff58cf8c401bde3', 'pages/tools/index.tsx': '39698030462757625301971115972491f3d17c6516e43eeec7fe6c3003c68ad9', 'pages/vehicle/index.tsx': '5a5360c9f62aaed09e2bd7bd13d9cdc3f2cb699f2236ec0fa04f6d3facb113ca'}
for name,sha in expected.items():
    data=(root/name).read_bytes();assert hashlib.sha256(data).hexdigest()==sha,name
    original[name]=data.decode()
def put(name,text):
    (root/name).write_text(text,encoding='utf-8')
def load(name):return (root/name).read_bytes().decode().replace('\r\r\n','\n').replace('\r\n','\n')
s=load('app.ts');a=s.index('    const applyTheme =');b=s.index('\n  }, [])',a)
s=s[:a]+'''    const applyTheme = ({ theme }: { theme: string }) => syncAppearanceChrome(theme)
    syncAppearanceChrome()
    const unsubscribe = subscribeAppearance(() => syncAppearanceChrome())
    Taro.onThemeChange(applyTheme)
    return () => { Taro.offThemeChange(applyTheme); unsubscribe() }'''+s[b:]
s="import { subscribeAppearance, syncAppearanceChrome } from './services/appearance'\n"+s
put('app.ts',s)
s=load('app.scss');put('app.scss',s+'\n@import "./styles/presentation.scss";\n')
for p in sorted((root/'pages').glob('*/index.tsx')):
 name=str(p.relative_to(root));s=load(name)
 if 'className="page' not in s:continue
 assert name in expected,name
 s="import { useAppearance } from '../../services/appearance'\n"+s
 s,n=re.subn(r'(export default function \w+\(\) \{)',r'\1\n  const appearance = useAppearance()',s,count=1);assert n==1,name
 s=re.sub(r'className="(page[^"]*)"',lambda m:'className={`'+m[1]+' ${appearance.className}`}',s)
 put(name,s)
s=load('pages/tools/index.tsx');s="import { PresentationPreferences } from '../../components/PresentationControls'\n"+s
needle="{kind === 'settings' ? <>";assert s.count(needle)==1
s=s.replace(needle,needle+'<PresentationPreferences />');put('pages/tools/index.tsx',s)
s=load('pages/me/index.tsx');a=s.index('      <View className="more-status">');b=s.index('      <View className="card account-hero">',a)
menus=s[a:b];s=s[:a]+'''      {!session ? <View className="login-welcome"><Text className="title">每一程，都有记录。</Text><Text className="muted">连接你的 Tesla，查看车辆状态、行程与充电。账号登录、官方授权和车辆确认各自独立。</Text></View> : null}
'''+s[b:]
pos=s.index('      {session ? (\n        <View className="card">',s.index('      {authorizationPending ?'))
s=s[:pos]+'''      {session ? <>{MENUS}</> : null}
'''.replace('{MENUS}',menus)+s[pos:]
s=s.replace('className={`page more-page ${appearance.className}`}','className={`page more-page ${appearance.className}${session ? \'\' : \' minimal-login\'}`}')
put('pages/me/index.tsx',s)
s=load('pages/drives/index.tsx')
s="import { VerticalDayStack } from '../../components/PresentationControls'\nimport { historyDayGroups } from '../../domain/ui_presentation'\n"+s
needle="          {historyItems.map(item => item.kind === 'parked' ? (";assert s.count(needle)==1
s=s.replace(needle,'''          <VerticalDayStack groups={historyDayGroups(historyItems, item => item.kind === 'drive' ? item.drive.startDate : item.startDate)}
            scopeKey={JSON.stringify([getApiOrigin(), readAppSession(Taro)?.userId, getApiSessionGeneration(), car?.stableId, query])}>
          {group => group.items.map(item => item.kind === 'parked' ? (''')
a=s.index('          <VerticalDayStack');b=s.index('          {!offline && meta?.hasMore',a)
assert s[a:b].count('          ))}')==1
s=s[:a]+s[a:b].replace('          ))}','          ))}\n          </VerticalDayStack>')+s[b:]
a=s.index('            const estRangeLossKm =');b=s.index('            const cloudDetail =',a)
s=s[:a]+'''            const standbyWatts = parkedCloud?.averagePowerW ?? null
            const socPointsPerHour = delta != null && parkedOpen.minutes > 0 ? delta / (parkedOpen.minutes / 60) : null
            const heroMetrics = [
              parkedCloud?.energyKwh != null ? `已记录 ${parkedCloud.energyKwh.toFixed(2)} kWh` : null,
              standbyWatts != null ? `平均功率 ${Math.round(standbyWatts)} W` : null,
            ].filter((metric): metric is string => metric != null)
'''+s[b:]
s=s.replace('<MetricValue label="续航损失" value={estRangeLossKm != null ? (delta != null && delta < 0 ? -Math.round(estRangeLossKm) : 0) : null} unit="km" icon="energy" digits={0} />','<MetricValue label="平均功率" value={standbyWatts} unit="W" icon="energy" digits={0} />')
s=s.replace('驻车电量下降 {Math.abs(socPointsPerHour).toFixed(1)} 百分点/小时{standbyWatts != null ? ` · 待机功耗 ≈${Math.round(standbyWatts)} W` : \'\'}','驻车净电量变化 {socPointsPerHour.toFixed(1)} 百分点/小时；不能据此判定休眠或哨兵状态。')
s=s.replace('label="估算能耗" value={parkedCloud.energyKwh}','label="已记录能量" value={parkedCloud.energyKwh}')
a=s.index('              <View className="section-card parked-standby-analysis">');b=s.index('\n            </>',a)
s=s[:a]+'''              <View className="section-card parked-standby-analysis">
                <View className="section-card-header"><View className="section-card-title"><VisualIcon name="energy" /><Text>驻车数据说明</Text></View></View>
                <Text className="muted parked-analysis-note">电量变化包含驻车期间可能的充电；休眠、哨兵状态与续航损失需要各自的记录。缺少能量或功率证据时保持未知，不使用猜测的电池容量换算。</Text>
              </View>'''+s[b:]
put('pages/drives/index.tsx',s)
s=load('pages/charges/index.tsx');s="import { ChargeActivityGrid } from '../../components/PresentationControls'\nimport { civilDay } from '../../domain/ui_presentation'\n"+s
s=s.replace("const key = `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}`","const key = civilDay(row.startDate)!" )
s=s.replace("label: `${date.getMonth() + 1}月`", "label: `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`")
s=s.replace('.slice(-6).map(([, month]) => month)', '.map(([, month]) => month)')
s=s.replace('  const chartMaximum = Math.max(0, ...chartMonths.map(chartValue))\n','')
a=s.index('        <View className="charge-chart-bars">');b=s.index('        <Text className="muted">统计已返回的',a)
s=s[:a]+'''        <ChargeActivityGrid unit={chartKind === 'energy' ? 'kWh' : chartKind === 'count' ? '次' : chartCurrency || '币种待确认'} cells={chartMonths.map(month => ({
          key: month.label, label: month.label.slice(5), value: chartKnown(month) ? chartValue(month) : null,
          display: `${chartKnown(month) ? `${chartKind === 'cost' && month.costEstimated ? '含估算 ' : ''}${chartValue(month).toFixed(chartKind === 'count' ? 0 : 2)} ${chartKind === 'energy' ? 'kWh' : chartKind === 'count' ? '次' : chartCurrency}` : '未知'} · 已知 ${chartKnown(month)} / ${month.count} 条`,
        }))} />
'''+s[b:]
put('pages/charges/index.tsx',s)
s=load('app.config.ts');s=s.replace("'#157a86'", "'#1d1d1f'").replace("'#f5f7f9'", "'#f5f5f7'");put('app.config.ts',s)
for name,phrase in [('pages/drives/index.tsx','请先在“更多”中建立会话。'),('pages/charges/index.tsx','请先在“更多”中登录特斯拉账号。')]:
    p=root/name;s=p.read_text()
    s=s.replace('<Text className="muted">'+phrase+'</Text>', '<Text className="muted">'+phrase+'</Text><Button className="button" onClick={() => Taro.switchTab({ url: \'/pages/me/index\' })}>前往登录与授权</Button>')
    p.write_text(s)
for name in ['pages/charges/index.tsx','pages/drives/index.tsx']:
    old=original[name];p=root/name
    physical=old.splitlines(keepends=True) if '\r\r\n' not in old else [x+'\n' for x in old.split('\n')[:-1]]
    logical=[x.rstrip('\r\n') for x in physical];new=p.read_text().splitlines();out=[]
    for tag,a,b,c,d in difflib.SequenceMatcher(None,logical,new,autojunk=False).get_opcodes():
        out.extend(physical[a:b] if tag=='equal' else [line+'\n' for line in new[c:d]])
    p.write_bytes(''.join(out).encode())
final={'miniprogram/src/app.config.ts': '516c6eb0d97c9c27ff29f56f186bb3be2342bf16803109e6e5a06879680c65fc', 'miniprogram/src/app.scss': '94a48af972bc0585e95259b9a1b5374fe3167f8a79645b1869dfdc072eb2c574', 'miniprogram/src/app.ts': 'e1c97becb9fba1cd967e11510ae4ee552ee40eb45aebf3275259fa0d606b0afe', 'miniprogram/src/components/PresentationControls.test.tsx': '23a17293ae32cd0496d39ca607f0fc9c4b67c1ceb3558f5be47de138f5c2e84b', 'miniprogram/src/components/PresentationControls.tsx': 'e855cda73cd57950c214e2d212961c032ce22bb5309db7487f857a9c6065bd3d', 'miniprogram/src/domain/ui_presentation.ts': '51d2a50f8610ae484482fad2dbedd2bc61a2e7e7ce8558e14a5a8e3c4b873218', 'miniprogram/src/pages/analysis/index.tsx': '18573f8556c822f468d1c9aa4ffeac2d3ed9d08ca265edc146ad9f3f72838517', 'miniprogram/src/pages/auth/index.tsx': 'ec2d721680d0b33d87a846af1b59ecaedc9ae18149292beb49e3eeeaf680b4b0', 'miniprogram/src/pages/charges/index.tsx': 'ea46913baa7bc60e779be7e5dff4ef400149a110e7f61fc35dcc2388d2e5b444', 'miniprogram/src/pages/drives/index.tsx': '9770b17fa88bb4d60a6cc9cecd55eb5f049c2248c27435b65bc409da1ee6beaa', 'miniprogram/src/pages/insights/index.tsx': '3b447a550e4df9a923286c6bc075ac93e644525af76505aee8c64b08dd82f56e', 'miniprogram/src/pages/legal/index.tsx': 'd184cfdbc8adf9f003e986f06d1e0091ff02d1692740932dec902f7ed1006b3e', 'miniprogram/src/pages/me/index.tsx': '583aeedfefc7c9b50dc74ce86a7c3bc523d257c2252e27b7c54b658aba31952f', 'miniprogram/src/pages/tools/index.tsx': 'af2755fd94e26dd9460d0454d8f0c9f27825ff78085a923bf5a410d68663aa80', 'miniprogram/src/pages/vehicle/index.tsx': '965b60678846de71b5a71353df1b10f7b2e4b013e71913a1879147447a0f4f38', 'miniprogram/src/services/appearance.ts': '7b72db3b33230efa8018a1fc6604f3218534d5532657ae20d514c2eb19a21ca6', 'miniprogram/src/styles/presentation.scss': 'f99e25b3ece26e0e7ef9a1ebb7121c8e5732d6a108a1a53c54039b181cba5a4e'}
for name,sha in final.items():
    assert hashlib.sha256(Path(name).read_bytes()).hexdigest()==sha, name
