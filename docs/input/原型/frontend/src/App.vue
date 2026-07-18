<script setup lang="ts">
import { computed } from 'vue';
import { useRoute } from 'vue-router';
import AdminLayout from '@/layouts/AdminLayout.vue';
import CockpitLayout from '@/layouts/CockpitLayout.vue';

const route = useRoute();
const layout = computed(() => route.meta.layout ?? 'admin');
</script>

<template>
  <CockpitLayout v-if="layout === 'cockpit'">
    <router-view />
  </CockpitLayout>
  <router-view v-else-if="layout === 'blank'" />
  <AdminLayout v-else>
    <router-view />
  </AdminLayout>
</template>
