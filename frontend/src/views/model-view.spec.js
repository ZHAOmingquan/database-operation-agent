// @vitest-environment jsdom
import { describe, it, expect, vi, beforeAll } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import Antd from 'ant-design-vue'
import ModelView from './ModelView.vue'

// 模拟后端：厂商一个（minimax，带 base_url 扩展值），模型ID两条
vi.mock('../api', () => ({
  modelApi: { list: vi.fn(() => Promise.resolve([])) },
  dictApi: {
    list: vi.fn((type) => type === 'model_provider'
      ? Promise.resolve([{ dictKey: 'minimax', dictLabel: 'MiniMax', extValue: 'https://api.minimaxi.com/v1' }])
      : Promise.resolve([])),
    modelIds: vi.fn(() => Promise.resolve([
      { dictKey: 'MiniMax-M3', dictLabel: 'MiniMax-M3' },
      { dictKey: 'MiniMax-M2.7', dictLabel: 'MiniMax-M2.7' }
    ]))
  }
}))

beforeAll(() => {
  window.matchMedia = window.matchMedia || (() => ({
    matches: false, media: '', addListener() {}, removeListener() {},
    addEventListener() {}, removeEventListener() {}, onchange: null, dispatchEvent: () => false
  }))
  window.ResizeObserver = window.ResizeObserver || class {
    observe() {} unobserve() {} disconnect() {}
  }
  window.requestAnimationFrame = window.requestAnimationFrame || ((cb) => setTimeout(cb, 0))
  window.cancelAnimationFrame = window.cancelAnimationFrame || ((id) => clearTimeout(id))
})

async function openCreateModal(wrapper) {
  const btn = wrapper.findAll('button').find((b) => b.text().includes('新建模型'))
  await btn.trigger('click')
  await flushPromises()
}

function modalSelects() {
  return [...document.querySelectorAll('.ant-modal .ant-select')]
}

async function openDropdown(selectEl) {
  selectEl.querySelector('.ant-select-selector')
    .dispatchEvent(new MouseEvent('mousedown', { bubbles: true }))
  await flushPromises()
}

function optionsOf(dd) {
  return dd ? [...dd.querySelectorAll('.ant-select-item-option')].map((o) => o.textContent.trim()) : []
}

// 模型ID 下拉带有「自定义模型ID」输入框（dropdownRender 内容），这是它区别于供应商下拉的标志；
// 不依赖 hidden 类判断（jsdom 中关闭动画不会给已关闭下拉加 hidden 类）
function modelIdDropdown() {
  return [...document.querySelectorAll('.ant-select-dropdown')]
    .find((d) => d.querySelector('input[placeholder="自定义模型ID"]'))
}

async function pickOption(title) {
  const dds = [...document.querySelectorAll('.ant-select-dropdown')]
    .filter((d) => !d.classList.contains('ant-select-dropdown-hidden'))
  const opt = dds.flatMap((d) => [...d.querySelectorAll('.ant-select-item-option')])
    .find((o) => o.textContent.trim() === title)
  expect(opt, `下拉里应存在选项 ${title}`).toBeTruthy()
  opt.dispatchEvent(new MouseEvent('click', { bubbles: true }))
  await flushPromises()
}

describe('模型表单：供应商 → 模型ID 级联', () => {
  it('先选供应商再打开模型ID下拉：应显示对应模型ID', async () => {
    const wrapper = mount(ModelView, { attachTo: document.body, global: { plugins: [Antd] } })
    await flushPromises()
    await openCreateModal(wrapper)

    await openDropdown(modalSelects()[0]) // 供应商下拉
    await pickOption('MiniMax')

    // base_url 由厂商字典自动带出且置灰
    const baseUrlInput = document.querySelector('.ant-modal input[disabled]')
    expect(baseUrlInput).toBeTruthy()
    expect(baseUrlInput.value).toBe('https://api.minimaxi.com/v1')

    await openDropdown(modalSelects()[1]) // 模型ID下拉
    const texts = optionsOf(modelIdDropdown())
    expect(texts).toContain('MiniMax-M3')
    expect(texts).toContain('MiniMax-M2.7')
    wrapper.unmount()
  })

  it('先空开模型ID下拉再选供应商：重新打开后仍应显示（无缓存陈旧问题）', async () => {
    const wrapper = mount(ModelView, { attachTo: document.body, global: { plugins: [Antd] } })
    await flushPromises()
    await openCreateModal(wrapper)

    await openDropdown(modalSelects()[1]) // 先空开模型ID下拉
    expect(optionsOf(modelIdDropdown())).toEqual([])
    document.body.dispatchEvent(new MouseEvent('mousedown', { bubbles: true })) // 点外部关闭
    await flushPromises()

    await openDropdown(modalSelects()[0])
    await pickOption('MiniMax')

    await openDropdown(modalSelects()[1])
    const texts = optionsOf(modelIdDropdown())
    expect(texts).toContain('MiniMax-M3')
    expect(texts).toContain('MiniMax-M2.7')
    wrapper.unmount()
  })
})
