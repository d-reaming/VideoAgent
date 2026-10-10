# VideoAgent 架构全貌（权威目录导航）

> 本文是仓库的权威架构文档，后续任何迭代都以本文为基准目录。
> 若代码与本文描述不一致，以代码为准并**同步修订本文**，避免「文档与代码漂移」。

---

## 1. 项目定位与技术栈

**定位**：视频/音频 → ASR/OCR/画面语义 → 向量 + 关键词双路 RAG → 多 Agent（意图识别者 + 执行者 + 监督者三角色）问答的单体 Spring Boot 服务，前端为静态 HTML（`src/main/resources/static`）。

| 领域 | 选型 |
|---|---|
| 运行时 | Spring Boot 3.3.5，Java 17 |
| Agent 编排 | LangChain4j 1.19.0（OpenAI 兼容接入 DeepSeek chat），Qwen-VL 做画面描述/视觉问答 |
| 消息/异步 | Kafka（视频处理管道事件驱动），`max.poll.interval.ms=60000` |
| 缓存/锁/限流 | Redis + Redisson（分布式锁看门狗、令牌桶/意图独立限流） |
| 存储 | MySQL（业务源真相）、PostgreSQL + pgvector（向量 HNSW）、PG `video_keyword`（关键词倒排）、ES/Neo4j 预留未启用 |
| 音视处理 | ffmpeg（抽帧/探时长）、本地 Python worker（Whisper 转写、RapidOCR 字幕）、可选云端 ASR 降级 |

---

## 2. 四层目录树与模块地图

```
src/main/java/com/videoagent/
├── access/          # 接入层：HTTP 路由、认证、DTO、异常
│   ├── controller/  #   Auth / Video / Upload / ChunkUpload / Qa / Task / Metrics
│   ├── auth/        #   AuthInterceptor, UserContext(ThreadLocal)
│   ├── dto/         #   QaRequest/QaResponse/VideoDetail/上传 DTO/...
│   └── exception/   #   BusinessException, GlobalExceptionHandler (401/403/404/429/500 映射)
├── business/        # 业务层：问答编排、视频管道、意图/多 Agent、证据
│   ├── harness/     #   QaFacade(问答门面), RuleRouter, Backend, QuestionMode, ChatSessionService
│   ├── agent/       #   IntentClassifier, PlannerAgent, AgentRuntime, ToolAgent, ReviewerAgent,
│   │                #   AnswerAgent, AnswerReferenceParser, Plan, AgentBudget, 各 Tool
│   ├── strategy/    #   SummaryStrategyService (map-reduce 全片归纳)
│   ├── qa/          #   QaCheckpointService (WS3 checkpoint)
│   ├── evidence/    #   ReferenceVerifier, Evidence/EvidenceType/Confidence/Assertion
│   └── video/       #   VideoPipelineService, TranscriptionService, FrameExtractService,
│                    #   CorrectionService, NormalizationService, Indexing 触发、Cleanup,
│                    #   PipelineRecoverService, VideoAccessService, Chunk/Upload/Deletion
├── middleware/      # 中间件层：事件、锁、任务、RAG、缓存、限流、LLM 客户端、JWT
│   ├── event/       #   Topics, EventPublisher, VideoEvent, KafkaTopicConfig, TaskSseRegistry
│   ├── lock/        #   PipelineLockService (Redisson 看门狗锁)
│   ├── task/        #   TaskService(状态机+失败台账+死信), TaskRetryScheduler(自动重试)
│   ├── metrics/     #   PipelineMetricsService (锁等待/阶段耗时/限流耗时)
│   ├── rag/         #   VectorSearchService, IndexingService, ChunkingService, EmbeddingService,
│   │                #   KeywordIndexService, KeywordBackfillService, KeyWordTokenizer, Chunk
│   ├── cache/       #   SessionCacheService (cache-through)
│   ├── llm/         #   AsrProvider/LocalWhisperAsrProvider/CloudAsrProvider, VisionClient, OcrClient
│   ├── rate/        #   RateLimitService (每用户 Q&A/模型双令牌桶)
│   ├── jwt/         #   JwtUtil
│   └── util/        #   FfmpegCommandRunner
├── storage/         # 存储层
│   ├── entity/      #   Video, TaskRecord, TaskFailure, TranscriptSegment, FrameRecord, VideoOcr,
│   │                #   ChatSession, ChatMessage, QaCheckpoint, User (+ enums: VideoStatus/TaskStage/CleanupStatus)
│   ├── mapper/      #   MyBatis-Plus BaseMapper（MySQL）
│   └── repository/  #   VectorRepository(pgvector), KeywordRepository(video_keyword 倒排)
└── config/          # 配置 Bean：AgentConfig(ChatModel+线程池), WebConfig, Pg/Storage/Ocr/Asr/Ffmpeg/Llm
```

