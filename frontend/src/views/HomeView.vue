<script setup>
import { RouterLink } from 'vue-router'
import { ArrowRight, ChatDotRound, Lock, Microphone } from '@element-plus/icons-vue'
import { useIdentity } from '../composables/useIdentity'

const { normalizedId } = useIdentity()

const entries = [
  {
    route: '/coach',
    eyebrow: 'VOCAL COACH',
    title: '声乐教练',
    description: '听见演唱里的细节，获得真正能跟着练的专属建议。',
    icon: Microphone,
    className: 'coach-card',
  },
  {
    route: '/assistant',
    eyebrow: 'POP VOCAL AGENT',
    title: '流行演唱 Agent',
    description: '围绕流行演唱拆解目标、查找资料并整理结果。',
    icon: ChatDotRound,
    className: 'agent-card',
  },
]
</script>

<template>
  <section class="home">
    <div class="home-inner landing-home">
      <section class="landing-hero">
        <span class="hero-label"><i></i> 你好，<strong>{{ normalizedId }}</strong></span>
        <h1>听见你的声音，<br /><em>释放每一种可能。</em></h1>
        <p>从一次练唱到一项复杂研究，奥丁 AI 帮你听、想、查找并行动。</p>
        <small class="hero-partner">AI 驱动的声音与行动伙伴</small>

        <nav class="primary-entry-grid" aria-label="主要功能入口">
          <RouterLink v-for="item in entries" :key="item.route" :to="item.route" class="landing-entry" :class="item.className">
            <span class="entry-icon"><component :is="item.icon" /></span>
            <span class="entry-copy">
              <small>{{ item.eyebrow }}</small>
              <strong>{{ item.title }}</strong>
              <span>{{ item.description }}</span>
            </span>
            <ArrowRight class="entry-arrow" aria-hidden="true" />
          </RouterLink>
        </nav>

        <div class="product-preview" aria-label="奥丁 AI 对话界面预览">
          <div class="preview-bar" aria-hidden="true"><span></span><span></span><span></span><i>奥丁 AI</i></div>
          <div class="preview-body">
            <aside><Microphone /><strong>声乐教练</strong><small>最近会话</small><p>新手练唱计划</p></aside>
            <section>
              <header><ChatDotRound /> 声乐教练 <small>在线</small></header>
              <p>你好，我会结合你的目标，帮你制定可以持续练习的声乐方案。</p>
              <div class="preview-composer" aria-label="演示输入内容轮播">
                <span>帮我制定一份适合初学者的练唱计划</span>
                <span>推荐几首适合初学者的歌曲</span>
                <span>唱歌大白嗓怎么办</span>
              </div>
            </section>
          </div>
        </div>
      </section>

      <div class="privacy-note">
        <Lock />
        <p><strong>本地身份。</strong>用户标识仅保存在当前浏览器，用来区分会话上下文。</p>
      </div>
    </div>
  </section>
</template>
