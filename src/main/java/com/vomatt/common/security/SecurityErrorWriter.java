package com.vomatt.common.security;

import com.vomatt.common.i18n.MessageKey;
import com.vomatt.common.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.LocaleResolver;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Writes filter-level errors (401/403/429) as {@link ApiResponse#error(String, String)} JSON. */
@Component
@RequiredArgsConstructor
public class SecurityErrorWriter {

    private final ObjectMapper objectMapper;
    private final MessageSource messageSource;
    private final LocaleResolver localeResolver;

    public void write(HttpServletRequest request, HttpServletResponse response, int status, MessageKey key)
            throws IOException {
        // Filters run before DispatcherServlet, so resolve Accept-Language with the shared resolver ourselves
        String message = messageSource.getMessage(key.code(), null, localeResolver.resolveLocale(request));
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(ApiResponse.error(message, key.code())));
    }
}