---

## 3. 主链路 A：视频上传 → 处理管道 → READY → SSE

```
分片上传(WS4: ChunkUploadController init/upload/complete/progress)
   │  (或整包 UploadController /upload)
   ▼
ChunkUploadService 校验（时长>0、≤3h、含视频流 → 拒绝损坏文件）
   ▼  VideoUploadService 落库 video(状态 UPLOADED) + TaskService.createTask
   ▼  eventPublisher.publish(VIDEO_UPLOADED, videoId)
Kafka 管道（VideoPipelineService 消费，只派发到 pipelineExecutor，不阻塞 poll）：
   VIDEO_UPLOADED → [TRANSCRIBING]    TranscriptionService (本地Whisper/server 或云端降级)
   VIDEO_TRANSCRIBED → [FRAMING]       FrameExtractService (ffmpeg 抽帧 + Qwen-VL 描述 + OCR 三通道)
   VIDEO_FRAMED → [EMBEDDING]          IndexingService (分块→embedding 入 pgvector + 关键词倒排, indexed_seq 断点续传)
   VIDEO_READY → complete() (task DONE + video READY)
  每阶段：pipelineLock.tryRun("video:"+id) 看门狗锁 + shouldRun 幂等守卫；失败→TaskService.fail()
  每阶段：TaskSseRegistry 向 /api/tasks/video/{videoId}/sse 推送 TaskProgressDto
```

要点：
- **长任务隔离**：Kafka 消费者线程只做事件派发，转写/抽帧/向量化在独立 `pipelineExecutor`，避免阻塞 poll 触发 rebalance。
- **幂等防重**：同一 video 并发/重复事件，仅"持锁 + 状态==期望"者执行，其余跳过。
- **崩溃恢复（PipelineRecoverService，启动时）**：TRANSCRIBING/FRAMING → 置 FAILED 待重试；EMBEDDING → 从 `indexed_seq` 续跑补批次后再补发 READY；清理遗留续跑。软删视频不参与恢复。
- **删除/软删**：软删 + CleanupService 异步幂等物理清理（子表/向量/磁盘文件/记录）。

### 管道重试与死信（可靠性补强，见 §6.1）

---

## 4. 主链路 B：问答 → 意图路由 → 三角色 → 校验 → checkpoint

