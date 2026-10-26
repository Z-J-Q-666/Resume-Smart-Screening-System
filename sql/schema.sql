-- ============================================================
-- 简历智能筛选与岗位匹配系统 —— 建库建表脚本
-- MySQL 8.0+  字符集 utf8mb4
-- ============================================================

CREATE DATABASE IF NOT EXISTS `resume_screening`
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_general_ci;

USE `resume_screening`;

DROP TABLE IF EXISTS `job_application`;
DROP TABLE IF EXISTS `resume`;
DROP TABLE IF EXISTS `job`;

CREATE TABLE `job` (
  `id`             BIGINT       NOT NULL AUTO_INCREMENT,
  `title`          VARCHAR(100) NOT NULL COMMENT '岗位名称',
  `department`     VARCHAR(100)          DEFAULT NULL,
  `education_req`  VARCHAR(10)  NOT NULL DEFAULT '不限' COMMENT '不限/大专/本科/硕士/博士',
  `exp_years_req`  INT          NOT NULL DEFAULT 0,
  `must_skills`    JSON         NOT NULL COMMENT '必备技能，字符串数组',
  `plus_skills`    JSON                  DEFAULT NULL COMMENT '加分技能',
  `jd_text`        TEXT                  DEFAULT NULL COMMENT 'JD 原文',
  `status`         TINYINT      NOT NULL DEFAULT 1 COMMENT '1 开放 0 关闭',
  `created_at`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='岗位';

CREATE TABLE `resume` (
  `id`             BIGINT       NOT NULL AUTO_INCREMENT,
  `candidate_name` VARCHAR(50)           DEFAULT NULL COMMENT '抽取得到，解析前为空',
  `phone`          VARCHAR(20)           DEFAULT NULL,
  `email`          VARCHAR(100)          DEFAULT NULL,
  `file_name`      VARCHAR(255) NOT NULL COMMENT '原始文件名',
  `file_path`      VARCHAR(500) NOT NULL COMMENT '相对 uploads 根的路径',
  `file_hash`      CHAR(64)     NOT NULL COMMENT '文件内容 SHA-256，去重',
  `raw_text`       MEDIUMTEXT            DEFAULT NULL COMMENT 'PDF 文本层原文',
  `parsed_json`    JSON                  DEFAULT NULL COMMENT 'LLM 抽取的结构化 Profile',
  `status`         VARCHAR(20)  NOT NULL DEFAULT 'PENDING'
                   COMMENT 'PENDING/PARSING/EXTRACTED/FAILED',
  `error_msg`      VARCHAR(500)          DEFAULT NULL,
  `cost_ms`        INT                   DEFAULT NULL COMMENT '解析耗时',
  `created_at`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_file_hash` (`file_hash`),
  KEY `idx_status` (`status`),
  KEY `idx_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='简历';

CREATE TABLE `job_application` (
  `id`              BIGINT       NOT NULL AUTO_INCREMENT,
  `job_id`          BIGINT       NOT NULL,
  `resume_id`       BIGINT       NOT NULL,
  `rule_score`      DECIMAL(5,2)          DEFAULT NULL COMMENT '0-100 确定性分数',
  `semantic_score`  DECIMAL(5,2)          DEFAULT NULL COMMENT '0-100 LLM 分数',
  `score`           DECIMAL(5,2)          DEFAULT NULL COMMENT '综合分',
  `hit_skills`      JSON                  DEFAULT NULL,
  `miss_skills`     JSON                  DEFAULT NULL,
  `reason`          VARCHAR(500)          DEFAULT NULL COMMENT '推荐理由',
  `status`          VARCHAR(20)  NOT NULL DEFAULT 'WAITING'
                    COMMENT 'WAITING/SCORING/SCORED/FAILED',
  `error_msg`       VARCHAR(500)          DEFAULT NULL,
  `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_job_resume` (`job_id`, `resume_id`),
  KEY `idx_job_score` (`job_id`, `score` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='投递记录';
