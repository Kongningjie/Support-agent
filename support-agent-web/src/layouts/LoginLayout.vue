<template>
  <main class="login-layout">
    <section class="login-layout__brand" aria-label="产品介绍">
      <div class="brand-mark" aria-hidden="true">SA</div>
      <p class="eyebrow">企业内部技术支持</p>
      <h1>Support Agent</h1>
      <p>通过统一知识、可信引用与人工工单闭环，让技术支持过程更清晰、更可追溯。</p>
    </section>
    <section class="login-layout__content">
      <slot />
    </section>
  </main>
</template>
