import { Button, Picker, ScrollView, Text, View } from '@tarojs/components'
import { useState } from 'react'
import type { VehicleVisualConfig } from '../../domain/vehicle_visual'
import { resolveVehicleVisual } from '../../domain/vehicle_visual'
import { DEFAULT_VEHICLE_APPEARANCE } from '../../services/vehicle_appearance'

import { APPEARANCE_VARIANTS } from '../../domain/vehicle_appearance_catalogue'


const paints: Record<string, string> = { PBSB: '纯黑色', PX02: '钻石黑', PMBL: '曜石黑', PPSW: '白色', PN01: '隐秘灰', PMNG: '深灰色', PN00: '快银色', PMSS: '银色', PR01: '烈焰红', PPMR: '红色', PPSB: '深蓝色', PB01: '冰川蓝', PB02: '海洋蓝' }
const wheels: Record<string, string> = { WY19P: '19英寸 Crossflow', WY20A: '20英寸 Helix', WY19B: '19英寸 Gemini', WY20P: '20英寸 Induction', WY18B: '18英寸轮毂', WY18P: '18英寸 Photon', WY21A: '21英寸轮毂', W38A: '18英寸 Photon', W30P: '20英寸高性能', W38B: '18英寸 Aero', W39B: '19英寸 Sport', W32D: '20英寸 Überturbine', W32P: '20英寸 Performance', WT19: '19英寸 Tempest', WT20: '20英寸轮毂', WT22: '22英寸轮毂' }
type AppearanceConfig = VehicleVisualConfig & { modelYear?: number | null }
const defaults: AppearanceConfig = { ...DEFAULT_VEHICLE_APPEARANCE, modelYear: null }

function Selector({ label, labels, selected, onChange }: { label: string; labels: readonly string[]; selected: number; onChange: (index: number) => void }) {
  return <View className="appearance-field"><Text>{label}</Text><Picker mode="selector" range={[...labels]} value={Math.max(0, selected)} onChange={event => onChange(Number(event.detail.value))}><View>{labels[Math.max(0, selected)]}<Text>⌄</Text></View></Picker></View>
}

export default function AppearanceSelector({ initial, preview, onSave, onAutomatic, onClose }: { initial: AppearanceConfig; preview: boolean; onSave: (value: AppearanceConfig) => boolean; onAutomatic: () => boolean; onClose: () => void }) {
  const [draft, setDraft] = useState<AppearanceConfig>(() => {
    const plan = resolveVehicleVisual(initial)
    // Keep a recognized model/generation when its paint is absent or unsupported.
    // This only initializes the editor; no fallback is applied until the user saves.
    const guide = plan.assetKey ? plan : resolveVehicleVisual({ ...initial, exteriorColor: 'PPSW' })
    if (!guide.assetKey) return defaults
    const variant = guide.assetKey.split('_')[0]
    const model = APPEARANCE_VARIANTS.find(item => item.code === variant)?.model ?? 'Y'
    const paint = plan.assetKey?.split('_')[1] ?? APPEARANCE_VARIANTS.find(item => item.code === variant)!.paints[0]
    return { ...initial, model, variant, exteriorColor: paint, wheelType: guide.assetKey.split('_')[2] }
  })
  const [error, setError] = useState<string | null>(null)
  const modelOptions = ['Y', '3', 'S', 'X'], variants = APPEARANCE_VARIANTS.filter(item => item.model === draft.model)
  const variant = variants.find(item => item.code === draft.variant) ?? variants[0]
  const years = [null, ...Array.from({ length: new Date().getFullYear() - 2006 }, (_, i) => new Date().getFullYear() + 1 - i)]
  const updateVariant = (selected: typeof APPEARANCE_VARIANTS[number]) => setDraft(value => ({ modelYear: value.modelYear ?? null, model: selected.model, variant: selected.code, exteriorColor: selected.paints[0], wheelType: selected.wheels[0] }))
  return <View className="appearance-overlay" onClick={onClose}><ScrollView scrollY className="appearance-dialog" onClick={event => event.stopPropagation()}>
    <Text className="appearance-title">车辆外观</Text>
    <Text className="appearance-description">{preview ? '未登录时仅预览外观，登录后按各车辆分别设置。' : '优先使用账号识别结果；信息不完整时可手动选择外观。'}</Text>
    <Selector label="车型" labels={modelOptions.map(model => `Model ${model}`)} selected={modelOptions.indexOf(draft.model ?? 'Y')} onChange={index => updateVariant(APPEARANCE_VARIANTS.find(item => item.model === modelOptions[index]) ?? APPEARANCE_VARIANTS[0])} />
    <Selector label="款式" labels={variants.map(item => item.label)} selected={variants.findIndex(item => item.code === variant.code)} onChange={index => updateVariant(variants[index] ?? variants[0])} />
    <Selector label="年款" labels={years.map(year => year == null ? '未知 / 未设置' : `${year}款`)} selected={Math.max(0, years.indexOf(draft.modelYear ?? null))} onChange={index => setDraft(value => ({ ...value, modelYear: years[index] ?? null }))} />
    <Selector label="颜色" labels={variant.paints.map(code => paints[code])} selected={variant.paints.findIndex(code => code === draft.exteriorColor)} onChange={index => setDraft(value => ({ ...value, exteriorColor: variant.paints[index] }))} />
    <Selector label="轮毂" labels={variant.wheels.map(code => wheels[code])} selected={variant.wheels.findIndex(code => code === draft.wheelType)} onChange={index => setDraft(value => ({ ...value, wheelType: variant.wheels[index] }))} />
    <Text className="appearance-description">年款未知可留空，车辆图片按所选款式展示。</Text>
    {error ? <Text className="error">{error}</Text> : null}
    <Button className="button" onClick={() => { if (onSave(draft)) onClose(); else setError('外观设置未能保存，请重试') }}>保存外观</Button>
    <Button className="button button-secondary" onClick={() => { if (onAutomatic()) onClose(); else setError('未能恢复自动识别，请重试') }}>{preview ? '恢复默认外观' : '恢复自动识别'}</Button>
    <Button className="dashboard-text-button" onClick={onClose}>取消</Button>
  </ScrollView></View>
}
