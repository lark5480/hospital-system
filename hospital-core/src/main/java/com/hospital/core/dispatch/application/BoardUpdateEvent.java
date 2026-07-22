package com.hospital.core.dispatch.application;

/**
 * 看板数据变更事件。
 * 当 start/complete/callNext/reorderToTail/skip 操作发生时发布。
 */
public record BoardUpdateEvent(
    String station,
    Long taskId,
    String action
) {}
