package com.vomatt.entity;

import com.vomatt.entity.common.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

/** A user's report of a Comment or Reply; written by {@code CommentReportRepository.insertIfAbsent}, read by admins. */
@Entity
@Table(name = "comment_reports")
@Getter
@NoArgsConstructor
@ToString(exclude = {"comment", "reporter"})
public class CommentReport extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "comment_id")
    private VoteComment comment;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reporter_id")
    private User reporter;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason")
    private ReportReason reason;

    @Column(name = "note")
    private String note;
}
