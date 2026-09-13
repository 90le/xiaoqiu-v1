<script setup>
import { ref, computed } from 'vue'
import QiuLogo from '../components/QiuLogo.vue'

const step = ref(1) // 1=欢迎 2=模型 3=权限 4=能力 5=完成
const total = 5
const apiKey = ref('')
const selectedModel = ref('')
const permChecks = ref({})
const done = ref(false)

const MODELS = [
  { id: 'glm-5.3', label: 'GLM-5.3', desc: '智谱旗舰·思考型', icon: '🧠', rec: true },
  { id: 'glm-5.3-flash', label: 'GLM-5.3 Flash', desc: '智谱·快速响应', icon: '⚡' },
  { id: 'glm-4.7', label: 'GLM-4.7', desc: '上一代稳定版', icon: '🛡' },
]

// 权限状态（并行加载）
;(async () => {
  try {
    const r = await fetch('/api/perm_status', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' })
    const d = (await r.json()).structuredContent
    permChecks.value = d?.data || {}
  } catch {}
})()

const perms = computed(() => [
  { k: 'accessibility', icon: '🦯', label: '无障碍服务', desc: '让小丘能看屏幕、帮你操作App', ok: permChecks.value.accessibility },
  { k: 'overlay', icon: '🪟', label: '悬浮窗', desc: '悬浮球 + 语音特效', ok: permChecks.value.overlay },
  { k: 'allFiles', icon: '📁', label: '文件访问', desc: '读写文件、管理数据', ok: permChecks.value.allFiles },
  { k: 'notification', icon: '🔔', label: '通知使用权', desc: '监听微信/短信并语音播报', ok: permChecks.value.notification },
])
const permReady = computed(() => perms.value.filter(p => p.ok).length)

async function saveKey() {
  if (!apiKey.value.trim()) return
  try {
    await fetch('/api/setkey', { method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ provider: 'zai-coding-cn', key: apiKey.value.trim(), model: selectedModel.value || 'glm-5.3' }) })
  } catch {}
}

function finish() {
  localStorage.setItem('xq_onboarded', '1')
  done.value = true
  setTimeout(() => location.reload(), 300)
}

function skip() {
  localStorage.setItem('xq_onboarded', '1')
  location.reload()
}
</script>

