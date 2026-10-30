<script setup>
// 知识库面板（弹窗）
import { computed, ref, watch } from 'vue'
import { ElDialog, ElInput } from 'element-plus'
import Icon from './Icon.vue'
import { api, apiState } from '../api'
import { useToast } from '../toast'

const props = defineProps({
  modelValue: { type: Boolean, default: false }
})
const emit = defineEmits(['update:modelValue'])

const { show } = useToast()

const visible = computed({
  get: () => props.modelValue,
  set: (value) => emit('update:modelValue', value)
})

const files = ref([])
const stats = ref({ files: 0, chunks: 0, chars: 0 })
const busy = ref(false)
const inputRef = ref(null)
const probe = ref('')
const hits = ref([])
const probing = ref(false)
// 试检索跑过一轮才显示「没有命中」，否则空着的时候会误以为已经搜过了
const probed = ref(false)

// 与后端 KnowledgeExtractor.SUFFIXES 保持一致，选文件时就能挡掉不支持的格式
const ACCEPT = '.txt,.md,.markdown,.csv,.tsv,.json,.log,.docx,.xlsx,.xls,.pdf'

// 知识库的取字、切片、检索都在后端，断网时这个弹窗只能看不能动
const offline = computed(() => apiState.online === false)

const summary = computed(() => {
  const s = stats.value
  return s.files ? `${s.files} 份文件 · ${s.chunks} 段 · 共 ${s.chars} 字` : ''
})

function sizeText(bytes) {
  if (!bytes) return ''
  if (bytes < 1024) return `${bytes}B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(0)}KB`
  return `${(bytes / 1024 / 1024).toFixed(1)}MB`
}

async function load() {
  // 离线时不去请求，直接按空库显示，免得弹出一串连接失败
  if (offline.value) {
    files.value = []
    stats.value = { files: 0, chunks: 0, chars: 0 }
    return
  }
  try {
    const res = await api.bridgeKnowledge()
    files.value = res?.files || []
    stats.value = res?.stats || { files: 0, chunks: 0, chars: 0 }
  } catch (e) {
    // 知识库读不到不挡上传：后端重启或还没建库都按空库显示
    files.value = []
    stats.value = { files: 0, chunks: 0, chars: 0 }
  }
}

async function onPick(event) {
  const picked = Array.from(event.target.files || [])
  event.target.value = ''
  if (!picked.length || busy.value) return
  if (offline.value) {
    show('当前离线模式，知识库用不了；连上服务器后再上传', 'err')
    return
  }
  busy.value = true
  try {
    const res = await api.bridgeKnowledgeUpload(picked)
    const added = res?.added?.length || 0
    const skipped = res?.skipped || []
    await load()
    probed.value = false
    hits.value = []
    if (added) {
      const chunks = (res?.added || []).reduce((sum, item) => sum + (item.chunks || 0), 0)
      show(`知识库收进 ${added} 份文档，切成 ${chunks} 段`)
    }
    // 跳过原因（格式不支持 / 扫描件取不到字）要说清楚，否则用户以为传上去了
    if (skipped.length) {
      show(skipped.length > 1 ? `${skipped[0]} 等 ${skipped.length} 份未入库` : skipped[0], 'err')
    }
  } catch (err) {
    show(err.message || '知识库上传失败', 'err')
  } finally {
    busy.value = false
  }
}

async function remove(file) {
  if (busy.value) return
  busy.value = true
  try {
    const res = await api.bridgeRemoveKnowledge(file.id)
    files.value = res?.files || []
    stats.value = res?.stats || { files: 0, chunks: 0, chars: 0 }
    hits.value = hits.value.filter((hit) => hit.file !== file.name)
    show(`已移除《${file.name}》`)
  } catch (err) {
    show(err.message || '移除失败', 'err')
  } finally {
    busy.value = false
  }
}

// 试检索：用一句业务目标查一遍，看看现在会引用哪几段
async function search() {
  if (probing.value) return
  const q = probe.value.trim()
  if (!q) {
    show('先写一句业务目标，再试检索', 'err')
    return
  }
  if (offline.value) {
    show('当前离线模式，试检索要连服务器才能用', 'err')
    return
  }
  probing.value = true
  try {
    hits.value = await api.bridgeSearchKnowledge(q)
    probed.value = true
  } catch (err) {
    show(err.message || '试检索失败', 'err')
  } finally {
    probing.value = false
  }
}

// 每次打开都重新拉一次：别的地方（或另一台浏览器）刚加过文档也能看到
watch(visible, (open) => {
  if (open) load()
})
</script>

