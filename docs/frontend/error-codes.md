# errorCode 對照表

> 由 ErrorCodesDocTest 產生，勿手改。
> 重新產生：`./mvnw test -Dtest=ErrorCodesDocTest -Dsnapshot.update=true`，並提交差異。

前端依 `errorCode` 分支，不要比對 `message`。訊息語系由 `Accept-Language` 決定（見 [conventions.md](conventions.md)）。
HTTP status 由原始碼文字掃描 `ApiException.xxx(MessageKey.X` 取得；同一個 errorCode 可能出現在多個 status。
「建議 UI 行為」只依 HTTP status 通用推導，各 errorCode 的個別情境請看對應端點的 OpenAPI 說明。

## 錯誤碼

| errorCode | HTTP status | zh-TW 訊息 | en 訊息 | 建議 UI 行為 | 使用位置 |
|-----------|-------------|-----------|---------|--------------|----------|
| `common.bad_request` | 400 | 請求格式錯誤 | Bad request format | 顯示 message，讓使用者修正輸入 | GlobalExceptionHandler |
| `common.internal_error` | 500 | 伺服器錯誤，請稍後再試 | Internal server error, please try again later | 顯示通用錯誤提示 | GlobalExceptionHandler |
| `common.service_unavailable` | 503 | 服務暫時無法使用，請稍後再試 | Service temporarily unavailable, please try again later | 顯示服務暫時無法使用，稍後重試 | AuthService, GlobalExceptionHandler |
| `common.unauthorized` | 401 | 未授權的請求 | Unauthorized request | 見 [auth-flow.md](auth-flow.md) | JwtAuthenticationEntryPoint |
| `common.forbidden` | 403 | 沒有權限執行此操作 | You do not have permission to perform this action | 顯示無權限提示 | GlobalExceptionHandler, JwtAccessDeniedHandler |
| `common.rate_limited` | 429 | 請求過於頻繁，請稍後再試 | Too many requests, please try again later | 依 `Retry-After` 秒數後再重試 | RateLimitFilter |
| `common.validation_failed` | 400 | 輸入資料驗證失敗 | Validation failed | 顯示 message，讓使用者修正輸入 | GlobalExceptionHandler |
| `common.missing_param` | 400 | 缺少必要參數：{0} | Missing required parameter: {0} | 顯示 message，讓使用者修正輸入 | GlobalExceptionHandler, UserController |
| `common.type_mismatch` | 400 | 參數格式錯誤：{0} | Invalid parameter format: {0} | 顯示 message，讓使用者修正輸入 | GlobalExceptionHandler |
| `common.invalid_status` | 400 | 無效的狀態：{0} | Invalid status: {0} | 顯示 message，讓使用者修正輸入 | VoteListOrder, VoteService |
| `common.invalid_sort` | 400 | 無效的排序：{0} | Invalid sort: {0} | 顯示 message，讓使用者修正輸入 | VoteListOrder |
| `common.cursor_invalid` | 400 | 分頁 cursor 無效 | Invalid pagination cursor | 顯示 message，讓使用者修正輸入 | Cursor |
| `auth.token.invalid` | 401 | Token 無效 | Invalid token | 見 [auth-flow.md](auth-flow.md) | JwtAuthFilter |
| `auth.token.expired` | 401 | 登入已過期，請重新登入 | Session expired, please log in again | 見 [auth-flow.md](auth-flow.md) | JwtAuthFilter |
| `auth.invalid_otp` | 401 | 驗證碼錯誤 | Invalid verification code | 見 [auth-flow.md](auth-flow.md) | AuthService |
| `auth.otp.invalid_or_expired` | 401 | 驗證碼錯誤或已過期 | Verification code is invalid or has expired | 見 [auth-flow.md](auth-flow.md) | AuthService |
| `auth.otp.too_many_attempts` | 401 | 驗證次數過多，請重新發送驗證碼 | Too many attempts, please request a new verification code | 見 [auth-flow.md](auth-flow.md) | AuthService |
| `auth.otp.resend_cooldown` | 400 | 請等 {0} 秒後再重新發送 | Please wait {0} seconds before resending | 顯示 message，讓使用者修正輸入 | AuthService |
| `auth.admin.forbidden` | 403 | 此帳號無管理員權限 | This account does not have admin privileges | 顯示無權限提示 | AuthService |
| `auth.identifier.required` | 400 | 請提供 email 或手機號碼 | Please provide an email or phone number | 顯示 message，讓使用者修正輸入 | AuthService |
| `auth.email.send_failed` | 400 | 郵件發送失敗：{0} | Email delivery failed: {0} | 顯示 message，讓使用者修正輸入 | AuthService |
| `auth.phone.not_registered` | 400 | 此手機號碼尚未註冊 | This phone number is not registered | 顯示 message，讓使用者修正輸入 | AuthService |
| `auth.sms.send_failed` | 400 | 簡訊發送失敗：{0} | SMS delivery failed: {0} | 顯示 message，讓使用者修正輸入 | AuthService |
| `auth.refresh_token.invalid` | 401 | Refresh token 無效或已過期 | Refresh token is invalid or has expired | 見 [auth-flow.md](auth-flow.md) | AuthService |
| `auth.suspicious_login` | 401 | 安全性警告：偵測到異常登入，已強制登出所有裝置，請重新登入 | Security alert: suspicious login detected. All sessions have been terminated, please log in again | 見 [auth-flow.md](auth-flow.md) | AuthService |
| `auth.google.not_configured` | 400 | Google OAuth 尚未設定 | Google OAuth is not configured | 顯示 message，讓使用者修正輸入 | AuthService |
| `auth.google.no_email` | 401 | Google 驗證失敗：無法取得用戶信箱 | Google verification failed: unable to retrieve email | 見 [auth-flow.md](auth-flow.md) | AuthService |
| `auth.google.invalid_audience` | 401 | Google 驗證失敗：audience 不正確 | Google verification failed: incorrect audience | 見 [auth-flow.md](auth-flow.md) | AuthService |
| `auth.line.not_configured` | 400 | LINE Login 尚未設定 | LINE Login is not configured | 顯示 message，讓使用者修正輸入 | AuthService |
| `auth.line.token_exchange_failed` | 401 | LINE token 交換失敗 | LINE token exchange failed | 見 [auth-flow.md](auth-flow.md) | AuthService |
| `auth.line.profile_failed` | 401 | LINE 用戶資料取得失敗 | Failed to retrieve LINE profile | 見 [auth-flow.md](auth-flow.md) | AuthService |
| `auth.apple.invalid_token` | 401 | Apple token 格式無效 | Invalid Apple token format | 見 [auth-flow.md](auth-flow.md) | AuthService |
| `auth.apple.key_not_found` | 401 | Apple 驗證失敗：找不到對應公鑰 | Apple verification failed: matching public key not found | 見 [auth-flow.md](auth-flow.md) | AuthService |
| `auth.apple.invalid_signature` | 401 | Apple 驗證失敗：簽名無效 | Apple verification failed: invalid signature | 見 [auth-flow.md](auth-flow.md) | AuthService |
| `auth.apple.invalid_issuer` | 401 | Apple 驗證失敗：發行者不正確 | Apple verification failed: incorrect issuer | 見 [auth-flow.md](auth-flow.md) | AuthService |
| `auth.apple.invalid_audience` | 401 | Apple 驗證失敗：audience 不正確 | Apple verification failed: incorrect audience | 見 [auth-flow.md](auth-flow.md) | AuthService |
| `auth.apple.token_expired` | 401 | Apple 驗證失敗：token 已過期 | Apple verification failed: token has expired | 見 [auth-flow.md](auth-flow.md) | AuthService |
| `auth.apple.no_email` | 401 | Apple 驗證失敗：無法取得用戶信箱 | Apple verification failed: unable to retrieve email | 見 [auth-flow.md](auth-flow.md) | AuthService |
| `auth.apple.login_failed` | 401 | Apple 登入失敗 | Apple login failed | 見 [auth-flow.md](auth-flow.md) | AuthService |
| `auth.email_not_verified` | 401 | 此 OAuth 帳號的 email 尚未驗證，無法登入或合併既有帳號 | This OAuth account's email is not verified; cannot sign in or merge an existing account | 見 [auth-flow.md](auth-flow.md) | AuthService |
| `auth.account_banned` | 403 | 此帳號已被停權，無法登入 | This account has been banned and cannot sign in | 顯示無權限提示 | AuthService |
| `user.not_found` | 404 | 找不到此用戶 | User not found | 顯示找不到資源 | UserService, VoteCommentService, VoteService |
| `user.delete.forbidden` | 403 | 只能刪除自己的帳號 | You can only delete your own account | 顯示無權限提示 | UserService |
| `vote.not_found` | 404 | 找不到投票 | Vote not found | 顯示找不到資源 | VoteCommentService, VoteService |
| `vote.option.not_found` | 404 | 找不到投票選項 | Vote option not found | 顯示找不到資源 | VoteService |
| `vote.option.not_in_vote` | 400 | 選項不屬於此投票 | Option does not belong to this vote | 顯示 message，讓使用者修正輸入 | VoteService |
| `vote.not_allowed` | 400 | 投票尚未開始或已結束 | Voting period has ended or not started | 顯示 message，讓使用者修正輸入 | ApiException, VoteService |
| `vote.ended` | 400 | 投票已結束，無法再投票或撤回 | This poll has ended; ballots can no longer be cast or retracted | 顯示 message，讓使用者修正輸入 | VoteService |
| `vote.results.sealed` | 403 | 投票結束前無法查看結果 | Results are sealed until the poll ends | 顯示無權限提示 | VoteService |
| `vote.voters.hidden` | 403 | 你沒有權限查看此投票的投票者 | You are not allowed to see who voted in this poll | 顯示無權限提示 | VoteService |
| `vote.not_editable` | 400 | 只能在投票開始前編輯 | A poll can only be edited before it opens | 顯示 message，讓使用者修正輸入 | VoteService |
| `vote.list.sort_not_allowed` | 400 | 已結束的投票固定依結束時間排序，不可指定 sort | Ended polls are always ordered by end time; sort is not allowed | 顯示 message，讓使用者修正輸入 | VoteListOrder |
| `vote.search.query_too_short` | 400 | 搜尋字串至少需要 2 個字 | Search query must be at least 2 characters | 顯示 message，讓使用者修正輸入 | VoteService |
| `vote.multiple.not_allowed` | 400 | 此投票不允許複選 | Multiple choices not allowed for this vote | 顯示 message，讓使用者修正輸入 | VoteService |
| `vote.end_time.past` | 400 | 結束時間不可早於現在 | End time cannot be in the past | 顯示 message，讓使用者修正輸入 | VoteService |
| `vote.end_time.before_start` | 400 | 結束時間不可早於開始時間 | End time cannot be before start time | 顯示 message，讓使用者修正輸入 | VoteService |
| `vote.forbidden` | 403 | 只有投票建立者可執行此操作 | Only the vote creator can perform this action | 顯示無權限提示 | VoteService |
| `vote.options.min` | 400 | 至少需要 {0} 個選項 | Minimum {0} options required | 顯示 message，讓使用者修正輸入 | VoteService |
| `vote.options.max` | 400 | 最多只能有 {0} 個選項 | Maximum {0} options allowed | 顯示 message，讓使用者修正輸入 | VoteService |
| `vote.duration.exceeded` | 400 | 投票期間不可超過 {0} 天 | Vote duration cannot exceed {0} days | 顯示 message，讓使用者修正輸入 | VoteService |
| `comment.not_found` | 404 | 找不到留言 | Comment not found | 顯示找不到資源 | VoteCommentService |
| `comment.forbidden` | 403 | 只能修改或刪除自己的留言 | You can only modify your own comments | 顯示無權限提示 | VoteCommentService |
| `comment.parent.invalid` | 400 | 要回覆的留言不存在或不屬於此投票 | The comment being replied to does not exist in this poll | 顯示 message，讓使用者修正輸入 | VoteCommentService |
| `notification.not_found` | 404 | 找不到此通知 | Notification not found | 顯示找不到資源 | NotificationService |
| `tag.not_found` | 404 | 找不到標籤 | Tag not found | 顯示找不到資源 | TagService |
| `tag.name.exists` | 409 | 標籤名稱已存在：{0} | Tag name already exists: {0} | 顯示 message（資源狀態衝突） | TagService |
| `tag.slug.exists` | 409 | 標籤 slug 已存在：{0} | Slug already exists: {0} | 顯示 message（資源狀態衝突） | TagService |
| `tag.in_use` | 409 | 標籤仍被投票使用，無法刪除 | Cannot delete tag that is referenced by votes | 顯示 message（資源狀態衝突） | TagService |
| `tag.ids.invalid` | 400 | 部分標籤 ID 不存在 | Some tag IDs do not exist | 顯示 message，讓使用者修正輸入 | VoteService |
| `lookup.not_found` | 404 | 找不到字典資料 | Lookup not found | 顯示找不到資源 | LookupService |
| `lookup.exists` | 409 | 字典資料已存在：{0}/{1} | Dictionary item already exists: {0}/{1} | 顯示 message（資源狀態衝突） | LookupService |

