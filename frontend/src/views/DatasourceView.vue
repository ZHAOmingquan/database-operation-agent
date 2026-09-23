<template>
  <div style="padding: 16px">
    <a-space style="margin-bottom: 12px">
      <a-button type="primary" @click="openCreate">新建数据源</a-button>
      <a-button @click="load">刷新</a-button>
    </a-space>
    <a-table :data-source="rows" :columns="columns" row-key="id" size="middle" :loading="loading">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'readOnly'">
          <a-tag :color="record.readOnly ? 'orange' : 'green'">{{ record.readOnly ? '只读' : '可写' }}</a-tag>
        </template>
        <template v-else-if="column.key === 'action'">
          <a-space>
            <a @click="testRow(record)">测试连接</a>
            <a @click="openEdit(record)">编辑</a>
            <a-popconfirm title="确认删除该数据源？" @confirm="removeRow(record.id)">
              <a style="color: #ff4d4f">删除</a>
            </a-popconfirm>
          </a-space>
        </template>
      </template>
    </a-table>

    <a-modal v-model:open="modalOpen" :title="form.id ? '编辑数据源' : '新建数据源'"
             :confirm-loading="saving" @ok="save">
      <a-form layout="vertical" :model="form">
        <a-form-item label="名称" required>
          <a-input v-model:value="form.name" placeholder="唯一名称，如 local-mysql" />
        </a-form-item>
        <a-form-item label="类型" required>
          <a-select v-model:value="form.dbType" :options="[{value:'mysql',label:'MySQL'},{value:'postgresql',label:'PostgreSQL'}]" @change="onTypeChange" />
        </a-form-item>
        <a-row :gutter="12">
          <a-col :span="16"><a-form-item label="主机" required><a-input v-model:value="form.host" /></a-form-item></a-col>
          <a-col :span="8"><a-form-item label="端口" required><a-input-number v-model:value="form.port" style="width:100%" /></a-form-item></a-col>
        </a-row>
        <a-form-item label="数据库名" required><a-input v-model:value="form.databaseName" /></a-form-item>
        <a-form-item label="用户名" required><a-input v-model:value="form.username" /></a-form-item>
        <a-form-item :label="form.id ? '密码（留空则不修改）' : '密码'">
          <a-input-password v-model:value="form.password" />
        </a-form-item>
        <a-form-item label="额外连接参数（可选）"><a-input v-model:value="form.extraParams" placeholder="k=v&k2=v2" /></a-form-item>
        <a-form-item label="只读"><a-switch v-model:checked="form.readOnly" /></a-form-item>
      </a-form>
      <a-alert v-if="testMsg" :type="testOk ? 'success' : 'error'" :message="testMsg" show-icon style="margin-top:8px" />
      <template #footer>
        <a-button @click="testForm" :loading="testing">测试连接</a-button>
        <a-button @click="modalOpen = false">取消</a-button>
        <a-button type="primary" :loading="saving" @click="save">保存</a-button>
      </template>
    </a-modal>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { message } from 'ant-design-vue'
import { datasourceApi } from '../api'

const rows = ref([])
const loading = ref(false)
const modalOpen = ref(false)
const saving = ref(false)
const testing = ref(false)
const testMsg = ref('')
const testOk = ref(false)

const emptyForm = () => ({ id: null, name: '', dbType: 'mysql', host: '127.0.0.1', port: 3306,
  databaseName: '', username: 'root', password: '', extraParams: '', readOnly: false })
const form = reactive(emptyForm())

const columns = [
  { title: '名称', dataIndex: 'name' },
  { title: '类型', dataIndex: 'dbType' },
  { title: '主机:端口', customRender: ({ record }) => `${record.host}:${record.port}` },
  { title: '库名', dataIndex: 'databaseName' },
  { title: '用户', dataIndex: 'username' },
  { title: '权限', key: 'readOnly' },
  { title: '操作', key: 'action', width: 220 }
]

const load = async () => {
  loading.value = true
  try { rows.value = await datasourceApi.list() } finally { loading.value = false }
}

const onTypeChange = (v) => { if (v === 'mysql') form.port = 3306; else form.port = 5432 }
const openCreate = () => { Object.assign(form, emptyForm()); testMsg.value = ''; modalOpen.value = true }
const openEdit = (r) => { Object.assign(form, emptyForm(), r, { password: '' }); testMsg.value = ''; modalOpen.value = true }

const save = async () => {
  saving.value = true
  try {
    const payload = { ...form }
    if (form.id) await datasourceApi.update(form.id, payload)
    else await datasourceApi.create(payload)
    message.success('已保存')
    modalOpen.value = false
    await load()
  } finally { saving.value = false }
}

const testForm = async () => {
  testing.value = true
  try {
    const r = await datasourceApi.test({ ...form }, form.id)
    testOk.value = r.success
    testMsg.value = r.success ? `连接成功（${r.elapsedMs}ms）` : `连接失败：${r.message}`
  } finally { testing.value = false }
}

const testRow = async (r) => {
  const res = await datasourceApi.test({ ...r, password: '' }, r.id)
  if (res.success) message.success(`连接成功（${res.elapsedMs}ms）`)
  else message.error(`连接失败：${res.message}`)
}

const removeRow = async (id) => { await datasourceApi.remove(id); message.success('已删除'); await load() }

onMounted(load)
</script>
