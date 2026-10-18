<script setup>
import { computed } from 'vue'

/* 像素小人头像。四个工位各一张脸,靠头发/衣服颜色 + 一件配饰区分:
   本=墨镜(主管)、译=耳机(开会)、衡=眼镜(量人)、绘=贝雷帽(画画)。
   16x16 网格 + crispEdges,缩放时不会糊成圆角。 */
const props = defineProps({
  who: { type: String, default: 'ben' },
  size: { type: [Number, String], default: 52 }
})

const PALETTE = {
  ben: { hair: '#7fae5a', cloth: '#2f4a3c' },
  meeting: { hair: '#6f9fc4', cloth: '#3f5f7a' },
  profile: { hair: '#d99a55', cloth: '#8a5a2b' },
  planning: { hair: '#a07fc0', cloth: '#5b4a6e' }
}
const skin = '#f2c9a0'
const ink = '#22342c'
const p = computed(() => PALETTE[props.who] || PALETTE.ben)
</script>

<template>
  <svg
    class="of-avatar" :class="`of-avatar-${who}`" :viewBox="'0 0 16 16'"
    :width="size" :height="size" shape-rendering="crispEdges" aria-hidden="true" focusable="false"
  >
    <!-- 头发顶 -->
    <rect x="4" y="1" width="8" height="2" :fill="p.hair" />
    <rect x="3" y="2" width="1" height="5" :fill="p.hair" />
    <rect x="12" y="2" width="1" height="5" :fill="p.hair" />
    <!-- 脸 -->
    <rect x="4" y="3" width="8" height="6" :fill="skin" />
    <!-- 耳朵 -->
    <rect x="3" y="5" width="1" height="2" :fill="skin" />
    <rect x="12" y="5" width="1" height="2" :fill="skin" />
    <!-- 眼睛 -->
    <rect x="5" y="5" width="1" height="2" :fill="ink" />
    <rect x="10" y="5" width="1" height="2" :fill="ink" />
    <!-- 嘴 -->
    <rect x="7" y="8" width="2" height="1" fill="#c9664a" />
    <!-- 脖子 + 衣服 -->
    <rect x="7" y="9" width="2" height="1" :fill="skin" />
    <rect x="3" y="10" width="10" height="6" :fill="p.cloth" />
    <rect x="6" y="10" width="4" height="1" fill="#f7f4e9" />

    <!-- 配饰 -->
    <template v-if="who === 'ben'">
      <rect x="4" y="4" width="8" height="2" :fill="ink" />
      <rect x="5" y="4" width="1" height="1" fill="#b7d3e8" />
    </template>
    <template v-else-if="who === 'meeting'">
      <rect x="2" y="4" width="2" height="5" :fill="ink" />
      <rect x="12" y="4" width="2" height="5" :fill="ink" />
      <rect x="4" y="0" width="8" height="1" :fill="ink" />
    </template>
    <template v-else-if="who === 'profile'">
      <rect x="4" y="5" width="3" height="1" fill="#f7f4e9" />
      <rect x="9" y="5" width="3" height="1" fill="#f7f4e9" />
      <rect x="7" y="5" width="1" height="1" :fill="ink" />
    </template>
    <template v-else>
      <rect x="3" y="0" width="10" height="2" :fill="p.cloth" />
      <rect x="12" y="0" width="2" height="1" :fill="p.cloth" />
    </template>
  </svg>
</template>
