import { defineStore } from 'pinia';
import { ref } from 'vue';


export const useConnectivityStore = defineStore('connectivity', () => {
  const online = ref(true);

  function setOnline(value: boolean): void {
    online.value = value;
  }

  return { online, setOnline };
});
