package com.vomatt.billing;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.vomatt.common.annotation.CommonApiResponses;
import com.vomatt.common.annotation.PublicApiResponse;
import com.vomatt.common.config.OpenAPIConfig;
import com.vomatt.common.exception.ApiException;
import com.vomatt.common.i18n.MessageKey;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Paid plans. Until Stripe is integrated (vomatt-web docs/release/stripe-go-live.md) every endpoint answers 503
 * {@code billing.unavailable}; plans change only through the admin endpoint.
 */
@RestController
@RequestMapping("/api/billing")
@Tag(name = "Billing", description = "Paid plans (not available yet)")
@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "Paid plans are not available yet (errorCode `billing.unavailable`)",
        content = @Content(mediaType = "application/json", schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                examples = @ExampleObject(value = OpenAPIConfig.ERR_MESSAGE + "Paid plans are coming soon" + OpenAPIConfig.ERR_CODE
                        + "billing.unavailable" + OpenAPIConfig.ERR_ERROR + "Paid plans are coming soon" + OpenAPIConfig.ERR_END)))
public class BillingController {

    private static final String UNAVAILABLE = """
            **Errors**:
            - 503 `billing.unavailable`: paid plans are not available yet""";

    @Value("${app.billing.enabled:false}")
    private boolean enabled;

    @PostMapping("/checkout")
    @CommonApiResponses
    @Operation(summary = "Start upgrading to PRO", description = """
            **Auth**: required
            **Behavior**: not available yet. Will return the payment page URL to send the user to
            """ + UNAVAILABLE)
    public ResponseEntity<Void> checkout() {
        throw unavailable();
    }

    @PostMapping("/portal")
    @CommonApiResponses
    @Operation(summary = "Manage the subscription", description = """
            **Auth**: required
            **Behavior**: not available yet. Will return the subscription management page URL
            """ + UNAVAILABLE)
    public ResponseEntity<Void> portal() {
        throw unavailable();
    }

    @PostMapping("/webhook")
    @PublicApiResponse
    @Operation(summary = "Payment provider events", description = """
            **Auth**: none (called by the payment provider, not by apps)
            **Behavior**: not available yet. Will verify the provider's signature and change the user's plan
            """ + UNAVAILABLE)
    public ResponseEntity<Void> webhook() {
        throw unavailable();
    }

    private ApiException unavailable() {
        if (enabled) {
            // ponytail: no Stripe yet, so turning billing on has nothing to run; integrate per stripe-go-live.md
            throw new IllegalStateException("BILLING_ENABLED is set but Stripe is not integrated");
        }
        return ApiException.serviceUnavailable(MessageKey.BILLING_UNAVAILABLE);
    }
}
