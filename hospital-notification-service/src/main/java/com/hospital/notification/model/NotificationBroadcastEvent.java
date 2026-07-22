package com.hospital.notification.model;

/**
 * 通知广播事件，用于解耦 NotificationStore 和 NotificationController。
 */
public record NotificationBroadcastEvent(NotificationRecord record) {}
