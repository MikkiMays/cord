package com.dev.cord.model;

import com.fasterxml.jackson.annotation.JsonManagedReference;
import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.util.List;

@Entity
@Table(name = "cord_channel")
@NoArgsConstructor
@Data
@EqualsAndHashCode
public class Channel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", unique = true)
    private String name;

    @Column(name = "ban")
    private boolean ban;

    @OneToMany(mappedBy = "channel", cascade = CascadeType.ALL)
    @JsonManagedReference
    private List<Message> messages;

    public Channel(String name) {
        this.name = name;
    }
}


