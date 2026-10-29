package com.vomatt.auth;

import com.vomatt.auth.dto.AuthResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Creates load-test accounts (loadtest-NNNN@vomatt.test, find-or-create so reruns reuse them) and signs each in
 * through the regular login path, writing {@code userId,accessToken,refreshToken} rows to a CSV for k6. Runs only
 * under the {@code loadtest} profile, then exits. Never activate it against production.
 */
@Component
@Profile("loadtest")
@RequiredArgsConstructor
@Slf4j
class LoadTestAccountSeeder implements ApplicationRunner {

    private final AuthService authService;
    private final ConfigurableApplicationContext context;

    @Value("${loadtest.users:100}")
    private int users;

    @Value("${loadtest.out:loadtest-tokens.csv}")
    private Path out;

    @Override
    public void run(ApplicationArguments args) throws IOException {
        List<String> rows = new ArrayList<>(List.of("userId,accessToken,refreshToken"));
        for (int i = 1; i <= users; i++) {
            String name = "loadtest-%04d".formatted(i);
            AuthService.FoundUser found = authService.findOrCreateUserByEmail(name + "@vomatt.test", name, "email_otp", true);
            AuthResponse auth = authService.buildAuthResponse(found.user(), false, found.created());
            rows.add(found.user().getId() + "," + auth.token() + "," + auth.refreshToken());
        }
        Files.write(out, rows);
        log.warn("[LOADTEST] wrote {} accounts to {}", users, out.toAbsolutePath());
        System.exit(SpringApplication.exit(context, () -> 0));
    }
}
