package com.vomatt.reports;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.vomatt.common.exception.ApiException;
import com.vomatt.common.exception.GlobalExceptionHandler;
import com.vomatt.common.i18n.LocalizedMessageService;
import com.vomatt.common.i18n.MessageKey;
import com.vomatt.common.security.UserPrincipal;
import com.vomatt.reports.dto.PollReportResponse;

@ExtendWith(MockitoExtension.class)
class PollReportControllerTest {

    private static final String USER_ID = UUID.randomUUID().toString();
    private static final String VOTE_ID = UUID.randomUUID().toString();

    @Mock PollReportService reportService;
    @Mock LocalizedMessageService messageService;
    @InjectMocks PollReportController controller;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler(messageService))
                .build();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new UserPrincipal(USER_ID, List.of("user"), null), null, AuthorityUtils.createAuthorityList("ROLE_USER")));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static PollReportResponse report() {
        OffsetDateTime at = OffsetDateTime.parse("2026-10-19T01:00:00Z");
        return new PollReportResponse(VOTE_ID, "Lobby", at, 5, new PollReportResponse.Timeline(at, at, at, at),
                new PollReportResponse.Turnout(5, null, null), List.of(new PollReportResponse.OptionCount("a", "Yes", 5)),
                List.of(), List.of());
    }

    @Test
    void shouldWrapReportWhenJsonRequested() throws Exception {
        when(reportService.getReport(VOTE_ID, USER_ID)).thenReturn(report());

        mockMvc.perform(get("/api/votes/{voteId}/report", VOTE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.turnout.ballots").value(5));
    }

    @Test
    void shouldDownloadCsvWhenCsvRequested() throws Exception {
        when(reportService.getReport(VOTE_ID, USER_ID)).thenReturn(report());

        mockMvc.perform(get("/api/votes/{voteId}/report.csv", VOTE_ID))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/csv;charset=UTF-8"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"poll-report-" + VOTE_ID + ".csv\""))
                .andExpect(content().bytes(PollReportService.toCsv(report())));
    }

    @Test
    void shouldAnswerJsonErrorWhenCsvRefused() throws Exception {
        when(reportService.getReport(any(), any())).thenThrow(ApiException.forbidden(MessageKey.REPORT_NOT_READY));

        mockMvc.perform(get("/api/votes/{voteId}/report.csv", VOTE_ID))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("report.not_ready"));
    }
}
