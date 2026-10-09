# Implementation Plan：Vote System Redesign（後端）

依據前端 `Vote System Redesign` rev 2（2026-10-04）與 2026-10-05 的 grilling 決策整理。
名詞以 [CONTEXT.md](CONTEXT.md) 為準；架構決策見 [docs/adr/](docs/adr/)。程式碼與 API 路徑維持 `Vote` 命名（不為改名破壞合約）。

> 正式環境（Neon）目前 **0 筆 Poll**：不需資料回填、不需歷史相容；但部署順序仍須對齊前端，避免線上前端壞掉。

---

## 決策摘要

| # | 主題 | 決定 |
|---|------|------|
| Q1 | 名詞 | Poll / Ballot / Selection / Participant；程式碼不改名 |
| Q3 | 送出 Ballot | 整張取代，同一 transaction |
| Q4 | 撤回 | 新增 `DELETE /api/votes/{id}/vote`，冪等 |
| Q5 | 結束後 | Ballot 凍結，不可改票、不可撤回 |
| Q6 / Q26 | 生命週期 | Scheduled / Open / Ended；Close = 提前結束或取消 Scheduled；Ended 再 Close 無動作；Ended 優先於 Scheduled |
| Q27 | 結果封存 | Open 期間任何人（含發起人）看不到各選項票數與 Support；`/results` 回 403；Ended 後公開（含未登入） |
| Q28 | 單選 | 產品只有單選；`allowMultipleChoices=true` 回 400（欄位保留、deprecated） |
| Q29 | Turnout | 投票人數任何時候可見 |
| Q31 / Q37 | 投票者可見度 | `voter_visibility`：不公開 / 僅發起人（預設）/ 登入使用者；僅 Ended 後生效；Open 後不可改 |
| Q32 | 我的選擇 | `VoteResponse.myOptionId: string \| null` |
| Q33 | 結束時間 | `endTime` 必填（最長 365 天） |
| Q34 | 通知 | 讀取時推算，只存已讀狀態（ADR 0003） |
| Q35 | 列表參數 | `status=open&sort=newest\|closing`、`status=ended`（固定 endTime DESC）；可疊加 `tag`、`q` |
| Q36 | My Polls | `/api/votes/my` = 我建立或我投過；`status=open\|ended`；ended 附 `unread` |
| Q37 | 編輯 | `PUT /api/votes/{id}`，僅發起人、僅 Scheduled |
| Q39 | 計數 | `vote_options.vote_count`；Turnout = 各選項加總 |
| Q7 / Q18 | 公開範圍 | 未登入＝唯讀：可看 Poll、（Ended 後的）結果、留言與回覆；投票、留言、按讚需登入；投票者清單依 Voter Visibility |
| FE-Q3 | 舊匿名旗標 | JSON 名稱是 `anonymous`（Java 欄位 `isAnonymous`）；送 `isAnonymous` 會被靜默忽略；未知欄位一律忽略（已實測） |
| FE-Q4 | 取消的 Scheduled | Close Scheduled 時保留 `startTime`、`endTime = now` → `endTime < startTime` 即為 Cancelled；建立與編輯都驗證 `endTime > startTime` |
| FE-Q5 | 通知內容 | 勝出選項與我的選擇都含 id 與文字 |
| Q8 / Q9 | Feed / Explore | Feed = Open、開放時間新到舊；Explore 依 `status` 選 Open 或 Ended，不含 Scheduled |
| Q11–Q13 | 搜尋 | 題目＋描述（不含選項）；自建 bigram index（ADR 0001）；`q` 至少 2 字元 |
| Q15–Q17 | 分頁 | 前台 5 支列表全部改 cursor，直接取代 offset（ADR 0002） |
| Q19 / Q24 | 列表附帶欄位 | `myOptionId`、`participantCount`（Turnout）、`commentCount`（含回覆）；每頁批次查詢 |
| Q20 | 標題長度 | 維持 200 |
| Q21–Q25 | 留言回覆 | 一層；`replyCount` + 點開載入；回覆舊到新；刪除有回覆的留言保留佔位 |

---

