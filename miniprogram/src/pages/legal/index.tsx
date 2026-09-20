import { Text, View } from '@tarojs/components'
import Taro, { useLoad } from '@tarojs/taro'
import { useState } from 'react'
import { legalDocumentFor, legalDocuments, type LegalDocument } from '../../domain/legal'

export default function LegalPage() {
  const [document, setDocument] = useState<LegalDocument>(legalDocuments.terms)

  useLoad(options => {
    const next = legalDocumentFor(options.kind)
    setDocument(next)
    Taro.setNavigationBarTitle({ title: next.title })
  })

  return (
    <View className="page legal-page">
      <View className="legal-hero">
        <Text className="eyebrow">MATELINK · LEGAL</Text>
        <Text className="title">{document.title}</Text>
        <Text className="legal-version">同意记录版本 {document.version}</Text>
        <Text className="muted">{document.summary}</Text>
      </View>

      {document.sections.map(section => (
        <View key={section.title} className="card legal-section">
          <Text className="section-title">{section.title}</Text>
          {section.paragraphs.map(paragraph => <Text key={paragraph} className="legal-paragraph">{paragraph}</Text>)}
        </View>
      ))}

      <View className="legal-notice">
        <Text>MateLink 与 Tesla, Inc. 无隶属或授权关系。正式发布信息以通过审核后的版本为准。</Text>
      </View>
    </View>
  )
}
