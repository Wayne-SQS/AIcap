<script setup>
import { useToast } from '@/composables/useToast'
const { state } = useToast()
</script>

<template>
  <div class="toast" id="toast" role="status" aria-live="polite" :class="{ show: state.show }">{{ state.text }}</div>
</template>
