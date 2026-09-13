<script setup>
defineProps({ size: { type: Number, default: 40 } })
</script>
<template>
  <svg :width="size" :height="size" viewBox="0 0 48 48">
    <circle cx="34" cy="12" r="6" fill="#E8853D"/>
    <path d="M4 38 L18 16 L28 30 L34 22 L44 38 Z" fill="#3E7C59"/>
  </svg>
</template>
