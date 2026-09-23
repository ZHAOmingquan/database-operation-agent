<template>
  <div style="padding: 16px; height: 100%; overflow: auto">
    <a-card title="系统配置" :bordered="false">
      <a-spin :spinning="loading">
        <div v-for="c in configs" :key="c.configKey" class="config-item">
          <div class="config-row">
            <a-switch :checked="c.configValue === 'true'" @change="(v) => onToggle(c, v)" />
            <span class="config-title">{{ titleOf(c.configKey) }}</span>
            <a-tag v-if="c.configKey === 'developer_mode' && c.configValue === 'true'" color="red">已开启</a-tag>
          </div>
          <div class="config-desc">{{ c.description }}</div>
        </div>
      </a-spin>
    </a-card>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { message, Modal } from 'ant-design-vue'
import { configApi } from '../api'

const configs = ref([])
const loading = ref(false)
const titleOf = (key) => ({ developer_mode: '开发者模式' }[key] || key)

const load = async () => {
  loading.value = true
  try {
    configs.value = await configApi.list()
  } finally {
    loading.value = false
  }
}

const doSave = async (c, value) => {
  await configApi.update(c.configKey, String(value))
  message.success('已保存')
  await load()
}

const onToggle = (c, value) => {
  if (c.configKey === 'developer_mode' && value) {
    Modal.confirm({
      title: '开启开发者模式？',
      content: '开启后将允许数据库执行删除操作（DELETE/DROP/TRUNCATE）。请确保你已了解删除的后果。',
      okText: '确认开启',
      okType: 'danger',
      cancelText: '取消',
      onOk: () => doSave(c, value)
    })
  } else {
    doSave(c, value)
  }
}

onMounted(load)
</script>

<style scoped>
.config-item { padding: 12px 0; border-bottom: 1px solid #f0f0f0; }
.config-row { display: flex; align-items: center; gap: 12px; }
.config-title { font-weight: 600; }
.config-desc { color: #888; margin-top: 6px; font-size: 13px; }
</style>
