<script setup lang="ts">
import { computed } from 'vue'
import DOMPurify from 'dompurify'
import MarkdownIt from 'markdown-it'

const props = defineProps<{ content: string }>()

const markdown = new MarkdownIt({ html: false, linkify: true, breaks: true })
const defaultLinkOpen = markdown.renderer.rules.link_open
markdown.renderer.rules.link_open = (tokens, index, options, environment, self) => {
  const token = tokens[index]
  const hrefIndex = token?.attrIndex('href') ?? -1
  const hrefValue = hrefIndex >= 0 ? token?.attrs?.[hrefIndex]?.[1] : null
  const href = typeof hrefValue === 'string' ? hrefValue : null
  if (href && !isAllowedLink(href)) {
    token?.attrSet('href', '#')
  }
  token?.attrSet('rel', 'noopener noreferrer')
  return defaultLinkOpen
    ? defaultLinkOpen(tokens, index, options, environment, self)
    : self.renderToken(tokens, index, options)
}

const safeHtml = computed(() =>
  DOMPurify.sanitize(markdown.render(props.content), {
    USE_PROFILES: { html: true },
    FORBID_TAGS: ['style', 'iframe', 'object', 'embed'],
    FORBID_ATTR: ['style'],
  }),
)

/** 仅允许普通 Web 链接、锚点和站内相对路径。 */
function isAllowedLink(value: string): boolean {
  return /^(https?:\/\/|\/|#)/i.test(value)
}
</script>

<template>
  <!-- 内容已经过 html=false Markdown 解析和 DOMPurify 双重净化。 -->
  <!-- eslint-disable-next-line vue/no-v-html -- 冻结的双重净化链路需要渲染净化后的 Markdown。 -->
  <div class="safe-markdown" v-html="safeHtml" />
</template>
