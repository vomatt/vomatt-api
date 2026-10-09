# 名詞對照

領域名詞以 [CONTEXT.md](../../CONTEXT.md) 為準。API 路徑與 DTO 仍沿用舊名 `vote` / `Vote*`，前端畫面與程式內請使用左欄的名詞，避免混用。

| 領域名詞 | 意義 | API 路徑 | DTO |
|----------|------|----------|-----|
| **Poll** | 一個問題與選項，一定有結束時間 | `POST/PUT/GET /api/votes`、`GET /api/votes/{voteId}`、`GET /api/votes/my` | `CreateVoteRequest`、`VoteResponse` |
| **Scheduled / Open / Ended** | Poll 狀態（未開始 / 進行中 / 已結束） | 由 `VoteResponse` 欄位推得；列表以 status 參數篩選 | `VoteResponse` |
| **Close**（Cancelled） | 擁有者提前結束 Open Poll，或取消 Scheduled Poll | `PUT /api/votes/{voteId}/deactivate` | `SimpleResultResponse` |
| **Ballot / Selection** | 一位使用者在一個 Poll 的唯一選擇；重投會取代 | `POST /api/votes/{voteId}/vote` | `VoteRequest`、`VoteResponse` |
| **Retraction** | 撤回整張 Ballot | `DELETE /api/votes/{voteId}/vote`（另有 `DELETE …/vote/{optionId}`） | `VoteResponse` |
| **Participant** | 在該 Poll 持有 Ballot 的使用者 | `GET /api/votes/{voteId}/voters`、`GET /api/votes/{voteId}/my-vote-status` | `VoterResponse`、`UserVoteStatusResponse` |
| **Sealed** | Open Poll 的結果封存，結束前看不到各選項票數與 Support | `GET /api/votes/{voteId}/results` | `VoteResultResponse` |
| **Turnout** | Participant 人數，任何時候可見 | 隨 Poll 回傳 | `VoteResponse` |
| **Support** | 選了某選項的 Participant 比例，結束後才可見 | `GET /api/votes/{voteId}/results` | `VoteResultResponse` |
| **Voter Visibility** | 結束後誰能看到每人選了什麼（nobody / 僅擁有者 / 任何登入者） | 建立 Poll 時設定；讀取走 `/voters` | `CreateVoteRequest`、`VoterResponse` |
| **Ended Notification** | Poll 結束後給擁有者與 Participant 的通知 | `GET /api/notifications/unread-count`、`POST /api/notifications/{voteId}/read` | `UnreadCountResponse` |
| **Comment / Reply** | Poll 下的留言與其下一層回覆 | `/api/votes/{voteId}/comments`、`…/{commentId}/replies`、`…/{commentId}/like` | `CommentDto`、`CreateCommentRequest`、`UpdateCommentRequest` |
| **Feed** | 公開首頁：Open Poll，最近開始優先 | `GET /api/votes` | `VoteResponse`（`CursorResponse`） |
| **Explore / Search** | 依標籤瀏覽 / 以關鍵字搜尋 Poll | `GET /api/votes`、`GET /api/tags` | `VoteResponse`、`TagDto` |

> 路徑與 DTO 欄位以 Swagger 為準；此表若與 Swagger 不一致，以 Swagger 為準並請回報。
