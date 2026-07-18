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
    public static final String ROUTING_VISIT_CREATED = "visit.created";
    public static final String ROUTING_PATIENT_CALLED = "patient.called";

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
    public Binding notificationBinding(Queue notificationQueue, TopicExchange hospitalExchange) {
        return BindingBuilder.bind(notificationQueue).to(hospitalExchange).with(ROUTING_VISIT_CREATED);
    }

    @Bean
    public Binding notificationPatientBinding(Queue notificationPatientQueue, TopicExchange hospitalExchange) {
        return BindingBuilder.bind(notificationPatientQueue).to(hospitalExchange).with(ROUTING_PATIENT_CALLED);
    }

    @Bean
    public Jackson2JsonMessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}