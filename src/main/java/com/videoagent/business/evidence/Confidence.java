package com.videoagent.business.evidence;

/**
 * 断言/证据的置信分级。
 * DIRECT：有直接证据支撑（转写原文/画面字幕/元数据）；
 * INFERENCE：由直接证据 + 常识推断得出（允许在回答中给出但须标注"推断"）；
 * UNSURE：无法可靠确认。
 * 引入分级是为了松开"只有直接检索才能答"的过强约束，同时保留可溯源保真。
 */
public enum Confidence {
    DIRECT,
    INFERENCE,
    UNSURE
}