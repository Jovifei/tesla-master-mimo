const HAN = /[\u4e00-\u9fff]/

/** App util/AddressDisplayFormatter.toChineseDisplayAddress: keep the Chinese
 * components of a geocoded address without mutating a fully non-Chinese one. */
export function toChineseDisplayAddress(value: string | null | undefined): string | null {
  const original = value?.trim()
  if (!original) return null
  const chineseParts = original.split(/[,，]/).map(part => part.trim()).filter(part => HAN.test(part))
  return chineseParts.length ? chineseParts.join(' ') : original
}