```
POST /api/qa (QaController)  →  QaFacade.ask
 1. VideoAccessService.owned(videoId) 多租户校验
 2. RateLimitService.tryQa(uid) 每用户限流 (5/s)
 3. chatSessionService.getOrCreate + historyText(近6轮) + followUpHint(追问回指检测)
 4. RuleRouter.route 决策：
    - 视频范围：request.videoId ?? session.videoId
    - IntentClassifier.classify(question, uid)  ← LLM 意图识别出 QuestionMode{SUMMARY|SPOT|VISUAL|ANALYSIS|GENERAL}
        （独立限流：每用户20/min + 全局60/min；空输入/模型异常/非法返回/限流 → 安全回退 GENERAL）
    - 后端：ANALYSIS → AGENT；其余 → FAST；摘要模式需单视频语境否则降 GENERAL
    - topK 按模式调整
 5. WS3 Checkpoint：SUMMARY/AGENT 可缓存路径先查 (videoId, goalDigest) DONE 结果，命中复用
 6. 通道执行：
    SUMMARY      → SummaryStrategyService（map-reduce 分块归纳全片）
    GENERAL/SPOT/VISUAL → FAST：VectorSearchService.search + AnswerAgent 单轮
    ANALYSIS     → AGENT：
        PlannerAgent → Plan(结构化检索规划)
        AgentRuntime.run：工具循环(ragSearch/videoMeta/timeline/fullText/globalContext)
                           → ReviewerAgent 验收(reviewer 结构化反馈)
                           → 未 passes 定向补证后回炉（最多 MAX_REVISE_ROUNDS=2，共用预算）
 7. AnswerReferenceParser 解析 [mm:ss] 引用 → ReferenceVerifier 确定性校验（WS1，命中真实片段区间/文本）
 8. AGENT 通道有失实引用 → refineCitations 定向修正后重解析
 9. WS3 终态落库(QaCheckpoint DONE) + 消息落库(会话记忆)
```

`QuestionMode → Backend → 回答策略` 对照：

| mode | backend | 策略 | 代表性问题 |
|---|---|---|---|
| SUMMARY | FAST | map-reduce 全片归纳 | “讲了什么/总结/大意” |
| SPOT | FAST | 向量检索 + 单轮分级 | “某处说了什么/原话/何时” |
| VISUAL | FAST | 检索帧描述/OCR加权 | “画面里/图表/字幕” |
| ANALYSIS | AGENT | Planner+执行+Reviewer 回炉 | “对比/原因/影响/分析” |
| GENERAL | FAST | 全局泛问回退 | 无法归类的泛问 |

---

## 5. 关键机制

- **幂等守卫**：`shouldRun(videoId, expectedStatus)` — 状态不等于期望即视为重放跳过。
- **Redisson 看门狗锁**：`PipelineLockService.tryRun(key[, waitMs], act)`。不传 leaseTime，由看门狗自动续期，长耗时阶段不过期；进程崩溃后看门狗停续期自动释放。锁 key：`video-agent:lock:<...>`（`video:<id>` / 意图限流 / 删除等复用）。
- **崩溃恢复**：PipelineRecoverService（见 §3），embedding 断点续传用 `indexed_seq`。
- **缓存**：TaskService 管道状态 Redis TTL 1h（进度高频读）；ChatSession cache-through TTL 2h；QaCheckpoint 结果留档 MySQL 源真相。
- **限流**：RateLimitService 每用户 Q&A 5/s、模型 8/s（双令牌桶）；IntentClassifier 独立配额（用户 20/min + 全局 60/min），不挤占答案生成配额。
- **多库 RAG**：pgvector HNSW 向量 + PG `video_keyword` 倒排，双路融合；写入批量处理 + `indexed_seq` 断点续传，幂等 upsert。
- **证据湖统一 schema**：`EvidenceType{AUDIO/VISION/RETRIEVED/META/OCR}` × `Confidence{DIRECT/INFERENCE/UNSURE}` × Evidence × Assertion，Agent 工具与校验端统一消费。
- **OCR 三通道**：字幕去噪（min-confidence + 去垃圾字符）→ 作为画面文字权威，可校正同时间戳 ASR 专名乱码（CorrectedService/NormalizationService）。
- **云端 ASR 降级**：本地 Whisper 失败/未启用时回退 CloudAsrProvider。
- **分片断点续传（WS4）**：chunk init/upload/complete/progress，合并前校验缺失分片。
- **SSE 进度（WS5）**：`/api/tasks/video/{videoId}/sse`，客户端指数退避重连。
- **Agent 预算硬上限（见 §6.2）**：防工具循环/回炉无界消费。
- **判定性引用校验（WS1）**：AVOID 编造引用——校验真实片段区间、必要时 bigram 文本匹配；SUMMARY 放宽为仅时间区间覆盖。

---

