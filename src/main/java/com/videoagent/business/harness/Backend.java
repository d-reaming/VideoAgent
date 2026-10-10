package com.videoagent.business.harness;

/**
 * 执行后端：FAST=单轮/全量归纳，AGENT=多 Agent 语义编排。
 * 对应对外 QaResponse.channel 的 "FAST"/"AGENT" 字符串。
 */
public enum Backend {
    FAST, AGENT
}