## 階段 1：Ballot、生命週期、計數欄位
**目標**：Ballot 整張取代與撤回；結束後凍結；Close 規則；選項計數欄位
**部署**：不破壞合約，隨時可部署
**成功標準**：
- 改票／撤回各為單一請求且原子；連點不會重複計數
- 對 Ended Poll 投票或撤回回 `errorCode = vote.ended`
- Close 已 Ended 的 Poll 不改 `end_time`；Close Scheduled 後狀態為 Ended，且 `end_time < start_time`（Cancelled）
- `vote_options.vote_count` 與 `user_votes` 實際筆數一致（校正 SQL 驗證）
**測試**：
- `shouldReplaceBallotWhenUserVotesAgain`、`shouldDecrementOldAndIncrementNewCountWhenChangingVote`
- `shouldRetractIdempotentlyWhenNoBallot`、`shouldRejectRetractionWhenPollEnded`
- `shouldRejectVoteWhenPollEndedWithVoteEndedCode`
- `shouldNotChangeEndTimeWhenClosingEndedPoll`、`shouldMarkCancelledWhenScheduledPollClosed`（`endTime < startTime`）
**狀態**：未開始
**修改檔案**：
- `src/main/resources/db/schema.sql` - `vote_options.vote_count`（`ALTER ... ADD COLUMN IF NOT EXISTS`）
- `src/main/java/com/vomatt/entity/VoteOption.java` - `voteCount` 欄位
- `src/main/java/com/vomatt/entity/Vote.java` - `deactivate()` 依狀態處理（Scheduled 取消、Ended 無動作）
- `src/main/java/com/vomatt/votes/VoteService.java` - 整張取代、撤回、計數加減、「使用者 × Poll」advisory lock、Ended 檢查
- `src/main/java/com/vomatt/votes/VoteController.java` - `DELETE /{voteId}/vote`
- `src/main/java/com/vomatt/repository/VoteOptionRepository.java` - 原子加減計數
- `src/main/java/com/vomatt/common/i18n/MessageKey.java`、`src/main/resources/i18n/messages*.properties` - `VOTE_ENDED`
- `docs/sql/reconcile-vote-counts.sql` - 計數校正（新）
- `openapi.yaml`
- 測試：`VoteServiceTest`、`VoteControllerTest`

## 階段 2：封存與 Poll 規則
**目標**：Open 期間結果封存；單選；`endTime` 必填；投票者可見度；Scheduled 可編輯；投票者清單端點
**部署**：⚠️ 破壞合約——須等前端階段 1（票數改選填、移除複選）與階段 3（截止時間必填）上線後
**成功標準**：
- 所有回傳 `VoteResponse` 的路徑（GET、POST /vote、DELETE /vote、建立、deactivate、編輯）在 Open 期間都不含各選項票數／Support——封存集中在 mapper 一處
- Open 期間 `/results` 回 403；Ended 後未登入也可讀
- `participantCount`（Turnout）任何時候都有值
- 建立時缺 `endTime` 或 `allowMultipleChoices=true` 回 400
- 投票者清單只在 Ended 後、依 `voter_visibility` 開放；cursor 分頁
- `PUT /api/votes/{id}` 只在 Scheduled 且為發起人時成功；驗證 `endTime > startTime`；標籤 usage_count 同步
- 未登入可讀留言：`GET .../comments` 公開，`principal` 為 null 時不再 NPE（`likedByCurrentUser = false`）
**測試**：
- `shouldOmitOptionCountsWhenPollOpen`（每個回傳路徑各一）、`shouldReturnForbiddenForResultsWhenPollOpen`
- `shouldRejectCreateWhenEndTimeMissing`、`shouldRejectCreateWhenMultipleChoicesRequested`
- `shouldHideVotersWhenVisibilityIsNobody`、`shouldShowVotersOnlyToOwnerWhenVisibilityIsOwner`、`shouldRejectVotersWhenPollOpen`
- `shouldRejectEditWhenPollOpen`、`shouldAdjustTagUsageWhenTagsEdited`、`shouldRejectEditWhenEndTimeBeforeStartTime`
- `shouldListCommentsWhenSignedOut`
**狀態**：未開始
**修改檔案**：
- `src/main/resources/db/schema.sql` - `votes.end_time SET NOT NULL`、`votes.voter_visibility`
- `src/main/java/com/vomatt/entity/Vote.java` - `voterVisibility`、狀態推導（Ended 優先）
- `src/main/java/com/vomatt/votes/VoteMapper.java` - 單一封存點；Support 以 Participant 為分母；移除 `voters`
- `src/main/java/com/vomatt/votes/dto/` - `CreateVoteRequest`（`endTime` 必填、`voterVisibility`）、`UpdateVoteRequest`（新）、`VoteResponse`、`VoteResultResponse`、`VoterResponse` 分頁 DTO
- `src/main/java/com/vomatt/votes/VoteService.java` / `VoteController.java` - 編輯、投票者清單、`/results` 403
- `src/main/java/com/vomatt/common/response/CursorResponse.java` - cursor 回應格式（新，供本階段投票者清單與階段 3 使用）
- `src/main/java/com/vomatt/common/security/SecurityEndpoints.java` - 公開 `GET /api/votes/{id}`、`/results`、`/comments`（`/my`、`/my-vote-status`、`/voters` 維持需登入，須排在公開規則之前）
- `src/main/java/com/vomatt/comments/VoteCommentController.java` / `VoteCommentService.java` - 未登入讀留言（null-safe principal）
- `src/main/java/com/vomatt/tags/TagService.java` - 編輯時調整 usage_count
- `openapi.yaml`（`allowMultipleChoices`、`anonymous` 標 deprecated）
- 測試：`VoteServiceTest`、`VoteMapperTest`、`VoteControllerTest`

