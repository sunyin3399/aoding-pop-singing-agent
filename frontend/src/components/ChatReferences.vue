<script setup>
import { computed } from 'vue'
import { ArrowDown, Document, Link } from '@element-plus/icons-vue'
import { normalizeReferences } from '../features/chat/references'

const props = defineProps({ references: { type: [String, Array, Object], default: '' } })
const items = computed(() => normalizeReferences(props.references))
const statusLabels = {
  INTERNAL_APPROVED: '内部已审核',
  SCRAPED: '已读取网页',
  SEARCH_ONLY: '仅搜索摘要',
}
</script>

<template>
  <details v-if="items.length" class="message-references">
    <summary class="references-heading"><Document /><strong>参考资料</strong><small>{{ items.length }} 项</small><ArrowDown /></summary>
    <ul>
      <li v-for="item in items" :key="item.id">
        <a v-if="item.clickable" :href="item.url" :target="item.url.startsWith('/') ? undefined : '_blank'" :rel="item.url.startsWith('/') ? undefined : 'noopener noreferrer'">
          <span><Link />{{ item.title }}</span>
          <small>{{ item.domain }}</small>
        </a>
        <div v-else class="reference-disabled"><span>{{ item.title }}</span><small>{{ item.status === 'INTERNAL_APPROVED' ? '内部知识库' : '链接不可用' }}</small></div>
        <p v-if="item.excerpt">{{ item.excerpt }}</p>
        <em>{{ statusLabels[item.status] || '外部资料' }}</em>
      </li>
    </ul>
  </details>
</template>
