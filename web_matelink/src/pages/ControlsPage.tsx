import { useCallback, useEffect, useState, type ReactNode } from 'react'
import { ApiError, executeVehicleCommand, getCarStatus } from '../api/matelink'
import type { WebCarStatus } from '../api/matelink-types'
import {
  commandErrorMessage,
  commandLabel,
  needsCommandReauthorization,
  offeredCommands,
  type CommandUi,
} from '../api/vehicle-commands'
import { Icon, Tile, TONES, type IconName } from '../components/viz'
import { Panel, V3 } from '../components/v3'
import { useCar } from './useCar'

type Outcome = { kind: 'accepted'; command: string; verified: boolean } | { kind: 'failed'; command: string; errorClass: string }

const TIER_TITLES: Record<CommandUi['tier'], string> = { LOW: '常用', MID: '充电与车窗', HIGH: '安全' }

function Chip({ icon, children }: { icon: IconName; children: ReactNode }) {
  return (
    <span
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        gap: 6,
        background: 'var(--apple-surface)',
        border: '1px solid var(--apple-separator)',
        borderRadius: 999,
        padding: '5px 12px',
        fontSize: 13,
        fontWeight: 600,
        boxShadow: '0 2px 8px rgba(0,0,0,0.04)',
      }}
    >
      <span style={{ color: TONES.teal, display: 'inline-flex' }}>
        <Icon name={icon} size={15} />
      </span>
      {children}
    </span>
  )
}

function MiniButton({
  children,
  onClick,
  disabled,
  danger,
}: {
  children: ReactNode
  onClick: () => void
  disabled?: boolean
  danger?: boolean
}) {
  return (
    <button
      className="apple-btn-secondary"
      style={{ padding: '5px 13px', fontSize: 12.5, borderRadius: 999, ...(danger ? { color: TONES.red } : null) }}
      disabled={disabled}
      onClick={onClick}
    >
      {children}
    </button>
  )
}

