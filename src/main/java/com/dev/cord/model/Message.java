package com.dev.cord.model;

import com.fasterxml.jackson.annotation.JsonBackReference;
import jakarta.persistence.*;
import lombok.*;

import java.util.Date;

@Entity
@Table(name = "cord_message")
@NoArgsConstructor
@Data
@EqualsAndHashCode
public class Message {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "content")
    private String content;

    @Temporal(TemporalType.DATE)
    @Column(name = "send_time")
    private Date sendTime;

    @ManyToOne
    @JoinColumn(name = "sender")
    @JsonBackReference
    private User sender;

    @ManyToOne
    @JoinColumn(name = "channel")
    @JsonBackReference
    private Channel channel;

    public Message(String content, User sender, Channel channel) {
        this.content = content;
        this.sender = sender;
        this.channel = channel;
    }
}

