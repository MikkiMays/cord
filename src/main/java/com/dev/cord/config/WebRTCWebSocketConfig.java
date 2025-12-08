package com.dev.cord.config;

import com.dev.cord.handler.MeetingWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.*;

@Configuration
@EnableWebSocket
public class WebRTCWebSocketConfig implements WebSocketConfigurer {

    private final MeetingWebSocketHandler meetingWebSocketHandler;

    public WebRTCWebSocketConfig(MeetingWebSocketHandler meetingWebSocketHandler) {
        this.meetingWebSocketHandler = meetingWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(meetingWebSocketHandler, "/webrtc-signal")
                .setAllowedOriginPatterns("*"); // временно для dev/prod
    }
}
