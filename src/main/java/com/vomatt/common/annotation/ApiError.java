package com.vomatt.common.annotation;

import com.vomatt.common.i18n.MessageKey;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * One error an endpoint can answer. {@code ApiErrorCustomizer} turns every one on a handler into a bullet under
 * {@code **Errors**} at the end of the {@code @Operation} description and a named example (the English message) under
 * the response for {@link #status()}, so each error is declared once.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(ApiError.List.class)
public @interface ApiError {

    int status();

    MessageKey key();

    /** The condition, in English, completing "- 400 `code`: ...". */
    String when();

    /** Example values for the message's {0}, {1}... placeholders. */
    String[] args() default {};

    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @interface List {
        ApiError[] value();
    }
}
