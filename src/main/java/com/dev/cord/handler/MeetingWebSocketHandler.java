package com.dev.cord.handler;

import com.dev.cord.service.MeetingService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

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
public class MeetingWebSocketHandler extends TextWebSocketHandler {

    private final MeetingService meetingService;
    private final ObjectMapper objectMapper;

    // Scheduler для отложенных операций
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    // Grace period для завершения встречи (в секундах)
    private static final int MEETING_END_GRACE_PERIOD_SECONDS = 10;

    // meetingId -> (sessionId -> WebSocketSession)
    private final Map<String, Map<String, WebSocketSession>> meetingSessions = new ConcurrentHashMap<>();
    // meetingId -> (sessionId -> Participant)
    private final Map<String, Map<String, Participant>> meetingParticipants = new ConcurrentHashMap<>();
    // meetingId -> List<ChatMessage>
    private final Map<String, List<ChatMessage>> meetingChatHistory = new ConcurrentHashMap<>();
    // sessionId -> meetingId
    private final Map<String, String> sessionIdToMeetingId = new ConcurrentHashMap<>();
    // meetingId -> ScheduledFuture для отложенного завершения
    private final Map<String, ScheduledFuture<?>> pendingMeetingEnd = new ConcurrentHashMap<>();

    public MeetingWebSocketHandler(MeetingService meetingService, ObjectMapper objectMapper) {
        this.meetingService = meetingService;
        this.objectMapper = objectMapper;
    }

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

        // Отменяем pending завершение встречи, если было
        ScheduledFuture<?> pendingEnd = pendingMeetingEnd.remove(meetingId);
        if (pendingEnd != null) {
            pendingEnd.cancel(false);
            log.info("Отменено отложенное завершение встречи {} - новый участник подключился", meetingId);
        }

        // Сохраняем сессию
        meetingSessions.computeIfAbsent(meetingId, k -> new ConcurrentHashMap<>())
                .put(session.getId(), session);
        sessionIdToMeetingId.put(session.getId(), meetingId);

        // Сразу регистрируем участника как "Гость", если его ещё нет
        Map<String, Participant> participants =
                meetingParticipants.computeIfAbsent(meetingId, k -> new ConcurrentHashMap<>());

        participants.putIfAbsent(session.getId(),
                new Participant(session.getId(), "Гость", true, true));

        // 1. Отправляем sessionId клиенту
        send(session, Map.of(
                "type", "your-id",
                "sessionId", session.getId()
        ));

        // 2. История чата
        List<ChatMessage> history = meetingChatHistory.getOrDefault(meetingId, List.of());
        send(session, Map.of(
                "type", "chat-history",
                "items", history
        ));

        // 3. Текущий список участников (с уже добавленным "Гость")
        send(session, Map.of(
                "type", "participants",
                "items", new ArrayList<>(participants.values())
        ));

        // 4. Сообщаем остальным, что появился новый участник
        broadcastToOthers(session.getId(), meetingId, Map.of(
                "type", "new-user",
                "sessionId", session.getId(),
                "userName", participants.get(session.getId()).userName()
        ));

