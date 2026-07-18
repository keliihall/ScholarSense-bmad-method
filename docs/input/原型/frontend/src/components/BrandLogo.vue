<script setup lang="ts">
import { useId } from 'vue';

/**
 * 学林知微 品牌标识。
 * 寓意：幼苗（学林 · 成长关怀）自涟漪（观澜 · 观测数据细波）中生长，
 * 顶端光点代表「知微」——捕捉到的细微信号。温暖而非监控。
 */
withDefaults(
  defineProps<{
    /** 图标边长(px) */
    size?: number;
    /** 是否显示文字（学林知微） */
    wordmark?: boolean;
    /** 是否显示副标题 */
    sub?: boolean;
    /** 深色背景：文字用浅色 */
    dark?: boolean;
  }>(),
  { size: 34, wordmark: false, sub: false, dark: false },
);

// 渐变 id 唯一化，避免多实例冲突
const gid = `ss-logo-${useId()}`;
</script>

<template>
  <div class="brand-logo" :class="{ dark }">
    <svg
      class="mark"
      :width="size"
      :height="size"
      viewBox="0 0 48 48"
      xmlns="http://www.w3.org/2000/svg"
      role="img"
      aria-label="学林知微"
    >
      <defs>
        <linearGradient :id="gid" x1="0" y1="0" x2="1" y2="1">
          <stop offset="0" stop-color="#1b8f7a" />
          <stop offset="1" stop-color="#29d3c2" />
        </linearGradient>
      </defs>
      <rect x="2.5" y="2.5" width="43" height="43" rx="12.5" :fill="`url(#${gid})`" />
      <rect
        x="2.5"
        y="2.5"
        width="43"
        height="43"
        rx="12.5"
        fill="none"
        stroke="#ffffff"
        stroke-opacity="0.18"
      />
      <!-- 涟漪 · 观澜 -->
      <path
        d="M15 35.4 Q24 39.8 33 35.4"
        fill="none"
        stroke="#ffffff"
        stroke-width="2.1"
        stroke-linecap="round"
        opacity="0.95"
      />
      <path
        d="M12.6 38.8 Q24 44.2 35.4 38.8"
        fill="none"
        stroke="#ffffff"
        stroke-width="1.8"
        stroke-linecap="round"
        opacity="0.5"
      />
      <!-- 茎 -->
      <path
        d="M24 34 L24 23"
        fill="none"
        stroke="#ffffff"
        stroke-width="2.3"
        stroke-linecap="round"
      />
      <!-- 双叶 · 学林·成长 -->
      <path d="M24 24 C18.5 23 14.8 19 14.6 12.8 C20.6 13.4 24.2 17.4 24 24 Z" fill="#ffffff" />
      <path d="M24 24 C29.5 23 33.2 19 33.4 12.8 C27.4 13.4 23.8 17.4 24 24 Z" fill="#ffffff" />
      <!-- 芽尖光点 · 知微 -->
      <circle cx="24" cy="10.6" r="2.05" fill="#ffffff" />
    </svg>

    <div v-if="wordmark" class="words">
      <div class="name">学林知微</div>
      <div v-if="sub" class="sub">观澜智核 · 学生成长关怀</div>
    </div>
  </div>
</template>

<style scoped>
.brand-logo {
  display: inline-flex;
  align-items: center;
  gap: 10px;
}
.mark {
  flex-shrink: 0;
  display: block;
  filter: drop-shadow(0 2px 6px rgba(20, 50, 70, 0.18));
}
.words {
  line-height: 1.15;
}
.name {
  font-weight: 800;
  font-size: 17px;
  letter-spacing: 1px;
  color: var(--ss-text);
}
.sub {
  font-size: 11px;
  color: var(--ss-text-muted);
  margin-top: 2px;
}
.brand-logo.dark .name {
  color: #ffffff;
}
.brand-logo.dark .sub {
  color: #7fa8cf;
}
</style>
