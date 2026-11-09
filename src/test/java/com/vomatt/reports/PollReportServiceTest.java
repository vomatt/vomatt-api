package com.vomatt.reports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.vomatt.common.exception.ApiException;
import com.vomatt.common.i18n.MessageKey;
import com.vomatt.common.security.ActingMember;
import com.vomatt.entity.OfficialRole;
import com.vomatt.entity.Vote;
import com.vomatt.lookups.LookupService;
import com.vomatt.reports.dto.PollReportResponse;
import com.vomatt.reports.dto.PollReportResponse.Group;
import com.vomatt.repository.InviteeRepository;
import com.vomatt.repository.PollReportRepository;
import com.vomatt.repository.VoteOptionRepository;
import com.vomatt.repository.VoteRepository;
import com.vomatt.votes.VoteService;

@ExtendWith(MockitoExtension.class)
class PollReportServiceTest {

    @Mock VoteService voteService;
    @Mock VoteRepository voteRepository;
    @Mock VoteOptionRepository voteOptionRepository;
    @Mock InviteeRepository inviteeRepository;
    @Mock PollReportRepository reportRepository;
    @Mock LookupService lookupService;
    @InjectMocks PollReportService service;

    private final UUID voteId = UUID.randomUUID();
    private final String ownerId = UUID.randomUUID().toString();
    private Vote vote;

    @BeforeEach
    void setUp() {
        vote = mock(Vote.class);
        lenient().when(vote.getId()).thenReturn(voteId);
        lenient().when(vote.isOwnedBy(ownerId)).thenReturn(true);
        lenient().when(vote.isPro()).thenReturn(true);
        lenient().when(voteService.findAccessibleVote(any(), any())).thenReturn(vote);
        lenient().when(reportRepository.findData(voteId)).thenReturn(Optional.of("{\"voteId\":\"" + voteId + "\",\"minCellSize\":5}"));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static void act(OfficialRole role, UUID officialId, UUID memberId) {
        var auth = new UsernamePasswordAuthenticationToken(officialId.toString(), null, List.of());
        auth.setDetails(new ActingMember(memberId, officialId, role));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private void assertRefused(String callerId, MessageKey key) {
        assertThatThrownBy(() -> service.getReport(voteId.toString(), callerId))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getMessageKey()).isEqualTo(key));
    }

    @Test
    void shouldKeepThresholdAtTwoWhenConfiguredBelow() {
        when(lookupService.configInt(any(), anyInt())).thenReturn(1);
        assertThat(service.minCellSize()).isEqualTo(2);
    }

    // === Access matrix ===

    @Test
    void shouldReturnStoredReportWhenOwnerReadsRevealedProPoll() {
        assertThat(service.getReport(voteId.toString(), ownerId).voteId()).isEqualTo(voteId.toString());
    }

    @Test
    void shouldRefuseWhenCallerIsNotOwner() {
        assertRefused(UUID.randomUUID().toString(), MessageKey.VOTE_FORBIDDEN);
    }

    @Test
    void shouldAllowWhenActingMemberMayReadReports() {
        UUID memberId = UUID.randomUUID();
        act(OfficialRole.ANALYST, UUID.fromString(ownerId), memberId);

        assertThat(service.getReport(voteId.toString(), ownerId)).isNotNull();
    }

    @Test
    void shouldRefuseWhenActingMemberMayNotReadReports() {
        UUID memberId = UUID.randomUUID();
        act(OfficialRole.EDITOR, UUID.fromString(ownerId), memberId);

        assertRefused(ownerId, MessageKey.OFFICIAL_ROLE_FORBIDDEN);
    }

    @Test
    void shouldRefuseWhenPollIsNotPro() {
        when(vote.isPro()).thenReturn(false);
        assertRefused(ownerId, MessageKey.REPORT_NOT_PRO);
    }

    @Test
    void shouldRefuseWhenResultsStillSealed() {
        when(vote.isResultsSealedAt(any())).thenReturn(true);
        assertRefused(ownerId, MessageKey.REPORT_NOT_READY);
    }

    // === Small-cell suppression (threshold 5) ===

    private static Map<String, int[]> groups(Object... keyAndCounts) {
        Map<String, int[]> groups = new LinkedHashMap<>();
        for (int i = 0; i < keyAndCounts.length; i += 2) {
            groups.put((String) keyAndCounts[i], (int[]) keyAndCounts[i + 1]);
        }
        return groups;
    }

    @Test
    void shouldShowGroupWhenExactlyAtThreshold() {
        assertThat(PollReportService.suppress(groups("MALE", new int[] { 3, 2 }, "FEMALE", new int[] { 4, 4 }), 5))
                .containsExactly(new Group("MALE", 5, List.of(3, 2)), new Group("FEMALE", 8, List.of(4, 4)));
    }

