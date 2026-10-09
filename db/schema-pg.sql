-- PostgreSQL + pgvector 向量库初始化脚本（docker-entrypoint-initdb.d 自动执行）
-- POSTGRES_DB=video_agent 已建库

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS video_chunk (
  id BIGSERIAL PRIMARY KEY,
  video_id BIGINT NOT NULL,
  chunk_type VARCHAR(10) NOT NULL,          -- TEXT 转写块 / FRAME 帧描述块
  seq INT NOT NULL,
  start_sec INT,
  end_sec INT,
  content TEXT NOT NULL,
  metadata JSONB,
  embedding VECTOR(1024)
);

CREATE INDEX IF NOT EXISTS idx_video_chunk_hnsw
  ON video_chunk USING hnsw (embedding vector_cosine_ops);
CREATE INDEX IF NOT EXISTS idx_video_chunk_video
  ON video_chunk (video_id);
