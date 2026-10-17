package com.vomatt.common.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.mock;

import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import com.vomatt.common.i18n.LocalizedMessageService;
import com.vomatt.entity.User;
import com.vomatt.repository.PostgresRepositoryTest;
import com.vomatt.repository.UserRepository;

/** Feeds real Postgres violations, with the cause chain Spring and Hibernate build, to the handler. */
@DisplayName("Integrity violation mapping (Postgres)")
class IntegrityViolationPostgresTest extends PostgresRepositoryTest {

    @Autowired
    UserRepository userRepository;

    @Autowired
    DataSource dataSource;

    final GlobalExceptionHandler handler = new GlobalExceptionHandler(mock(LocalizedMessageService.class));

    @Test
    @DisplayName("should return 409 when a unique constraint is violated")
    void shouldReturnConflictWhenUniqueConstraintIsViolated() {
        persistUser("iv-dup");
        em.flush();

        DataIntegrityViolationException ex = catchThrowableOfType(DataIntegrityViolationException.class,
                () -> userRepository.saveAndFlush(User.builder().username("iv-dup").email("iv-other@test.local").build()));

        assertThat(handler.handleDataIntegrity(ex).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("should return 500 when a foreign key is violated")
    void shouldReturnInternalErrorWhenForeignKeyIsViolated() {
        DataIntegrityViolationException ex = catchThrowableOfType(DataIntegrityViolationException.class,
                () -> new JdbcTemplate(dataSource).update(
                        "INSERT INTO vomatt.user_preferences (id, user_id, preference_key) VALUES (?, ?, 'k')",
                        UUID.randomUUID(), UUID.randomUUID()));

        assertThat(handler.handleDataIntegrity(ex).getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
