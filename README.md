# 简历智能筛选与岗位匹配系统

求职者在官网投递，HR 在后台看排行。简历落成结构化档案，候选人按匹配度自动排序，每个分数都能拆成"学历 + 年限 + 技能 + 语义"四块讲清楚。

需求规格见 [`简历智能筛选系统-需求规格说明书.md`](./简历智能筛选系统-需求规格说明书.md)。
> 规格书里最初把系统设计成"只有 HR 一个角色，HR 自己上传简历"。实际落地时改成了两个入口：
> 求职者投递走公开链接，HR 只在后台看结果。差异见 §0。

## 关键指标

> 样本量小（简历 n=3、投递 n=6），数字用于说明**测法**，不作为效果宣称。待测项都写了口径，跑通后替换。

| 指标 | 实测 | 口径 |
|---|---|---|
| 简历解析成功率 | 3 / 3 | `EXTRACTED` / 总提交，演示集 |
| 抽取耗时 P50 | 2.07 s | `resume.cost_ms`，范围 2.04 – 2.36 s |
| 规则分 / 语义分均分 | 88.0 / 61.0 | n=6，语义分比规则分低 27 分 |
| 综合分均分 | 77.2 | 0.6 × 规则分 + 0.4 × 语义分 |
| 抽取字段准确率 | 待测 | 20 份人工标注简历，字段级 precision / recall |
| 单份处理成本 | 待测 | token 已在 `ExtractOutcome` 采集但未落库，需补 `ai_call_log` 表 |
| 语义分缓存命中率 | 待测 | 按 `(resume_id, job_id, prompt_version)` 复用，尚未实现 |
| AI 服务不可用时 | 直接 FAILED | 降级为纯规则分排序是待做项，当前会重试 3 次后终止 |

**一个还没解释的现象**：语义分比规则分平均低 27 分。规则分是确定性计算，语义分是模型判断；两者偏差这么大，说明模型对"整体匹配度"比规则更保守。样本只有 6 条，不足以定论，先记在这里，等评估集跑起来再回头看是不是系统性偏置。

---

## 0. 两个入口，各管一件事

| | 求职者 | HR |
|---|---|---|
| 入口 | `/apply/{jobId}`（公开，免登录） | `/board` `/jobs` `/resumes` |
| 能做什么 | 看岗位、填联系方式、传 PDF | 建岗位、拿投递链接、看分数排行、看分数拆解 |
| 看不到什么 | 分数、排名、其他候选人、投递人数 | — |

**为什么这么改**：让 HR 在后台替候选人上传简历，等于把两件事塞进一个界面——
投递本该发生在候选人那一侧，HR 拿到的是投递结果。所以：

- 新增公开接口 `/api/public/**`，只开放"看岗位 + 交简历"，不暴露任何后台数据；
- 投递记录多了一个 `WAITING` 状态（简历还没解析完），解析成功后由消费者接力触发打分；
- HR 后台的 `/api/applications` 保留，但语义变成"给内推/邮件来的线下简历**指派**岗位"，
  不再承担候选人的投递动作。

---

## 1. 技术栈

| 层 | 选型 | 端口 |
|---|---|---|
| 前端 | Vue 3 + Vite + Element Plus + Pinia | 5173 |
| 业务后端 | Spring Boot 3.2 + MyBatis-Plus + MySQL 8（JDK 17） | 8080 |
| 缓存 | Redis 7（单机） | 6379 |
| 消息队列 | RocketMQ 5.3（namesrv + broker） | 9876 / 10911 |
| AI 服务 | Python 3.10 + FastAPI + LangChain | 8000 |
| LLM | DeepSeek（OpenAI 兼容，`deepseek-chat`） | — |
| PDF 解析 | pdfplumber | — |

## 2. 目录结构

