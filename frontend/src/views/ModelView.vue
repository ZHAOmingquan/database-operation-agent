<template>
  <div style="padding: 16px">
    <a-space style="margin-bottom: 12px">
      <a-button type="primary" @click="openCreate">新建模型</a-button>
      <a-button @click="load">刷新</a-button>
    </a-space>
    <a-table :data-source="rows" :columns="columns" row-key="id" size="middle">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'enabled'">
          <a-switch :checked="record.enabled" :disabled="record.enabled" @change="enableRow(record)" />
        </template>
        <template v-else-if="column.key === 'provider'">
          {{ providerLabel(record.provider) }}
        </template>
        <template v-else-if="column.key === 'action'">
          <a-space>
            <a @click="testRow(record)">测试连接</a>
            <a @click="openEdit(record)">编辑</a>
            <a-popconfirm title="确认删除？" @confirm="removeRow(record.id)"><a style="color:#ff4d4f">删除</a></a-popconfirm>
          </a-space>
        </template>
      </template>
    </a-table>

    <a-modal v-model:open="modalOpen" :title="form.id ? '编辑模型' : '新建模型'" @ok="save" width="560px">
      <a-form layout="vertical" :model="form">
        <a-form-item label="名称" required><a-input v-model:value="form.name" /></a-form-item>
        <a-form-item label="模型供应商" required>
          <a-select v-model:value="form.provider" :options="providerOptions" @change="onProviderChange" />
        </a-form-item>
        <a-form-item label="模型ID" required>
          <a-select v-model:value="form.modelId" :options="modelIdOptions" show-search
                    placeholder="选择或输入模型ID" :filter-option="filterOption">
            <template #dropdownRender="{ menu }">
              <div>
                <a-input v-model:value="customModelId" style="margin: 4px 8px; width: calc(100% - 16px)"
                         placeholder="自定义模型ID" @pressEnter="addCustomModelId" />
                <a-divider style="margin: 4px 0" />
                <component :is="menu" />
              </div>
            </template>
          </a-select>
        </a-form-item>
        <a-form-item label="Base URL" required>
          <a-input v-model:value="form.baseUrl" placeholder="https://api.minimaxi.com/v1" />
        </a-form-item>
        <a-form-item :label="form.id ? 'API Key（留空则不修改）' : 'API Key'" required>
          <a-input-password v-model:value="form.apiKey" />
        </a-form-item>
        <a-row :gutter="12">
          <a-col :span="12"><a-form-item label="temperature"><a-input-number v-model:value="form.temperature" :min="0" :max="2" :step="0.1" style="width:100%" /></a-form-item></a-col>
          <a-col :span="12"><a-form-item label="maxTokens"><a-input-number v-model:value="form.maxTokens" :min="1" style="width:100%" /></a-form-item></a-col>
        </a-row>
      </a-form>
      <a-alert v-if="testMsg" :type="testOk ? 'success' : 'error'" :message="testMsg" show-icon style="margin-top:8px" />
      <template #footer>
        <a-button :loading="testing" @click="testForm">测试连接</a-button>
        <a-button @click="modalOpen = false">取消</a-button>
        <a-button type="primary" :loading="saving" @click="save">保存</a-button>
      </template>
    </a-modal>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { message } from 'ant-design-vue'
import { dictApi, modelApi } from '../api'

const rows = ref([])
const providerOptions = ref([])
const modelIdOptions = ref([])
const customModelId = ref('')
const modalOpen = ref(false)
const saving = ref(false)
const testing = ref(false)
const testMsg = ref('')
const testOk = ref(false)

const emptyForm = () => ({ id: null, name: '', provider: undefined, modelId: undefined,
  baseUrl: '', apiKey: '', temperature: 0.7, maxTokens: 4096 })
const form = reactive(emptyForm())

const columns = [
  { title: '名称', dataIndex: 'name' },
  { title: '供应商', key: 'provider' },
  { title: '模型ID', dataIndex: 'modelId' },
  { title: 'Base URL', dataIndex: 'baseUrl' },
  { title: '启用', key: 'enabled', width: 90 },
  { title: '操作', key: 'action', width: 200 }
]

const providerLabel = (key) => providerOptions.value.find((p) => p.value === key)?.label || key
const filterOption = (input, option) => (option.label ?? '').toLowerCase().includes(input.toLowerCase())

const load = async () => {
  rows.value = await modelApi.list()
  const dicts = await dictApi.list('model_provider', true)
  providerOptions.value = dicts.map((p) => ({ value: p.dictKey, label: p.dictLabel }))
}

const loadModelIds = async (provider) => {
  if (!provider) { modelIdOptions.value = []; return }
  const ids = await dictApi.modelIds(provider)
  modelIdOptions.value = ids.map((d) => ({ value: d.dictKey, label: d.dictLabel }))
}

const onProviderChange = (v) => { form.modelId = undefined; loadModelIds(v) }
const addCustomModelId = () => {
  if (!customModelId.value) return
  modelIdOptions.value.push({ value: customModelId.value, label: `${customModelId.value}（自定义）` })
  form.modelId = customModelId.value
  customModelId.value = ''
}

const openCreate = () => { Object.assign(form, emptyForm()); testMsg.value = ''; modelIdOptions.value = []; modalOpen.value = true }
const openEdit = async (r) => {
  Object.assign(form, emptyForm(), r, { apiKey: '' })
  testMsg.value = ''
  await loadModelIds(r.provider)
  modalOpen.value = true
}

const save = async () => {
  saving.value = true
  try {
    const payload = { ...form }
    if (form.id) await modelApi.update(form.id, payload)
    else await modelApi.create(payload)
    message.success('已保存')
    modalOpen.value = false
    await load()
  } finally { saving.value = false }
}

const testForm = async () => {
  testing.value = true
  testMsg.value = ''
  try {
    const r = await modelApi.test({ ...form }, form.id)
    testOk.value = r.success
    testMsg.value = r.success
      ? `调用成功（${r.elapsedMs}ms）：${r.reply}`
      : `调用失败：${r.message}`
  } finally { testing.value = false }
}

const testRow = async (r) => {
  const res = await modelApi.test({ ...r, apiKey: '' }, r.id)
  if (res.success) message.success(`调用成功（${res.elapsedMs}ms）：${res.reply}`)
  else message.error(`调用失败：${res.message}`)
}

const enableRow = async (r) => { await modelApi.enable(r.id); message.success('已启用'); await load() }
const removeRow = async (id) => { await modelApi.remove(id); message.success('已删除'); await load() }

onMounted(load)
</script>
