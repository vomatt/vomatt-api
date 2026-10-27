package com.vomatt.votes;

import org.mockito.Mockito;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import com.vomatt.common.email.EmailServiceNoOp;
import com.vomatt.common.redis.RedisService;
import com.vomatt.invitelists.InviteListMapper;
import com.vomatt.invitelists.InviteListService;
import com.vomatt.repository.LockTimeout;
import com.vomatt.repository.NotificationRepository;
import com.vomatt.repository.VoteListRepository;

/** Beans VoteService needs on top of a @DataJpaTest slice (Spring Data repositories are already present). */
@TestConfiguration
@Import({ VoteService.class, VoteMapper.class, VoteConfigurationProperties.class, VoteListRepository.class,
        NotificationRepository.class, LockTimeout.class, EmailServiceNoOp.class, InviteListService.class, InviteListMapper.class,
        com.vomatt.lookups.LookupService.class, com.vomatt.lookups.LookupMapper.class })
public class VoteServiceSlice {

    // No Redis in the slice: the invitation email counter always reads 0, so the daily limit never applies
    @Bean
    RedisService redisService() {
        return Mockito.mock(RedisService.class);
    }

    // Async with its own transaction, so it could not see the test's uncommitted rows; VoteViewRecorderTest covers it.
    // Marked AopInfrastructureBean so the @Async post-processor leaves it unproxied and calls stay on the test thread
    @Bean
    VoteViewRecorder voteViewRecorder() {
        return Mockito.mock(VoteViewRecorder.class,
                Mockito.withSettings().extraInterfaces(org.springframework.aop.framework.AopInfrastructureBean.class));
    }
}