```
简历智能筛选系统/
├── backend/                  Spring Boot 业务后端
│   ├── src/main/java/com/resume/screening/
│   │   ├── controller/       4 个 HR 控制器 + PublicController（求职者侧）
│   │   ├── service/          岗位 / 简历 / 投递 / 官网投递 四个业务服务
│   │   ├── match/            规则分计算 + 技能归一化
│   │   ├── mq/               生产者、两个消费者、对账任务
│   │   ├── cache/            Redis 状态缓存 / 幂等锁 / 限流 / 排行榜
│   │   └── ai/               Python 服务客户端
│   └── src/main/resources/application.yml
├── ai-service/               Python AI 服务
│   ├── app/
│   │   ├── main.py           /extract /match /health
│   │   ├── extract_chain.py  简历 → Profile（LangChain structured output）
│   │   ├── match_chain.py    Profile + 岗位 → 语义分与理由
│   │   ├── schemas.py        Pydantic Schema（对应需求 §9.2）
│   │   └── skill_alias.py    技能归一化
│   ├── resources/skill_alias.json
│   └── .env
├── frontend/                 Vue 3 前端
│   └── src/views/
│       ├── ApplyPage.vue         求职者投递页（公开）
│       ├── CandidateBoard.vue    HR：候选人看板
│       ├── JobList.vue           HR：岗位管理 + 投递链接
│       ├── ResumeUpload.vue      HR：简历库 / 线下导入
│       └── ApplicationDetail.vue HR：匹配详情
├── sql/schema.sql            建库建表
├── scripts/
│   ├── gen_demo_resumes.py   生成 9 份演示简历 PDF
│   ├── gen_test_resume.py    生成 1 份正式排版的测试简历
│   └── acceptance_test.py    验收清单自动核对
├── demo/resumes/             演示简历
├── docs/message-contract.md  **跨语言字段约定，改字段先改这里**
├── start-all.bat             启动 MQ / AI / 前端（后端在 IDEA 里跑）
└── stop-all.bat              停止
```

## 3. 启动

### 3.1 前置

| 依赖 | 说明 |
|---|---|
| JDK 17 | `C:\download\jdk\jdk17` |
| MySQL 8 | `root / 123456`，需先建库 |
| Redis | 6379，无密码 |
| RocketMQ 5.3.3 | `C:\download\rocketmq-all-5.3.3-bin-release` |
| conda 环境 `ai` | 已装 fastapi / langchain / pdfplumber |

> LLM 配置在 `ai-service/.env`（`LLM_BASE_URL` / `LLM_API_KEY` / `LLM_MODEL`）。
> 该文件含密钥、已被 `.gitignore` 忽略；新环境请复制 `ai-service/.env.example` 后填入自己的 key。

### 3.2 建库

```bash
mysql -uroot -p123456 < sql/schema.sql
```

### 3.3 一键启动（后端交给 IDEA）

双击 `start-all.bat`。它会拉起 **NameServer / Broker / AI 服务 / 前端** 四个控制台窗口，**不启动后端**——后端在 IDEA 里跑，方便打断点和看日志。

脚本会先探测端口：已经在监听的会显示 `SKIP`，不会重复启动。脚本内容全 ASCII，控制台不会乱码。

停止：`stop-all.bat`（先按窗口标题杀，再按端口兜底）。**它不会动 8080**，后端请在 IDEA 里按 Stop。

### 3.4 在 IDEA 里启动后端

1. IDEA 里 `Open` → 选 **`backend` 目录**（不是项目根目录），按 Maven 项目导入；
2. `File → Project Structure → Project SDK` 选 **JDK 17**（`C:\download\jdk\jdk17`）；
3. 确认 `Settings → Build → Compiler → Annotation Processors` 勾了 **Enable annotation processing**（Lombok 需要）；
4. 直接 Run `com.resume.screening.ResumeScreeningApplication`，等日志出现 `Tomcat started on port 8080`。

若控制台中文乱码，在 Run Configuration 的 VM options 加 `-Dfile.encoding=UTF-8`。

其余三个服务也可以手动起：

