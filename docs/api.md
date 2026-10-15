# API 与本机开发

页面与后端同源。开发时后端8093，前端 Vite 把 `/api` 代理到后端。正式构建后一个 JAR 提供页面和 API。

所有写操作携带 `GET /api/session` 返回的 CSRF header/token。登录使用表单POST，Cookie由浏览器保管；页面不持久保存密码。错误使用 `{error:{code,message}}`，不暴露本机路径、SQL和模型原始错误。

| 方法与路径 | 作用 |
| --- | --- |
| GET /api/session | 当前用户、CSRF及模型是否已配置 |
| POST /api/login | username/password表单登录 |
| POST /api/logout | 退出当前会话 |
| GET /api/spaces | 当前用户可访问的资料库 |
| GET /api/spaces/{sid}/documents | 文档、版本、分段数与索引模式 |
| POST /api/spaces/{sid}/documents | multipart file导入，成功201 |
| PUT /api/spaces/{sid}/documents/{did} | multipart file/version替换 |
| DELETE /api/spaces/{sid}/documents/{did}?version=1 | 删除当前版本，成功204 |
| GET /api/spaces/{sid}/search?q=问题&mode=hybrid&limit=5 | 关键词/向量/混合检索与来源 |
| GET /api/spaces/{sid}/conversations | 当前用户的会话列表 |
| POST /api/spaces/{sid}/conversations | JSON title创建会话 |
| GET /api/spaces/{sid}/conversations/{cid} | 会话历史、来源与工具轨迹 |
| POST /api/spaces/{sid}/conversations/{cid}/messages | question/mode/searchMode发起问答 |

问答mode取rag或agent；searchMode取bm25、vector或hybrid。检索无匹配时显式返回依据不足；模型错误返回502，不返回固定成功答案。未配置必要模型时返回503。

401需要登录，403越权或来源校验失败，404资源不存在，409资料/会话已变化，413文件过大，422输入不合法，429并发繁忙。遇到409需重载并核对，不自动重复写入。
