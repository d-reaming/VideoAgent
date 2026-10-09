package com.videoagent.middleware.rag;

/**
 * 检索块（入向量库的最小单元）。
 */
public record Chunk(String videoId, String type, int seq, Integer startSec, Integer endSec, String content,
                    String metadata) {
}
