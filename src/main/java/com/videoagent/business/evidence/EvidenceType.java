package com.videoagent.business.evidence;

/**
 * 证据来源类型（证据湖统一 schema 之 type）。
 * 所有问答依据统一归入此四类，消除"语音/画面各造一套"的混乱。
 */
public enum EvidenceType {
    /** 语音转写（ASR） */
    AUDIO,
    /** 画面帧描述（VLM） */
    VISION,
    /** 语义检索命中片段（RAG top-K） */
    RETRIEVED,
    /** 视频元数据（标题/时长等） */
    META
}