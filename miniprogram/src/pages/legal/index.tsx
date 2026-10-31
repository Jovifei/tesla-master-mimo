import { Text, View } from '@tarojs/components'
import { useLoad } from '@tarojs/taro'
import { useState } from 'react'
import { legalDocumentFor, legalDocuments, type LegalDocument } from '../../domain/legal'
import SubpageHeader from '../../components/SubpageHeader'

export default function LegalPage() {
  const [document, setDocument] = useState<LegalDocument>(legalDocuments.terms)

  useLoad(options => {
    const next = legalDocumentFor(options.kind)
    setDocument(next)
  })

  return (
    <View className="page legal-page subpage-page">
      <SubpageHeader title={document.title} subtitle="MateLink 法律文件" />
      <View className="legal-hero">
        <Text className="eyebrow">MATELINK · LEGAL</Text>
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
