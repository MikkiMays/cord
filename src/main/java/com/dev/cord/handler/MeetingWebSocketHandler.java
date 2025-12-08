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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket handler для управления видеовстречами.
 *
 * Протокол сообщений:
 * ==================
 *
 * Сервер -> Клиент:
 * - your-id: {sessionId} - идентификатор клиента при подключении
 * - participants: {items[]} - список всех участников
 * - new-user: {sessionId, userName} - новый участник (для инициации WebRTC)
 * - user-left: {sessionId, userName} - участник вышел
 * - chat: {message} - сообщение чата
 * - chat-history: {items[]} - история чата при подключении
 * - media-status: {sender, audioEnabled, videoEnabled} - изменение медиа
 *
 * Клиент -> Сервер:
 * - set-name: {name} - регистрация имени
 * - media-status: {audioEnabled, videoEnabled} - изменение медиа
 * - chat: {content, clientMessageId} - сообщение чата
 * - leave: {} - выход
 * - offer/answer/candidate: WebRTC сигналы
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class MeetingWebSocketHandler extends TextWebSocketHandler {

    private final MeetingService meetingService;
    private final ObjectMapper objectMapper;

    // meetingId -> (sessionId -> WebSocketSession)
    private final Map<String, Map<String, WebSocketSession>> meetingSessions = new ConcurrentHashMap<>();
    // meetingId -> (sessionId -> Participant)
    private final Map<String, Map<String, Participant>> meetingParticipants = new ConcurrentHashMap<>();
    // meetingId -> List<ChatMessage>
    private final Map<String, List<ChatMessage>> meetingChatHistory = new ConcurrentHashMap<>();
    // sessionId -> meetingId
    private final Map<String, String> sessionIdToMeetingId = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String meetingId = extractMeetingId(session).orElse(null);
        if (meetingId == null) {
            log.warn("WebSocket без meetingId, закрываем: {}", session.getId());
            session.close(CloseStatus.BAD_DATA);
            return;
        }

        if (meetingService.getMeeting(meetingId).isEmpty()) {
            log.warn("Встреча {} не найдена, закрываем: {}", meetingId, session.getId());
            session.close(new CloseStatus(4404, "Meeting not found"));
            return;
        }

        // Сохраняем сессию
        meetingSessions.computeIfAbsent(meetingId, k -> new ConcurrentHashMap<>())
                .put(session.getId(), session);
        sessionIdToMeetingId.put(session.getId(), meetingId);

        // 1. Отправляем sessionId клиенту
        send(session, Map.of(
                "type", "your-id",
                "sessionId", session.getId()
        ));

        // 2. Отправляем историю чата
        List<ChatMessage> history = meetingChatHistory.getOrDefault(meetingId, List.of());
        send(session, Map.of(
                "type", "chat-history",
                "items", history
        ));

        // 3. Отправляем текущий список участников
        Map<String, Participant> participants = meetingParticipants.getOrDefault(meetingId, Map.of());
        send(session, Map.of(
                "type", "participants",
                "items", new ArrayList<>(participants.values())
        ));

        log.info("Сессия {} подключилась к встрече {}", session.getId(), meetingId);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage textMessage) throws Exception {
        String meetingId = sessionIdToMeetingId.get(session.getId());
        if (meetingId == null) {
            session.close(CloseStatus.BAD_DATA);
            return;
        }

        Map<String, Object> msg = parseJson(textMessage.getPayload());
        String type = (String) msg.get("type");
        if (type == null) {
            return;
        }

        log.debug("Сообщение '{}' от {} в {}", type, session.getId(), meetingId);

        switch (type) {
            case "set-name" -> handleSetName(session, meetingId, msg);
            case "media-status" -> handleMediaStatus(session, meetingId, msg);
            case "chat" -> handleChat(session, meetingId, msg);
            case "leave" -> session.close(CloseStatus.NORMAL);
            case "offer", "answer", "candidate" -> relayWebRtcSignal(session, meetingId, msg);
            default -> log.debug("Неизвестный тип: {}", type);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable ex) throws Exception {
        log.warn("Ошибка транспорта {}: {}", session.getId(), ex.getMessage());
        super.handleTransportError(session, ex);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        String meetingId = sessionIdToMeetingId.remove(session.getId());
        if (meetingId == null) {
            return;
        }

        // Удаляем участника
        Map<String, Participant> participants = meetingParticipants.get(meetingId);
        Participant leaving = participants != null ? participants.remove(session.getId()) : null;

        // Удаляем сессию
        Map<String, WebSocketSession> sessions = meetingSessions.get(meetingId);
        if (sessions != null) {
            sessions.remove(session.getId());

            if (sessions.isEmpty()) {
                // Комната пуста - очищаем всё
                meetingSessions.remove(meetingId);
                meetingParticipants.remove(meetingId);
                meetingChatHistory.remove(meetingId);
                meetingService.endMeeting(meetingId);
                log.info("Встреча {} завершена - все вышли", meetingId);
                return;
            }
        }

        // Уведомляем остальных
        if (leaving != null) {
            broadcast(meetingId, Map.of(
                    "type", "user-left",
                    "sessionId", session.getId(),
                    "userName", leaving.userName()
            ));

            // Рассылаем обновлённый список участников
            broadcastParticipantsList(meetingId);
            log.info("Участник '{}' покинул '{}'", leaving.userName(), meetingId);
        }
    }

    /**
     * Регистрация участника по имени
     */
    private void handleSetName(WebSocketSession session, String meetingId, Map<String, Object> msg) throws IOException {
        String name = (String) msg.get("name");
        String userName = StringUtils.hasText(name) ? name.trim() : "Гость";

        Map<String, Participant> participants = meetingParticipants.computeIfAbsent(meetingId, k -> new ConcurrentHashMap<>());
        boolean isNew = !participants.containsKey(session.getId());

        // Получаем существующего или создаём нового
        Participant existing = participants.get(session.getId());
        Participant updated = new Participant(
                session.getId(),
                userName,
                existing != null ? existing.audioEnabled() : true,
                existing != null ? existing.videoEnabled() : true
        );
        participants.put(session.getId(), updated);

        if (isNew) {
            // Уведомляем ДРУГИХ о новом участнике (для WebRTC)
            broadcastToOthers(session.getId(), meetingId, Map.of(
                    "type", "new-user",
                    "sessionId", session.getId(),
                    "userName", userName
            ));
            log.info("Новый участник '{}' в '{}'", userName, meetingId);
        }

        // Рассылаем обновлённый список ВСЕМ
        broadcastParticipantsList(meetingId);
    }

    /**
     * Обработка изменения статуса медиа (микрофон/камера)
     */
    private void handleMediaStatus(WebSocketSession session, String meetingId, Map<String, Object> msg) throws IOException {
        Map<String, Participant> participants = meetingParticipants.get(meetingId);
        if (participants == null) return;

        Participant existing = participants.get(session.getId());
        if (existing == null) return;

        Boolean audio = msg.containsKey("audioEnabled") ? (Boolean) msg.get("audioEnabled") : null;
        Boolean video = msg.containsKey("videoEnabled") ? (Boolean) msg.get("videoEnabled") : null;

        // Обновляем участника
        Participant updated = new Participant(
                existing.sessionId(),
                existing.userName(),
                audio != null ? audio : existing.audioEnabled(),
                video != null ? video : existing.videoEnabled()
        );
        participants.put(session.getId(), updated);

        log.debug("Медиа статус '{}': audio={}, video={}", existing.userName(), updated.audioEnabled(), updated.videoEnabled());

        // ВАЖНО: Рассылаем обновлённый список ВСЕМ чтобы UI обновился
        broadcastParticipantsList(meetingId);
    }

    /**
     * Обработка сообщения чата
     */
    private void handleChat(WebSocketSession session, String meetingId, Map<String, Object> msg) throws IOException {
        String content = (String) msg.get("content");
        if (content == null) content = (String) msg.get("message");
        if (!StringUtils.hasText(content)) return;

        String clientMessageId = (String) msg.get("clientMessageId");

        Map<String, Participant> participants = meetingParticipants.get(meetingId);
        Participant sender = participants != null ? participants.get(session.getId()) : null;
        String userName = sender != null ? sender.userName() : "Гость";

        ChatMessage chatMsg = new ChatMessage(
                session.getId(),
                userName,
                content.trim(),
                System.currentTimeMillis(),
                clientMessageId
        );

        // Сохраняем в историю
        meetingChatHistory.computeIfAbsent(meetingId, k -> new ArrayList<>()).add(chatMsg);

        // Рассылаем ТОЛЬКО ДРУГИМ (отправитель уже добавил локально)
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "chat");
        payload.put("message", chatMsg);
        broadcastToOthers(session.getId(), meetingId, payload);
    }

    /**
     * Ретрансляция WebRTC сигнала конкретному получателю
     */
    private void relayWebRtcSignal(WebSocketSession session, String meetingId, Map<String, Object> msg) throws IOException {
        String receiver = (String) msg.get("receiver");
        if (receiver == null) return;

        Map<String, WebSocketSession> sessions = meetingSessions.get(meetingId);
        if (sessions == null) return;

        WebSocketSession target = sessions.get(receiver);
        if (target != null && target.isOpen()) {
            msg.put("sender", session.getId());
            send(target, msg);
        }
    }

    /**
     * Рассылка списка участников всем в комнате
     */
    private void broadcastParticipantsList(String meetingId) throws IOException {
        Map<String, Participant> participants = meetingParticipants.getOrDefault(meetingId, Map.of());
        List<Participant> list = new ArrayList<>(participants.values());

        log.debug("Рассылка списка участников: {} чел.", list.size());

        broadcast(meetingId, Map.of(
                "type", "participants",
                "items", list
        ));
    }

    /**
     * Рассылка всем в комнате
     */
    private void broadcast(String meetingId, Map<String, ?> msg) throws IOException {
        Map<String, WebSocketSession> sessions = meetingSessions.getOrDefault(meetingId, Map.of());
        String json = toJson(msg);
        for (WebSocketSession s : sessions.values()) {
            if (s.isOpen()) {
                s.sendMessage(new TextMessage(json));
            }
        }
    }

    /**
     * Рассылка всем кроме отправителя
     */
    private void broadcastToOthers(String senderId, String meetingId, Map<String, ?> msg) throws IOException {
        Map<String, WebSocketSession> sessions = meetingSessions.getOrDefault(meetingId, Map.of());
        String json = toJson(msg);
        for (WebSocketSession s : sessions.values()) {
            if (!s.getId().equals(senderId) && s.isOpen()) {
                s.sendMessage(new TextMessage(json));
            }
        }
    }

    private void send(WebSocketSession session, Map<String, ?> msg) throws IOException {
        session.sendMessage(new TextMessage(toJson(msg)));
    }

    private Optional<String> extractMeetingId(WebSocketSession session) {
        URI uri = session.getUri();
        if (uri == null || uri.getQuery() == null) return Optional.empty();
        for (String param : uri.getQuery().split("&")) {
            String[] kv = param.split("=");
            if (kv.length == 2 && "meetingId".equals(kv[0])) {
                return Optional.of(kv[1]);
            }
        }
        return Optional.empty();
    }

    private Map<String, Object> parseJson(String json) {
        try {
            return objectMapper.readValue(json, HashMap.class);
        } catch (Exception e) {
            log.error("Ошибка парсинга JSON: {}", e.getMessage());
            return new HashMap<>();
        }
    }

    private String toJson(Map<String, ?> msg) throws JsonProcessingException {
        return objectMapper.writeValueAsString(msg);
    }

    // Записи для данных
    private record Participant(String sessionId, String userName, boolean audioEnabled, boolean videoEnabled) {}
    private record ChatMessage(String sessionId, String userName, String content, long timestamp, String clientMessageId) {}
}
