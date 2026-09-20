<script setup>
import { computed } from 'vue'
import { renderSafeMarkdown } from '../features/chat/markdown'

const props = defineProps({
  content: { type: String, default: '' },
  references: { type: [String, Array, Object], default: '' },
})
const rendered = computed(() => renderSafeMarkdown(props.content, globalThis.window, props.references))
</script>

<template>
  <div class="chat-markdown" v-html="rendered"></div>
</template>
