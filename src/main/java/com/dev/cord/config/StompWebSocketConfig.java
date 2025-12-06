package com.dev.cord.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.*;

@Configuration
@EnableWebSocketMessageBroker
public class StompWebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        config.enableSimpleBroker("/topic"); // Префикс для сообщений
        config.setApplicationDestinationPrefixes("/app"); // Префикс для входящих сообщений
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/chat") // Эндпоинт для STOMP-сообщений
                .setAllowedOriginPatterns("http://localhost:*", "http://127.0.0.1:*") // CORS
                .withSockJS(); // Использование SockJS для обратной совместимости
    }
}

