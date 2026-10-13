package com.vomatt.notifications.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Number of unread notifications (Invitations and Ended Notifications)")
public record UnreadCountResponse(@Schema(description = "Unread Invitations to Scheduled or Open INVITE_ONLY Polls plus unread Ended Notifications "
        + "of Polls the caller owns, holds a Ballot in or is an Invitee of", example = "3") long count) {
}
