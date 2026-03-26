package com.vomattapi.application.dto.vote;

import lombok.Data;

import java.util.List;

@Data
public class UserVoteStatusResponse {
    private boolean hasVoted;
    private List<String> selectedOptions;
}
