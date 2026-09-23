<template>
  <a-modal :open="open" :title="editing ? '编辑数据源' : '新建数据源'"
           :confirm-loading="saving" @update:open="(v) => emit('update:open', v)" @ok="save">
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
      <a-form-item :label="editing ? '密码（留空则不修改）' : '密码'">
        <a-input-password v-model:value="form.password" />
      </a-form-item>
      <a-form-item label="额外连接参数（可选）"><a-input v-model:value="form.extraParams" placeholder="k=v&k2=v2" /></a-form-item>
      <a-form-item label="只读"><a-switch v-model:checked="form.readOnly" /></a-form-item>
    </a-form>
    <a-alert v-if="testMsg" :type="testOk ? 'success' : 'error'" :message="testMsg" show-icon style="margin-top:8px" />
    <template #footer>
      <a-button @click="testForm" :loading="testing">测试连接</a-button>
      <a-button @click="emit('update:open', false)">取消</a-button>
      <a-button type="primary" :loading="saving" @click="save">保存</a-button>
    </template>
  </a-modal>
</template>

<script setup>
import { reactive, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import { datasourceApi } from '../api'

const props = defineProps({
  open: { type: Boolean, default: false },
  editing: { type: Object, default: null } // 传入数据源记录则为编辑模式
})
const emit = defineEmits(['update:open', 'saved'])

const saving = ref(false)
const testing = ref(false)
const testMsg = ref('')
const testOk = ref(false)

const emptyForm = () => ({ id: null, name: '', dbType: 'mysql', host: '127.0.0.1', port: 3306,
  databaseName: '', username: 'root', password: '', extraParams: '', readOnly: false })
const form = reactive(emptyForm())

watch(() => props.open, (v) => {
  if (!v) return
  Object.assign(form, emptyForm(), props.editing || {}, props.editing ? { password: '' } : {})
  testMsg.value = ''
})

const onTypeChange = (v) => { if (v === 'mysql') form.port = 3306; else form.port = 5432 }

const save = async () => {
  saving.value = true
  try {
    const payload = { ...form }
    const saved = form.id ? await datasourceApi.update(form.id, payload) : await datasourceApi.create(payload)
    message.success('已保存')
    emit('update:open', false)
    emit('saved', saved)
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
</script>