<template>
  <div v-if="!done" class="ob-wrap">
    <!-- 进度指示 -->
    <div class="ob-progress">
      <div v-for="i in total" :key="i" :class="['ob-dot', { on: i <= step, cur: i === step }]" @click="i < step && (step = i)"></div>
    </div>

    <!-- Step 1: 欢迎 -->
    <template v-if="step === 1">
      <div class="ob-hero">
        <QiuLogo :size="80" />
        <h1>你好，我是小丘</h1>
        <p class="ob-tag">你的随身 AI 工作台</p>
        <div class="ob-feats">
          <div class="ob-feat"><span class="fic">🗣</span><span>喊「小丘」随时唤醒<br>语音对话·任务执行</span></div>
          <div class="ob-feat"><span class="fic">💻</span><span>真·Linux 终端<br>跑命令·写代码·装包</span></div>
          <div class="ob-feat"><span class="fic">📱</span><span>手机就是身体<br>截屏·操作App·读通知</span></div>
          <div class="ob-feat"><span class="fic">🧠</span><span>越用越懂你<br>自动记忆·技能沉淀</span></div>
        </div>
      </div>
      <button class="ob-btn tap" @click="step = 2">开始配置 <span class="ar">›</span></button>
      <button class="ob-skip tap" @click="skip">跳过，直接用</button>
    </template>

    <!-- Step 2: 模型 -->
    <template v-else-if="step === 2">
      <h2>选择 AI 大脑</h2>
      <p class="ob-sub">小丘的"智力"来自大模型——选一个你喜欢的（后面随时可换）</p>
      <div class="ob-models">
        <div v-for="m in MODELS" :key="m.id" class="ob-model tap" :class="{ sel: selectedModel === m.id }" @click="selectedModel = m.id">
          <span class="ob-mic">{{ m.icon }}</span>
          <div class="ob-mtxt">
            <b>{{ m.label }}</b>
            <span>{{ m.desc }}</span>
          </div>
          <span v-if="m.rec" class="ob-rec">推荐</span>
          <span v-if="selectedModel === m.id" class="ob-check">✓</span>
        </div>
      </div>
      <label class="ob-label">智谱 API Key（可选，有则填）</label>
      <input v-model="apiKey" type="password" placeholder="粘贴你的 Key（没有可跳过）" class="ob-input">
      <button class="ob-btn tap" @click="saveKey(); step = 3" :disabled="!selectedModel">
        {{ selectedModel ? '下一步' : '请选择模型' }} <span class="ar">›</span>
      </button>
    </template>

    <!-- Step 3: 权限 -->
    <template v-else-if="step === 3">
      <h2>开启能力</h2>
      <p class="ob-sub">这些权限让小丘真正"住进"你的手机（可随时在设置中开启）</p>
      <div class="ob-perms">
        <div v-for="p in perms" :key="p.k" class="ob-perm">
          <span class="ob-pic">{{ p.icon }}</span>
          <div class="ob-ptxt">
            <b>{{ p.label }}</b>
            <span>{{ p.desc }}</span>
          </div>
          <span :class="['ob-pst', p.ok ? 'ok' : '']">{{ p.ok ? '✅' : '○' }}</span>
        </div>
      </div>
      <p class="ob-hint">💡 已开启 {{ permReady }}/{{ perms.length }} 项——不开启也能用基础功能</p>
      <button class="ob-btn tap" @click="step = 4">下一步 <span class="ar">›</span></button>
    </template>

    <!-- Step 4: 试用 -->
    <template v-else-if="step === 4">
      <h2>试一试</h2>
      <p class="ob-sub">对小丘说句话，或者直接打字试试</p>
      <div class="ob-try">
        <div class="ob-example tap" @click="step = 5">
          <span class="obic">🗣</span>
          <div>
            <b>喊「小丘」</b>
            <span>任意界面/息屏都能唤醒</span>
          </div>
        </div>
        <div class="ob-example tap" @click="step = 5">
          <span class="obic">💬</span>
          <div>
            <b>打字对话</b>
            <span>像微信一样发消息</span>
          </div>
        </div>
        <div class="ob-example tap" @click="step = 5">
          <span class="obic">⌨️</span>
          <div>
            <b>让 AI 跑命令</b>
            <span>「帮我看看电池还剩多少」</span>
          </div>
        </div>
      </div>
      <button class="ob-btn tap" @click="step = 5">下一步 <span class="ar">›</span></button>
    </template>

    <!-- Step 5: 完成 -->
    <template v-else-if="step === 5">
      <div class="ob-done">
        <div class="ob-done-ic">🏔</div>
        <h2>一切就绪！</h2>
        <p class="ob-sub">小丘已准备好为你工作<br>有事喊「小丘」，没事也可以聊聊</p>
        <div class="ob-tips">
          <p>💡 语音唤醒在「设置→语音与唤醒」里开启</p>
          <p>💡 遇到问题在「设置→关于」里看诊断</p>
          <p>💡 小丘越用越懂你——记忆会自动沉淀</p>
        </div>
      </div>
      <button class="ob-btn tap" @click="finish">开始使用 🚀</button>
    </template>
  </div>
</template>

<style scoped>
.ob-wrap { position: fixed; inset: 0; z-index: 999; background: var(--bg); display: flex; flex-direction: column; align-items: center; padding: 60px 28px 40px; overflow-y: auto; }
.ob-progress { display: flex; gap: 10px; margin-bottom: 40px; }
.ob-dot { width: 8px; height: 8px; border-radius: 50%; background: var(--line); transition: all .2s; }
.ob-dot.on { background: var(--hill); }
.ob-dot.cur { width: 24px; border-radius: 4px; }

