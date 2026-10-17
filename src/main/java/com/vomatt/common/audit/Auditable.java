package com.vomatt.common.audit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Auditable {
    
    String action();
    
    String resourceType();
    
    int resourceIdIndex() default -1;

    /** Index of the argument stored in details ({@link AuditDetails#auditDetails()}, else toString()); -1 for none. */
    int detailsIndex() default -1;
}