```bash
# RocketMQ
cd C:\download\rocketmq-all-5.3.3-bin-release
java -server -Xms512m -Xmx512m -Drocketmq.home.dir=. -cp ".;conf;lib/*" org.apache.rocketmq.namesrv.NamesrvStartup
java -server -Xms512m -Xmx512m -Drocketmq.home.dir=. -cp ".;conf;lib/*" org.apache.rocketmq.broker.BrokerStartup -c conf/broker-local.conf

# AI 服务
conda activate ai
cd ai-service && python run.py

# 前端
cd frontend && npm install && npm run dev
```

> 也可以用 `mvn -DskipTests package` 打成 jar 后 `java -jar target/resume-screening-backend-0.1.0.jar --server.port=8080` 跑。
> 这条路径下**必须显式带 `--server.port=8080`**，因为本机有 `SERVER__PORT` 环境变量会顶掉 `application.yml` 的配置（IDEA 的 Run Configuration 一般没有这个变量，不受影响）。

### 3.5 演示数据

```bash
conda activate ai
python scripts/gen_demo_resumes.py     # 生成 9 份 PDF 到 demo/resumes/
python scripts/gen_test_resume.py      # 单独生成 1 份测试简历（林嘉伟_Java后端.pdf）
```

`scripts/gen_test_resume.py` 产出的是**一页正式排版**的简历（7 年 Java 后端、本科、含"至今"经历），
适合手动走一遍"打开投递页 → 提交 → 回后台看排行"的完整流程。改内容直接改脚本里的 `SECTIONS`。

**演示动线（3 分钟）**

1. HR 在「岗位管理」建岗位，点「复制链接」拿到 `http://127.0.0.1:5173/apply/1`；
2. 换一个浏览器窗口（或发给同事）打开这个链接，填姓名/手机，传 `demo/resumes/张伟_Java后端_5年.pdf`，提交；
3. 页面提示"投递成功，简历正在解析"；
4. 回到 HR 后台「候选人看板」→ 该岗位，几秒后张伟出现在列表里，综合分、规则分、语义分、命中技能齐全；
5. 再投 2～3 份，排名自动拉开；点进任一人看分数拆解。

简历构成（正好对上需求 §11.2 的演示脚本）：

| 简历 | 学历 | 年限 | 技能 | 预期 |
|---|---|---|---|---|
| 张伟 | 本科 | 5 年 | Java/Spring Boot/MySQL/RocketMQ 全中 | 排名第一 |
| 陈晨 | 硕士 | 4.5 年 | 全中 | 第二 |
| 孙磊 | 本科 | 7 年 | 全中 | 第三 |
| 李婷 | 本科 | 0.5 年 | 全中但年限差 2 年以上 | 年限分 0 |
| 王浩 | 大专 | 3 年 | 纯前端，0 命中 | 垫底 |
| 扫描件 | — | — | 无文本层 | 投递时直接提示失败 |

## 4. 接口

统一响应：`{ "code": 0, "message": "ok", "data": {} }`，`code != 0` 时 HTTP 状态码同步设置。

**求职者侧（公开，免登录）**

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/public/jobs/{id}` | 岗位公开信息（已关闭的也返回，供页面提示） |
| POST | `/api/public/applications` | 投递简历（multipart：`jobId` `name` `phone` `email` `file`） |

> 公开接口只返回 `{accepted, duplicated, message}`，不带任何主键、分数、投递人数。

**HR 后台**

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/jobs` | 创建岗位 |
| GET | `/api/jobs` | 岗位分页列表（含投递数/均分） |
| GET | `/api/jobs/{id}` | 岗位详情 |
| PUT | `/api/jobs/{id}` | 编辑岗位 |
| PUT | `/api/jobs/{id}/status` | 开放 / 关闭 |
| GET | `/api/jobs/{id}/applications` | 候选人看板（按综合分倒序，可按最低分/学历过滤） |
| GET | `/api/jobs/{id}/top?n=20` | TOP N 快速通道（走 Redis ZSet，缺失回源 MySQL） |
| POST | `/api/resumes` | 线下简历导入（multipart，字段 `file`） |
| POST | `/api/resumes/batch` | 线下简历批量导入（字段 `files`，一次 ≤20） |
| GET | `/api/resumes` | 简历库列表（可按状态筛选，带投递岗位） |
| GET | `/api/resumes/{id}` | 简历详情（含 Profile） |
| GET | `/api/resumes/{id}/status` | **轮询解析状态** |
| POST | `/api/resumes/{id}/retry` | 重新解析 |
| POST | `/api/applications` | 给线下简历**指派**岗位并打分（只接受已解析的简历） |
| GET | `/api/applications/{id}` | 匹配详情（分数拆解 + 理由） |

