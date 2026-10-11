package com.vomatt.common.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.method.HandlerMethod;

import com.vomatt.common.response.ApiResponse;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.responses.ApiResponses;

class ResponseSchemaCustomizerTest {

    record Item(String name) {
    }

    @SuppressWarnings("unused")
    static class Handlers {
        ResponseEntity<ApiResponse<List<Item>>> list() {
            return null;
        }
    }

    private final ResponseSchemaCustomizer customizer = new ResponseSchemaCustomizer();

    private Operation operation(String... codes) throws Exception {
        ApiResponses responses = new ApiResponses();
        for (String code : codes) {
            responses.addApiResponse(code, new io.swagger.v3.oas.models.responses.ApiResponse()
                    .content(new Content().addMediaType("application/json", new MediaType())));
        }
        return new Operation().responses(responses);
    }

    private HandlerMethod handler() throws Exception {
        return new HandlerMethod(new Handlers(), Handlers.class.getDeclaredMethod("list"));
    }

    @Test
    @DisplayName("should fill in the full generic return type when a 2xx response has no schema")
    void shouldFillSchemaFromGenericReturnTypeWhenMissing() throws Exception {
        Operation op = customizer.customize(operation("200"), handler());

        MediaType json = op.getResponses().get("200").getContent().get("application/json");
        assertNotNull(json.getSchema());
        assertEquals("#/components/schemas/ApiResponseListItem", json.getSchema().get$ref());
    }

    @Test
    @DisplayName("should remove the generic 200 when a 201 is also present")
    void shouldDropGenericOkWhenCreatedDeclared() throws Exception {
        Operation op = customizer.customize(operation("200", "201"), handler());

        assertFalse(op.getResponses().containsKey("200"));
        assertNotNull(op.getResponses().get("201").getContent().get("application/json").getSchema());
    }

    @Test
    @DisplayName("should register referenced schemas into components")
    void shouldRegisterReferencedSchemasInComponents() throws Exception {
        customizer.customize(operation("200"), handler());
        OpenAPI openApi = new OpenAPI().components(new Components());

        customizer.customise(openApi);

        assertTrue(openApi.getComponents().getSchemas().containsKey("ApiResponseListItem"));
        assertTrue(openApi.getComponents().getSchemas().containsKey("Item"));
    }
}
