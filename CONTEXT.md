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
A Poll that no longer accepts Ballots, whether its end time passed or its owner Closed it early; those two causes are not distinguished. A Cancelled Poll is also Ended.
_Avoid_: Expired, inactive, deactivated

**Cancelled**:
A Scheduled Poll that its owner Closed before it opened. It is Ended, never accepted Ballots, and is the only kind of Poll whose end precedes its start.

**Close**:
The owner ending an Open Poll early, or cancelling a Scheduled one; the Poll becomes Ended. Closing an Ended Poll changes nothing. Ended takes precedence over Scheduled.
_Avoid_: Deactivate

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
The number of Participants in a Poll. Visible at all times, including while Sealed.
_Avoid_: Total votes, vote count

**Voter Visibility**:
The owner's choice, fixed once the Poll is Open, of who may see which option each Participant chose once the Poll has Ended: nobody, the owner only (default), or any signed-in user. Participants see this level before casting a Ballot.
_Avoid_: Anonymous poll (as a yes/no)

**Support**:
The fraction of a Poll's Participants whose Ballot chose a given option. Only visible once the Poll has Ended.
_Avoid_: Percentage, vote share

**Ended Notification**:
What a Poll's owner and its Participants receive once the Poll has Ended, pointing them to the results. Participants who Retracted receive none, and a Poll Closed while Scheduled sends none.
_Avoid_: Alert, results notice

### Discussion

**Comment**:
A top-level message a user posts on a Poll. A deleted Comment that still has Replies remains as a placeholder with its text and author hidden.

**Reply**:
A message posted under a Comment. Replies are one level deep: replying to a Reply adds another Reply to the same Comment.
_Avoid_: Sub-comment, nested comment

### Discovery

**Feed**:
The public home stream of Open Polls, most recently opened first.
_Avoid_: Timeline, home list

**Explore**:
Browsing Polls under a tag, either the Open ones (newest or closing soonest first) or the Ended ones (most recently ended first). Scheduled Polls are excluded.
_Avoid_: Search (unless free-text is involved)

**Search**:
Finding Open or Ended Polls whose title or description contains a free-text query, ordered as in Explore. Option text is not matched. A query must be at least two characters.
