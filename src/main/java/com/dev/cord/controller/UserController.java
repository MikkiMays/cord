package com.dev.cord.controller;

import com.dev.cord.model.CordUser;
import com.dev.cord.service.UserService;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/users")
@CrossOrigin(origins = "http://localhost:3000", allowCredentials = "true")
public class UserController {
    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    // Регистрация
    @PostMapping
    public CordUser register(@RequestBody CordUser user) {
        user.setStatus("online");
        return userService.register(user);
    }

    // Вход
    @PostMapping("/login")
    public CordUser login(@RequestBody CordUser user, HttpSession session) {
        CordUser loggedInUser = userService.login(user.getEmail(), user.getPassword());
        if (loggedInUser != null) {
            session.setAttribute("user", loggedInUser);
            return loggedInUser;
        } else {
            throw new RuntimeException("Invalid credentials");
        }
    }

    // Выход
    @PostMapping("/logout")
    public void logout(HttpSession session) {
        CordUser user = (CordUser) session.getAttribute("user");
        if (user != null) {
            userService.logout(user);
            session.invalidate();
        }
    }

    // Получение списка пользователей
    @GetMapping
    public List<CordUser> getAllUsers() {
        return userService.getAllUsers();
    }
}
