package com.vomatt.votes;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;

import com.vomatt.repository.VoteListRepository;

/** Beans VoteService needs on top of a @DataJpaTest slice (Spring Data repositories are already present). */
@TestConfiguration
@Import({ VoteService.class, VoteMapper.class, VoteConfigurationProperties.class, VoteListRepository.class })
public class VoteServiceSlice {
}
