package com.vomatt.common.config;

import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.core.converter.ResolvedSchema;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.core.ResolvableType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Type;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Documentation only. Endpoint-level {@code @ApiResponse(content = @Content(examples = ...))} makes springdoc
 * emit the examples without a schema, so typed clients lose the response type. This fills the missing 2xx schema
 * from the handler's full generic return type and drops the generic 200 that {@code @CommonApiResponses} adds to
 * endpoints that actually answer 201.
 */
@Component
public class ResponseSchemaCustomizer implements OperationCustomizer, OpenApiCustomizer {

    private final Map<String, Schema> referenced = new ConcurrentHashMap<>();

    @Override
    public Operation customize(Operation operation, HandlerMethod handlerMethod) {
        if (operation.getResponses() == null) {
            return operation;
        }
        if (operation.getResponses().containsKey("201")) {
            operation.getResponses().remove("200");
        }
        ResolvedSchema resolved = null;
        for (Map.Entry<String, ApiResponse> entry : operation.getResponses().entrySet()) {
            if (!entry.getKey().startsWith("2") || entry.getValue().getContent() == null) {
                continue;
            }
            for (MediaType mediaType : entry.getValue().getContent().values()) {
                if (mediaType.getSchema() == null) {
                    resolved = resolved != null ? resolved : resolve(handlerMethod);
                    mediaType.setSchema(resolved.schema);
                }
            }
        }
        if (resolved != null && resolved.referencedSchemas != null) {
            referenced.putAll(resolved.referencedSchemas);
        }
        return operation;
    }

    @Override
    public void customise(OpenAPI openApi) {
        Components components = openApi.getComponents();
        referenced.forEach((name, schema) -> {
            if (components.getSchemas() == null || !components.getSchemas().containsKey(name)) {
                components.addSchemas(name, schema);
            }
        });
    }

    private ResolvedSchema resolve(HandlerMethod handlerMethod) {
        ResolvableType returnType = ResolvableType.forMethodReturnType(handlerMethod.getMethod());
        if (ResponseEntity.class.equals(returnType.resolve())) {
            returnType = returnType.getGeneric(0);
        }
        Type type = returnType.getType();
        return ModelConverters.getInstance().resolveAsResolvedSchema(new AnnotatedType(type).resolveAsRef(true));
    }
}
