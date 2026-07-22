package com.hospital.notification.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Messaging infrastructure (notification-service).
 * Mirrors core's queue/exchange/binding so events route correctly.
 * Uses JSON converter — local DTO matches core's event shape.
 */
@Configuration
public class MessagingConfig {

    public static final String EXCHANGE = "hospital.exchange";
    public static final String Q_NOTIFICATION = "q.notification";
    public static final String Q_NOTIFICATION_PATIENT = "q.notification.patient";
    public static final String Q_NOTIFICATION_VISIT_STATUS = "q.notification.visit.status";
    public static final String Q_NOTIFICATION_ORDER_CREATED = "q.notification.order.created";
    public static final String ROUTING_VISIT_CREATED = "visit.created";
    public static final String ROUTING_PATIENT_CALLED = "patient.called";
    public static final String ROUTING_VISIT_STATUS = "visit.status";
    public static final String ROUTING_ORDER_CREATED = "order.created";

    @Bean
    public TopicExchange hospitalExchange() {
        return new TopicExchange(EXCHANGE, true, false);
    }

    @Bean
    public Queue notificationQueue() {
        return new Queue(Q_NOTIFICATION, true);
    }

    @Bean
    public Queue notificationPatientQueue() {
        return new Queue(Q_NOTIFICATION_PATIENT, true);
    }

    @Bean
    public Queue notificationVisitStatusQueue() {
        return new Queue(Q_NOTIFICATION_VISIT_STATUS, true);
    }

    @Bean
    public Queue notificationOrderCreatedQueue() {
        return new Queue(Q_NOTIFICATION_ORDER_CREATED, true);
    }

    @Bean
    public Binding notificationBinding(Queue notificationQueue, TopicExchange hospitalExchange) {
        return BindingBuilder.bind(notificationQueue).to(hospitalExchange).with(ROUTING_VISIT_CREATED);
    }

    @Bean
    public Binding notificationPatientBinding(Queue notificationPatientQueue, TopicExchange hospitalExchange) {
        return BindingBuilder.bind(notificationPatientQueue).to(hospitalExchange).with(ROUTING_PATIENT_CALLED);
    }

    @Bean
    public Binding notificationVisitStatusBinding(Queue notificationVisitStatusQueue, TopicExchange hospitalExchange) {
        return BindingBuilder.bind(notificationVisitStatusQueue).to(hospitalExchange).with(ROUTING_VISIT_STATUS);
    }

    @Bean
    public Binding notificationOrderCreatedBinding(Queue notificationOrderCreatedQueue, TopicExchange hospitalExchange) {
        return BindingBuilder.bind(notificationOrderCreatedQueue).to(hospitalExchange).with(ROUTING_ORDER_CREATED);
    }

    @Bean
    public Jackson2JsonMessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}