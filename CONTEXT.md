# Vomatt

A social polling platform: users publish polls, others cast ballots and discuss them.

## Language

### Polling

**Poll**:
A question with a set of options that users respond to. Every Poll has an end time.
_Avoid_: Vote (for the question itself), survey

**Scheduled**:
A Poll whose start time has not arrived; visible but not yet accepting Ballots. Only a Scheduled Poll can be edited, and only by its owner.

**Open**:
A Poll currently accepting Ballots, changes and Retractions.

**Ended**:
A Poll that no longer accepts Ballots: its Deadline passed, its owner Closed it, or every Invitee holds a Ballot. A Poll that Ended before its Deadline ended early.
_Avoid_: Expired, inactive, deactivated

**Deadline**:
The end time the owner set for a Poll. Ending early does not move it, so the Deadline and the moment the Poll actually Ended can differ.
_Avoid_: Planned end time, scheduled end

**Close**:
The owner ending an Open Unlisted or Invite-only Poll before its Deadline; the Poll becomes Ended. A Public Poll cannot be Closed, and a Scheduled Poll is Deleted rather than Closed.
_Avoid_: Deactivate, cancel

**Delete**:
The owner withdrawing a Poll so nobody, the owner included, can reach it any more; its data is kept. Allowed while the Poll is Scheduled, or within 20 minutes of posting even if it holds Ballots. Nobody is notified.
_Avoid_: Remove, Close

### Audience

**Audience**:
Who can find and cast Ballots in a Poll: Public, Unlisted or Invite-only. Fixed once the Poll is Open.
_Avoid_: Privacy, visibility (that is Voter Visibility)

**Public**:
A Poll listed in Feed, Explore, Search and its owner's profile, open to any signed-in user.

**Unlisted**:
A Poll reachable only through its link; anyone with the link can read it, and any signed-in user with the link can cast a Ballot or Comment. Never listed anywhere except its owner's own Polls.
_Avoid_: Private, hidden

**Invite-only**:
A Poll that only its owner and its Invitees can see, cast Ballots in or Comment on. Never listed anywhere except the owner's and the Invitees' own Polls.
_Avoid_: Private, restricted

**Invitee**:
A user, or an email address not yet tied to a user, that the owner of an Invite-only Poll allowed to cast a Ballot in it. The owner is never an Invitee but may still cast a Ballot. Once every Invitee holds a Ballot, the Poll Ends; an email address never tied to a user keeps it Open until its Deadline or a Close.
_Avoid_: Voter list, allowed voter

**Invitation**:
An Invitee being added to an Invite-only Poll. Invitees can be added while the Poll is Open, but an Invitee holding a Ballot cannot be removed. A user is told of their Invitation; an email address not yet tied to a user is sent one by email and becomes that user once they verify the address.

**Group**:
A reusable list of users and email addresses that its owner keeps privately for inviting. Inviting a Group adds its current members as Invitees; later changes to the Group do not touch existing Polls.
_Avoid_: Team, circle

### Ballots

**Ballot**:
One user's choice in one Poll; it holds exactly one Selection. Polls are single-choice. Casting a Ballot replaces the user's previous Ballot in that Poll. Ballots are frozen once the Poll has ended.
_Avoid_: Vote (for the user's submission)

**Selection**:
One option chosen within a Ballot.
_Avoid_: Vote, user vote

**Retraction**:
A user withdrawing their whole Ballot from a Poll, after which they are no longer a Participant.
_Avoid_: Unvote, remove vote

**Participant**:
A user who holds a Ballot in a given Poll.
_Avoid_: Voter (when counting), respondent

**Sealed**:
The state of an Open Poll's results: nobody, including its owner, can see per-option counts or Support until the Poll has Ended. A user sees only their own Ballot. Turnout stays visible.
_Avoid_: Hidden, private results

**Turnout**:
The number of Participants in a Poll. Visible at all times, including while Sealed. An Invite-only Poll also shows how many Invitees there are, and its owner can see which Invitees hold no Ballot yet.
_Avoid_: Total votes, vote count

**Voter Visibility**:
The owner's choice, fixed once the Poll is Open, of who may see which option each Participant chose once the Poll has Ended: nobody, the owner only (default), or anyone who can see the Poll. Anonymous Ballots stay out of it at every level. Participants see this level before casting a Ballot.
_Avoid_: Anonymous poll (as a yes/no)

**Anonymous Ballot**:
A Ballot its Participant chose to keep out of the voter list, whatever the Poll's Voter Visibility allows. It hides which option the user chose, not that they hold a Ballot: Turnout and the Invitees without a Ballot still count them. A user's account sets whether Ballots are Anonymous by default; each Ballot can say otherwise. A changed or recast Ballot stays as Anonymous as it was unless it says otherwise, and recasting the same option can change it while the Poll is Open. A Retraction removes the Ballot, so a Ballot cast after it starts again from the account default. To keep Anonymous Ballots anonymous, the voter list of a Poll holding 1 or 2 of them, not counting the viewer's own, is hidden altogether; with 3 or more it is shown without them, which still reveals their choice if they all chose the same option. The gate cannot tell when several of those Ballots belong to accounts one person controls.
_Avoid_: Anonymous poll, hidden vote

**Support**:
The fraction of a Poll's Participants whose Ballot chose a given option. Only visible once the Poll has Ended.
_Avoid_: Percentage, vote share

**Ended Notification**:
What a Poll's owner and its Participants receive once the Poll has Ended, pointing them to the results; for an Invite-only Poll, every Invitee too. Participants who Retracted receive none unless they are Invitees.
_Avoid_: Alert, results notice

### Discussion

**Comment**:
A top-level message a user posts on a Poll. A deleted Comment that still has Replies remains as a placeholder with its text and author hidden.

**Reply**:
A message posted under a Comment. Replies are one level deep: replying to a Reply adds another Reply to the same Comment.
_Avoid_: Sub-comment, nested comment

### Discovery

**Feed**:
The home stream of Open Public Polls, most recently opened first.
_Avoid_: Timeline, home list

**Explore**:
Browsing Public Polls under a tag, either the Open ones (newest or closing soonest first) or the Ended ones (most recently ended first). Scheduled Polls are excluded.
_Avoid_: Search (unless free-text is involved)

**Top**:
Open Public Polls ordered by Turnout, highest first; ties go to the most recently opened.
_Avoid_: Hot, trending, popular

**Search**:
Finding Open or Ended Public Polls whose title or description contains a free-text query, ordered as in Explore. Option text is not matched. A query must be at least two characters.
