//package com.dev.cord.controller;
//
//import com.dev.cord.dto.MessageDto;
//import com.dev.cord.model.Channel;
//import com.dev.cord.model.Message;
//import com.dev.cord.model.User;
//import com.dev.cord.repository.ChannelRepository;
//import com.dev.cord.repository.CordUserRepository;
//import com.dev.cord.service.ChatService;
//import lombok.AllArgsConstructor;
//import lombok.extern.slf4j.Slf4j;
//import org.springframework.http.ResponseEntity;
//import org.springframework.web.bind.annotation.*;
//
//import java.util.Optional;
//
//@RestController
//@RequestMapping("/api/chat")
//@AllArgsConstructor
//@Slf4j
//public class ChatController {
//
//    private final ChatService chatService;
//    private final CordUserRepository userRepository;
//    private final ChannelRepository channelRepository;
//
//    // Создать пользователя (например, для симуляции подключения нового пользователя)
//    @PostMapping("/user")
//    public ResponseEntity<User> createUser(@RequestBody String username) {
//        User user = new User(username);
//        return ResponseEntity.ok(userRepository.save(user));
//    }
//
//    // Создать канал (комнату чата)
//    @PostMapping("/channel")
//    public ResponseEntity<Channel> createChannel(@RequestBody String channelName) {
//        Channel channel = new Channel(channelName);
//        return ResponseEntity.ok(channelRepository.save(channel));
//    }
//
//    // Отправить сообщение в канал
//    @PostMapping("/{channelId}/send")
//    public ResponseEntity<Message> sendMessage(
//            @PathVariable String channelId, @RequestBody MessageDto messageDto) {
//
//        Optional<User> userOpt = userRepository.findById(messageDto.getUserId());
//        if (userOpt.isEmpty()) {
//            return ResponseEntity.badRequest().build();
//        }
//
//        User sender = userOpt.get();
//        Message message = chatService.sendMessage(channelId, messageDto.getMessage(), sender);
//
//        // Логируем сообщение в терминале
//        log.info("Сообщение от {} в канале {}: {}", sender.getUsername(), channelId, messageDto.getMessage());
//
//        return ResponseEntity.ok(message);
//    }
//
//    // Получить все сообщения из канала
//    @GetMapping("/{channelId}/messages")
//    public ResponseEntity<Iterable<Message>> getMessages(@PathVariable String channelId) {
//        Optional<Channel> channelOpt = chatService.getChannelById(channelId);
//        return channelOpt
//                .<ResponseEntity<Iterable<Message>>> map(
//                        channel -> ResponseEntity.ok(channel.getMessages()))
//                .orElseGet(
//                        () -> ResponseEntity.notFound().build());
//    }
//}
