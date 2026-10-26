# 跨语言字段约定

> 需求 §10「可维护」：跨语言字段约定集中在本文件，**改字段先改文档**。
> 三条通道的命名风格不一致是刻意的——两种语言各用自己最自然的写法，转换只发生在边界上。

| 通道 | 命名风格 | 原因 |
|---|---|---|
| Java → RocketMQ 消息体 | `camelCase` | Java 侧 `ObjectMapper` 默认输出 |
| Java → Python HTTP 请求 | `snake_case` | 与 Python Pydantic 模型字段一致 |
| Python → Java HTTP 响应（外层） | `camelCase` | 与 Java DTO 对齐 |
| Python → Java HTTP 响应（`profile` 内层） | `snake_case` | 与需求 §9.2 的 Pydantic 模型逐字对齐，Java 以 JSON 原样落库 `parsed_json` |

---

## 1. RocketMQ 消息体

Topic 与消费组见 `application.yml` 的 `app.mq` 段。

### 1.1 `resume-extract`

生产者：`POST /api/resumes`（事务提交后投递）
消费者：`ExtractConsumer`，消费组 `resume-extract-consumer-group`

```json
{
  "messageId": "3f2a9c1e-8f2b-4a1e-9c7d-0b2a1c3d4e5f",
  "resumeId": 1001,
  "filePath": "2026/10/24/3f2a9c1e.pdf",
  "attempt": 1,
  "timestamp": 1761312000000
}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `messageId` | string | UUID，消费端日志关联键 |
| `resumeId` | long | `resume.id`，同时是幂等锁的键 |
| `filePath` | string | **相对 uploads 根**的路径，服务端生成 |
| `attempt` | int | 生产者重投时递增，仅作日志标记；实际重试次数以 `MessageExt.getReconsumeTimes()` 为准 |
| `timestamp` | long | 投递时刻（毫秒） |

### 1.2 `match-score`

生产者：`POST /api/applications`（事务提交后投递）
消费者：`MatchConsumer`，消费组 `match-score-consumer-group`

```json
{
  "messageId": "8b1d7e4a-1c2d-4e3f-8a9b-0c1d2e3f4a5b",
  "applicationId": 5001,
  "resumeId": 1001,
  "jobId": 12,
  "timestamp": 1761312000000
}
```

---

## 2. Java → Python

### 2.1 `POST /extract`

请求：`multipart/form-data`，字段名 `file`，`Content-Type: application/pdf`

响应（成功）：

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "rawText": "<PDF 文本层全文>",
    "pageCount": 2,
    "profile": {
      "name": "张伟",
      "phone": "13800138000",
      "email": "zhangwei@example.com",
      "education": {
        "degree": "本科",
        "school": "华南理工大学",
        "major": "计算机科学与技术",
        "grad_year": 2020
      },
      "total_years": 5.0,
      "skills": ["Java", "Spring Boot", "MySQL"],
      "experiences": [
        {
          "company": "广州云链网络科技有限公司",
          "title": "高级 Java 开发工程师",
          "period": "2023.07-至今",
          "summary": "主导支付网关重构……"
        }
      ],
      "summary": "五年后端经验，长期在高并发交易链路工作……"
    },
    "costMs": 8234,
    "tokens": { "prompt": 1384, "completion": 247, "total": 1631 }
  }
}
```

- `rawText` 是**全文**，用于落库 `resume.raw_text`；送模型的文本在 Python 侧截断到 8000 字符（§10）。
- `profile` 的字段名严格等于 §9.2 的 `ResumeProfile`，Java 不做改名，直接写入 `parsed_json`。
- `skills` 已按 `skill_alias.json` 归一化（`SpringBoot` → `Spring Boot`）。

### 2.2 `POST /match`

请求：`application/json`，**snake_case**

```json
{
  "job_title": "Java 后端开发工程师",
  "jd_text": "<JD 原文，超 3000 字符截断>",
  "must_skills": ["Java", "Spring Boot", "MySQL", "RocketMQ"],
  "plus_skills": ["Redis"],
  "profile": { "...同 §2.1 的 profile..." }
}
```

响应（成功）：

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "semanticScore": 92,
    "reason": "五年经验深耕交易/支付链路……",
    "costMs": 3120,
    "tokens": { "prompt": 2100, "completion": 96, "total": 2196 }
  }
}
```

- 只返回**语义分与理由**。命中/缺失技能由 Java 做集合运算（§FR-MAT-02），Python 不参与。
- `semanticScore` 为 0–100 整数；`reason` ≤ 200 字（prompt 要求 ≤120）。

### 2.3 `GET /health`

```json
{ "code": 0, "message": "ok", "data": { "status": "UP", "model": "deepseek-chat", "baseUrl": "https://api.deepseek.com/v1" } }
```

### 2.4 错误响应

Python 对**已知业务失败**返回 HTTP 200 + 非 0 `code`，只有未预期异常才返回 HTTP 5xx。
这样 Java 侧只需读 body 的 `code`，不必区分状态码。

```json
{ "code": 2001, "message": "未检测到文本层，请上传文本型 PDF", "data": null }
```

| code | 含义 | Java 侧处理 |
|---|---|---|
| 1001 | 请求不合法（空文件 / 超 10MB / 非 PDF / 岗位信息缺失） | 直接判失败，不重试 |
| 2001 | PDF 无文本层（扫描件 / 乱码） | 直接 `FAILED`，不重试（省一次 LLM 调用） |
| 2002 | LLM 调用失败 / 超时 / AI 服务不可达 | **可重试**，交给 MQ `RECONSUME_LATER` |
| 2003 | 输出不符合 Pydantic Schema | 直接 `FAILED`，不重试 |

> Java 侧的重试判定集中在 `AiException#retryable`，改这里就等于改重试策略。

---

## 3. 重试与幂等的约定（非字段，但同样跨语言）

| 项 | 约定 |
|---|---|
| 消费最大重试 | `app.mq.max-reconsume-times`（默认 3），第 3 次仍失败即落 `FAILED` |
| 幂等锁 | `resume:lock:{resumeId}` / `match:lock:{applicationId}`，TTL 120s，Lua 比对 token 释放 |
| 幂等兜底 | 状态已是 `EXTRACTED` / `SCORED` 直接跳过，不重复调 LLM |
| 状态权威 | MySQL 是唯一真相，Redis 清了不影响功能 |
| **两条链路的接力** | 官网投递先落 `job_application.status='WAITING'`；`resume-extract` 消费成功后由同一次消费**续投** `match-score`（`ExtractConsumer#promoteWaitingApplications`），并用 `UPDATE ... WHERE status='WAITING'` 抢占防重。简历解析终止失败时，等它的投递一并置 `FAILED`（`failWaitingByResumeId`）。 |

---

## 4. 修改字段的流程

1. 先改本文件；
2. 改 Python 的 `ai-service/app/schemas.py`；
3. 改 Java 的 `AiClient` / `mq/*Message`；
4. 跑 `python scripts/acceptance_test.py`，确认第 6 条（分项/命中技能对齐）仍通过。
