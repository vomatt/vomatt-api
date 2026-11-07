package com.vomatt.contact;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.vomatt.common.annotation.PublicApiResponse;
import com.vomatt.common.response.ApiResponse;
import com.vomatt.common.response.SimpleResultResponse;
import com.vomatt.common.util.ClientIpResolver;
import com.vomatt.contact.dto.CreateContactMessageRequest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/contact")
@RequiredArgsConstructor
@Tag(name = "Contact", description = "Questions from the about site's contact form")
public class ContactController {

    private final ContactService contactService;

    @PostMapping
    @PublicApiResponse
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Message stored; `id` is the new message id",
            content = @Content(examples = @ExampleObject(value = """
                    {"success":true,"data":{"success":true,"id":"0199c3a2-7b5e-7c1d-9a4f-3e2b1d5c6f70","status":null,"message":null},"message":null,"errorCode":null,"error":null}""")))
    @Operation(summary = "Send a contact message", description = """
            **Auth**: none
            **Precondition**: the client IP is under the daily contact limit (UTC day; 5 unless an admin set lookup `config` / `contact_daily_limit`, a positive number or numeric string). A server-side caller names the visitor's IP with `X-Client-IP` and `X-Client-IP-Secret`, as vomatt-web does
            **Behavior**: stores the message for staff to read in the back office; `name` and `email` are trimmed. Responds 201 with the new message id
            **Side effects**: counts toward the client IP's daily contact limit (only once stored)
            **Errors**:
            - 400 `common.validation_failed`: `name` blank or longer than 100 characters, `email` not a valid address or longer than 254 characters, `message` blank or longer than 5000 characters, or `locale` not `en` / `zh`
            - 429 `common.rate_limited`: daily contact limit reached, or too many write requests from this IP""",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
                    examples = @ExampleObject(value = """
                            {
                              "name": "Alice Chen",
                              "email": "alice@example.com",
                              "message": "Can my school use Vomatt for student council elections?",
                              "locale": "en"
                            }
                            """))))
    public ResponseEntity<ApiResponse<SimpleResultResponse>> createMessage(
            @Valid @RequestBody CreateContactMessageRequest request, HttpServletRequest httpRequest) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(SimpleResultResponse.ok(
                contactService.createMessage(ClientIpResolver.resolve(httpRequest), request))));
    }
}
