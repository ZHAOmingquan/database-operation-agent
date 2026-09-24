<template>
  <div style="padding: 16px">
    <a-tabs v-model:activeKey="activeType" @change="load">
      <a-tab-pane key="model_provider" tab="模型厂商" />
      <a-tab-pane key="model_id" tab="模型ID" />
    </a-tabs>
    <a-space style="margin-bottom: 12px">
      <a-button type="primary" @click="openCreate">新增</a-button>
      <a-button @click="load">刷新</a-button>
    </a-space>
    <a-table :data-source="rows" :columns="columns" row-key="id" size="middle">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'extValue'">
          {{ record.extValue || '—' }}
        </template>
        <template v-else-if="column.key === 'enabled'">
          <a-tag :color="record.enabled ? 'green' : 'default'">{{ record.enabled ? '启用' : '停用' }}</a-tag>
        </template>
        <template v-else-if="column.key === 'action'">
          <a-space>
            <a @click="openEdit(record)">编辑</a>
            <a-popconfirm title="确认删除？" @confirm="removeRow(record.id)"><a style="color:#ff4d4f">删除</a></a-popconfirm>
          </a-space>
        </template>
      </template>
    </a-table>

    <a-modal v-model:open="modalOpen" :title="form.id ? '编辑字典' : '新增字典'" @ok="save">
      <a-form layout="vertical" :model="form">
        <a-form-item label="字典键" required><a-input v-model:value="form.dictKey" /></a-form-item>
        <a-form-item label="显示名" required><a-input v-model:value="form.dictLabel" /></a-form-item>
        <a-form-item v-if="activeType === 'model_provider'" label="厂商 base_url（模型表单自动带出）">
          <a-input v-model:value="form.extValue" placeholder="https://api.minimaxi.com/v1" />
        </a-form-item>
        <a-form-item v-if="activeType === 'model_id'" label="所属厂商" required>
          <a-select v-model:value="form.parentKey" :options="providerOptions" />
        </a-form-item>
        <a-form-item label="排序"><a-input-number v-model:value="form.sort" style="width:100%" /></a-form-item>
        <a-form-item label="启用"><a-switch v-model:checked="form.enabled" /></a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { message } from 'ant-design-vue'
import { dictApi } from '../api'

const activeType = ref('model_provider')
const rows = ref([])
const providerOptions = ref([])
const modalOpen = ref(false)
const form = reactive({ id: null, dictType: 'model_provider', dictKey: '', dictLabel: '', parentKey: null, sort: 0, enabled: true, extValue: '' })

const columns = [
  { title: '键', dataIndex: 'dictKey' },
  { title: '显示名', dataIndex: 'dictLabel' },
  { title: '所属厂商', dataIndex: 'parentKey' },
  { title: '扩展值', key: 'extValue' },
  { title: '排序', dataIndex: 'sort', width: 80 },
  { title: '状态', key: 'enabled', width: 90 },
  { title: '操作', key: 'action', width: 140 }
]

const load = async () => {
  rows.value = await dictApi.list(activeType.value)
  if (activeType.value === 'model_id') {
    providerOptions.value = (await dictApi.list('model_provider', true))
      .map((p) => ({ value: p.dictKey, label: p.dictLabel }))
  }
}

const openCreate = () => {
  Object.assign(form, { id: null, dictType: activeType.value, dictKey: '', dictLabel: '', parentKey: null, sort: 0, enabled: true, extValue: '' })
  modalOpen.value = true
}
const openEdit = (r) => { Object.assign(form, r); modalOpen.value = true }

const save = async () => {
  const payload = { ...form }
  if (form.id) await dictApi.update(form.id, payload)
  else await dictApi.create(payload)
  message.success('已保存')
  modalOpen.value = false
  await load()
}
const removeRow = async (id) => { await dictApi.remove(id); message.success('已删除'); await load() }

onMounted(load)
</script>
