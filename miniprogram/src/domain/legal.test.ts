import { describe, expect, it } from 'vitest'
import { JOURVOLT_PRIVACY_VERSION, JOURVOLT_TERMS_VERSION, legalDocumentFor, legalDocuments } from './legal'

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
})
