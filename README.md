# VideoAgent — 视频/音频 + 多 Agent 问答

Java 主语言实现的长视频问答项目：上传视频 → ffmpeg 提音频 → faster-whisper 转写（云端兜底）→ 抽关键帧 + Qwen-VL 视觉描述 → 图文统一向量化（PGVector）→ 问答时 harness 规则路由 + LangChain4j 多 Agent 编排，回答带时间戳引用，前端播放器可跳转。

## 四大亮点

| 亮点 | 说明 | 落地点 |
|---|---|---|
| A 时间轴感知问答 | 回答带 `[mm:ss]` 引用，可点击跳转视频片段 | `business/agent/AnswerReferenceParser` + `static/qa.html` |
| B 多模态语义索引 | 转写文本块(TEXT) + 帧描述块(FRAME) 统一向量空间联合检索 + 时间聚类 | `middleware/rag/` + PGVector `video_chunk` |
| C 规则+语义混合编排 | harness 确定性规则层（毫秒/可解释）+ Agent 语义编排层（工具协作） | `business/harness/RuleRouter` + `business/agent/AgentRuntime` |
| D 可观测任务流水线 | Kafka 事件驱动 + 状态机 + ASR 双通道降级 + 阶段耗时统计 | `middleware/task/TaskService` + `static/upload.html` |

## 架构

```
前端(静态页 upload.html / qa.html)
  ↓ REST
接入层 access          Upload/Qa/Task/Video Controller、全局异常
业务层 business         harness(QaFacade+RuleRouter) · agent(Planner/Tool/Answer/AgentRuntime)
                        video(VideoPipelineService/Transcription/FrameExtract)
中间件 middleware        event(Kafka) · task(状态机/耗时/重试) · rag(切块/Embedding/检索) · llm(ASR/视觉/DeepSeek)
存储层 storage          MySQL(业务) · PostgreSQL+pgvector(向量) · Redis(进度/降级标记) · 本地磁盘 data/
```

处理流水线：`UPLOADED → TRANSCRIBING → FRAMING → EMBEDDING → READY | FAILED`，每阶段由 Kafka 事件驱动，失败记录重试次数支持手动重投。

## 快速开始

### 1. 启动依赖（MySQL / PostgreSQL+pgvector / Kafka / Redis）

```bash
docker compose up -d
```

### 2. 配置密钥（环境变量）

| 变量 | 用途 |
|---|---|
| `DEEPSEEK_API_KEY` | 主问答 LLM（必填） |
| `DASHSCOPE_API_KEY` | 视觉描述 + Embedding（必填） |
| `CLOUD_ASR_ENABLED` | 云端 ASR 兜底开关（可选，默认 false） |

### 3. 本地 ASR 环境（可选，默认本地通道）

```bash
pip install faster-whisper
# 首次运行会自动下载模型（默认 small；GPU 可把 application.yml 中 model-size 改为 large-v3、device 改为 cuda）
```

### 4. 启动应用

```bash
mvn spring-boot:run
```

- 前端首页：http://localhost:8080/
- Swagger：http://localhost:8080/swagger-ui.html

### 5. 体验流程

1. 上传一个 ≤10 分钟的视频，在「上传」页观察流水线状态流转与阶段耗时
2. 状态变为「全部完成」后进入「问答」页
3. 提问（如「视频讲了什么」「画面上有哪些图表」），回答中 `[mm:ss]` 可点击跳转

## 验证

- 语音类问题 → 回答带 `[mm:ss]` 引用（语音来源）
- 画面类问题 → 引用帧描述（画面来源）
- 停掉本地 whisper 后重新上传 → 自动降级云端 ASR，任务页显示降级标记
- 上传损坏文件 → 任务 FAILED + 错误信息 + 手动重试
