<script setup>
import { onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowDown, Check, DataLine, Moon, Reading, Sunny, User } from '@element-plus/icons-vue'
import { USER_OPTIONS, useIdentity } from './composables/useIdentity'
import { useKnowledgePending } from './stores/knowledgePending'

const route = useRoute()
const router = useRouter()
const { normalizedId, setUserId } = useIdentity()
const { pendingCount, ensureInitialized } = useKnowledgePending()
const theme = ref('light')

onMounted(() => {
  theme.value = localStorage.getItem('aoding-theme') || 'light'
  ensureInitialized()
})

watch(theme, (value) => {
  document.documentElement.dataset.theme = value
  localStorage.setItem('aoding-theme', value)
}, { immediate: true })

function toggleTheme() {
  theme.value = theme.value === 'light' ? 'dark' : 'light'
}

function switchIdentity(userId) {
  const previous = normalizedId.value
  setUserId(userId)
  if (previous !== normalizedId.value) {
    ElMessage.success(`已切换至 ${normalizedId.value}`)
    if (route.name !== 'home') router.replace('/')
  }
}
</script>

<template>
  <div class="app-shell">
    <header class="topbar">
      <button class="brand" type="button" @click="router.push('/')">
        <span class="brand-mark"><img src="/assets/odin-cat-logo.png" alt="" /></span>
        <span>奥丁 AI</span>
      </button>
      <div class="topbar-actions">
        <nav class="topbar-nav" aria-label="工具入口">
          <button type="button" @click="router.push('/spectrum-lab')"><DataLine /><span>频谱实验室</span></button>
          <button type="button" class="nav-review" @click="router.push('/admin/knowledge-review')"><Reading /><span>知识审核</span><span v-if="pendingCount > 0" class="review-badge" :aria-label="`${pendingCount} 条待审核候选`">{{ pendingCount > 99 ? '99+' : pendingCount }}</span></button>
        </nav>
        <button
          class="theme-toggle"
          type="button"
          :aria-label="theme === 'light' ? '切换为暗色模式' : '切换为明亮模式'"
          :aria-pressed="theme === 'dark'"
          @click="toggleTheme"
        >
          <Moon v-if="theme === 'light'" />
          <Sunny v-else />
        </button>
        <span class="memory-state">会话记忆已连接</span>
        <el-dropdown trigger="click" placement="bottom-end" @command="switchIdentity">
          <button class="identity-chip" type="button" aria-label="切换测试用户">
            <span class="identity-avatar"><User /></span>
            <span class="identity-copy"><small>当前用户</small>{{ normalizedId }}</span>
            <ArrowDown class="chevron" />
          </button>
          <template #dropdown>
            <el-dropdown-menu class="identity-menu">
              <el-dropdown-item
                v-for="userId in USER_OPTIONS"
                :key="userId"
                :command="userId"
                :class="{ 'is-current': userId === normalizedId }"
              >
                <span class="identity-menu-check" aria-hidden="true">
                  <Check v-if="userId === normalizedId" />
                </span>
                <span>{{ userId }}</span>
              </el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </header>

    <main><router-view :key="route.fullPath" /></main>
  </div>
</template>

<style scoped>
.nav-review { position: relative; }
.review-badge {
  position: absolute;
  top: -7px;
  right: -8px;
  min-width: 17px;
  height: 17px;
  padding: 0 4px;
  display: grid;
  place-items: center;
  border-radius: 999px;
  background: #d83a2f;
  color: #fff;
  font-size: 11px;
  font-weight: 700;
  line-height: 1;
  box-shadow: 0 0 0 2px var(--page, #ffffff);
}
</style>