<template>
  <el-dialog v-model="visible" title="知识库" width="620px" append-to-body>
    <div class="kb-head">
      <p>
        制度、计算口径、字段对照表都可以放这里。模型规划与回答时会按相关度引用，
        并在追问时说明出自哪份文件；同名文件按更新处理。
      </p>
      <span v-if="summary" class="kb-sum">{{ summary }}</span>
    </div>

    <div v-if="offline" class="kb-off">
      <Icon name="alert" :size="13" />
      <span>当前为离线模式：知识库要连上服务器才能取字、切片和检索，联网后再上传或试检索。</span>
    </div>

    <div class="kb-bar">
      <button class="kb-upload" type="button" :disabled="busy || offline" @click="inputRef?.click()">
        <Icon name="upload" :size="13" />{{ busy ? '正在取字…' : '上传文档' }}
      </button>
      <span class="kb-accept">支持 Word / Excel / PDF / TXT / CSV / MD</span>
      <input ref="inputRef" class="kb-input" type="file" multiple :accept="ACCEPT" @change="onPick" />
    </div>

    <div v-if="files.length" class="kb-list">
      <div v-for="file in files" :key="file.id" class="kb-item">
        <Icon name="file" :size="14" class="kb-icon" />
        <div class="kb-meta">
          <span class="kb-name" :title="file.name">{{ file.name }}</span>
          <span class="kb-sub">
            {{ file.kind }} · {{ file.chars }} 字 · {{ file.chunks }} 段
            <template v-if="sizeText(file.size)"> · {{ sizeText(file.size) }}</template>
            · {{ file.uploaded_at }}
          </span>
          <span v-if="file.note" class="kb-note">{{ file.note }}</span>
        </div>
        <button class="kb-del" type="button" :title="`移除《${file.name}》`" :disabled="busy" @click="remove(file)">
          <Icon name="trash" :size="13" />
        </button>
      </div>
    </div>
    <p v-else-if="!offline" class="kb-empty">
      还没上传文档。传一份制度或口径说明，模型就会按你的规则算，而不是按它自己的常识猜。
    </p>

    <div v-if="files.length" class="kb-probe">
      <el-input
        v-model="probe"
        size="small"
        :disabled="probing || offline"
        placeholder="试检索：输入一句业务目标，看看会引用哪几段"
        @keyup.enter="search()"
      />
      <button class="kb-probe-btn" type="button" :disabled="probing || offline" @click="search()">
        {{ probing ? '检索中…' : '试检索' }}
      </button>
    </div>
    <div v-if="hits.length" class="kb-hits">
      <div v-for="(hit, index) in hits" :key="index" class="kb-hit">
        <span class="kb-hit-head">《{{ hit.file }}》· {{ hit.kind }} · 相关度 {{ hit.score }}%</span>
        <span class="kb-hit-text">{{ hit.text.slice(0, 160) }}{{ hit.text.length > 160 ? '…' : '' }}</span>
      </div>
    </div>
    <p v-else-if="probed" class="kb-empty">这段话没有命中任何切片，说明库里还没有能对上的内容。</p>
  </el-dialog>
</template>

<style scoped>
.kb-head {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-bottom: 12px;
}

.kb-head p {
  margin: 0;
  color: var(--ink-2);
  font-size: 12.5px;
  line-height: 1.7;
}

.kb-sum {
  color: var(--muted);
  font-size: 11.5px;
}

.kb-off {
  display: flex;
  gap: 6px;
  align-items: flex-start;
  margin-bottom: 10px;
  padding: 8px 10px;
  border: 1px solid #f0d8a8;
  border-radius: 6px;
  background: #fffdf7;
  color: #92400e;
  font-size: 12px;
  line-height: 1.6;
}

.kb-bar {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  padding-bottom: 12px;
}

.kb-upload {
  display: inline-flex;
  flex: none;
  gap: 5px;
  align-items: center;
  height: 30px;
  padding: 0 12px;
  border: 1px solid var(--line);
  border-radius: 6px;
  background: var(--accent-soft);
  color: var(--accent);
  font-size: 12.5px;
  cursor: pointer;
}

.kb-upload:hover:not(:disabled) {
  background: #e4edff;
}

.kb-upload:disabled {
  cursor: not-allowed;
  opacity: 0.6;
}

.kb-accept {
  flex: 1 1 200px;
  min-width: 0;
  color: var(--muted);
  font-size: 11.5px;
}

.kb-input {
  display: none;
}

.kb-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
  max-height: 260px;
  overflow-y: auto;
}

.kb-item {
  display: flex;
  gap: 8px;
  align-items: flex-start;
  padding: 9px 10px;
  border: 1px solid var(--line-soft);
  border-radius: 6px;
  background: #fbfcfe;
}

.kb-icon {
  flex: none;
  margin-top: 2px;
  color: var(--muted);
}

.kb-meta {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.kb-name {
  overflow: hidden;
  color: var(--ink);
  font-size: 12.5px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.kb-sub {
  color: var(--muted);
  font-size: 11px;
}

.kb-note {
  color: var(--warn);
  font-size: 11px;
}

.kb-del {
  display: inline-flex;
  flex: none;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  border: 0;
  border-radius: 5px;
  background: none;
  color: var(--muted);
  cursor: pointer;
}

.kb-del:hover:not(:disabled) {
  background: var(--danger-soft);
  color: var(--danger);
}

.kb-del:disabled {
  cursor: not-allowed;
  opacity: 0.5;
}

.kb-empty {
  margin: 0;
  padding: 2px 0 6px;
  color: var(--muted);
  font-size: 12px;
  line-height: 1.7;
}

.kb-probe {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px dashed var(--line);
}

.kb-probe :deep(.el-input) {
  flex: 1;
  min-width: 0;
}

.kb-probe-btn {
  flex: none;
  height: 30px;
  padding: 0 12px;
  border: 1px solid var(--line);
  border-radius: 6px;
  background: #fff;
  color: var(--ink-2);
  font-size: 12px;
  cursor: pointer;
}

.kb-probe-btn:hover:not(:disabled) {
  border-color: var(--accent);
  color: var(--accent);
}

.kb-probe-btn:disabled {
  cursor: not-allowed;
  opacity: 0.6;
}

.kb-hits {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-top: 10px;
}

.kb-hit {
  display: flex;
  flex-direction: column;
  gap: 3px;
  padding: 9px 10px;
  border: 1px dashed var(--line);
  border-radius: 6px;
  background: #fbfcfe;
}

.kb-hit-head {
  color: var(--accent);
  font-size: 11.5px;
  font-weight: 600;
}

.kb-hit-text {
  color: var(--ink-2);
  font-size: 11.5px;
  line-height: 1.65;
  overflow-wrap: anywhere;
}
</style>
