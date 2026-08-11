package com.ktb.chatapp.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ktb.chatapp.dto.LoginRequest;
import com.ktb.chatapp.model.User;
import com.ktb.chatapp.repository.UserRepository;
import com.ktb.chatapp.security.AuthenticatedUser;
import com.ktb.chatapp.service.JwtService;
import com.ktb.chatapp.service.SessionCreationResult;
import com.ktb.chatapp.service.SessionMetadata;
import com.ktb.chatapp.service.SessionService;
import com.ktb.chatapp.service.UserDetailsServiceImpl;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.validation.BindingResult;

@ExtendWith(MockitoExtension.class)
class AuthControllerHotPathTest {

    @Mock
    private AuthenticationManager authenticationManager;
    @Mock
    private UserRepository userRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private JwtService jwtService;
    @Mock
    private SessionService sessionService;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private Authentication authentication;
    @Mock
    private BindingResult bindingResult;
    @Mock
    private HttpServletRequest request;

    @InjectMocks
    private AuthController controller;

    @Test
    void login_reusesAuthenticatedUserAndLetsCreateSessionReplaceTheOldSession() {
        User user = User.builder()
                .id("user-1")
                .email("user@example.com")
                .password("encoded-password")
                .build();
        when(authenticationManager.authenticate(any())).thenReturn(authentication);
        when(authentication.getPrincipal()).thenReturn(new AuthenticatedUser(user));
        when(sessionService.createSession(eq("user-1"), any(SessionMetadata.class)))
                .thenReturn(SessionCreationResult.builder().sessionId("session-1").build());
        when(jwtService.generateToken("session-1", "user@example.com", "user-1"))
                .thenReturn("token-1");

        ResponseEntity<?> response = controller.login(
                new LoginRequest("USER@example.com", "password"),
                bindingResult,
                request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verifyNoInteractions(userRepository);
        verify(sessionService, never()).removeAllUserSessions("user-1");
        verify(sessionService).createSession(eq("user-1"), any(SessionMetadata.class));
    }

    @Test
    void userDetailsKeepsTheAuthenticatedDomainUser() {
        User user = User.builder()
                .id("user-1")
                .email("user@example.com")
                .password("encoded-password")
                .build();
        when(userRepository.findByEmail("user@example.com")).thenReturn(java.util.Optional.of(user));

        Object principal = new UserDetailsServiceImpl(userRepository)
                .loadUserByUsername("USER@example.com");

        assertThat(principal).isEqualTo(new AuthenticatedUser(user));
    }
}
