-- ============================================================
-- 增量迁移：AI 可观测性（2026-11-15）
-- 适用场景：库中已有数据，不想重建表。
-- 从零建库请直接执行 schema.sql，它已包含本次变更。
-- ============================================================

USE `resume_screening`;

-- 1. 简历表增加 prompt 版本：同一份简历在不同 prompt 版本下的抽取质量要能对比
ALTER TABLE `resume`
  ADD COLUMN `prompt_version` VARCHAR(16) DEFAULT NULL
  COMMENT '抽取所用 prompt 版本，评估对比与缓存失效的依据'
  AFTER `cost_ms`;

-- 2. AI 调用流水
-- 命中语义分缓存时也落一行（cache_hit=1，tokens=0），否则命中率算不出来
CREATE TABLE IF NOT EXISTS `ai_call_log` (
  `id`                BIGINT      NOT NULL AUTO_INCREMENT,
  `task`              VARCHAR(20) NOT NULL COMMENT 'EXTRACT / MATCH',
  `resume_id`         BIGINT               DEFAULT NULL,
  `application_id`    BIGINT               DEFAULT NULL,
  `job_id`            BIGINT               DEFAULT NULL,
  `model`             VARCHAR(60) NOT NULL COMMENT '如 deepseek-chat',
  `prompt_version`    VARCHAR(16) NOT NULL DEFAULT 'unknown' COMMENT 'prompt 版本，评估对比的锚点',
  `prompt_tokens`     INT         NOT NULL DEFAULT 0,
  `completion_tokens` INT         NOT NULL DEFAULT 0,
  `cost_ms`           INT         NOT NULL DEFAULT 0 COMMENT '本次调用总耗时（含网络与重试）',
  `cache_hit`         TINYINT     NOT NULL DEFAULT 0 COMMENT '1=命中语义分缓存，未真正调用 LLM',
  `success`           TINYINT     NOT NULL DEFAULT 1,
  `error_code`        INT                  DEFAULT NULL COMMENT '失败时的 AI 错误码，2001/2002/2003',
  `created_at`        DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_task_created` (`task`, `created_at`),
  KEY `idx_resume` (`resume_id`),
  KEY `idx_application` (`application_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 调用流水';

-- 3. 语义分缓存命中统计视图（可选，方便直接查数字）
CREATE OR REPLACE VIEW `v_ai_daily` AS
SELECT DATE(`created_at`)                             AS `day`,
       `task`,
       COUNT(*)                                       AS `calls`,
       SUM(`cache_hit`)                               AS `cache_hits`,
       SUM(`prompt_tokens`)                           AS `prompt_tokens`,
       SUM(`completion_tokens`)                       AS `completion_tokens`,
       ROUND(AVG(`cost_ms`))                          AS `avg_cost_ms`
  FROM `ai_call_log`
 GROUP BY DATE(`created_at`), `task`;
