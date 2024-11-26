package com.dev.cord.service;

import com.dev.cord.model.Channel;
import com.dev.cord.model.Message;
import com.dev.cord.model.User;
import com.dev.cord.repository.ChannelRepository;
import com.dev.cord.repository.MessageRepository;
import lombok.AllArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@AllArgsConstructor
public class ChatService {
    private final ChannelRepository channelRepository;
    private final MessageRepository messageRepository;
    private final SimpMessagingTemplate messagingTemplate;

    public Message sendMessage(String channelId, String messageContent, User sender) {
        Optional<Channel> channelOpt = channelRepository.findById(Long.parseLong(channelId));
        if (channelOpt.isPresent()) {
            Channel channel = channelOpt.get();
            Message message = new Message(messageContent, sender, channel);
            messagingTemplate.convertAndSend("/topic/messages", message);
            return messageRepository.save(message); // Сохраняем сообщение в базе
        } else {
            throw new RuntimeException("Channel not found");
        }
    }

    public Optional<Channel> getChannelById(String channelId) {
        return channelRepository.findById(Long.parseLong(channelId));
    }
}
