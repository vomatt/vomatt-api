package com.vomatt.common.security;

import com.vomatt.common.i18n.MessageKey;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final SecurityErrorWriter errorWriter;

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex)
            throws IOException {
        // JwtAuthFilter records why a presented token was rejected; absent means no token at all
        MessageKey key = request.getAttribute(JwtAuthFilter.AUTH_ERROR_ATTR) instanceof MessageKey k
                ? k : MessageKey.COMMON_UNAUTHORIZED;
        errorWriter.write(request, response, HttpServletResponse.SC_UNAUTHORIZED, key);
    }
}
