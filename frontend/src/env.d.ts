/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_SCHOLARSENSE_PUBLIC_BASE_PATH?: '/scholarsense/';
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
