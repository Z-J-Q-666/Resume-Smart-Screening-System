-- ============================================================
-- 增量迁移：RAG 校准语义分（2026-11-19）
-- 适用场景：库中已有数据。从零建库请直接执行 schema.sql。
-- ============================================================

USE `resume_screening`;

-- 1. 录用决策。语义分要校准，必须先有"什么算好"的锚点。
CREATE TABLE IF NOT EXISTS `hiring_decision` (
  `id`             BIGINT      NOT NULL AUTO_INCREMENT,
  `application_id` BIGINT      NOT NULL,
  `job_id`         BIGINT      NOT NULL COMMENT '冗余，检索时免 join',
  `resume_id`      BIGINT      NOT NULL COMMENT '冗余，检索时免 join',
  `outcome`        VARCHAR(16) NOT NULL COMMENT 'HIRED / REJECTED',
  `note`           VARCHAR(500) DEFAULT NULL,
  `decided_by`     VARCHAR(50)  DEFAULT NULL,
  `decided_at`     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  -- 一条投递只保留一个最终结论，改了就是覆盖
  UNIQUE KEY `uk_application` (`application_id`),
  KEY `idx_job_outcome` (`job_id`, `outcome`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='录用决策，语义分校准的锚点';

-- 2. 简历向量。只存 profile 的向量，不存原文——
--    校准要的是"这个人像不像被录取过的那批人"，不是"文字像不像"。
CREATE TABLE IF NOT EXISTS `resume_embedding` (
  `id`          BIGINT      NOT NULL AUTO_INCREMENT,
  `resume_id`   BIGINT      NOT NULL,
  `model`       VARCHAR(60) NOT NULL COMMENT '嵌入模型名，换模型要重新算',
  `dim`         INT         NOT NULL,
  `vector`      JSON        NOT NULL COMMENT 'float 数组',
  `source_hash` CHAR(64)    NOT NULL COMMENT 'profile JSON 的 sha256：profile 重抽过才需要重算',
  `created_at`  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_resume_model` (`resume_id`, `model`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='简历 profile 向量，用于检索相似的历史录用者';
