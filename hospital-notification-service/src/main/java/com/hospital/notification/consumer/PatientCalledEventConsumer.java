package com.hospital.notification.consumer;

import com.hospital.notification.config.MessagingConfig;
import com.hospital.notification.model.PatientCalledEvent;
import com.hospital.notification.store.NotificationStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 消费者:接收 dispatch 发出的叫号事件,落痕供 C 端「消息通知」展示。
 * 独立队列 q.notification.patient(仅绑定 patient.called),与门诊事件队列互不干扰。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PatientCalledEventConsumer {

    private final NotificationStore store;

    @RabbitListener(queues = MessagingConfig.Q_NOTIFICATION_PATIENT)
    public void onPatientCalled(PatientCalledEvent event) {
        log.info("[notification] patientCalled event received patientId={}, station={}, itemName={}",
                event.patientId(), event.station(), event.itemName());
        store.recordCalled(event);
    }
}
