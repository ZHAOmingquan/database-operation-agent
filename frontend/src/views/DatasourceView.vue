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

    <DatasourceFormModal v-model:open="modalOpen" :editing="editing" @saved="load" />
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { message } from 'ant-design-vue'
import { datasourceApi } from '../api'
import DatasourceFormModal from '../components/DatasourceFormModal.vue'

const rows = ref([])
const loading = ref(false)
const modalOpen = ref(false)
const editing = ref(null)

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

const openCreate = () => { editing.value = null; modalOpen.value = true }
const openEdit = (r) => { editing.value = r; modalOpen.value = true }

const testRow = async (r) => {
  const res = await datasourceApi.test({ ...r, password: '' }, r.id)
  if (res.success) message.success(`连接成功（${res.elapsedMs}ms）`)
  else message.error(`连接失败：${res.message}`)
}

const removeRow = async (id) => { await datasourceApi.remove(id); message.success('已删除'); await load() }

onMounted(load)
</script>
