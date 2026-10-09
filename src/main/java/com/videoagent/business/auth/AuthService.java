package com.videoagent.business.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.videoagent.access.dto.AuthDtos.LoginResponse;
import com.videoagent.access.exception.BusinessException;
import com.videoagent.middleware.jwt.JwtUtil;
import com.videoagent.storage.entity.User;
import com.videoagent.storage.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 用户注册/登录 + JWT 签发。
 * 密码以 BCrypt 哈希存储，不存明文；登录成功签发 JWT 供后续请求携带。
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserMapper userMapper;
    private final JwtUtil jwtUtil;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public LoginResponse register(String username, String password) {
        String name = username.trim();
        Long exists = userMapper.selectCount(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, name));
        if (exists != null && exists > 0) {
            throw new BusinessException("用户名已存在");
        }
        User user = new User();
        user.setUsername(name);
        user.setPasswordHash(encoder.encode(password));
        user.setCreatedAt(LocalDateTime.now());
        userMapper.insert(user);
        String token = jwtUtil.create(user.getId(), user.getUsername());
        return new LoginResponse(token, user.getId(), user.getUsername());
    }

    public LoginResponse login(String username, String password) {
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, username.trim())
                .last("limit 1"));
        if (user == null || !encoder.matches(password, user.getPasswordHash())) {
            throw new BusinessException(401, "用户名或密码错误");
        }
        String token = jwtUtil.create(user.getId(), user.getUsername());
        return new LoginResponse(token, user.getId(), user.getUsername());
    }
}