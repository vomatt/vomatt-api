package com.vomattapi.application.dto.vote;

import java.util.List;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

@Data
public class VoteRequest {
    
    @NotEmpty(message = "At least one option must be selected")
    private List<String> optionIds;
}