## 6. 可靠性补强（本次新增）

### 6.1 管道自动重试 + 死信台账

- 表 `task_failure`（MySQL）：每次阶段失败落一行 `(video_id, stage, error_msg, retryable, attempt, dead_at, created_at)`。
- `TaskService.fail()`：置 `task.pending FAILED` + `retryCount++` + 落账一行；`attempt >= AUTO_RETRY_MAX(3)` 时转终态 `DEAD` 并给该尝试落 `dead_at`（死信账）。视频保持 FAILED。
- `TaskRetryScheduler`（`@Scheduled fixedDelay=30s`，可配 `video-agent.task.retry.interval-ms`）：
  - 扫描 `task_record.status=FAILED`；
  - `retryCount >= AUTO_RETRY_MAX` → 防御性 `markDead`；
  - 未到上限且退避到期（1次→30s、2次→60s、≥3次→120s，按 `updated_at` 判断）→ 复用 `TaskService.retry()` 的阶段→topic 映射与锁契约自动重投。
- 映射 helper：`stageToRepublishTopic(stage)` / `stageToExpectedBefore(stage)`（手动与自动重试共用）。
- 死后不再自动重试；手动重试 API（`/api/tasks/video/{id}/retry`）仅对 FAILED（非 DEAD）生效。

### 6.2 Agent 预算控制

- `business/agent/AgentBudget`（`video-agent.agent.budget.*`）：`max-llm-calls`(默认12) + `max-tokens`(累计 input+output, 默认20000) + `deadline-ms`(默认120000)。
- `AgentRuntime`：单次问答一个预算；工具循环每轮 `consumeLlmCall()` + 从 `ChatResponse.tokenUsage()` 累加 token；任一 `isExceeded()` 即中断返回当前最佳；Reviewer 回炉共用同一预算，耗尽则接受当前最佳，杜绝无限回炉。
- 预算口径随日志暴露（`[runtime] … budget: calls=… token=… 耗时=…`），便于定位。

---

## 7. DB schema 概览

**MySQL（业务源真相，`db/schema-mysql.sql`，docker-entrypoint 初始化）**：

| 表 | 职责 |
|---|---|
| `video` | 视频元数据/状态机(UPLOADED→…→READY/FAILED)/软删 |
| `task_record` | 处理任务：阶段/状态(RUNNING/DONE/FAILED/DEAD)/retry_count/耗时 JSON/错误 |
| `task_failure` | 失败/死信台账（本次新增） |
| `transcript_segment` | ASR 转写分段(seq/起止秒/文本) |
| `frame_record` | 抽帧 + Qwen-VL 描述 |
| `video_ocr` | OCR 字幕(帧时间/文本/conf) |
| `chat_session` | 会话 |
| `chat_message` | 会话消息(+refs JSON) |
| `qa_checkpoint` | 目标级问答 Checkpoint(按 video_id+goal_digest 唯一) |
| `user` | 用户表（AuthService 维护） |

