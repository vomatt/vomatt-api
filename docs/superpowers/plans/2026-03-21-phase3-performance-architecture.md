# Phase 3 Performance & Architecture Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 修復 CODE_REVIEW_REPORT.md Phase 3 中 4 個效能與架構問題，消除 N+1 查詢、拆分過大 Controller、統一 API 路徑、並重新命名語意混淆的欄位。

**Architecture:** 依複雜度由低至高執行：N+1 查詢優化（修改 Repository/Mapper/Service）→ Controller 拆分（純重組）→ API 版本路徑統一→ DB 欄位重新命名（需 Flyway migration）。

**Tech Stack:** Java 21, Spring Boot 3.5.x, Spring Data JPA, Flyway

---

## 修改檔案總覽

| Task | 檔案 | 變更 |
|------|------|------|
| M1 | `UserVoteRepository.java` | 新增 countDistinctUserByVoteId、countByOptionGroupedForVote |
| M1 | `VoteMapper.java` | toResponse/toOptionResponse/toResultResponse 接受預取計數 |
| M1 | `VoteServiceImpl.java` | convertToVoteResponse 使用 DB COUNT 查詢 |
| M2 | `VoteCommentController.java` | 新建，移入留言 8 個端點 |
| M2 | `VoteController.java` | 移除留言相關端點 |
| M2 | `WebSecurityConfig.java` | 更新 permit 路徑（如需要） |
| H3 | `AuthController.java` | @RequestMapping 改為 /api/v1/auth |
| H3 | `WebSecurityConfig.java` | permitAll 規則更新 |
| H5 | `V8__rename_verification_code.sql` | 新建 Flyway migration |
| H5 | `User.java` | verificationCode → credential |
| H5 | `UserServiceImpl.java` | changeVerificationCode → changeCredential |
| H5 | `UserDetailsServiceImpl.java` | getPassword() 對應欄位 |
| H5 | `SignupService.java` | 使用 credential |
| H5 | `AuthService.java` | 使用 credential |

---

## Task 1: M1 — N+1 查詢優化

**核心問題：**
- `getVoteResults` 中先取所有 UserVote 再 stream distinct user count → 改 COUNT DISTINCT
- `VoteMapper.toOptionResponse` 呼叫 `option.getVoteCount()` 觸發每個 option 各自 lazy load

**修正策略：**
1. UserVoteRepository 新增兩個 JPQL 查詢
2. VoteMapper 方法接受外部傳入的預取 count，不再觸發 lazy load
3. VoteServiceImpl 在呼叫 VoteMapper 前先一次性查好計數

---

## Task 2: M2 — 拆分 VoteCommentController

將 VoteController 中所有 comment 端點（createComment、getComments、updateComment、deleteComment、likeComment、unlikeComment）移至新的 `VoteCommentController`，路徑保持 `/api/v1/votes/{voteId}/comments/**`。

---

## Task 3: H3 — 統一 API 版本路徑

`/api/auth/**` → `/api/v1/auth/**`
更新 WebSecurityConfig 的 permitAll 規則。

---

## Task 4: H5 — verificationCode 欄位重新命名

新增 Flyway migration V8：`ALTER TABLE users RENAME COLUMN verification_code TO credential`
對應更新 User entity 與所有參考到 verificationCode 的 service。
