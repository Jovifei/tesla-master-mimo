export const JOURVOLT_TERMS_VERSION = '2026-08-21'
export const JOURVOLT_PRIVACY_VERSION = '2026-08-21'

export type LegalDocumentKind = 'terms' | 'privacy'

export type LegalSection = {
  title: string
  paragraphs: string[]
}

export type LegalDocument = {
  title: string
  version: string
  summary: string
  sections: LegalSection[]
}

export const legalDocuments: Record<LegalDocumentKind, LegalDocument> = {
  terms: {
    title: 'MateLink 服务条款',
    version: JOURVOLT_TERMS_VERSION,
    summary: 'MateLink 是独立第三方只读车辆数据工具，不属于 Tesla 官方产品，也不提供车辆远程控制。',
    sections: [
      {
        title: '1. 服务范围',
        paragraphs: [
          '本服务用于展示用户本人授权车辆的状态、行程、充电和数据准备情况。部分数据依赖 Tesla 官方接口、车辆在线状态和持续遥测，可能延迟、缺失或暂不可用。',
          '页面会区分真实观测、派生结果、采集中和不可用状态；缺失数据不会被填成零值或示例数据。',
        ],
      },
      {
        title: '2. 账号与授权',
        paragraphs: [
          '用户应拥有对应 Tesla 账号和车辆的合法使用权限。Tesla 登录、权限同意与必要的车辆钥匙确认均在 Tesla 官方页面完成，MateLink 不接收或保存 Tesla 密码。',
          '用户不得利用本服务访问他人车辆数据、绕过授权、批量抓取接口或从事违法活动。',
        ],
      },
      {
        title: '3. 数据与可靠性',
        paragraphs: [
          '车辆数据可能受网络、Tesla 服务、车辆休眠、权限和采集覆盖率影响。展示结果不应作为驾驶安全、维修、保险、金融、法律或紧急决策的唯一依据。',
          '退出登录不会自动删除已按账号和车辆隔离的本机历史；账号注销会请求服务端删除账号关联数据并清理当前设备中的账号历史缓存，Tesla 官方授权仍需按提示另行撤销。',
        ],
      },
      {
        title: '4. 服务变更',
        paragraphs: [
          '为处理安全、合规、接口限额或故障，运营者可以暂停相应功能。公测阶段不承诺生产级持续可用性。',
        ],
      },
      {
        title: '5. 运营信息',
        paragraphs: [
          '运营主体、联系渠道、投诉方式和备案信息将在小程序主体认证完成后，以微信平台展示信息及正式发布页面为准；未配置完整前不开放正式服务。',
        ],
      },
    ],
  },
  privacy: {
    title: 'MateLink 隐私指引',
    version: JOURVOLT_PRIVACY_VERSION,
    summary: '本指引说明特斯拉账号登录、Tesla 授权和车辆数据在 MateLink 小程序中的处理边界。',
    sections: [
      {
        title: '1. 我们处理的信息',
        paragraphs: [
          '登录由 Tesla 官方页面验证特斯拉账号，服务端使用同一 Tesla 身份对应的 MateLink 账号建立会话，以读取该账号已有车辆和历史数据。主登录不要求微信身份凭证。',
          '用户可选择把当前微信与已登录的 MateLink 账号关联，以便之后快捷进入；服务端保存该关联，本机保存是否启用快捷进入。',
          '完成 Tesla 官方授权后，服务端会处理账号会话、车辆标识、车辆状态、位置、行程、充电和持续遥测所需的数据。授权令牌在服务端加密保存，小程序不保存 Tesla 密码。',
        ],
      },
      {
        title: '2. 使用目的',
        paragraphs: [
          '这些信息仅用于身份验证、展示获授权车辆数据、持续采集、故障诊断以及同一账号和车辆范围内的历史恢复。不会把其他账号或车辆的历史认领到当前账号。',
        ],
      },
      {
        title: '3. 存储与安全',
        paragraphs: [
          '小程序会在设备本地缓存按 API 来源、账号、车辆和记录类型隔离的历史摘要。云端模式还会保存持续采集所需的最新观测、位置与路线点、行程和充电会话。',
          '接口使用 HTTPS；令牌、私钥、精确位置和私人地址不得写入公开日志、源代码或报告。正式保留期限、备份删除规则和运营安全措施须在上线前由运营者确认并发布。',
        ],
      },
      {
        title: '4. 第三方服务',
        paragraphs: [
          '小程序运行会使用微信提供的平台能力；账号登录、车辆授权与数据访问会使用 Tesla 官方服务。相应处理同时受微信和 Tesla 的规则与隐私说明约束。',
        ],
      },
      {
        title: '5. 用户权利',
        paragraphs: [
          '用户可以退出登录、重新授权、解绑微信或在“更多”页面申请注销账号。退出会关闭本机自动快捷进入，解绑会撤销微信进入的会话。退出、卸载或清除小程序数据不等于删除云端存档；账号注销会删除服务端账号关联数据并尽力清理当前设备中的账号历史缓存，成功后仍应前往 Tesla 官方页面撤销第三方授权。',
        ],
      },
      {
        title: '6. 运营信息',
        paragraphs: [
          '运营主体、联系邮箱、投诉渠道、数据保留期限和备案信息将在小程序主体认证完成后补齐；未配置完整并完成复核前，不将本版本标记为可正式上线。',
        ],
      },
    ],
  },
}

export function legalDocumentFor(value: string | undefined): LegalDocument {
  return value === 'privacy' ? legalDocuments.privacy : legalDocuments.terms
}

export const CONSENT_STORAGE_KEY = 'matelink.consent.v1'

export type StoredConsent = {
  termsVersion: string
  privacyVersion: string
  acceptedAtEpochMs: number
}

export function readStoredConsent(storage: { getStorageSync(key: string): unknown }): StoredConsent | null {
  try {
    const raw = storage.getStorageSync(CONSENT_STORAGE_KEY) as Partial<StoredConsent> | undefined
    if (!raw || typeof raw !== 'object') return null
    if (raw.termsVersion === JOURVOLT_TERMS_VERSION &&
        raw.privacyVersion === JOURVOLT_PRIVACY_VERSION &&
        typeof raw.acceptedAtEpochMs === 'number' && Number.isFinite(raw.acceptedAtEpochMs) &&
        raw.acceptedAtEpochMs > 0) {
      return {
        termsVersion: raw.termsVersion,
        privacyVersion: raw.privacyVersion,
        acceptedAtEpochMs: raw.acceptedAtEpochMs,
      }
    }
    return null
  } catch { return null }
}

export function writeStoredConsent(storage: { setStorageSync(key: string, value: unknown): void }): void {
  try {
    storage.setStorageSync(CONSENT_STORAGE_KEY, {
      termsVersion: JOURVOLT_TERMS_VERSION,
      privacyVersion: JOURVOLT_PRIVACY_VERSION,
      acceptedAtEpochMs: Date.now(),
    })
  } catch { /* storage quota or restricted */ }
}
