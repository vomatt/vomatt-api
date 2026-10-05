package com.vomatt.repository;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ContextConfiguration;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

import com.vomatt.entity.User;
import com.vomatt.entity.Vote;
import com.vomatt.entity.VoteOption;
import com.vomatt.vomattapi.VomattApiApplication;

import jakarta.persistence.EntityManager;

/**
 * Base for repository tests against a real Postgres 17 loaded with the production schema.sql,
 * the same way docker-compose initialises the local database. One container is shared by all subclasses.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = VomattApiApplication.class)
public abstract class PostgresRepositoryTest {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17")
            .withCopyFileToContainer(MountableFile.forClasspathResource("db/schema.sql"),
                    "/docker-entrypoint-initdb.d/01-schema.sql");

    static {
        POSTGRES.start();
    }

    @Autowired
    protected EntityManager em;

    protected User persistUser(String username) {
        User user = User.builder().username(username).email(username + "@test.local").build();
        em.persist(user);
        return user;
    }

    protected Vote persistPoll(User creator, OffsetDateTime startTime, OffsetDateTime endTime, String... options) {
        Vote poll = new Vote("Poll " + UUID.randomUUID(), null, creator, endTime);
        poll.setStartTime(startTime);
        for (int i = 0; i < options.length; i++) {
            VoteOption option = new VoteOption(options[i], null, poll);
            option.setDisplayOrder(i);
            poll.addOption(option);
        }
        em.persist(poll);
        return poll;
    }
}
