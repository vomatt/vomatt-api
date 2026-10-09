package com.vomatt.common.security;

import com.vomatt.common.i18n.LocalizedMessageService;
import com.vomatt.common.i18n.MessageKey;
import com.vomatt.common.i18n.VomattLocaleResolver;
import com.vomatt.common.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.i18n.LocaleContext;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Writes filter-level errors (401/403/429) as {@link ApiResponse#error(String, String)} JSON. */
@Component
@RequiredArgsConstructor
public class SecurityErrorWriter {

    private final ObjectMapper objectMapper;
    private final LocalizedMessageService messages;
    private final VomattLocaleResolver localeResolver = new VomattLocaleResolver();

    public void write(HttpServletRequest request, HttpServletResponse response, int status, MessageKey key)
            throws IOException {
        // Filters run before DispatcherServlet, so resolve Accept-Language ourselves
        LocaleContext previous = LocaleContextHolder.getLocaleContext();
        String message;
        try {
            LocaleContextHolder.setLocale(localeResolver.resolveLocale(request));
            message = messages.resolve(key);
        } finally {
            LocaleContextHolder.setLocaleContext(previous);
        }
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(ApiResponse.error(message, key.code())));
    }
}