    @Test
    void shouldMergeGroupsBelowThresholdWhenTogetherTheyReachIt() {
        assertThat(PollReportService.suppress(groups("18-29", new int[] { 4, 4 }, "30-39", new int[] { 2, 1 },
                "60+", new int[] { 1, 1 }, "NOT_PROVIDED", new int[] { 0, 0 }), 5))
                .containsExactly(new Group("18-29", 8, List.of(4, 4)), new Group(PollReportService.MERGED, 5, List.of(3, 2)));
    }

    @Test
    void shouldPullNextSmallestGroupIntoMergedWhenMergedStillBelowThreshold() {
        // A lone small group would be total minus the shown ones; the smallest shown group joins it
        assertThat(PollReportService.suppress(groups("MALE", new int[] { 5, 4 }, "FEMALE", new int[] { 3, 3 },
                "UNDISCLOSED", new int[] { 1, 0 }), 5))
                .containsExactly(new Group("MALE", 9, List.of(5, 4)), new Group(PollReportService.MERGED, 7, List.of(4, 3)));
    }

    @Test
    void shouldNullMergedCountsWhenAllVotersTogetherBelowThreshold() {
        assertThat(PollReportService.suppress(groups("MALE", new int[] { 2, 0 }, "FEMALE", new int[] { 1, 1 }), 5))
                .containsExactly(new Group(PollReportService.MERGED, null, null));
    }

    @Test
    void shouldOmitMergedWhenNoGroupBelowThreshold() {
        assertThat(PollReportService.suppress(groups("MALE", new int[] { 5, 0 }, "FEMALE", new int[] { 0, 0 }), 5))
                .containsExactly(new Group("MALE", 5, List.of(5, 0)));
    }

    // === Age ===

    @Test
    void shouldComputeAgeAtGivenDateWhenBirthdayNear() {
        LocalDate end = LocalDate.of(2026, 11, 9);
        assertThat(PollReportService.ageBand(LocalDate.of(2008, 11, 10), end)).isEqualTo("UNDER_18");
        assertThat(PollReportService.ageBand(LocalDate.of(2008, 11, 9), end)).isEqualTo("18-29");
        assertThat(PollReportService.ageBand(LocalDate.of(1996, 11, 10), end)).isEqualTo("18-29");
        assertThat(PollReportService.ageBand(LocalDate.of(1996, 11, 9), end)).isEqualTo("30-39");
        assertThat(PollReportService.ageBand(LocalDate.of(1977, 1, 1), end)).isEqualTo("40-49");
        assertThat(PollReportService.ageBand(LocalDate.of(1967, 1, 1), end)).isEqualTo("50-59");
        assertThat(PollReportService.ageBand(LocalDate.of(1966, 11, 9), end)).isEqualTo("60+");
        assertThat(PollReportService.ageBand(null, end)).isEqualTo("NOT_PROVIDED");
    }

    // === CSV ===

    @Test
    void shouldWriteExcelCsvWhenReportExported() {
        OffsetDateTime at = OffsetDateTime.parse("2026-10-19T01:00:00Z");
        PollReportResponse report = new PollReportResponse(voteId.toString(), "Lobby, \"phase 2\"", at, 5,
                new PollReportResponse.Timeline(at, at, at, at), new PollReportResponse.Turnout(9, 10, 0.9),
                List.of(new PollReportResponse.OptionCount("a", "Yes", 6), new PollReportResponse.OptionCount("b", "=SUM(A1)", 3)),
                List.of(new Group("30-39", 9, List.of(6, 3))),
                List.of(new Group("MALE", 5, List.of(3, 2)), new Group("MERGED", null, null)));

        byte[] bytes = PollReportService.toCsv(report);
        assertThat(Arrays.copyOf(bytes, 3)).containsExactly((byte) 0xEF, (byte) 0xBB, (byte) 0xBF);
        String csv = new String(bytes, StandardCharsets.UTF_8).substring(1);
        assertThat(csv).doesNotContain(ownerId);
        assertThat(csv.split("\r\n", -1)).containsSubsequence(
                "Poll,\"Lobby, \"\"phase 2\"\"\"",
                "Ballots,9",
                "Invitees,10",
                "Turnout rate,0.9",
                "Option,Ballots",
                "Yes,6",
                "'=SUM(A1),3",
                "",
                "Age band,Voters,Yes,'=SUM(A1)",
                "30-39,9,6,3",
                "",
                "Gender,Voters,Yes,'=SUM(A1)",
                "MALE,5,3,2",
                "MERGED,,,",
                "");
    }
}
