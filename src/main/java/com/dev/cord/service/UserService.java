package com.dev.cord.service;

import com.dev.cord.model.CordUser;
import com.dev.cord.repository.CordUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class UserService {
    private final CordUserRepository userRepository;

    public CordUser register(CordUser user) {
        // Здесь можно добавить проверку на существование пользователя и хеширование пароля
        return userRepository.save(user);
    }

    public CordUser login(String email, String password) {
        CordUser user = userRepository.findByEmail(email);
        if (user != null && user.getPassword().equals(password)) {
            user.setStatus("online");
            userRepository.save(user);
            return user;
        }
        return null;
    }

    public void logout(CordUser user) {
        user.setStatus("offline");
        userRepository.save(user);
    }

    public List<CordUser> getAllUsers() {
        return userRepository.findAll();
    }
}