## 階段 3：cursor 列表、Feed、Explore、搜尋
**目標**：前台列表改 cursor；`status` / `sort` / `tag` / `q`；bigram 搜尋；列表附帶欄位與 N+1 清理
**部署**：⚠️ 破壞合約——必須與前端階段 4（server-first feed）同時部署
**成功標準**：
- `GET /api/votes`、`/comments`、`/api/votes/my`、`/api/users/search`、`/api/tags/popular` 回 `CursorResponse`
- Feed 預設 `status=open&sort=newest`；`closing` 依 `end_time` ASC；`status=ended` 依 `end_time` DESC；`status=ended` 帶 `sort` 回 400
- `q` 走 bigram GIN index（`EXPLAIN` 為 Bitmap Index Scan）；`q` 少於 2 字元回 400；`%`、`_` 已跳脫
- 每頁固定查詢數（不隨筆數成長）；`myOptionId`、`participantCount`、`commentCount` 正確
**測試**：
- `shouldReturnNextCursorWhenMorePagesExist`、`shouldNotRepeatItemsAcrossCursorPages`
- `shouldExcludeScheduledFromExplore`、`shouldRejectSortWhenStatusEnded`
- `shouldRejectQueryShorterThanTwoChars`、`shouldEscapeLikeWildcardsInQuery`
- `shouldReturnNullMyOptionIdWhenSignedOut`
**狀態**：未開始
**修改檔案**：
- `src/main/resources/db/schema.sql` - `vomatt.text_bigrams()`（單一 SQL 表達式，無內部分號）、`votes.search_bigrams` 生成欄位、GIN index；排序 index：`(start_time DESC, id DESC)`、`(end_time, id)` 等
- `src/main/java/com/vomatt/repository/VoteRepository.java` - keyset 查詢（feed／closing／ended／tag／search，搜尋用 native query；`search_bigrams` 不映射進 entity）
- `src/main/java/com/vomatt/repository/UserVoteRepository.java`、`VoteCommentRepository.java` - 每頁批次查 `myOptionId`、`commentCount`
- `src/main/java/com/vomatt/votes/VoteService.java` / `VoteController.java` / `VoteMapper.java` - 新參數、cursor、批次組裝
- `src/main/java/com/vomatt/comments/*`、`src/main/java/com/vomatt/users/UserController.java`、`src/main/java/com/vomatt/tags/TagController.java` - 改 cursor
- `openapi.yaml`
- 測試：`VoteServiceTest`、`VoteControllerTest`、`VoteCommentServiceTest`、`TagServiceTest`、`UserServiceTest`

## 階段 4：通知與 My Polls
**目標**：「Poll 結束」通知（讀取時推算）；My Polls 改為「我參與的」
**部署**：與前端階段 5 同時部署
**成功標準**：
- 發起人與結束時的 Participant 收到通知；撤回者、取消的 Scheduled Poll 不產生通知
- 通知含標題、勝出選項（id + 文字，平手多個）、Support、Turnout、我的選擇（id + 文字）、身分
- 提前 Close 立即出現在通知中
- `unread-count` 正確；`POST /{pollId}/read` 冪等
- `/api/votes/my?status=open` 含自己建立的 Scheduled、依即將截止排序；`status=ended` 依結束時間 DESC，附 `unread`
**測試**：
- `shouldNotifyOwnerAndParticipantsWhenPollEnded`、`shouldNotNotifyRetractedUser`、`shouldNotNotifyWhenScheduledPollCancelled`
- `shouldCountUnreadNotifications`、`shouldMarkNotificationReadIdempotently`、`shouldIncludeOptionTextInNotification`
- `shouldIncludeParticipatedPollsInMyPolls`
**狀態**：未開始
**修改檔案**：
- `src/main/resources/db/schema.sql` - `notification_reads (user_id, vote_id, read_at)`（新表）
- `src/main/java/com/vomatt/notifications/` - `NotificationController`、`NotificationService`、`dto/`（新 domain）
- `src/main/java/com/vomatt/entity/NotificationRead.java`、`src/main/java/com/vomatt/repository/NotificationReadRepository.java`（新）
- `src/main/java/com/vomatt/votes/VoteService.java` / `VoteController.java` - `/my` 重新定義
- `openapi.yaml`
- 測試：`NotificationServiceTest`、`NotificationControllerTest`、`VoteServiceTest`

