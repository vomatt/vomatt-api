package com.vomatt.notifications;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.vomatt.common.annotation.CommonApiResponses;
import com.vomatt.common.config.OpenAPIConfig;
import com.vomatt.common.response.ApiResponse;
import com.vomatt.common.response.CursorResponse;
import com.vomatt.common.response.SimpleResultResponse;
import com.vomatt.common.security.UserPrincipal;
import com.vomatt.notifications.dto.NotificationResponse;
import com.vomatt.notifications.dto.UnreadCountResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
@Tag(name = "Notification", description = "The notification feed: Poll results, Invitations, follows, Comments and closing-soon "
        + "reminders, derived when read (no push, no email). Kinds can be switched off with `/api/users/me/notification-preferences`")
public class NotificationController {

    private final NotificationService notificationService;

    @GetMapping
    @CommonApiResponses
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Success",
            content = @Content(examples = @ExampleObject(value = """
                    {"success":true,"data":{"items":[\
                    {"id":"closing_soon_0199f2a3-5b7e-7d40-a1c8-9e3b2f6d4c05","kind":"CLOSING_SOON","createdAt":"2026-10-26T12:00:00+08:00","read":false,\
                    "actor":{"id":"0199c1a2-9e4f-7a21-8c3d-1b2a3c4d5e6f","username":"jtan","displayName":"J. Tan","avatarUrl":"bot:boba","isFollowing":true,"followsYou":false},\
                    "poll":{"id":"0199f2a3-5b7e-7d40-a1c8-9e3b2f6d4c05","title":"Best ramen in Taipei?","startTime":"2026-10-20T12:00:00+08:00","endTime":"2026-10-27T12:00:00+08:00","status":"OPEN","turnout":342,\
                    "myOptionId":null,"winningOptionIds":null,"winningOptionLabels":null,"tie":null},"comment":null},\
                    {"id":"comment_0199f2a5-1c2d-7e3f-8a4b-5c6d7e8f9a0b","kind":"COMMENT","createdAt":"2026-10-26T09:30:00+08:00","read":false,\
                    "actor":{"id":"0199c1a2-7b3e-7d4a-9f10-2c5e8a1b3d47","username":"rosa","displayName":"Rosa","avatarUrl":"bot:mochi","isFollowing":false,"followsYou":false},\
                    "poll":{"id":"0199f2a3-8c1d-7e2f-9a3b-4c5d6e7f8a9b","title":"Tabs or spaces?","startTime":"2026-10-23T12:00:00+08:00","endTime":"2026-10-30T12:00:00+08:00","status":"OPEN","turnout":57,\
                    "myOptionId":"0199f2a4-8d11-7c3e-b0a4-5e29c7d6f813","winningOptionIds":null,"winningOptionLabels":null,"tie":null},\
                    "comment":{"id":"0199f2a5-1c2d-7e3f-8a4b-5c6d7e8f9a0b","parentId":"0199f2a4-ff01-7a2b-9c3d-4e5f6a7b8c9d","excerpt":"Tabs, because the reader picks the width"}},\
                    {"id":"followed_0199f2a6-2d3e-7f40-9b5c-6d7e8f9a0b1c","kind":"FOLLOWED","createdAt":"2026-10-25T20:00:00+08:00","read":true,\
                    "actor":{"id":"0199c1a2-1a2b-7c3d-8e4f-5a6b7c8d9e0f","username":"bkim","displayName":null,"avatarUrl":"bot:boba","isFollowing":true,"followsYou":true},"poll":null,"comment":null},\
                    {"id":"result_0199f2a3-1a2b-7c3d-8e4f-9a0b1c2d3e4f","kind":"RESULT","createdAt":"2026-10-25T18:00:00+08:00","read":true,"actor":null,\
                    "poll":{"id":"0199f2a3-1a2b-7c3d-8e4f-9a0b1c2d3e4f","title":"Lunch?","startTime":"2026-10-25T09:00:00+08:00","endTime":"2026-10-25T18:00:00+08:00","status":"ENDED","turnout":12,\
                    "myOptionId":"0199f2a4-2b3c-7d4e-8f5a-6b7c8d9e0f1a","winningOptionIds":["0199f2a4-2b3c-7d4e-8f5a-6b7c8d9e0f1a","0199f2a4-3c4d-7e5f-9a6b-7c8d9e0f1a2b"],\
                    "winningOptionLabels":["Dumplings","Noodles"],"tie":true},"comment":null}],\
                    "nextCursor":"Tk9USUZJQ0FUSU9OU3wwMTk5ZjJhMy0xYTJiLTdjM2QtOGU0Zi05YTBiMWMyZDNlNGZ8MjAyNi0xMC0yNVQxMDowMDowMFo"},\
                    "message":null,"errorCode":null,"error":null}""")))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "`cursor` was not issued by this list (errorCode `common.cursor_invalid`)",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                    examples = @ExampleObject(value = """
                            {"success":false,"data":null,"message":"Invalid cursor","errorCode":"common.cursor_invalid","error":"Invalid cursor"}""")))
    @Operation(summary = "List notifications", description = """
            **Auth**: required
            **Precondition**: none
            **Behavior**: the caller's notifications, newest `createdAt` first, cursor-paged. They are not stored but derived \
            from Polls, Invitations, follows and Comments when read, so an item disappears when what it is about goes away \
            (a Comment deleted, a follow undone, a Poll Deleted). Kinds:
            - RESULT: a Poll the caller owns, holds a Ballot in, is an Invitee of or bookmarked has Ended (end time passed, Closed, or every Invitee voted); \
            one item per Poll whatever the reasons. A Participant who made a Retraction and is neither an Invitee nor bookmarked it gets none
            - CANCELLED: an Invite-only Poll the caller is an Invitee of was Cancelled before it opened (only Polls Cancelled before 2026-10-14; \
            a Scheduled Poll is now Deleted, which notifies nobody)
            - INVITATION: the caller is an Invitee of a Scheduled or Open Invite-only Poll; listed until the Poll Ends, when its RESULT takes over. \
            Being removed and invited again is a new, unread INVITATION
            - FOLLOWED: someone followed the caller (one item per follower; group them in the UI)
            - COMMENT: someone else commented on or replied under the caller's Poll, or replied to the caller's Comment, on a Poll the caller can see; \
            not a Reply in a thread started by a user the caller blocked (the Comment lists hide that thread)
            - CLOSING_SOON: an Open Poll ends within 24 hours and the caller holds no Ballot in it, if the caller is its Invitee, \
            or it is a Public Poll of a user the caller follows. Gone once the caller casts a Ballot or the Poll Ends. Off by default
            Kinds switched off in the caller's preferences are left out (FOLLOWED cannot be switched off). Nothing from a suspended user, \
            a user the caller blocked or a user who blocked the caller is listed, nor anything about a Poll whose owner blocked the caller. `poll` never carries per-option counts, its \
            outcome (`winningOptionIds`, `winningOptionLabels`, `tie`) stays null until the Poll has Ended, and `actor` is never \
            a voter, so Sealed results and Anonymous Ballots stay hidden. Each item carries what a row needs: the caller's \
            Selection (`poll.myOptionId`), the caller's follows with the actor both ways (`actor.isFollowing`, `actor.followsYou`), \
            and for a Reply the Comment it is under (`comment.parentId`). \
            An item can appear or vanish behind a cursor (a follow, a block); de-duplicate by `id`.
            **Side effects**: none
            **Errors**:
            - 400 `common.cursor_invalid`: `cursor` came from another list""")
    public ResponseEntity<ApiResponse<CursorResponse<NotificationResponse>>> list(
            @Parameter(description = "`nextCursor` of the previous page; omit for the first page") @RequestParam(required = false) String cursor,
            @Parameter(description = "Page size, default 20, clamped to 1..50", example = "20") @RequestParam(required = false) Integer limit,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(notificationService.list(principal.userId(), cursor, limit)));
    }

    @GetMapping("/unread-count")
    @CommonApiResponses
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Success",
            content = @Content(examples = @ExampleObject(value = """
                    {"success":true,"data":{"count":3},"message":null,"errorCode":null,"error":null}""")))
    @Operation(summary = "Unread notification count", description = """
            **Auth**: required
            **Precondition**: none
            **Behavior**: counts the notifications `GET /api/notifications` lists with `read` false: every kind the caller's \
            preferences leave on. The count stops at 100: `100` means 100 or more (show it as "99+"). \
            Poll this endpoint (for example on app focus and about once a minute) to drive a badge; there is no push.
            **Side effects**: none
            **Errors**: none specific to this endpoint""")
    public ResponseEntity<ApiResponse<UnreadCountResponse>> unreadCount(
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(notificationService.countUnread(principal.userId())));
    }

    @PostMapping("/{id}/read")
    @CommonApiResponses
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Success",
            content = @Content(examples = @ExampleObject(value = """
                    {"success":true,"data":{"success":true,"id":null,"status":null,"message":null},"message":null,"errorCode":null,"error":null}""")))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "The caller has no such notification (errorCode `notification.not_found`); "
            + "with a deprecated Poll id, the Poll does not exist or is Deleted (`vote.not_found`)",
            content = @Content(schema = @Schema(ref = OpenAPIConfig.ERROR_REF),
                    examples = @ExampleObject(value = """
                            {"success":false,"data":null,"message":"Notification not found","errorCode":"notification.not_found","error":"Notification not found"}""")))
    @Operation(summary = "Mark a notification read", description = """
            **Auth**: required
            **Precondition**: the caller has the notification now (it would be listed by `GET /api/notifications` with its kind switched on)
            **Behavior**: marks it read. Idempotent: marking an already-read notification succeeds and changes nothing.
            Deprecated: `id` may instead be a Poll id (UUID), as before the feed existed. That reads the Poll's current \
            notification: its INVITATION while the Poll has not Ended, its RESULT after. Use the notification `id`.
            **Side effects**: the unread count drops by 1 (first call only); `read` becomes true in the feed. For a Poll's RESULT \
            or INVITATION, `unread` becomes false on that Poll in `GET /api/votes/my`. A Poll's later RESULT is a separate \
            notification, unread until read itself.
            **Errors**:
            - 404 `notification.not_found`: no such notification for the caller now (unknown or malformed id, a Comment deleted, a follow undone...)
            - 404 `vote.not_found`: deprecated Poll id only; the Poll does not exist or is Deleted""")
    public ResponseEntity<ApiResponse<SimpleResultResponse>> markRead(
            @Parameter(description = "Notification id from the feed (deprecated: a Poll id)", required = true,
                    example = "comment_0199f2a5-1c2d-7e3f-8a4b-5c6d7e8f9a0b") @PathVariable String id,
            @AuthenticationPrincipal UserPrincipal principal) {
        notificationService.markRead(principal.userId(), id);
        return ResponseEntity.ok(ApiResponse.ok(SimpleResultResponse.ok()));
    }

    @PostMapping("/read-all")
    @CommonApiResponses
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Success",
            content = @Content(examples = @ExampleObject(value = """
                    {"success":true,"data":{"success":true,"id":null,"status":null,"message":null},"message":null,"errorCode":null,"error":null}""")))
    @Operation(summary = "Mark all notifications read", description = """
            **Auth**: required
            **Precondition**: none
            **Behavior**: marks read every notification whose `createdAt` is at or before now, of every kind (switched-off \
            kinds included, so turning one back on does not bring back old unread items). Idempotent.
            **Side effects**: the unread count becomes 0; notifications created later arrive unread
            **Errors**: none specific to this endpoint""")
    public ResponseEntity<ApiResponse<SimpleResultResponse>> markAllRead(@AuthenticationPrincipal UserPrincipal principal) {
        notificationService.markAllRead(principal.userId());
        return ResponseEntity.ok(ApiResponse.ok(SimpleResultResponse.ok()));
    }
}
