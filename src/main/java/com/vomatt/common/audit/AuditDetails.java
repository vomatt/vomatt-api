package com.vomatt.common.audit;

/** A request argument that says what the audit log stores in details; other arguments are stored by toString(). */
public interface AuditDetails {

    /** Plain text for audit_logs.details; null for none. */
    String auditDetails();
}