**PostgreSQL（`db/schema-pg.sql`）**：
- `video_chunk`：多模态分块(TEXT/FRAME) + `VECTOR(1024)`，HNSW 余弦索引 + video_id 索引。
- `video_keyword`：关键词倒排(token/freq/分块定位，单参数 `token = ANY(?)` 绑定）。

---

## 8. 配置项总览（`resources/application.yml`）

| Key | 说明 | 环境变量缺省 |
|---|---|---|
| `server.port` | 端口 | 8080 |
| `spring.datasource.*` | MySQL(localhost:13306) | — |
| `spring.kafka.bootstrap-servers` | 19092 | — |
| `spring.data.redis.host/port` | 16379 | — |
| `video-agent.pipeline.frame-concurrency` | Qwen-VL 并发 | 5 |
| `video-agent.pipeline.pool.*` | pipelineExecutor 线程池 | 4/8/200 |
| `video-agent.lock.wait-ms` | 锁默认等待 | 0 |
| `video-agent.auth.jwt-secret/expire-hours` | JWT | env(VideoAgentJwtSecret…) |
| `video-agent.limit.qa-rate/model-rate` | 每用户 Q&A/模型令牌桶 | 5/8 |
| `video-agent.task.retry.interval-ms` | 自动重试周期 | 30000 |
| `video-agent.agent.budget.*` | Agent 预算(调用/token/墙钟) | 12/20000/120000 |
| `video-agent.storage.root` | 文件根 | ./data |
| `video-agent.llm.deepseek.*` | DeepSeek(base-url/key/model) | env(DEEPSEEK_API_KEY) |
| `video-agent.llm.dashscope.*` | Qwen-VL/embedding | env(DASHSCOPE_API_KEY) |
| `video-agent.ocr.local.*` / `asr.local.*` | 本地 worker 脚本/模型/语言模式(AUTO/FIXED) | OCR_ENABLED 等 |
| `video-agent.pg.*` | PG/pgvector 连接 | — |

---

## 9. 约定与历史坑（务必遵从）

- /api/** 全部需要 JWT（AuthInterceptor，UserContext ThreadLocal 存 uid）；`/files/**` 支持 `?token=`（浏览器 `<video>` 无法带 header）。
- 静态资源 `Cache-Control: no-store`，彻底防浏览器缓存旧前端。
- 数据库实体（Video/ChatSession…）必须带 user_id 多租户隔离；多数据源不能用事务，清理需幂等防孤儿数据。
- Redisson 锁必须用看门狗（不传 leaseTime），否则长任务持锁过期竞态。
- 长任务绝不能直接在 Kafka 消费者线程跑；所有阶段卸到 pipelineExecutor。
- PG 倒排检索禁止多参数 `IN(?,?,…)`（偶发 index-out-of-range），用 `token = ANY(?)` + 数组单参数。
- Q&A 引用必须可核验（真实分段区间 + 可验证文本），已实现 WS1→WS3 防编造。
- OCR 校验用 `HEX(text)`/python decode，勿信 PowerShell 终端显示（UTF-8 日文会显示成乱码，不代表数据损坏）。
- Python worker stdout 用 `ensure_ascii=True` + Java 侧 UTF-8/PYTHONIOENCODING 双保险。
- 视频处理要在 EMBEDDING 崩溃时幂等续跑（不能只补 READY，会丢未入库尾部批次）。

---

## 10. 测试地图（57 项，`src/test/java`）

| 分组 | 覆盖 |
|---|---|
| `IntentClassifierTest` | LLM 意图识别 + 兜底(GENERAL) |
| `AgentRuntimeBudgetTest` | Agent 预算：轮次/token/墙钟中断（本次新增） |
| `TaskRetrySchedulerTest` | 自动重试退避/超上限死信/映射（本次新增） |
| `TaskFailureLedgerTest` | 失败落账 + 重试耗尽 DEAD（本次新增） |
| `TaskServiceRetryTest` | 手动重试状态机/锁/topic 映射 |
| `SummaryStrategyServiceTest` | map-reduce 归纳合并不重复 |
| `QaCheckpointServiceTest` | checkpoint 存取/幂等 |
| `ReferenceVerifierTest` | 引用确定性校验 |
| `VideoPipelineServiceShouldRunTest` | 幂等守卫状态匹配 |
| `GlobalExceptionHandlerTest` | 异常→HTTP 状态映射 |
| `JwtUtilTest` | JWT 签发/校验 |
| `RuleRouterTest` | 路由策略（原） |

---

## 11. 快速操作

- 启动：`mvn -o -DskipTests package` → `java -jar target/video-agent-0.1.0-SNAPSHOT.jar --server.port=8080`（需先注入 DASHSCOPE/DEEPSEEK API key、WHISPER_PYTHON、OCR_ENABLED、JWT secret）。
- 依赖：MySQL 13306、Kafka 19092、Redis 16379、PG 5432（docker-compose 起）。
- Swagger：`/swagger-ui.html`；监控：`/api/monitor/pipeline`。