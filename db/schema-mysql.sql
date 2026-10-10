-- MySQL 业务库初始化脚本（docker-entrypoint-initdb.d 自动执行）
-- 环境变量 MYSQL_DATABASE=video_agent 已建库，此处补充表结构

USE video_agent;

CREATE TABLE IF NOT EXISTS video (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  title VARCHAR(255) NOT NULL,
  filename VARCHAR(255) NOT NULL,
  file_path VARCHAR(500) NOT NULL,
  duration_sec INT DEFAULT 0,
  status VARCHAR(20) NOT NULL DEFAULT 'UPLOADED',
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS task_record (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  video_id BIGINT NOT NULL,
  stage VARCHAR(20) NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'RUNNING',
  retry_count INT DEFAULT 0,
  error_msg VARCHAR(1000),
  phase_durations JSON,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_video (video_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS transcript_segment (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  video_id BIGINT NOT NULL,
  seq INT NOT NULL,
  start_sec INT NOT NULL,
  end_sec INT NOT NULL,
  text TEXT NOT NULL,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_video (video_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS frame_record (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  video_id BIGINT NOT NULL,
  frame_time INT NOT NULL,
  image_path VARCHAR(500) NOT NULL,
  description TEXT,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_video (video_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS video_ocr (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  video_id BIGINT NOT NULL,
  frame_time INT NOT NULL,
  text TEXT NOT NULL,
  confidence DECIMAL(4,3) DEFAULT NULL,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_video (video_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS chat_session (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  video_id BIGINT,
  title VARCHAR(255),
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS chat_message (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  session_id BIGINT NOT NULL,
  role VARCHAR(20) NOT NULL,
  content TEXT NOT NULL,
  refs JSON,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_session (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 失败/死信台账：每次阶段失败落一行，重试耗尽后置 DEAD 并记录 dead_at（替代仅靠 task_record.status=FAILED 的粗粒度）。
-- 由 TaskRetryScheduler 扫描重试；达到 AUTO_RETRY_MAX 仍未成功则 task_record 置 DEAD，本表作为失败明细/死信账本。
CREATE TABLE IF NOT EXISTS task_failure (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  video_id BIGINT NOT NULL,
  stage VARCHAR(20) NOT NULL,
  error_msg VARCHAR(1000),
  retryable TINYINT NOT NULL DEFAULT 1 COMMENT '1=可重试, 0=永久失败',
  attempt INT NOT NULL DEFAULT 1 COMMENT '第几次失败尝试（1 起）',
  dead_at DATETIME DEFAULT NULL COMMENT '成为死信(GOING_DEAD)的时刻，NULL=仍可重试',
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_video (video_id),
  INDEX idx_dead (dead_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 目标级问答 Checkpoint（WS3）：按 (video_id, goal_digest) 保存 Plan/草稿/Reviewer 状态/最终结果，
-- 相同问题的 DONE 结果可幂等复用；中间态为异步续跑留档。
CREATE TABLE IF NOT EXISTS qa_checkpoint (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  video_id BIGINT NOT NULL,
  goal_digest VARCHAR(64) NOT NULL COMMENT 'MD5(归一化问题)',
  stage VARCHAR(40) NOT NULL COMMENT 'PLANNED/EXECUTED/REVIEWED/DONE',
  plan_json JSON,
  exec_draft MEDIUMTEXT,
  reviewer_state JSON,
  result MEDIUMTEXT,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_video_goal (video_id, goal_digest)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