function TileSection({ title, children }: { title: string; children: ReactNode }) {
  return (
    <Panel title={title}>
      <div className="grid grid-cols-2 md:grid-cols-3 xl:grid-cols-4 gap-3">{children}</div>
    </Panel>
  )
}

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

  const byName = new Map(offered.map(command => [command.name, command]))
  const lockCmd = byName.get('lock')
  const unlockCmd = byName.get('unlock')
  const flashCmd = byName.get('flash')
  const honkCmd = byName.get('honk')
  const climateOnCmd = byName.get('climate_on')
  const climateOffCmd = byName.get('climate_off')
  const setClimateTempCmd = byName.get('set_climate_temp')
  const chargePortOpenCmd = byName.get('charge_port_open')
  const chargePortCloseCmd = byName.get('charge_port_close')
  const chargeStartCmd = byName.get('charge_start')
  const chargeStopCmd = byName.get('charge_stop')
  const setChargeLimitCmd = byName.get('set_charge_limit')
  const windowsVentCmd = byName.get('windows_vent')
  const windowsCloseCmd = byName.get('windows_close')
  const sentryOnCmd = byName.get('sentry_on')
  const sentryOffCmd = byName.get('sentry_off')

  const showLow = Boolean(lockCmd || flashCmd || honkCmd || climateOnCmd || climateOffCmd || setClimateTempCmd || chargePortOpenCmd || chargePortCloseCmd)
  const showMid = Boolean(chargeStartCmd || chargeStopCmd || setChargeLimitCmd || windowsVentCmd || windowsCloseCmd || sentryOnCmd || sentryOffCmd)
  const showHigh = Boolean(unlockCmd)

  const charging = status?.chargingState === 'Charging'
  const chargeValue = charging
    ? status?.chargerPowerKw != null
      ? `充电中 · ${status.chargerPowerKw} kW`
      : '充电中'
    : status?.chargingState === 'Complete'
      ? '已充满'
      : status?.chargingState === 'Disconnected'
        ? '未连接'
        : status?.chargingState === 'Stopped'
          ? '已停止'
          : undefined

  const anyBusy = busy != null
  const busyLabel = (name: string, label: string) => (busy === name ? '执行中…' : label)

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      <div style={{ display: 'flex', alignItems: 'flex-end', justifyContent: 'space-between', flexWrap: 'wrap', gap: 12 }}>
        <div>
          <h1 style={{ fontSize: 28, fontWeight: 700 }}>远程控制</h1>
          {car || status ? (
            <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', marginTop: 10 }}>
              {car ? <Chip icon="car">{car.name}</Chip> : null}
              {status?.batteryLevel != null ? (
                <Chip icon="battery">
                  电量 <span className="tabular">{status.batteryLevel}%</span>
                </Chip>
              ) : null}
              {status?.locked != null ? (
                <Chip icon={status.locked ? 'lock' : 'unlock'}>{status.locked ? '已锁定' : '未锁定'}</Chip>
              ) : null}
              {charging ? <Chip icon="bolt">充电中</Chip> : null}
            </div>
          ) : null}
        </div>
        {cars.length > 1 ? (
          <select className="apple-input" style={{ width: 'auto' }} value={carId ?? ''} onChange={event => setCarId(Number(event.target.value))}>
            {cars.map(item => (
              <option key={item.id} value={item.id}>{item.name}</option>
            ))}
          </select>
        ) : null}
      </div>

      {carError ? <Panel><p style={{ color: V3.errorText, fontSize: 14.5 }}>{carError}</p></Panel> : null}

      {reauth ? (
        <Panel>
          <p style={{ fontSize: 15, fontWeight: 600, marginBottom: 6 }}>车辆控制需要重新授权</p>
          <p className="text-secondary" style={{ fontSize: 13, lineHeight: 1.7 }}>
            当前授权范围不足{missing.length ? `（缺失：${missing.join('、')}）` : ''}。请在 MateLink App 或小程序中重新完成 Tesla 授权后再使用网页控制；这一步必须由车主在官方授权页确认，网页不会索取你的 Tesla 密码。
          </p>
        </Panel>
      ) : null}

      {outcome ? (
        <div className="banner-in">
          <Panel>
            <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
              <span style={{ color: outcome.kind === 'accepted' ? TONES.green : TONES.red, display: 'inline-flex', flexShrink: 0 }}>
                <Icon name={outcome.kind === 'accepted' ? 'shield' : 'bolt'} size={20} />
              </span>
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
          </Panel>
        </div>
      ) : null}

      {!reauth && status && !offered.length ? (
        <Panel><p className="text-secondary">正在评估命令授权…</p></Panel>
      ) : null}

      {/* While any command is in flight the whole grid is inert, preserving
          the original "all buttons disabled while busy" behavior — including
          for Tile's built-in toggle button, which only knows its own busy. */}
      <div style={{ display: 'flex', flexDirection: 'column', gap: 16, pointerEvents: anyBusy ? 'none' : undefined }}>
        {showLow ? (
          <TileSection title={TIER_TITLES.LOW}>
            {lockCmd ? (
              <Tile
                icon={status?.locked ? 'lock' : 'unlock'}
                label="车锁"
                value={status?.locked == null ? undefined : status.locked ? '已锁定' : '未锁定'}
                active={status?.locked === true}
              >
                <div style={{ display: 'flex', gap: 8 }}>
                  <MiniButton
                    disabled={anyBusy}
                    onClick={() => {
                      const target = status?.locked ? (unlockCmd ?? lockCmd) : lockCmd
                      if (target) onCommand(target)
                    }}
                  >
                    {busy === 'lock' || busy === 'unlock' ? '执行中…' : status?.locked ? '解锁' : '上锁'}
                  </MiniButton>
                </div>
              </Tile>
            ) : null}

            {flashCmd ? (
              <Tile icon="zap" label={commandLabel('flash')}>
                <div style={{ display: 'flex', gap: 8 }}>
                  <MiniButton disabled={anyBusy} onClick={() => onCommand(flashCmd)}>
                    {busyLabel('flash', '执行')}
                  </MiniButton>
                </div>
              </Tile>
            ) : null}

            {honkCmd ? (
              <Tile icon="speaker" label={commandLabel('honk')}>
                <div style={{ display: 'flex', gap: 8 }}>
                  <MiniButton disabled={anyBusy} onClick={() => onCommand(honkCmd)}>
                    {busyLabel('honk', '执行')}
                  </MiniButton>
                </div>
              </Tile>
            ) : null}

            {climateOnCmd || climateOffCmd || setClimateTempCmd ? (
              <Tile
                icon="climate"
                label="空调"
                value={status?.insideTempC != null ? `车内 ${status.insideTempC}°C` : undefined}
              >
                {climateOnCmd || climateOffCmd ? (
                  <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
                    {climateOnCmd ? (
                      <MiniButton disabled={anyBusy} onClick={() => onCommand(climateOnCmd)}>
                        {busyLabel('climate_on', '打开')}
                      </MiniButton>
                    ) : null}
                    {climateOffCmd ? (
                      <MiniButton disabled={anyBusy} onClick={() => onCommand(climateOffCmd)}>
                        {busyLabel('climate_off', '关闭')}
                      </MiniButton>
                    ) : null}
                  </div>
                ) : null}
                {setClimateTempCmd ? (
                  <div style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' }}>
                    <input
                      className="apple-input tabular"
                      style={{ width: 84, padding: '6px 10px' }}
                      type="number"
                      min={15}
                      max={28}
                      step={0.5}
                      value={temperature}
                      onChange={event => setTemperature(Number(event.target.value))}
                      aria-label="空调温度（°C）"
                    />
                    <span className="text-secondary" style={{ fontSize: 12.5 }}>°C</span>
                    <MiniButton disabled={anyBusy} onClick={() => onCommand(setClimateTempCmd)}>
                      {busyLabel('set_climate_temp', '设置')}
                    </MiniButton>
                  </div>
                ) : null}
              </Tile>
            ) : null}

            {chargePortOpenCmd || chargePortCloseCmd ? (
              <Tile icon="plug" label="充电口">
                <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
                  {chargePortOpenCmd ? (
                    <MiniButton disabled={anyBusy} onClick={() => onCommand(chargePortOpenCmd)}>
                      {busyLabel('charge_port_open', '打开')}
                    </MiniButton>
                  ) : null}
                  {chargePortCloseCmd ? (
                    <MiniButton disabled={anyBusy} onClick={() => onCommand(chargePortCloseCmd)}>
                      {busyLabel('charge_port_close', '关闭')}
                    </MiniButton>
                  ) : null}
                </div>
              </Tile>
            ) : null}
          </TileSection>
        ) : null}

        {showMid ? (
          <TileSection title={TIER_TITLES.MID}>
            {chargeStartCmd || chargeStopCmd ? (
              <Tile icon={charging ? 'stop' : 'play'} label="充电" value={chargeValue} active={charging}>
                <div style={{ display: 'flex', gap: 8 }}>
                  <MiniButton
                    disabled={anyBusy}
                    onClick={() => {
                      const target = charging ? chargeStopCmd : chargeStartCmd
                      if (target) onCommand(target)
                    }}
                  >
                    {busy === 'charge_start' || busy === 'charge_stop' ? '执行中…' : charging ? '停止充电' : '开始充电'}
                  </MiniButton>
                </div>
              </Tile>
            ) : null}

            {setChargeLimitCmd ? (
              <Tile icon="battery" label={commandLabel('set_charge_limit')} value={`${chargeLimit}%`}>
                <div style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' }}>
                  <input
                    className="apple-input tabular"
                    style={{ width: 84, padding: '6px 10px' }}
                    type="number"
                    min={50}
                    max={100}
                    step={1}
                    value={chargeLimit}
                    onChange={event => setChargeLimit(Number(event.target.value))}
                    aria-label="充电上限（%）"
                  />
                  <span className="text-secondary" style={{ fontSize: 12.5 }}>%</span>
                  <MiniButton disabled={anyBusy} onClick={() => onCommand(setChargeLimitCmd)}>
                    {busyLabel('set_charge_limit', '设置')}
                  </MiniButton>
                </div>
              </Tile>
            ) : null}

            {windowsVentCmd || windowsCloseCmd ? (
              <Tile
                icon="window"
                label="车窗"
                value={status?.windowsOpen == null ? undefined : status.windowsOpen ? '已打开' : '已关闭'}
                active={status?.windowsOpen === true}
              >
                <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
                  {windowsVentCmd ? (
                    <MiniButton disabled={anyBusy} onClick={() => onCommand(windowsVentCmd)}>
                      {busyLabel('windows_vent', '通风')}
                    </MiniButton>
                  ) : null}
                  {windowsCloseCmd ? (
                    <MiniButton disabled={anyBusy} onClick={() => onCommand(windowsCloseCmd)}>
                      {busyLabel('windows_close', '关闭')}
                    </MiniButton>
                  ) : null}
                </div>
              </Tile>
            ) : null}

            {sentryOnCmd || sentryOffCmd ? (
              <Tile
                icon="shield"
                label="哨兵模式"
                value={status?.sentryMode == null ? undefined : status.sentryMode ? '已开启' : '已关闭'}
                active={status?.sentryMode === true}
                busy={busy === 'sentry_on' || busy === 'sentry_off'}
                onClick={() => {
                  const target = status?.sentryMode ? sentryOffCmd : sentryOnCmd
                  if (target) onCommand(target)
                }}
              />
            ) : null}
          </TileSection>
        ) : null}

        {showHigh && unlockCmd ? (
          <TileSection title={TIER_TITLES.HIGH}>
            <Tile icon="unlock" label={commandLabel('unlock')} danger>
              <div style={{ display: 'flex', gap: 8 }}>
                <MiniButton danger disabled={anyBusy} onClick={() => onCommand(unlockCmd)}>
                  {busyLabel('unlock', '解锁')}
                </MiniButton>
              </div>
            </Tile>
          </TileSection>
        ) : null}
      </div>

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
