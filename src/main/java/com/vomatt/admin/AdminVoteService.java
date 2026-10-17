package com.vomatt.admin;

import static com.vomatt.admin.AdminService.blankToNull;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.vomatt.admin.dto.AdminVoteResponse;
import com.vomatt.common.exception.ApiException;
import com.vomatt.common.i18n.MessageKey;
import com.vomatt.common.response.PageResponse;
import com.vomatt.entity.Audience;
import com.vomatt.entity.Vote;
import com.vomatt.entity.VoteStatus;
import com.vomatt.repository.AdminQueryRepository;
import com.vomatt.repository.VoteRepository;
import com.vomatt.votes.VoteService;
import com.vomatt.votes.dto.VoteResponse;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Poll moderation: sees every Poll (any Audience, Deleted included) and acts without the owner check. Responses go
 * through {@link VoteService#toResponses}, so results stay Sealed for admins too. Writes take the Poll-row lock first,
 * as the owner's actions do.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class AdminVoteService {

    private final AdminQueryRepository adminQueryRepository;
    private final VoteRepository voteRepository;
    private final VoteService voteService;

    /** @param status scheduled, open, ended, or null for any */
    @Transactional(readOnly = true)
    public PageResponse<AdminVoteResponse> listVotes(String q, String status, UUID creatorId, Audience audience,
                                                     Boolean deleted, Integer page, Integer limit) {
        String statusFilter = blankToNull(status);
        VoteStatus voteStatus = statusFilter == null ? null : VoteStatus.parse(statusFilter);
        Page<Vote> votes = adminQueryRepository.findVotes(VoteService.searchQuery(q), voteStatus, creatorId, audience, deleted,
                OffsetDateTime.now(), PageResponse.pageable(page, limit));
        return PageResponse.of(toResponses(votes.getContent()), votes.getTotalElements(), votes.getNumber() + 1,
                votes.getSize());
    }

    @Transactional(readOnly = true)
    public AdminVoteResponse getVote(UUID voteId) {
        return toResponse(voteRepository.findByIdWithOptions(voteId)
                .orElseThrow(() -> ApiException.notFound(MessageKey.VOTE_NOT_FOUND)));
    }

    /** Close as the owner would (same Audience and status rules), without the owner check. */
    public AdminVoteResponse closeVote(UUID voteId, String actorId) {
        Vote vote = lockVote(voteId);
        // A Deleted Poll is frozen: restore it first
        if (vote.getDeletedAt() != null) {
            throw ApiException.notFound(MessageKey.VOTE_NOT_FOUND);
        }
        vote.closeAt(OffsetDateTime.now());
        log.info("Vote {} closed by admin {}", voteId, actorId);
        return toResponse(vote);
    }

    /** Delete regardless of the owner's delete window; idempotent. Tags stop counting the Poll, as on the owner's Delete. */
    public void deleteVote(UUID voteId, String actorId) {
        voteService.softDelete(lockVote(voteId), OffsetDateTime.now());
        log.info("Vote {} deleted by admin {}", voteId, actorId);
    }

    /** Undo a Delete; idempotent. Tags count the Poll again. */
    public AdminVoteResponse restoreVote(UUID voteId, String actorId) {
        Vote vote = lockVote(voteId);
        voteService.restore(vote);
        log.info("Vote {} restored by admin {}", voteId, actorId);
        return toResponse(vote);
    }

    private Vote lockVote(UUID voteId) {
        return voteService.lockVote(voteId).orElseThrow(() -> ApiException.notFound(MessageKey.VOTE_NOT_FOUND));
    }

    private AdminVoteResponse toResponse(Vote vote) {
        return toResponses(List.of(vote)).getFirst();
    }

    private List<AdminVoteResponse> toResponses(List<Vote> votes) {
        List<VoteResponse> responses = voteService.toResponses(votes, null);
        return IntStream.range(0, votes.size())
                .mapToObj(i -> new AdminVoteResponse(responses.get(i), votes.get(i).getDeletedAt(),
                        votes.get(i).getCreator().getEmail(), votes.get(i).getCreator().isActive()))
                .toList();
    }
}
