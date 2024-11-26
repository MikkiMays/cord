package com.dev.cord.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.concurrent.ConcurrentHashMap;
import java.util.*;

@Component
@Slf4j
public class MeetingWebSocketHandler extends TextWebSocketHandler {

    private final Map<String, Set<WebSocketSession>> meetings = new ConcurrentHashMap<>();
    private final Map<String, String> sessionIdToMeetingId = new ConcurrentHashMap<>();
    private final Map<String, String> sessionIdToUserName = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String meetingId = getMeetingId(session);
        if (meetingId == null) {
            session.close();
            return;
        }

        meetings.computeIfAbsent(meetingId, k -> ConcurrentHashMap.newKeySet()).add(session);
        sessionIdToMeetingId.put(session.getId(), meetingId);

        // Отправляем клиенту его sessionId
        Map<String, String> message = new HashMap<>();
        message.put("type", "your-id");
        message.put("sessionId", session.getId());
        session.sendMessage(new TextMessage(serializeMessage(message)));

        log.info("Новый пользователь подключился к встрече {}", meetingId);

        // Теперь ждём, пока клиент отправит нам своё имя
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage textMessage) throws Exception {
        String meetingId = sessionIdToMeetingId.get(session.getId());
        if (meetingId == null) return;

        Map<String, Object> message = parseMessage(textMessage.getPayload());
        String messageType = (String) message.get("type");

        if ("set-name".equals(messageType)) {
            String userName = (String) message.get("name");
            sessionIdToUserName.put(session.getId(), userName);

            // Логируем подключение нового пользователя с именем
            log.info("Пользователь '{}' подключился к встрече '{}'", userName, meetingId);

            // Отправляем другим участникам информацию о новом пользователе
            Map<String, Object> newUserMessage = new HashMap<>();
            newUserMessage.put("type", "new-user");
            newUserMessage.put("sessionId", session.getId());
            newUserMessage.put("userName", userName);

            broadcastToOthers(session, meetingId, newUserMessage);
        } else {
            message.put("sender", session.getId());

            broadcastToOthers(session, meetingId, message);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        String meetingId = sessionIdToMeetingId.get(session.getId());
        String userName = sessionIdToUserName.get(session.getId());
        if (meetingId != null) {
            meetings.get(meetingId).remove(session);
            if (meetings.get(meetingId).isEmpty()) {
                meetings.remove(meetingId);
            }
            // Сообщить остальным участникам об отключении пользователя
            Map<String, String> message = new HashMap<>();
            message.put("type", "user-left");
            message.put("sessionId", session.getId());
            message.put("userName", userName);

            broadcastToOthers(session, meetingId, message);

            // Логируем отключение пользователя
            log.info("Пользователь '{}' покинул встречу '{}'", userName, meetingId);
        }
        // Удаляем данные пользователя после отправки сообщений
        sessionIdToMeetingId.remove(session.getId());
        sessionIdToUserName.remove(session.getId());
    }

    private String getMeetingId(WebSocketSession session) {
        URI uri = session.getUri();
        if (uri != null) {
            String query = uri.getQuery();
            if (query != null) {
                String[] params = query.split("&");
                for (String param : params) {
                    String[] keyValue = param.split("=");
                    if (keyValue.length == 2 && "meetingId".equals(keyValue[0])) {
                        return keyValue[1];
                    }
                }
            }
        }
        return null;
    }

    private void broadcastToOthers(WebSocketSession sender, String meetingId, Map<String, ?> message) throws Exception {
        Set<WebSocketSession> sessions = meetings.get(meetingId);
        if (sessions != null) {
            String payload = serializeMessage(message);
            for (WebSocketSession session : sessions) {
                if (!session.getId().equals(sender.getId()) && session.isOpen()) {
                    session.sendMessage(new TextMessage(payload));
                }
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

    private String serializeMessage(Map<String, ?> message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (Exception e) {
            log.error("Ошибка при сериализации сообщения: {}", e.getMessage());
            return "{}";
        }
    }
}
