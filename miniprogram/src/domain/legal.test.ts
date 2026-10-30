import { describe, expect, it } from 'vitest'
import {
  JOURVOLT_PRIVACY_VERSION,
  JOURVOLT_TERMS_VERSION,
  legalDocumentFor,
  legalDocuments,
  readStoredConsent,
  writeStoredConsent,
} from './legal'

describe('legal documents', () => {
  it('keeps rendered consent versions aligned with the API contract', () => {
    expect(legalDocuments.terms.version).toBe(JOURVOLT_TERMS_VERSION)
    expect(legalDocuments.privacy.version).toBe(JOURVOLT_PRIVACY_VERSION)
  })

  it('covers identity, Tesla data, storage and account deletion without release placeholders', () => {
    const text = Object.values(legalDocuments).flatMap(document => document.sections.flatMap(section => section.paragraphs)).join('\n')
    expect(text).toContain('微信')
    expect(text).toContain('Tesla')
    expect(text).toContain('云端')
    expect(text).toContain('注销')
    expect(text).not.toMatch(/<运营主体|<联系邮箱|<备案号/)
  })

  it('fails closed to terms for an unknown document kind', () => {
    expect(legalDocumentFor('privacy')).toBe(legalDocuments.privacy)
    expect(legalDocumentFor('unknown')).toBe(legalDocuments.terms)
  })

  it('validates and preserves stored legal consent only when versions strictly match', () => {
    const store = new Map<string, unknown>()
    const storage = {
      getStorageSync: (k: string) => store.get(k),
      setStorageSync: (k: string, v: unknown) => { store.set(k, v) },
    }

    expect(readStoredConsent(storage)).toBeNull()

    writeStoredConsent(storage)
    const stored = readStoredConsent(storage)
    expect(stored).not.toBeNull()
    expect(stored?.termsVersion).toBe(JOURVOLT_TERMS_VERSION)
    expect(stored?.privacyVersion).toBe(JOURVOLT_PRIVACY_VERSION)
    expect(stored?.acceptedAtEpochMs).toBeGreaterThan(0)

    // Stale policy version fails closed
    store.set('matelink.consent.v1', {
      termsVersion: '1970-01-01',
      privacyVersion: JOURVOLT_PRIVACY_VERSION,
      acceptedAtEpochMs: Date.now(),
    })
    expect(readStoredConsent(storage)).toBeNull()
  })

  it('rejects a non-finite recorded acceptance time', () => {
    const storage = { getStorageSync: () => ({ termsVersion: JOURVOLT_TERMS_VERSION, privacyVersion: JOURVOLT_PRIVACY_VERSION, acceptedAtEpochMs: Infinity }) }
    expect(readStoredConsent(storage)).toBeNull()
  })
})
