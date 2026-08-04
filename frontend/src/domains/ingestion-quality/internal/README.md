# ingestion-quality internals

Files placed here are private to this domain. Other domains import only the parent `index.ts` public entry.

`DataSourceCatalogView.vue` 是 R6 数据责任人的目录列表、版本、源合同、校验错误和发布表面。
所有响应只保存在 Vue ref/TanStack Query 的零缓存边界；注销、换号、会话版本变化或撤权会先中止请求并清空结果。
