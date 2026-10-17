package com.vomatt.common.config;

import org.springframework.boot.data.redis.autoconfigure.LettuceClientOptionsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import io.lettuce.core.ClientOptions.DisconnectedBehavior;

@Configuration
public class RedisConfig {

    /**
     * Reject commands while disconnected instead of queueing them, so a Redis outage fails fast (503) rather than hanging.
     * Customizes Boot's own ClientOptions builder, keeping its connect timeout, timeout options and SSL settings.
     */
    @Bean
    public LettuceClientOptionsBuilderCustomizer lettuceFailFast() {
        return builder -> builder.disconnectedBehavior(DisconnectedBehavior.REJECT_COMMANDS);
    }

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        // Jackson 3 has built-in Java time support; the builder defaults are enough
        GenericJacksonJsonRedisSerializer serializer = GenericJacksonJsonRedisSerializer.builder().build();
        StringRedisSerializer stringSerializer = new StringRedisSerializer();

        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);
        template.setValueSerializer(serializer);
        template.setHashValueSerializer(serializer);
        template.afterPropertiesSet();
        return template;
    }
}