        log.info("Session {} connected to meeting {}", session.getId(), meetingId);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage textMessage) throws Exception {
        String meetingId = sessionIdToMeetingId.get(session.getId());
        if (meetingId == null) {
            log.warn("Session {} не привязана к встрече, закрываем", session.getId());
            session.close(CloseStatus.BAD_DATA);
            return;
        }

        Map<String, Object> msg = parseJson(textMessage.getPayload());
        String type = (String) msg.get("type");
        if (type == null) {
            return;
        }

        log.debug("Message '{}' from {} in {}", type, session.getId(), meetingId);

        switch (type) {
            case "set-name" -> handleSetName(session, meetingId, msg);
            case "media-status" -> handleMediaStatus(session, meetingId, msg);
            case "chat" -> handleChat(session, meetingId, msg);
            case "leave" -> {
                log.info("Participant {} requested leave from {}", session.getId(), meetingId);
                session.close(CloseStatus.NORMAL);
            }
            case "offer", "answer", "candidate" -> relayWebRtcSignal(session, meetingId, msg);
            case "ping" -> {
                // RTT для клиента
                Object t = msg.get("t");
                send(session, Map.of(
                        "type", "pong",
                        "t", t
                ));
            }
            default -> log.debug("Unknown message type: {}", type);
        }

    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable ex) throws Exception {
        log.warn("Transport error for session {}: {}", session.getId(), ex.getMessage());
        super.handleTransportError(session, ex);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        String meetingId = sessionIdToMeetingId.remove(session.getId());
        if (meetingId == null) {
            log.debug("Session {} closed without meeting association", session.getId());
            return;
        }

        log.info("Session {} disconnected from meeting {} (status: {})",
                session.getId(), meetingId, status);

        // Удаляем участника
        Map<String, Participant> participants = meetingParticipants.get(meetingId);
        Participant leaving = participants != null ? participants.remove(session.getId()) : null;

        // Удаляем сессию
        Map<String, WebSocketSession> sessions = meetingSessions.get(meetingId);
        if (sessions != null) {
            sessions.remove(session.getId());

            if (sessions.isEmpty()) {
                // Комната пуста - планируем отложенное завершение
                log.info("Meeting {} is empty, scheduling end in {} seconds",
                        meetingId, MEETING_END_GRACE_PERIOD_SECONDS);

                ScheduledFuture<?> future = scheduler.schedule(() -> {
                    endMeetingIfEmpty(meetingId);
                }, MEETING_END_GRACE_PERIOD_SECONDS, TimeUnit.SECONDS);

                pendingMeetingEnd.put(meetingId, future);
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
            log.info("Participant '{}' left meeting '{}'", leaving.userName(), meetingId);
        }
    }

    /**
     * Завершает встречу если она всё ещё пуста
     */
    private void endMeetingIfEmpty(String meetingId) {
        pendingMeetingEnd.remove(meetingId);

        Map<String, WebSocketSession> sessions = meetingSessions.get(meetingId);
        if (sessions == null || sessions.isEmpty()) {
            // Действительно пусто - завершаем
            meetingSessions.remove(meetingId);
            meetingParticipants.remove(meetingId);
            meetingChatHistory.remove(meetingId);
            meetingService.endMeeting(meetingId);
            log.info("Meeting {} ended - no participants after grace period", meetingId);
        } else {
            log.info("Meeting {} not ended - participants rejoined during grace period", meetingId);
        }
    }

    /**
     * Регистрация участника по имени
     */
//    private void handleSetName(WebSocketSession session, String meetingId, Map<String, Object> msg) throws IOException {
//        String name = (String) msg.get("name");
//        String userName = StringUtils.hasText(name) ? name.trim() : "Гость";
//
//        Map<String, Participant> participants = meetingParticipants.computeIfAbsent(meetingId, k -> new ConcurrentHashMap<>());
//        boolean isNew = !participants.containsKey(session.getId());
//
//        // Получаем существующего или создаём нового
//        Participant existing = participants.get(session.getId());
//        Participant updated = new Participant(
//                session.getId(),
//                userName,
//                existing != null ? existing.audioEnabled() : true,
//                existing != null ? existing.videoEnabled() : true
//        );
//        participants.put(session.getId(), updated);
//
//        if (isNew) {
//            // Уведомляем ДРУГИХ о новом участнике (для WebRTC)
//            broadcastToOthers(session.getId(), meetingId, Map.of(
//                    "type", "new-user",
//                    "sessionId", session.getId(),
//                    "userName", userName
//            ));
//            log.info("New participant '{}' in meeting '{}'", userName, meetingId);
//        }
//
//        // Рассылаем обновлённый список ВСЕМ
//        broadcastParticipantsList(meetingId);
//    }

    private void handleSetName(WebSocketSession session, String meetingId, Map<String, Object> msg) throws IOException {
        String name = (String) msg.get("name");
        String userName = StringUtils.hasText(name) ? name.trim() : "Гость";

        Map<String, Participant> participants =
                meetingParticipants.computeIfAbsent(meetingId, k -> new ConcurrentHashMap<>());

        Participant existing = participants.get(session.getId());
        Participant updated = new Participant(
                session.getId(),
                userName,
                existing == null || existing.audioEnabled(),
                existing == null || existing.videoEnabled()
        );
        participants.put(session.getId(), updated);

        log.info("Participant '{}' set name to '{}' in meeting '{}'",
                session.getId(), userName, meetingId);

        // Рассылаем обновлённый список всем
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

        log.debug("Media status '{}': audio={}, video={}", existing.userName(), updated.audioEnabled(), updated.videoEnabled());

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
        if (receiver == null) {
            log.warn("WebRTC signal without receiver from {}", session.getId());
            return;
        }

        Map<String, WebSocketSession> sessions = meetingSessions.get(meetingId);
        if (sessions == null) {
            log.warn("No sessions for meeting {} when relaying WebRTC signal", meetingId);
            return;
        }

        WebSocketSession target = sessions.get(receiver);
        if (target != null && target.isOpen()) {
            msg.put("sender", session.getId());
            send(target, msg);
            log.debug("Relayed {} from {} to {}", msg.get("type"), session.getId(), receiver);
        } else {
            log.warn("Target session {} not found or closed for WebRTC signal", receiver);
        }
    }

    /**
     * Рассылка списка участников всем в комнате
     */
    private void broadcastParticipantsList(String meetingId) throws IOException {
        Map<String, Participant> participants = meetingParticipants.getOrDefault(meetingId, Map.of());
        List<Participant> list = new ArrayList<>(participants.values());

        log.debug("Broadcasting participants list: {} participants", list.size());

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
                try {
                    s.sendMessage(new TextMessage(json));
                } catch (IOException e) {
                    log.warn("Failed to send message to session {}: {}", s.getId(), e.getMessage());
                }
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
                try {
                    s.sendMessage(new TextMessage(json));
                } catch (IOException e) {
                    log.warn("Failed to send message to session {}: {}", s.getId(), e.getMessage());
                }
            }
        }
    }

    private void send(WebSocketSession session, Map<String, ?> msg) throws IOException {
        if (session.isOpen()) {
            session.sendMessage(new TextMessage(toJson(msg)));
        }
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJson(String json) {
        try {
            return objectMapper.readValue(json, HashMap.class);
        } catch (Exception e) {
            log.error("JSON parse error: {}", e.getMessage());
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
