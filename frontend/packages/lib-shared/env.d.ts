interface ImportMetaEnv {
  readonly VITE_API_BASE_URL: string;
  readonly VITE_DEV_DOMAIN: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}

// vue 的 Ref 按 mobile/web 的 auto-import 约定在本包全局可用
type Ref<T = any> = import('vue').Ref<T>;
