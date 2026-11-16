package com.vomatt.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import com.vomatt.entity.Audience;
import com.vomatt.entity.Tag;
import com.vomatt.entity.User;
import com.vomatt.entity.Vote;

/** V20261116120300__vote_tags_times.sql: vote_tags carries its Poll's start / end time, kept in sync by triggers. */
@DisplayName("V20261116120300__vote_tags_times.sql (Postgres)")
class VoteTagsTimesMigrationPostgresTest extends PostgresRepositoryTest {

    private static final String SCRIPT = "db/migration/V20261116120300__vote_tags_times.sql";

    private final OffsetDateTime now = OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS);

    private Vote taggedPoll(Audience audience, Tag tag) {
        User creator = persistUser("vtt-" + UUID.randomUUID());
        Vote poll = persistPoll(creator, now.minusHours(1), now.plusDays(1), audience, "A", "B");
        poll.addTag(tag);
        em.flush();
        return poll;
    }

    private List<OffsetDateTime> tagTimes(Vote poll, Tag tag) {
        Object[] row = (Object[]) em.createNativeQuery(
                        "SELECT start_time, end_time FROM vomatt.vote_tags WHERE vote_id = :v AND tag_id = :t")
                .setParameter("v", poll.getId()).setParameter("t", tag.getId()).getSingleResult();
        return List.of(asTime(row[0]), asTime(row[1]));
    }

    private static OffsetDateTime asTime(Object value) {
        return value instanceof java.time.Instant i ? i.atOffset(java.time.ZoneOffset.UTC) : (OffsetDateTime) value;
    }

    private static void assertTimes(List<OffsetDateTime> actual, OffsetDateTime start, OffsetDateTime end) {
        assertThat(actual.get(0)).isAtSameInstantAs(start);
        assertThat(actual.get(1)).isAtSameInstantAs(end);
    }

    @Test
    void shouldBackfillTimesWhenMigrationReruns() throws Exception {
        Tag tag = persistTag();
        Vote poll = taggedPoll(Audience.PUBLIC, tag);
        em.createNativeQuery("UPDATE vomatt.vote_tags SET start_time = now() - interval '9 days', end_time = now() - interval '8 days' WHERE vote_id = :v")
                .setParameter("v", poll.getId()).executeUpdate();

        String script = new ClassPathResource(SCRIPT).getContentAsString(StandardCharsets.UTF_8);
        for (int run = 0; run < 2; run++) {
            em.createNativeQuery(script).executeUpdate();
        }

        assertTimes(tagTimes(poll, tag), poll.getStartTime(), poll.getEndTime());
    }

    @Test
    void shouldCopyPollTimesWhenTagAdded() {
        Tag tag = persistTag();
        Vote poll = taggedPoll(Audience.PUBLIC, tag);

        assertTimes(tagTimes(poll, tag), now.minusHours(1), now.plusDays(1));
    }

    @Test
    void shouldSyncTagTimesWhenPollClosed() {
        Tag tag = persistTag();
        Vote poll = taggedPoll(Audience.UNLISTED, tag);

        poll.closeAt(now);
        em.flush();

        assertTimes(tagTimes(poll, tag), now.minusHours(1), now);
    }

    @Test
    void shouldSyncTagTimesWhenPollEditedOrUpdatedNatively() {
        Tag tag = persistTag();
        Vote poll = taggedPoll(Audience.PUBLIC, tag);

        poll.setStartTime(now.plusHours(1));
        poll.scheduleEnd(now.plusDays(3));
        em.flush();
        assertTimes(tagTimes(poll, tag), now.plusHours(1), now.plusDays(3));

        em.createNativeQuery("UPDATE vomatt.votes SET end_time = :end WHERE id = :id")
                .setParameter("end", now.plusDays(5)).setParameter("id", poll.getId()).executeUpdate();
        assertTimes(tagTimes(poll, tag), now.plusHours(1), now.plusDays(5));

        // A tag added after the change carries the current times
        Tag later = persistTag();
        em.flush();
        em.clear();
        Vote reloaded = em.find(Vote.class, poll.getId());
        reloaded.addTag(em.find(Tag.class, later.getId()));
        em.flush();
        assertTimes(tagTimes(reloaded, later), now.plusHours(1), now.plusDays(5));
    }
}