h1 { font-size: 28px; font-weight: 800; color: var(--ink); margin: 20px 0 8px; }
h2 { font-size: 24px; font-weight: 800; color: var(--ink); margin: 0 0 8px; text-align: center; }
.ob-tag { font-size: 16px; color: var(--hill); font-weight: 600; margin-bottom: 30px; }
.ob-sub { font-size: 14px; color: var(--muted); text-align: center; margin-bottom: 28px; line-height: 1.6; }

.ob-hero { display: flex; flex-direction: column; align-items: center; flex: 1; justify-content: center; }
.ob-feats { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; width: 100%; max-width: 340px; }
.ob-feat { display: flex; flex-direction: column; align-items: center; gap: 8px; padding: 18px 12px; background: var(--card); border: 1px solid var(--line); border-radius: 16px; text-align: center; font-size: 13px; color: var(--muted); line-height: 1.5; }
.fic { font-size: 28px; }

.ob-models { width: 100%; max-width: 340px; display: flex; flex-direction: column; gap: 10px; margin-bottom: 16px; }
.ob-model { display: flex; align-items: center; gap: 12px; padding: 14px; background: var(--card); border: 2px solid var(--line); border-radius: 16px; }
.ob-model.sel { border-color: var(--hill); background: #F4FAF6; }
.ob-mic { font-size: 24px; }
.ob-mtxt { flex: 1; }
.ob-mtxt b { font-size: 16px; display: block; }
.ob-mtxt span { font-size: 12px; color: var(--muted); }
.ob-rec { font-size: 10px; padding: 2px 8px; border-radius: 99px; background: var(--hill-soft); color: var(--hill); font-weight: 700; }
.ob-check { font-size: 18px; color: var(--hill); font-weight: 700; }

.ob-label { font-size: 13px; color: var(--muted); margin: 8px 0 6px; width: 100%; max-width: 340px; }
.ob-input { width: 100%; max-width: 340px; }

.ob-perms { width: 100%; max-width: 340px; display: flex; flex-direction: column; gap: 10px; margin-bottom: 16px; }
.ob-perm { display: flex; align-items: center; gap: 12px; padding: 12px 14px; background: var(--card); border: 1px solid var(--line); border-radius: 14px; }
.ob-pic { font-size: 20px; }
.ob-ptxt { flex: 1; }
.ob-ptxt b { font-size: 15px; display: block; }
.ob-ptxt span { font-size: 12px; color: var(--muted); }
.ob-pst { font-size: 16px; color: var(--line); }
.ob-pst.ok { color: var(--hill); }
.ob-hint { font-size: 12px; color: var(--muted); text-align: center; margin-bottom: 20px; }

.ob-try { width: 100%; max-width: 340px; display: flex; flex-direction: column; gap: 12px; margin-bottom: 24px; }
.ob-example { display: flex; align-items: center; gap: 14px; padding: 16px; background: var(--card); border: 1px solid var(--line); border-radius: 16px; }
.obic { font-size: 24px; }
.ob-example b { font-size: 16px; display: block; }
.ob-example span { font-size: 13px; color: var(--muted); }

.ob-done { display: flex; flex-direction: column; align-items: center; flex: 1; justify-content: center; }
.ob-done-ic { font-size: 60px; margin-bottom: 16px; }
.ob-tips { margin-top: 24px; }
.ob-tips p { font-size: 13px; color: var(--muted); margin: 8px 0; text-align: center; }

.ob-btn { width: 100%; max-width: 340px; border: 0; border-radius: 16px; background: var(--hill); color: #fff; font-size: 17px; font-weight: 700; padding: 16px; display: flex; align-items: center; justify-content: center; gap: 6px; }
.ob-btn:disabled { opacity: .4; }
.ob-btn .ar { font-size: 14px; }
.ob-skip { border: 0; background: none; color: var(--muted); font-size: 14px; margin-top: 16px; padding: 8px; }
</style>
