import { useCallback, useEffect, useState } from 'react'
import { ApiError, executeVehicleCommand, getCarStatus } from '../api/matelink'
import type { WebCarStatus } from '../api/matelink-types'
import {
  commandErrorMessage,
  commandLabel,
  needsCommandReauthorization,
  offeredCommands,
  type CommandUi,
} from '../api/vehicle-commands'
import { useCar } from './useCar'

type Outcome = { kind: 'accepted'; command: string; verified: boolean } | { kind: 'failed'; command: string; errorClass: string }

const TIER_TITLES: Record<CommandUi['tier'], string> = { LOW: '常用', MID: '充电与车窗', HIGH: '安全' }

export default function ControlsPage() {
  const { cars, car, carId, setCarId, error: carError } = useCar()
  const [status, setStatus] = useState<WebCarStatus | null>(null)
  const [busy, setBusy] = useState<string | null>(null)
  const [outcome, setOutcome] = useState<Outcome | null>(null)
  const [confirming, setConfirming] = useState<{ command: CommandUi; params?: Record<string, unknown> } | null>(null)
  const [temperature, setTemperature] = useState(21)
  const [chargeLimit, setChargeLimit] = useState(80)

  const loadStatus = useCallback(async (target: number) => {
    try {
      const next = await getCarStatus(target)
      setStatus(next)
      if (next.driverTempC != null) setTemperature(next.driverTempC)
    } catch {
      setStatus(null)
    }
  }, [])

  useEffect(() => {
    if (carId != null) void loadStatus(carId)
  }, [carId, loadStatus])

  const execute = async (command: CommandUi, params?: Record<string, unknown>, confirm = false) => {
    if (carId == null) return
    setBusy(command.name)
    setOutcome(null)
    try {
      const result = await executeVehicleCommand(carId, command.name, params, confirm)
      setOutcome({ kind: 'accepted', command: command.name, verified: result.verified ?? false })
      void loadStatus(carId)
    } catch (reason) {
      const errorClass = reason instanceof ApiError ? reason.code : 'error'
      if (errorClass === 'confirmation_required' && !confirm) {
        setConfirming({ command, params })
      } else {
        setOutcome({ kind: 'failed', command: command.name, errorClass })
      }
    } finally {
      setBusy(null)
    }
  }

  const onCommand = (command: CommandUi) => {
    const params =
      command.name === 'set_climate_temp'
        ? { driver_temp: temperature, passenger_temp: temperature }
        : command.name === 'set_charge_limit'
          ? { percent: chargeLimit }
          : undefined
    if (command.needsConfirmation) {
      setConfirming({ command, params })
      return
    }
    void execute(command, params)
  }

  const offered = offeredCommands(status)
  const reauth = needsCommandReauthorization(status)
  const missing = status?.commandAuthorization?.missingScopes ?? []

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      <div style={{ display: 'flex', alignItems: 'flex-end', justifyContent: 'space-between', flexWrap: 'wrap', gap: 12 }}>
        <div>
          <h1 style={{ fontSize: 28, fontWeight: 700 }}>远程控制</h1>
          <p className="text-secondary" style={{ fontSize: 13, marginTop: 4 }}>
            {car?.name ?? ''}
            {status ? ` · 电量 ${status.batteryLevel ?? '--'}% · ${status.locked == null ? '' : status.locked ? '已锁定' : '未锁定'}` : ''}
          </p>
        </div>
        {cars.length > 1 ? (
          <select className="apple-input" style={{ width: 'auto' }} value={carId ?? ''} onChange={event => setCarId(Number(event.target.value))}>
            {cars.map(item => (
              <option key={item.id} value={item.id}>{item.name}</option>
            ))}
          </select>
        ) : null}
      </div>

      {carError ? <div className="apple-card"><p className="text-error">{carError}</p></div> : null}

      {reauth ? (
        <div className="apple-card">
          <p style={{ fontSize: 15, fontWeight: 600, marginBottom: 6 }}>车辆控制需要重新授权</p>
          <p className="text-secondary" style={{ fontSize: 13, lineHeight: 1.7 }}>
            当前授权范围不足{missing.length ? `（缺失：${missing.join('、')}）` : ''}。请在 MateLink App 或小程序中重新完成 Tesla 授权后再使用网页控制；这一步必须由车主在官方授权页确认，网页不会索取你的 Tesla 密码。
          </p>
        </div>
      ) : null}

      {outcome ? (
        <div className="apple-card">
          {outcome.kind === 'accepted' ? (
            <p style={{ fontSize: 14 }}>
              {commandLabel(outcome.command)}：已受理{outcome.verified ? '，并已回读确认生效' : ''}。
            </p>
          ) : (
            <p className="text-error" style={{ fontSize: 14 }}>
              {commandLabel(outcome.command)}：{commandErrorMessage(outcome.errorClass)}
            </p>
          )}
        </div>
      ) : null}

      {!reauth && status && !offered.length ? (
        <div className="apple-card"><p className="text-secondary">正在评估命令授权…</p></div>
      ) : null}

      {(['LOW', 'MID', 'HIGH'] as const).map(tier => {
        const commands = offered.filter(command => command.tier === tier)
        if (!commands.length) return null
        return (
          <div key={tier} className="apple-card">
            <h2 style={{ fontSize: 15, fontWeight: 600, marginBottom: 12 }}>{TIER_TITLES[tier]}</h2>
            <div style={{ display: 'flex', flexWrap: 'wrap', gap: 10, alignItems: 'center' }}>
              {commands.map(command => (
                <span key={command.name} style={{ display: 'inline-flex', gap: 8, alignItems: 'center' }}>
                  <button className="apple-btn-secondary" disabled={busy != null} onClick={() => onCommand(command)}>
                    {busy === command.name ? '执行中…' : commandLabel(command.name)}
                  </button>
                  {command.name === 'set_climate_temp' ? (
                    <input
                      className="apple-input tabular"
                      style={{ width: 92 }}
                      type="number"
                      min={15}
                      max={28}
                      step={0.5}
                      value={temperature}
                      onChange={event => setTemperature(Number(event.target.value))}
                      aria-label="空调温度（°C）"
                    />
                  ) : null}
                  {command.name === 'set_charge_limit' ? (
                    <input
                      className="apple-input tabular"
                      style={{ width: 92 }}
                      type="number"
                      min={50}
                      max={100}
                      step={1}
                      value={chargeLimit}
                      onChange={event => setChargeLimit(Number(event.target.value))}
                      aria-label="充电上限（%）"
                    />
                  ) : null}
                </span>
              ))}
            </div>
          </div>
        )
      })}

      <p className="text-tertiary" style={{ fontSize: 12, lineHeight: 1.7 }}>
        命令由后端逐条校验授权与安全条件（行驶中、未插枪、次数上限等）；车辆控制总开关由服务端控制，未开启时命令会返回“车辆控制尚未开启”。
      </p>

      {confirming ? (
        <div
          style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.35)', display: 'flex', alignItems: 'center', justifyContent: 'center', padding: 20, zIndex: 50 }}
          onClick={() => setConfirming(null)}
        >
          <div className="apple-card" style={{ maxWidth: 360, width: '100%' }} onClick={event => event.stopPropagation()}>
            <h3 style={{ fontSize: 17, fontWeight: 700, marginBottom: 8 }}>确认{commandLabel(confirming.command.name)}？</h3>
            <p className="text-secondary" style={{ fontSize: 13, lineHeight: 1.7, marginBottom: 16 }}>
              这是一个敏感操作，请确认车辆当前停放妥当、周围安全。
            </p>
            <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end' }}>
              <button className="apple-btn-secondary" onClick={() => setConfirming(null)}>取消</button>
              <button
                className="apple-btn-primary"
                onClick={() => {
                  const pending = confirming
                  setConfirming(null)
                  void execute(pending.command, pending.params, true)
                }}
              >
                确认执行
              </button>
            </div>
          </div>
        </div>
      ) : null}
    </div>
  )
}
