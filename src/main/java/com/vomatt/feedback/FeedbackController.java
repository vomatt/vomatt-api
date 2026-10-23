package com.vomatt.feedback;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.vomatt.common.annotation.CommonApiResponses;
import com.vomatt.common.audit.Auditable;
import com.vomatt.common.config.OpenAPIConfig;
import com.vomatt.common.response.ApiResponse;
import com.vomatt.common.response.SimpleResultResponse;
import com.vomatt.common.security.UserPrincipal;
import com.vomatt.feedback.dto.CreateFeedbackRequest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/feedback")
@RequiredArgsConstructor
@Tag(name = "Feedback", description = "Product feedback from signed-in users")
public class FeedbackController {

    private final FeedbackService feedbackService;

    @PostMapping
    @Auditable(action = "CREATE", resourceType = "FEEDBACK")
    @CommonApiResponses
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Feedback stored; `id` is the new feedback id",
            content = @Content(examples = @ExampleObject(value = """
                    {"success":true,"data":{"success":true,"id":"0199c3a2-7b5e-7c1d-9a4f-3e2b1d5c6f70","status":null,"message":null},"message":null,"errorCode":null,"error":null}""")))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Current user not found (errorCode `user.not_found`)",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                    examples = @ExampleObject(value = """
                            {"success":false,"data":null,"message":"User not found","errorCode":"user.not_found","error":"User not found"}""")))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "Daily feedback limit reached (10 by default, admin-configurable) (`common.rate_limited`)",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                    examples = @ExampleObject(value = """
                            {"success":false,"data":null,"message":"Too many requests, please try again later","errorCode":"common.rate_limited","error":"Too many requests, please try again later"}""")))
    @Operation(summary = "Send feedback", description = """
            **Auth**: required
            **Precondition**: under the daily feedback limit (UTC day; 10 unless an admin set lookup `config` / `feedback_daily_limit`)
            **Behavior**: stores the text as feedback from the caller. Responds 201 with the new feedback id
            **Side effects**: the submission is written to the audit log
            **Errors**:
            - 400 `common.validation_failed`: `content` is blank or longer than 2000 characters
            - 404 `user.not_found`: the signed-in user no longer exists
            - 429 `common.rate_limited`: daily feedback limit reached, or too many write requests from this IP""",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
                    examples = @ExampleObject(value = """
                            { "content": "Please add dark mode." }
                            """))))
    public ResponseEntity<ApiResponse<SimpleResultResponse>> createFeedback(
            @Valid @RequestBody CreateFeedbackRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(SimpleResultResponse.ok(feedbackService.createFeedback(principal.userId(), request.getContent()))));
    }
}
