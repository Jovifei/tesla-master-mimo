import { Button, Input, Switch, Text, View } from '@tarojs/components'
import Taro from '@tarojs/taro'
import { useEffect, useRef, useState } from 'react'
import { evaluateTpmsAlerts, validTpmsProfile, type TpmsProfile } from '../domain/tpms_alerts'
import type { TpmsSnapshot } from '../domain/tpms'
import type { TpmsHistory } from '../services/tpms_cache'
import { readTpmsProfile, tpmsProfileKey, writeTpmsProfile, type TpmsProfileScope } from '../services/tpms_profile'

type Draft = { enabled: boolean; changeEnabled: boolean; targetBar: string; lowBar: string; highBar: string; deltaBar: string; windowHours: string }
const blank = (): Draft => ({ enabled: false, changeEnabled: false, targetBar: '', lowBar: '', highBar: '', deltaBar: '', windowHours: '' })
const labels = { targetBar: '目标胎压 bar', lowBar: '低压阈值 bar', highBar: '高压阈值 bar', deltaBar: '变化阈值 bar（0.1–2）', windowHours: '变化窗口小时（1–720）' }
export type TpmsSettingsPanelProps = { scope: TpmsProfileScope; snapshot?: TpmsSnapshot | null; history?: TpmsHistory | null; isCurrent: () => boolean }
export default function TpmsSettingsPanel({ scope, snapshot, history, isCurrent }: TpmsSettingsPanelProps) {
  const key = tpmsProfileKey(scope), owner = useRef<string | null>(null)
  const [draft, setDraft] = useState<Draft>(blank), [profile, setProfile] = useState<TpmsProfile | null>(null), [message, setMessage] = useState<string | null>(null)
  const current = () => { try { return isCurrent() } catch { return false } }
  useEffect(() => {
    owner.current = key; setDraft(blank()); setProfile(null); setMessage(null)
    const saved = current() ? readTpmsProfile(Taro, scope) : null
    if (saved) { setProfile(saved); setDraft({ enabled: saved.enabled, changeEnabled: saved.changeEnabled, targetBar: String(saved.targetBar), lowBar: String(saved.lowBar), highBar: String(saved.highBar), deltaBar: String(saved.deltaBar), windowHours: String(saved.windowHours) }) }
    return () => { owner.current = null }
  }, [key])
  const save = () => {
    if (!key || owner.current !== key || !current()) { setDraft(blank()); setProfile(null); setMessage('账号或车辆已切换，请重新打开设置'); return }
    const number = (value: string) => value.trim() ? Number(value) : NaN
    const next: TpmsProfile = { enabled: draft.enabled, changeEnabled: draft.changeEnabled, targetBar: number(draft.targetBar), lowBar: number(draft.lowBar), highBar: number(draft.highBar), deltaBar: number(draft.deltaBar), windowHours: number(draft.windowHours) }
    if (!validTpmsProfile(next)) { setMessage('请输入 0<低压<目标<高压；变化阈值0.1–2 bar，窗口1–720整小时'); return }
    const saved = writeTpmsProfile(Taro, scope, next, () => owner.current === key && current())
    if (!saved.written) { setMessage(saved.reason === 'ambiguous_archive' ? '已有设置记录无法验证，原记录已保留' : '本机设置未保存，请刷新后重试'); return }
    setProfile(next); setMessage('本机胎压设置已保存')
  }
  const comparison = evaluateTpmsAlerts(profile, snapshot, history?.points)
  const names = { fl: '左前', fr: '右前', rl: '左后', rr: '右后' }
  return <View className="card tpms-settings"><Text className="section-title">本机胎压比较设置</Text>
    <Text className="muted">请按车辆标牌与个人需要明确填写；未自动猜测车型目标胎压。仅比较合格的新鲜逐轮观测，不代表后台通知或已送达。</Text>
    <View><Text>启用高低压比较</Text><Switch checked={draft.enabled} onChange={event => setDraft({ ...draft, enabled: event.detail.value })} /></View>
    {Object.entries(labels).map(([field, label]) => <View key={field}><Text>{label}</Text><Input type="digit" value={draft[field as keyof typeof labels]} onInput={event => setDraft({ ...draft, [field]: event.detail.value })} /></View>)}
    <View><Text>启用前台历史变化比较</Text><Switch checked={draft.changeEnabled} onChange={event => setDraft({ ...draft, changeEnabled: event.detail.value })} /></View>
    <Button className="button" onClick={save}>保存本机胎压设置</Button>{message ? <Text className="muted">{message}</Text> : null}
    {comparison.wheels.map(wheel => <View className="status" key={wheel.wheel}><Text>{names[wheel.wheel]}</Text><Text>{wheel.state === 'unknown' ? '缺少新鲜合格观测' : wheel.state === 'disabled' ? '高低压比较已关闭' : wheel.state === 'low' ? '低于设置阈值' : wheel.state === 'high' ? '高于设置阈值' : '设置范围内'}{wheel.pressureBar === null ? '' : ` · ${wheel.pressureBar} bar`}{wheel.changed ? ` · 变化 ${wheel.deltaBar?.toFixed(2)} bar` : ''}</Text></View>)}
    <Text className="muted">变化仅使用此账号、此车辆、此来源的本机前台历史；无旧观测时不生成变化结论。{history?.limited ? '历史覆盖有界，部分旧观测未保留。' : ''}</Text>
  </View>
}
