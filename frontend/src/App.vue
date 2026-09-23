<template>
  <a-layout style="height: 100%">
    <a-layout-header class="topnav">
      <div class="logo">DB 智能体</div>
      <a-menu class="nav-menu" mode="horizontal" theme="light" :selected-keys="[selectedKey]" @click="onMenu">
        <a-menu-item key="/chat">对话工作台</a-menu-item>
        <a-menu-item key="/datasources">数据源管理</a-menu-item>
        <a-menu-item key="/models">模型管理</a-menu-item>
        <a-menu-item key="/dict">字典管理</a-menu-item>
        <a-menu-item key="/configs">系统配置</a-menu-item>
      </a-menu>
    </a-layout-header>
    <a-layout-content class="app-content">
      <router-view />
    </a-layout-content>
  </a-layout>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'

const route = useRoute()
const router = useRouter()
const selectedKey = computed(() => route.path)
const onMenu = ({ key }) => router.push(key)
</script>

<style scoped>
.topnav {
  display: flex;
  align-items: center;
  height: 56px;
  padding: 0 16px;
  background: #fff;
  border-bottom: 1px solid #f0f0f0;
}
.logo { color: #1677ff; font-size: 16px; font-weight: 600; margin-right: 24px; white-space: nowrap; }
.topnav :deep(.ant-menu-horizontal) { flex: 1; line-height: 54px; border-bottom: none; background: transparent; }
.app-content { height: calc(100% - 56px); overflow: hidden; }
</style>
