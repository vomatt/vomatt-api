package com.vomatt.common.config;

import com.vomatt.common.annotation.ApiError;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.examples.Example;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Documentation only: renders each {@link ApiError} on a handler as an {@code **Errors**} bullet appended to the
 * description and as an example under its status, with the English message the API would answer.
 */
@Component
@RequiredArgsConstructor
public class ApiErrorCustomizer implements OperationCustomizer {

    private final MessageSource messageSource;

    @Override
    public Operation customize(Operation operation, HandlerMethod handlerMethod) {
        ApiError[] errors = handlerMethod.getMethod().getAnnotationsByType(ApiError.class);
        if (errors.length == 0) {
            return operation;
        }
        StringBuilder bullets = new StringBuilder("**Errors**:\n");
        // status -> example name -> example; a code listed twice under one status keeps one example
        Map<String, Map<String, Example>> examples = new LinkedHashMap<>();
        for (ApiError error : errors) {
            String code = error.key().code();
            bullets.append("- ").append(error.status()).append(" `").append(code).append("`: ").append(error.when()).append('\n');
            examples.computeIfAbsent(String.valueOf(error.status()), s -> new LinkedHashMap<>())
                    .merge(code, example(error), (first, again) -> first.description(first.getDescription() + "; " + again.getDescription()));
        }
        String description = operation.getDescription() == null ? "" : operation.getDescription();
        operation.setDescription(description + (description.isEmpty() || description.endsWith("\n") ? "" : "\n") + bullets);

        ApiResponses responses = operation.getResponses() != null ? operation.getResponses() : new ApiResponses();
        examples.forEach((status, named) -> {
            ApiResponse existing = responses.get(status);
            // New objects: springdoc may share the responses built from @CommonApiResponses between operations
            MediaType json = new MediaType().schema(new Schema<>().$ref(OpenAPIConfig.ERROR_REF)).examples(new LinkedHashMap<>(named));
            responses.addApiResponse(status, new ApiResponse()
                    .description(existing != null && existing.getDescription() != null ? existing.getDescription()
                            : HttpStatus.valueOf(Integer.parseInt(status)).getReasonPhrase())
                    .headers(existing != null ? existing.getHeaders() : null)
                    .content(new Content().addMediaType(org.springframework.http.MediaType.APPLICATION_JSON_VALUE, json)));
        });
        operation.setResponses(responses);
        return operation;
    }

    private Example example(ApiError error) {
        String message = messageSource.getMessage(error.key().code(), error.args().length == 0 ? null : error.args(), Locale.ENGLISH);
        if (message.matches("(?s).*\\{\\d.*")) {
            throw new IllegalStateException("@ApiError " + error.key() + " needs args for: " + message);
        }
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("success", false);
        value.put("data", null);
        value.put("message", message);
        value.put("errorCode", error.key().code());
        value.put("error", message);
        return new Example().description(error.when()).value(value);
    }
}