## 階段 5：留言回覆
**目標**：一層回覆
**部署**：階段 3 之後隨時（新增欄位與端點）
**成功標準**：
- 回覆的 `parent_id` 只能指向頂層留言；回覆「回覆」時掛在同一則頂層留言下
- 頂層留言附 `replyCount`；`GET .../comments/{commentId}/replies` 舊到新、cursor 分頁，未登入可讀
- 刪除有回覆的留言顯示佔位（隱藏內容與作者）；回覆全刪後佔位消失
- `commentCount` 含回覆、不含已刪除
**測試**：
- `shouldAttachReplyToRootWhenReplyingToReply`、`shouldKeepPlaceholderWhenDeletedCommentHasReplies`
- `shouldCountRepliesInCommentCount`、`shouldListRepliesOldestFirst`
**狀態**：未開始
**修改檔案**：
- `src/main/resources/db/schema.sql` - `vote_comments.parent_id` + index
- `src/main/java/com/vomatt/entity/VoteComment.java`
- `src/main/java/com/vomatt/comments/` - `VoteCommentService`、`VoteCommentController`、`CommentMapper`、`dto/`
- `src/main/java/com/vomatt/repository/VoteCommentRepository.java`
- `openapi.yaml`
- 測試：`VoteCommentServiceTest`、`VoteCommentControllerTest`

## 階段 6：移植 release-readiness 分支
**目標**：把 `origin/release-readiness` 中 main 缺少的部分移植回來（見 [RELEASE_READINESS_REVIEW.md](RELEASE_READINESS_REVIEW.md)），其餘以 main 為準
**部署**：不破壞合約，隨時可部署（6-6 為新增端點）
**成功標準**：
- 6-1 編輯 Scheduled Poll 未帶 `startTime` 時保留原值（不會因編輯而開放），驗證以實際開始時間為準
- 6-2 新留言 `edited=false`；時間戳為 null 時不 NPE
- 6-3 未登入可讀留言與回覆（`likedByCurrentUser=false`）；發留言、按讚仍需登入
- 6-4 公開的 `/api/votes/{id}` 規則只比對 UUID，`/my` 等具名子路徑不依賴規則先後順序
- 6-5 顯示名稱、簡介預設公開
- 6-6 `GET /api/users/{username}/votes`：該使用者的 Open 與 Ended Poll（不含 Scheduled 與開放前就取消的），cursor 分頁
**測試**：
- `shouldKeepStartTimeWhenEditOmitsIt`
- `shouldNotMarkNewCommentEdited`、`shouldNotFailWhenTimestampsMissing`
- `shouldListCommentsWhenSignedOut`
- `shouldKeepNamedVoteRoutesAuthenticated`
- `shouldShowDisplayNameAndBioByDefault`
- `shouldListUserPollsExcludingScheduled`
**狀態**：進行中
**修改檔案**：
- `src/main/java/com/vomatt/votes/VoteService.java`、`VoteController.java`、`src/main/java/com/vomatt/repository/VoteListRepository.java`
- `src/main/java/com/vomatt/comments/CommentMapper.java`、`VoteCommentController.java`、`VoteCommentService.java`
- `src/main/java/com/vomatt/common/security/SecurityEndpoints.java`
- `src/main/java/com/vomatt/users/VisibilityField.java`、`UserService.java`
- 對應測試

---

## 部署順序（後端 ↔ 前端 rev 2 階段）

| 後端階段 | 可部署時機 |
|---|---|
| 1 | 隨時 |
| 2 | 前端階段 1、3 上線後 |
| 3 | 與前端階段 4 同時 |
| 4 | 與前端階段 5 同時 |
| 5 | 後端階段 3 之後隨時 |

## 每階段共通
- 單元測試（Controller `standaloneSetup`、Service `MockitoExtension`），覆蓋率 ≥ 80%
- 更新 `openapi.yaml`
- schema 變更以 `ALTER TABLE ... IF NOT EXISTS` 撰寫（Neon 上手動執行 `schema.sql`）

## 不在本次範圍
- Email／推播／「即將截止」提醒（需真正的排程器，見 ADR 0003）
- 搜尋相關性排序與容錯（需外部搜尋引擎，見 ADR 0001）
- Open 期間編輯 Poll
