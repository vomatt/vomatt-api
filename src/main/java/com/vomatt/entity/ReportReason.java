package com.vomatt.entity;

/** Why a user reported a Comment or a Poll; stored by name in {@code comment_reports.reason} and {@code vote_reports.reason}. */
public enum ReportReason {
    SPAM, HARASSMENT, HATE, MISINFORMATION, OTHER
}
