import MarkdownIt from 'markdown-it'

// html:false 转义原始 HTML（防注入）；breaks 让单个换行即渲染为换行（贴合大模型输出习惯）
const md = new MarkdownIt({ breaks: true, linkify: true, html: false })

// 外链新窗口打开
const defaultLinkOpen = md.renderer.rules.link_open
  || ((tokens, idx, options, env, self) => self.renderToken(tokens, idx, options))
md.renderer.rules.link_open = (tokens, idx, options, env, self) => {
  tokens[idx].attrSet('target', '_blank')
  tokens[idx].attrSet('rel', 'noopener noreferrer')
  return defaultLinkOpen(tokens, idx, options, env, self)
}

/** 把 Markdown 文本渲染为 HTML（配合 v-html 使用；样式见全局 .markdown-body） */
export const renderMarkdown = (text) => md.render(text || '')
