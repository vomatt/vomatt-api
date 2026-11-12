package com.vomatt.common.sms;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.vomatt.common.email.EmailServiceNoOp;

/** The OTP-logging senders load only under dev / loadtest without prod; every other profile fails closed. */
class OtpNoOpProfileTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(SmsServiceNoOp.class, SmsServiceUnavailable.class, EmailServiceNoOp.class);

    @Test
    void shouldLogOtpsWhenDevOrLoadtest() {
        for (String profiles : new String[] { "dev", "loadtest" }) {
            runner.withPropertyValues("spring.profiles.active=" + profiles).run(ctx -> {
                assertThat(ctx).as(profiles).hasSingleBean(SmsServiceNoOp.class).doesNotHaveBean(SmsServiceUnavailable.class);
                assertThat(ctx).as(profiles).hasSingleBean(EmailServiceNoOp.class);
            });
        }
    }

    @Test
    void shouldFailClosedWhenProfileIsProdUnknownOrMissing() {
        for (String profiles : new String[] { "prod", "dev,prod", "staging", "" }) {
            runner.withPropertyValues("spring.profiles.active=" + profiles).run(ctx -> {
                assertThat(ctx).as(profiles).hasSingleBean(SmsServiceUnavailable.class).doesNotHaveBean(SmsServiceNoOp.class);
                assertThat(ctx).as(profiles).doesNotHaveBean(EmailServiceNoOp.class);
            });
        }
    }

    @Test
    void shouldNotLoadEmailNoOpWhenEmailEnabled() {
        runner.withPropertyValues("spring.profiles.active=dev", "app.email.enabled=true")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(EmailServiceNoOp.class));
    }
}