错误码：`1001` 格式/大小不合法、`1002` 不存在、`1003` 简历未解析完（409）、`1004` 岗位已关闭（409）、`1005` 必备技能为空、`1006` 上传过快（429）、`2001/2002/2003` AI 侧错误。

**投递状态机**

```
官网投递 ──→ WAITING（等简历解析）──→ SCORING ──→ SCORED / FAILED
HR 指派 ─────────────────────────────→ SCORING ──→ SCORED / FAILED
```

`WAITING` 的推进由 `ExtractConsumer` 在解析成功后接手：查 `resume_id = ? AND status = 'WAITING'`
的投递记录，用 `UPDATE ... WHERE status='WAITING'` 抢占后投递打分消息，抢不到就说明别的线程已处理。
`ReconcileJob` 每分钟补一次"简历已解析但投递还卡在 WAITING"的漏网记录。

## 5. 三条关键设计

**D1 — MQ 不是装饰。** 抽取一次要 5–15 秒，同步等会让浏览器超时、用户重复点击。线程池的队列在内存里，服务一重启任务就没了，用户看到的状态永远卡在 `PENDING`；MQ 给的是持久化任务队列，这是它相对 `@Async` 唯一不可替代的价值。另外还挂了一个 `ReconcileJob` 对账任务，兜住"投递失败""处理到一半进程被杀"这两种漏单。

**D2 — 规则归代码，语义归模型。** 学历/年限/必备技能覆盖率由 Java 确定性计算，同样输入永远同样分数；"这个人整体上像不像这个岗位要的人"交给 LLM。把业务规则塞进 prompt 会让模型把本科和硕士判成差不多，还每次结果都不一样。

**D3 — Redis 只做加速，MySQL 是唯一真相。** 状态、排行榜、幂等锁都在 Redis，但全是可重建的派生数据。执行 `FLUSHDB` 后系统功能完整，只是第一次轮询变慢——验收清单第 10 条就是验这个。

## 6. 验收

```bash
conda activate ai
python scripts/acceptance_test.py
```

自动核对需求 §11.1 的第 1–8、10 条，输出 PASS/FAIL 明细。

**第 9 条（MQ 真在用的压轴验证）需手动做：**

1. 关掉 AI 服务窗口（或 `taskkill` 掉 8000 端口进程）；
2. 上传一份新简历 → 接口仍秒回 200，状态停在 `PARSING`；
3. 重新启动 AI 服务；
4. 等 10–60 秒（RocketMQ 重试间隔 10s/30s/60s），状态自动变 `EXTRACTED`。

这一步证明任务确实躺在 broker 里而不是内存里。

## 7. 已知边界

- 只接**文本型 PDF**。扫描件/图片简历直接判 `FAILED` 并提示，不做 OCR（需求 §14 明确不做）。
- 分项分（学历分/年限分/技能分）**不落库**，详情页按当前岗位要求重算。规则分可复现，重算永远自洽；如果岗位要求被改过，会把"存储的规则分与重算值不一致"打进 WARN 日志。
- 投递记录的打分是"重复投递即更新"，靠 `uk_job_resume` 唯一索引保证，不新增记录。
- 语义分由 LLM 给出，同一份简历重复打分会有小幅波动（`temperature=0` 已尽量压低），这是模型打分的固有属性，不是 bug。
