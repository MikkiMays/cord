package com.dev.cord.handler;

import com.dev.cord.service.MeetingService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.net.URI;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Slf4j
@RequiredArgsConstructor
public class MeetingWebSocketHandler extends TextWebSocketHandler {

    private final MeetingService meetingService;
    private final ObjectMapper objectMapper;

    private final Map<String, Map<String, WebSocketSession>> meetingSessions = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Participant>> meetingParticipants = new ConcurrentHashMap<>();
    private final Map<String, String> sessionIdToMeetingId = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String meetingId = getMeetingId(session).orElse(null);
        if (meetingId == null) {
            session.close(CloseStatus.BAD_DATA);
            return;
        }

        meetingService.startOrGetMeeting(meetingId);
        meetingSessions.computeIfAbsent(meetingId, key -> new ConcurrentHashMap<>()).put(session.getId(), session);
        meetingParticipants.computeIfAbsent(meetingId, key -> new ConcurrentHashMap<>());
        sessionIdToMeetingId.put(session.getId(), meetingId);

        sendMessage(session, Map.of(
                "type", "your-id",
                "sessionId", session.getId()
        ));

        sendExistingParticipants(session, meetingId);
        log.info("Пользователь {} подключился к встрече {}", session.getId(), meetingId);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage textMessage) throws Exception {
        String meetingId = sessionIdToMeetingId.get(session.getId());
        if (meetingId == null) {
            session.close(CloseStatus.BAD_DATA);
            return;
        }

        Map<String, Object> message = parseMessage(textMessage.getPayload());
        String messageType = (String) message.get("type");

        if ("set-name".equals(messageType)) {
            registerUser(session, meetingId, (String) message.get("name"));
        } else {
            message.put("sender", session.getId());
            broadcastToOthers(session, meetingId, message);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.warn("Transport error for session {}: {}", session.getId(), exception.getMessage());
        super.handleTransportError(session, exception);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        String meetingId = sessionIdToMeetingId.get(session.getId());
        if (meetingId != null) {
            Map<String, Participant> participants = meetingParticipants.get(meetingId);
            Participant leaving = participants != null ? participants.remove(session.getId()) : null;
            Map<String, WebSocketSession> sessions = meetingSessions.get(meetingId);
            if (sessions != null) {
                sessions.remove(session.getId());

                if (sessions.isEmpty()) {
                    meetingSessions.remove(meetingId);
                    meetingParticipants.remove(meetingId);
                    meetingService.endMeeting(meetingId);
                }
            }

            Map<String, Object> message = new HashMap<>();
            message.put("type", "user-left");
            message.put("sessionId", session.getId());
            message.put("userName", leaving != null ? leaving.userName() : "guest");
            broadcastToOthers(session, meetingId, message);

            log.info("Пользователь '{}' покинул встречу '{}'", message.get("userName"), meetingId);
        }
        sessionIdToMeetingId.remove(session.getId());
    }

    private Optional<String> getMeetingId(WebSocketSession session) {
        URI uri = session.getUri();
        if (uri == null) {
            return Optional.empty();
        }
        String query = uri.getQuery();
        if (query == null) {
            return Optional.empty();
        }
        for (String param : query.split("&")) {
            String[] keyValue = param.split("=");
            if (keyValue.length == 2 && "meetingId".equals(keyValue[0])) {
                return Optional.ofNullable(keyValue[1]);
            }
        }
        return Optional.empty();
    }

    private void registerUser(WebSocketSession session, String meetingId, String providedName) throws IOException {
        String userName = StringUtils.hasText(providedName) ? providedName.trim() : "guest-" + session.getId();
        Map<String, Participant> participants = meetingParticipants.computeIfAbsent(meetingId, key -> new ConcurrentHashMap<>());
        participants.put(session.getId(), new Participant(session.getId(), userName));

        Map<String, Object> newUserMessage = new HashMap<>();
        newUserMessage.put("type", "new-user");
        newUserMessage.put("sessionId", session.getId());
        newUserMessage.put("userName", userName);
        broadcastToOthers(session, meetingId, newUserMessage);

        log.info("Пользователь '{}' зарегистрирован во встрече '{}'", userName, meetingId);
    }

    private void sendExistingParticipants(WebSocketSession session, String meetingId) throws IOException {
        Collection<Participant> participants = meetingParticipants.getOrDefault(meetingId, Map.of()).values();
        sendMessage(session, Map.of(
                "type", "participants",
                "items", participants
        ));
    }

    private void broadcastToOthers(WebSocketSession sender, String meetingId, Map<String, ?> message) throws IOException {
        Map<String, WebSocketSession> sessions = meetingSessions.getOrDefault(meetingId, Map.of());
        String payload = serializeMessage(message);
        for (WebSocketSession session : sessions.values()) {
            if (!session.getId().equals(sender.getId()) && session.isOpen()) {
                session.sendMessage(new TextMessage(payload));
            }
        }
    }

    private Map<String, Object> parseMessage(String payload) {
        try {
            return objectMapper.readValue(payload, HashMap.class);
        } catch (Exception e) {
            log.error("Ошибка при разборе сообщения: {}", e.getMessage());
            return new HashMap<>();
        }
    }

    private String serializeMessage(Map<String, ?> message) throws JsonProcessingException {
        return objectMapper.writeValueAsString(message);
    }

    private void sendMessage(WebSocketSession session, Map<String, ?> payload) throws IOException {
        session.sendMessage(new TextMessage(serializeMessage(payload)));
    }

    private record Participant(String sessionId, String userName) {}
}
