package com.vomatt.users;

import com.vomatt.common.annotation.PublicApiResponse;
import com.vomatt.common.annotation.StorageUnavailableResponse;
import com.vomatt.common.config.OpenAPIConfig;
import com.vomatt.common.exception.ApiException;
import com.vomatt.common.i18n.MessageKey;
import com.vomatt.common.security.SecurityEndpoints;
import com.vomatt.common.storage.StorageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

@RestController
@RequiredArgsConstructor
@Tag(name = "User", description = "User management APIs")
public class AvatarController {

    private final StorageService storageService;

    @GetMapping(SecurityEndpoints.AVATAR_FILE)
    @PublicApiResponse
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "The JPEG image",
            content = @Content(mediaType = MediaType.IMAGE_JPEG_VALUE, schema = @Schema(type = "string", format = "binary")))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No image at this path, e.g. replaced or deleted (errorCode `common.not_found`)",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                    examples = @ExampleObject(value = """
                            {"success":false,"data":null,"message":"Resource not found","errorCode":"common.not_found","error":"Resource not found"}""")))
    @StorageUnavailableResponse
    @Operation(summary = "Get an uploaded avatar", description = """
            **Auth**: none
            **Precondition**: `file` is `<id>.jpg` as in an `avatarUrl` of `/api/avatars/<id>.jpg`; other shapes are not served. \
            Such an `avatarUrl` is a path relative to the API origin, not a full URL: web, iOS and Android clients must prefix their \
            API base URL (the web app runs on a different origin, so using the path as is would request it from the web host)
            **Behavior**: streams the image as `image/jpeg` with `Cache-Control: public, max-age=31536000, immutable`: \
            a path never changes content, since every upload gets a new one
            **Side effects**: none
            **Errors**:
            - 404 `common.not_found`: no image at this path (the owner replaced or removed it, or deleted the account)
            - 503 `common.storage_unavailable`: image storage is not configured or unreachable; retry later""")
    public ResponseEntity<InputStreamResource> getAvatar(
            @Parameter(description = "Image file name, `<id>.jpg`", required = true, example = "019a5e3c-2f1d-7b6a-8c4e-5d7f9a1b3c2e.jpg")
            @PathVariable String file) {
        var object = storageService.get(UserService.AVATAR_KEY_PREFIX + file)
                .orElseThrow(() -> ApiException.notFound(MessageKey.COMMON_NOT_FOUND));
        var builder = ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable());
        if (object.response().contentLength() != null) {
            builder.contentLength(object.response().contentLength());
        }
        return builder.body(new InputStreamResource(object));
    }
}
