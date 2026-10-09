# Review：`release-readiness` 分支 vs `main`

- 審查日期：2026-10-09
- 分支：`origin/release-readiness`（5 commits，作者 Hsiang Chin，31 檔，+1191/−144）
- 分岔點：`bb0a991`（2026-10-02）；之後 `main` 另有 18 commits
- 需求來源：vomatt-web `docs/release/backend-requests.md`
- 未在此分支執行測試

## 結論

**不建議直接合併。** `main` 已用不同設計（cursor 分頁、bigram 搜尋、`voterVisibility`、`VoteStatus`）完成大部分相同需求；`git merge-tree` 預估 8 檔衝突（`VoteController`、`VoteService`、`VoteMapper`、`VoteResponse` 與 4 個測試檔）。

建議以 `main` 為準，只把下方 1.2 的 4 項移植回來，之後關閉此分支。

---

## 1. 需求差異

### 1.1 兩邊都有，但做法不同（採 `main`）

| 需求 | release-readiness | main | 建議 |
|---|---|---|---|
| 編輯 Scheduled Poll | `PUT /{id}`，標籤不動 | `PUT /{id}`，同步標籤與 usage_count、可改 `voterVisibility` | 採 main |
| 列表 | offset 分頁；`sort=newest\|popular\|ending`；`status=open\|ended\|all`；`LIKE`（含選項文字） | cursor（ADR 0002）；`sort=newest\|closing`；`status=open\|ended`；bigram（ADR 0001） | 採 main；需要 `popular` / `all` 另開需求 |
| 我投過的 Poll | 新增 `GET /participated` | 併入 `GET /my`（我建立或我投過） | 採 main；web 改呼叫 `/my` |
| `myOptionId` | `Optional<String>` + `myOptionIds` | `String` | 採 main（CONTEXT.md：Poll 為單選） |
| 投票者可見度 | 暫時只給發起人 | `voterVisibility` 欄位 + `/voters` | 採 main |
| 封存、`vote.ended`、`participantCount`、`commentCount` | 有 | 有 | 採 main |

### 1.2 只有此分支有（main 缺，建議移植）

| 項目 | main 現況 | 建議 |
|---|---|---|
| 未登入讀留言 | `AUTHENTICATED_VOTES` 仍含 `/api/votes/*/comments`，訪客讀不到 | 移植（需求 P0 #2；`IMPLEMENTATION_PLAN.md` Q7/Q18 也要求） |
| `CommentMapper.edited` | `createdAt.equals(updatedAt)`：非 null-safe，新留言可能被判成已編輯 | 移植「晚於建立 1 秒才算編輯」+ null-safe |
| 顯示名稱、簡介預設公開 | 無 | 移植 `VisibilityField.visibleByDefault`（需求 P1 #12） |
| `?creatorUsername=`（公開個人頁的 Poll） | 無 | 依 main 的 cursor 風格重寫；需確認是否排除 Scheduled（此分支未排除，但端點說明寫「scheduled are never listed」，前後矛盾） |

### 1.3 需求文件需更新

vomatt-web `backend-requests.md`：
- #8 寫「`status`, `sort` and `q` are not」，已過時
- #6 寫「newest first」，兩邊實作都不是

---

## 2. Bad Smell

| # | Smell | 位置 | 建議 |
|---|---|---|---|
| 1 | Duplicated Code | `VoteListSort.fromParam` 與 `VoteListStatus.fromParam` 同形 | 隨分支捨棄；main 已用 `VoteListOrder.of` |
| 2 | Data Clumps | `searchPublic*` 兩方法重複 `(now, includeOpen, includeEnded, pattern)` | 同上 |
| 3 | Primitive Obsession | DTO 欄位 `Optional<String> myOptionId` 靠 `@JsonInclude` 表達三態 | DTO 不用 `Optional`；採 main 的 `String` |
| 4 | Speculative Generality | `myOptionIds`（產品只有單選） | 刪除 |
| 5 | Controller 含邏輯 | `getActiveVotes` 的 if/else 分派與 pageable 重組 | 移到 Service（main 已如此） |
| 6 | 隱性副作用（**main 也有**） | `updateVote`：`startTime == null` 時設成 `now()`，編輯即開放 Poll | **在 main 修**：編輯未帶 `startTime` 時保留原值 |
| 7 | 不穩定結果 | `myOptionId = findFirst()`，查詢無 `ORDER BY` | 隨分支捨棄 |

---

## 3. 違反 CLAUDE.md

| 規則 | 違反處 |
|---|---|
| Javadoc 只寫在 public 方法 | `VoteService` 的 `requireVotingOpen`、`applyFields`、`addOptions`、`toResponses` 等 private 方法用 `/** */` |
| DTO 欄位加 `@Schema` | `VoteResponse` 新增 4 欄位皆未加 |
| 不修改 API 合約（除非明確要求） | `?sort=` 從 Spring sort 改為自訂 enum，`sort=createdAt,desc` 變 400 |
| 先提計劃、隨時更新計劃文件 | 未更新 `IMPLEMENTATION_PLAN.md` |
| 從現有代碼學習 > 引入新模式 | 搜尋用 `LIKE`，與 ADR 0001（bigram）衝突；分頁沒用 ADR 0002（cursor） |
| 名詞依 CONTEXT.md | 文件與註解用 vote / ended / participated，未用 Poll / Ended / Participant / Ballot |

---

## 4. 其他建議

1. **處理順序**
   1. 在 main 修 2-6（編輯未帶 `startTime`）
   2. 從 main 開新分支，逐項移植 1.2，每項一個 commit 並附測試
   3. 完成後關閉 `release-readiness`
2. **安全**：此分支用 UUID regex 限定公開路徑（`/api/votes/{voteId:[0-9a-fA-F]+-[0-9a-fA-F-]+}`），`/my` 等具名子路徑不會因規則順序被放行，比 main 依賴 `AUTHENTICATED_VOTES` 排序更穩，建議一併帶回。
3. **效能**：此分支的留言按讚整頁批次查詢（`countByCommentIds` / `findLikedCommentIds`）；尚未確認 main 的留言列表是否仍逐筆查，若是可一併移植。
4. **與 vomatt-web 同步**：web 的 `release-readiness` 依此分支 API 開發；改以 main 為準後，前端需改 `/participated` → `/my`、offset → cursor、`sort` 值。動手前先和前端對齊。
5. **驗證**：每個移植 commit 執行 `./mvnw clean test`（Testcontainers 需先設 OrbStack 的 `DOCKER_HOST`）。
