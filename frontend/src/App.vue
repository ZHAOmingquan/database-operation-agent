<template>
  <a-layout style="height: 100%">
    <a-layout-sider theme="dark" width="180">
      <div class="logo">DB 智能体</div>
      <a-menu theme="dark" mode="inline" :selected-keys="[selectedKey]" @click="onMenu">
        <a-menu-item key="/chat">对话工作台</a-menu-item>
        <a-menu-item key="/datasources">数据源管理</a-menu-item>
        <a-menu-item key="/models">模型管理</a-menu-item>
        <a-menu-item key="/dict">字典管理</a-menu-item>
        <a-menu-item key="/configs">系统配置</a-menu-item>
      </a-menu>
    </a-layout-sider>
    <a-layout>
      <a-layout-content style="height: 100%; overflow: hidden">
        <router-view />
      </a-layout-content>
    </a-layout>
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
.logo { color: #fff; font-size: 16px; font-weight: 600; text-align: center; line-height: 56px; }
</style>
