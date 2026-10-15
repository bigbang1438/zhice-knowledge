# 知策｜团队知识库与问答助手

把团队制度、产品手册和服务说明放进同一个资料库，提出问题后查看回答、原文依据和工具执行过程。适合作为 Java Web 与 AI 应用工程的个人实践作品。

这是 **AI 辅助开发的个人学习项目**。演示账号、企业名称和资料均为虚构，不代表实习、商业交付或真实客户使用。模型接入与运行结果分开记录；不会把固定检索结果当成模型成功回答。

## 可以做什么

- 导入 UTF-8 Markdown、TXT 和可提取文字的 PDF，保留文件名、PDF 页码及提取文本行号。
- 用关键词、向量或混合模式检索。点击来源可核对原文。
- 选择“直接问答”或“分步查找”：前者先检索后回答，后者由模型调用受限工具查找和阅读片段。
- 保存当前用户、当前资料库中的会话；问答期间资料发生更新时，拦截过期结果。
- 更新文档时先完成解析与向量生成，再一次提交新版本；失败时保留旧资料。删除后新检索不再返回被删片段。

## 技术与边界

Java 21、Spring Boot MVC / Security / JDBC、H2、Apache PDFBox、Vue 3、Vite、Ollama Chat / Embed。此项目直接调用模型 HTTP 协议，**没有使用 Spring AI 或 LangChain**。

向量保存在业务数据库中，Java 对受限规模的片段执行余弦计算，BM25 与向量排名使用 RRF 合并。这是小资料库实现，没有宣称专用向量数据库、海量检索吞吐或通用问答准确率。单个文件最多 2 MiB、PDF 最多 100 页、每个资料库最多 80 份文档及 2,000 个片段。扫描 PDF 需要额外 OCR，本项目明确拒绝无可提取文字的文件。

引用编号检查只能验证来源关联，不能自动证明答案每句话都受来源支持。请核对重要结论的原文。模型更换后需重新导入文档生成对应向量；同维度不同模型的向量不应混用。

## 运行

需要 Java 21、Maven 3.6.3+、Node.js 22.12+。先构建页面，再构建 Java：

```powershell
cd frontend
npm ci
npm test
npm run build
cd ..
mvn verify
```

设置本机演示密码并启动：

```powershell
$env:ZHICE_DEMO_PASSWORD = Read-Host '设置至少12字符的本机演示密码'
java -jar target/zhice-knowledge-1.0.0.jar
```

打开 http://127.0.0.1:8093 。虚构账号 `demo_anna` 与 `demo_ben` 使用首次初始化时的密码，各自只能访问分配给自己的资料库。密码以 BCrypt 哈希保存。修改环境变量不会重置已有数据库账号密码。

无模型时可以导入文本并使用关键词检索，页面会明确显示尚未配置模型。问答不会降级成伪造模型响应。

### 启用本地模型

从 [Ollama 官方渠道](https://ollama.com/)安装服务，准备聊天和嵌入模型，例如：

```powershell
ollama pull qwen3:4b
ollama pull bge-m3
```

模型文件需要单独下载，并遵循各自许可。默认 Ollama 地址为 `127.0.0.1:11434`；另开一个启动应用的终端：

```powershell
$env:ZHICE_DEMO_PASSWORD = Read-Host '输入已有本机演示密码'
$env:ZHICE_OLLAMA_URL = 'http://127.0.0.1:11434'
$env:ZHICE_CHAT_MODEL = 'qwen3:4b'
$env:ZHICE_EMBED_MODEL = 'bge-m3'
java -jar target/zhice-knowledge-1.0.0.jar
```

`.env.example` 是配置说明，应用不会自动读取 `.env`。模型参数只从服务端环境读取，网页不能指定任意模型地址。配置存在不代表模型服务健康；超时和不合格输出会显示失败。

默认不接受把分析标记混进正文的模型响应。只有明确核对过旧版导入模板会在正文中打开分析段时，才设置 `ZHICE_THOUGHT_IN_CONTENT=true`；此适配要求完整结束标记，丢弃分析段后仅保留最终答案，截断响应会失败。优先使用正确分离分析与答案的模型适配器。

上传 `examples/` 中的原创资料，尝试：“团队周会在什么时候？”“任务删除后能恢复多久？”再查看来源和会话历史。检索评测与真实模型案例见 [验证记录](docs/verification.md)。

## 了解实现

- [架构、状态与取舍](docs/architecture.md)
- [API 与本机开发](docs/api.md)
- [按代码学习与面试讲解](docs/walkthrough.md)
- [固定检索问题集](evaluation/questions.json)

服务默认只监听本机。账号管理、共享成员邀请、HTTPS、公网部署与大规模异步索引不在当前实现范围。MIT 许可适用于原创代码，用户资料和模型保留其原有权利。