## 成功訊息與其他

未在錯誤路徑上被掃到的 key（多為成功回應的 `message`，例如 `*.success`、`*.sent`）。前端不需要依這些值分支。

| key | zh-TW 訊息 | en 訊息 | 使用位置 |
|-----|-----------|---------|----------|
| `common.not_found` | 找不到指定資源 | The requested resource was not found |  |
| `common.conflict` | 資源衝突，請重試 | Resource conflict, please try again |  |
| `common.admin_only` | 此功能限管理員使用 | This feature is for administrators only |  |
| `common.file_required` | 請選擇要上傳的檔案 | Please select a file to upload |  |
| `common.file_read_failed` | 檔案讀取失敗 | Failed to read file |  |
| `common.file_too_large` | 檔案大小超過上限（{0}），請壓縮後重新上傳 | File size exceeds the limit ({0}). Please compress and re-upload |  |
| `common.invalid_content_type` | 不支援的檔案格式：{0} | Unsupported file type: {0} |  |
| `common.image_upload_failed` | 圖片上傳失敗：{0} | Image upload failed: {0} |  |
| `common.sign_url_failed` | 簽署 URL 失敗 | Failed to generate signed URL |  |
| `auth.otp.sent` | 驗證碼已發送，請檢查信筱 | Verification code sent, please check your email |  |
| `auth.login.success` | 登入成功 | Login successful |  |
| `auth.logout.success` | 已登出 | Logged out successfully |  |
| `auth.token.refreshed` | Token 已更新 | Token refreshed successfully |  |
| `auth.otp.expired` | 驗證碼已過期 | Verification code has expired |  |
| `auth.invalid_credentials` | 帳號或密碼錯誤 | Invalid email or password |  |
| `auth.user.not_found` | 找不到此者帳號 | Account not found |  |
| `auth.account.already_exists` | 該信筱已註冊 | This email is already registered |  |
| `auth.invalid_user_id` | 用戶 ID 格式無效 | Invalid user ID format |  |
| `user.update.success` | 用戶資料已更新 | User profile updated |  |
| `user.profile.not_found` | 找不到此用戶的個人資料 | User profile not found |  |
| `user.username.taken` | 使用者名稱已被使用 | Username already taken |  |
| `user.email.taken` | Email 已被使用 | Email already in use |  |
| `user.phone.taken` | 手機號碼已被使用 | Phone number already in use |  |
