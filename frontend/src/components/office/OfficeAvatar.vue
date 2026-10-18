<script setup>
import { computed } from 'vue'
const props = defineProps({ who: { type: String, default: 'ben' }, size: { type: [Number, String], default: 72 }, busy: Boolean })
const palette = { ben: { hair: '#4d5040', shirt: '#6c8c65', pants: '#3d5149' }, meeting: { hair: '#5b4940', shirt: '#8aafba', pants: '#4e6372' }, profile: { hair: '#8d593b', shirt: '#d2a062', pants: '#615444' }, planning: { hair: '#534254', shirt: '#a99bb7', pants: '#5b5367' } }
const p = computed(() => palette[props.who] || palette.ben)
</script>
<template>
  <svg class="office-person" :class="{ 'is-working': busy }" viewBox="0 0 24 32" :width="size" :height="Number(size) * 4 / 3" shape-rendering="crispEdges" aria-hidden="true" focusable="false">
    <path d="M5 30h15v2H5z" fill="#56503d" opacity=".18" />
    <g class="person-body">
      <path d="M7 21h10v8H7z" :fill="p.pants" /><path d="M10 24h3v6h-3z" fill="#f1debb" />
      <path d="M6 29h5v2H6zm7 0h6v2h-6z" fill="#344137" />
      <path d="M6 15h12v9H6zM4 17h2v6H4zm14 0h2v6h-2z" :fill="p.shirt" />
      <path d="M5 22h3v3H5zm12 0h3v3h-3zM10 13h4v4h-4z" fill="#e9ba91" />
      <path d="M7 17h2v5H7z" fill="#fff9e8" opacity=".38" /><path d="M11 17h3v2h-3z" fill="#fff9e8" />
      <path d="M6 4h12v10H6zM4 7h2v5H4zm14 0h2v5h-2z" fill="#f0cba6" />
      <path d="M7 2h10v2h3v5h-3V6H9v2H5V4h2z" :fill="p.hair" /><path d="M5 7h2v3H5z" :fill="p.hair" />
      <path d="M8 9h2v2H8zm6 0h2v2h-2z" fill="#344137" /><path d="M11 13h3v1h-3z" fill="#bd7d67" />
      <path d="M7 12h2v1H7zm9 0h2v1h-2z" fill="#df9d89" />
      <template v-if="who === 'ben'"><path d="M7 8h4v3H7zm6 0h4v3h-4zm-2 1h2v1h-2z" fill="#3c5041" /><path d="M8 8h2v1H8zm6 0h2v1h-2z" fill="#b7d0bc" /></template>
      <template v-else-if="who === 'meeting'"><path d="M3 7h3v6H3zm15 0h3v6h-3zM6 2h12v2H6z" fill="#3c5559" /><path d="M19 12v3h-5v-1h4v-2z" fill="#3c5559" /></template>
      <template v-else-if="who === 'profile'"><path d="M7 8h4v4H7zm6 0h4v4h-4z" fill="#535548" /><path d="M8 9h2v2H8zm6 0h2v2h-2z" fill="#e4e9c6" /><path d="M11 9h2v1h-2z" fill="#535548" /></template>
      <template v-else><path d="M5 2h15v3H5zM8 0h8v2H8z" fill="#7c6c88" /><path d="M5 4h5v2H5z" fill="#63566b" /><path d="M17 19h2v4h-2z" fill="#ebc66d" /></template>
    </g>
  </svg>
</template>
<style scoped>
.office-person{overflow:visible;image-rendering:pixelated;flex-shrink:0}.person-body{transform-origin:center bottom;animation:person-idle 4s steps(2,end) infinite}.is-working .person-body{animation:person-work .7s steps(2,end) infinite}@keyframes person-idle{0%,70%,100%{transform:translateY(0)}75%,95%{transform:translateY(-1px)}}@keyframes person-work{50%{transform:translate(1px,-1px)}}@media(prefers-reduced-motion:reduce){.person-body,.is-working .person-body{animation:none}}
</style>
