package com.vomatt.reports;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.Period;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.vomatt.common.exception.ApiException;
import com.vomatt.common.i18n.MessageKey;
import com.vomatt.common.security.ActingMember;
import com.vomatt.common.util.TimeUtil;
import com.vomatt.entity.Audience;
import com.vomatt.entity.Gender;
import com.vomatt.entity.Vote;
import com.vomatt.entity.VoteOption;
import com.vomatt.lookups.LookupService;
import com.vomatt.reports.dto.PollReportResponse;
import com.vomatt.reports.dto.PollReportResponse.Group;
import com.vomatt.repository.BallotDemographics;
import com.vomatt.repository.InviteeRepository;
import com.vomatt.repository.PollReportRepository;
import com.vomatt.repository.VoteOptionRepository;
import com.vomatt.repository.VoteRepository;
import com.vomatt.votes.VoteService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pro poll result reports. A report is a snapshot computed once after the Poll Ends (by the first authorized read, or
 * by {@code RetentionJob} before it seals Anonymous Ballots) and stored in {@code poll_reports}; later reads return it
 * unchanged, whatever happens to the voters' profiles. Aggregates only: no user id or name ever goes in.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class PollReportService {

    static final String MIN_CELL_SIZE_KEY = "report_min_cell_size";
    static final int DEFAULT_MIN_CELL_SIZE = 5;
    static final String MERGED = "MERGED";
    static final String NOT_PROVIDED = "NOT_PROVIDED";
    static final List<String> AGE_BANDS = List.of("UNDER_18", "18-29", "30-39", "40-49", "50-59", "60+", NOT_PROVIDED);
    // Own mapper, not the web one: the stored format must not follow API serialization settings
    private static final JsonMapper JSON = JsonMapper.builder().build();
    static final List<String> GENDERS = Stream.concat(Arrays.stream(Gender.values()).map(Enum::name), Stream.of(NOT_PROVIDED)).toList();

    private final VoteService voteService;
    private final VoteRepository voteRepository;
    private final VoteOptionRepository voteOptionRepository;
    private final InviteeRepository inviteeRepository;
    private final PollReportRepository reportRepository;
    private final LookupService lookupService;

    /**
     * The report of a Pro poll for its owner; while acting as an official account owner, only members whose role may
     * read reports. Generated on the first read after the Poll has Ended and its results are revealed.
     */
    public PollReportResponse getReport(String voteId, String callerId) {
        Vote vote = voteService.findAccessibleVote(UUID.fromString(voteId), callerId);
        if (!vote.isOwnedBy(callerId)) {
            throw ApiException.forbidden(MessageKey.VOTE_FORBIDDEN);
        }
        ActingMember.current()
                .filter(a -> !a.role().canReadReports())
                .ifPresent(a -> {
                    throw ApiException.forbidden(MessageKey.OFFICIAL_ROLE_FORBIDDEN);
                });
        if (!vote.isPro()) {
            throw ApiException.badRequest(MessageKey.REPORT_NOT_PRO);
        }
        if (vote.isCancelled()) {
            throw ApiException.badRequest(MessageKey.REPORT_CANCELLED);
        }
        OffsetDateTime now = OffsetDateTime.now();
        if (vote.isResultsSealedAt(now)) {
            throw ApiException.forbidden(MessageKey.REPORT_NOT_READY);
        }
        String data = reportRepository.findData(vote.getId()).orElseGet(() -> {
            store(vote, now, minCellSize());
            // Read back: a concurrent first read may have stored its own snapshot first
            return reportRepository.findData(vote.getId()).orElseThrow();
        });
        return JSON.readValue(data, PollReportResponse.class);
    }

    /**
     * RetentionJob: snapshots one Ended Pro poll in its own transaction, so a database error rolls back this report
     * only, never the retention run. Returns whether this call stored it.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean storeMissing(UUID voteId, OffsetDateTime now) {
        return voteRepository.findById(voteId).map(vote -> store(vote, now, minCellSize())).orElse(false);
    }

    private boolean store(Vote vote, OffsetDateTime now, int minCellSize) {
        PollReportResponse report = generate(vote, now, minCellSize);
        boolean stored = reportRepository.insertIfAbsent(vote.getId(), now, JSON.writeValueAsString(report));
        if (stored) {
            log.info("Poll report generated for {}", vote.getId());
        }
        return stored;
    }

    // Never below 2: a threshold of 1 would show single voters' choices by age band and gender
    int minCellSize() {
        return Math.max(2, lookupService.configInt(MIN_CELL_SIZE_KEY, DEFAULT_MIN_CELL_SIZE));
    }

    PollReportResponse generate(Vote vote, OffsetDateTime now, int minCellSize) {
        List<VoteOption> options = voteOptionRepository.findByVoteIdOrderByDisplayOrder(vote.getId());
        List<BallotDemographics> ballots = reportRepository.findBallots(vote.getId());
        LocalDate endDate = TimeUtil.taipeiBusinessDate(vote.getEndTime());

        // A sealed Anonymous Ballot (option NULL) counts in turnout but in no option
        Map<UUID, Integer> optionIndex = new LinkedHashMap<>();
        options.forEach(o -> optionIndex.put(o.getId(), optionIndex.size()));
        List<BallotDemographics> chosen = ballots.stream().filter(b -> optionIndex.containsKey(b.optionId())).toList();

        int[] optionCounts = new int[options.size()];
        chosen.forEach(b -> optionCounts[optionIndex.get(b.optionId())]++);

        Integer invitees = null;
        if (vote.getAudience() == Audience.INVITE_ONLY) {
            invitees = inviteeRepository.countByVoteIds(List.of(vote.getId())).stream()
                    .mapToInt(c -> c.getCount().intValue()).sum();
        }
        Double rate = invitees == null || invitees == 0 ? null : (double) ballots.size() / invitees;

        return new PollReportResponse(
                vote.getId().toString(),
                vote.getTitle(),
                now,
                minCellSize,
                new PollReportResponse.Timeline(vote.getCreatedAt(), vote.getStartTime(), vote.getEndTime(),
                        vote.resultsRevealAt()),
                new PollReportResponse.Turnout(ballots.size(), invitees, rate),
                IntStream.range(0, options.size())
                        .mapToObj(i -> new PollReportResponse.OptionCount(options.get(i).getId().toString(), options.get(i).getText(), optionCounts[i]))
                        .toList(),
                breakdown(AGE_BANDS, chosen, b -> ageBand(b.birthday(), endDate), optionIndex, minCellSize),
                breakdown(GENDERS, chosen, b -> b.gender() == null ? NOT_PROVIDED : b.gender().name(), optionIndex, minCellSize));
    }

    private static List<Group> breakdown(List<String> order, List<BallotDemographics> ballots, Function<BallotDemographics, String> key,
            Map<UUID, Integer> optionIndex, int minCellSize) {
        Map<String, int[]> counts = new LinkedHashMap<>();
        order.forEach(k -> counts.put(k, new int[optionIndex.size()]));
        ballots.forEach(b -> counts.get(key.apply(b))[optionIndex.get(b.optionId())]++);
        return suppress(counts, minCellSize);
    }

    /** Age in whole years on {@code at}; null birthday is NOT_PROVIDED. */
    static String ageBand(LocalDate birthday, LocalDate at) {
        if (birthday == null) {
            return NOT_PROVIDED;
        }
        int age = Period.between(birthday, at).getYears();
        if (age < 18) {
            return "UNDER_18";
        }
        if (age >= 60) {
            return "60+";
        }
        int decade = age < 30 ? 18 : age / 10 * 10;
        return decade + "-" + (age < 30 ? 29 : decade + 9);
    }

    /**
     * Small-cell suppression for one dimension. Groups (in the given order, each with its per-option counts) without
     * voters are dropped. Every group under {@code minCellSize} voters goes into MERGED; while MERGED is still under it,
     * the smallest remaining group joins too (one alone could otherwise be worked out from the option totals). MERGED
     * still under it with nothing left to join keeps null voters and counts.
     */
    static List<Group> suppress(Map<String, int[]> groups, int minCellSize) {
        List<Map.Entry<String, int[]>> shown = new ArrayList<>(groups.entrySet().stream().filter(e -> sum(e.getValue()) > 0).toList());
        List<Map.Entry<String, int[]>> merged = new ArrayList<>(shown.stream().filter(e -> sum(e.getValue()) < minCellSize).toList());
        shown.removeAll(merged);
        int mergedVoters = merged.stream().mapToInt(e -> sum(e.getValue())).sum();
        while (!merged.isEmpty() && mergedVoters < minCellSize && !shown.isEmpty()) {
            Map.Entry<String, int[]> next = shown.stream().min(Comparator.comparingInt(e -> sum(e.getValue()))).orElseThrow();
            shown.remove(next);
            merged.add(next);
            mergedVoters += sum(next.getValue());
        }
        List<Group> result = new ArrayList<>(shown.stream().map(e -> new Group(e.getKey(), sum(e.getValue()), list(e.getValue()))).toList());
        if (!merged.isEmpty()) {
            int[] total = new int[merged.getFirst().getValue().length];
            merged.forEach(e -> IntStream.range(0, total.length).forEach(i -> total[i] += e.getValue()[i]));
            result.add(mergedVoters < minCellSize ? new Group(MERGED, null, null) : new Group(MERGED, mergedVoters, list(total)));
        }
        return result;
    }

    private static int sum(int[] counts) {
        return IntStream.of(counts).sum();
    }

    private static List<Integer> list(int[] counts) {
        return IntStream.of(counts).boxed().toList();
    }

    /** The report as CSV for Excel: UTF-8 with a BOM, CRLF line ends, RFC 4180 quoting. */
    public static byte[] toCsv(PollReportResponse r) {
        List<List<Object>> rows = new ArrayList<>();
        rows.add(List.of("Poll", r.title()));
        rows.add(List.of("Poll ID", r.voteId()));
        rows.add(List.of("Generated at", r.generatedAt()));
        rows.add(List.of("Created at", r.timeline().createdAt()));
        rows.add(List.of("Opened at", r.timeline().openedAt()));
        rows.add(List.of("Closed at", r.timeline().closedAt()));
        rows.add(List.of("Revealed at", r.timeline().revealedAt()));
        rows.add(List.of("Ballots", r.turnout().ballots()));
        rows.add(row("Invitees", r.turnout().invitees()));
        rows.add(row("Turnout rate", r.turnout().rate()));
        rows.add(List.of("Minimum group size", r.minCellSize()));
        rows.add(List.of());
        rows.add(List.of("Option", "Ballots"));
        r.options().forEach(o -> rows.add(List.of(o.text(), o.count())));
        addBreakdown(rows, "Age band", r.ageBands(), r.options());
        addBreakdown(rows, "Gender", r.genders(), r.options());

        StringBuilder csv = new StringBuilder("﻿");
        rows.forEach(row -> csv.append(String.join(",", row.stream().map(PollReportService::cell).toList())).append("\r\n"));
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void addBreakdown(List<List<Object>> rows, String title, List<Group> groups, List<PollReportResponse.OptionCount> options) {
        rows.add(List.of());
        List<Object> header = new ArrayList<>(List.of(title, "Voters"));
        options.forEach(o -> header.add(o.text()));
        rows.add(header);
        for (Group g : groups) {
            List<Object> row = new ArrayList<>();
            row.add(g.group());
            row.add(g.voters());
            options.forEach(o -> row.add(null));
            if (g.counts() != null) {
                IntStream.range(0, g.counts().size()).forEach(i -> row.set(i + 2, g.counts().get(i)));
            }
            rows.add(row);
        }
    }

    private static List<Object> row(String label, Object value) {
        List<Object> row = new ArrayList<>();
        row.add(label);
        row.add(value);
        return row;
    }

    // Quote when needed (RFC 4180); text starting like a formula gets a leading apostrophe so Excel shows it as text
    private static String cell(Object value) {
        if (value == null) {
            return "";
        }
        String s = value.toString();
        if (value instanceof String && !s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) {
            s = "'" + s;
        }
        return s.matches("(?s).*[\",\r\n].*") ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }
}
