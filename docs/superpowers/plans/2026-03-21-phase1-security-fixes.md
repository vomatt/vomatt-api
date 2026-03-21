# Phase 1 Security Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修復 CODE_REVIEW_REPORT.md Phase 1 中 5 個安全性問題，消除權限提升漏洞、JWT 黑名單失效、CORS 無保護、憑證洩漏與 Rate Limiter 繞過。

**Architecture:** 5 個獨立的小型修改，各自針對單一安全問題。所有變更集中在 Controller、Security Filter 與 DTO 層，不影響 Domain/Repository 層。

**Tech Stack:** Java 21, Spring Boot 3.5.x, Spring Security, Redis (JWT blacklist), Resilience4j (rate limiting)

---

## 檔案修改總覽

| 檔案 | 修改原因 |
|------|----------|
| `application/dto/request/SignupRequest.java` | C4: 移除 roles 欄位 |
| `application/service/SignupService.java` | C4: assignRolesToUser 改為固定 ROLE_USER |
| `application/security/jwt/AuthTokenFilter.java` | C1: 加入 JWT 黑名單檢查 |
| `application/security/WebSecurityConfig.java` | C3: CORS 改為設定驅動 |
| `application/controller/AuthController.java` | C3+M5+H7: 移除 @CrossOrigin、移除驗證碼 log、修正 signup 自動登入 |
| `infrastructure/config/SecurityConfigurationProperties.java` | C3: 新增 corsAllowedOrigins 屬性 |
| `resources/application.properties` | C3: 新增 CORS allowed origins 設定 |

---

## Task 1: C4 — 移除 SignupRequest.roles 欄位（防止權限提升）

**Files:**
- Modify: `src/main/java/com/vomattapi/application/dto/request/SignupRequest.java`
- Modify: `src/main/java/com/vomattapi/application/service/SignupService.java`

- [x] **Step 1: 移除 SignupRequest.roles 欄位**
- [x] **Step 2: SignupService.assignRolesToUser 改為固定指派 ROLE_USER**
- [x] **Step 3: 確認編譯無誤**

---

## Task 2: C1 — AuthTokenFilter 加入 JWT 黑名單檢查

**Files:**
- Modify: `src/main/java/com/vomattapi/application/security/jwt/AuthTokenFilter.java`

- [x] **Step 1: 注入 JwtBlacklistService**
- [x] **Step 2: 在 validateJwtToken 後加入黑名單檢查**
- [x] **Step 3: 確認編譯無誤**

---

## Task 3: C3 — 收斂 CORS 設定

**Files:**
- Modify: `src/main/java/com/vomattapi/application/controller/AuthController.java`
- Modify: `src/main/java/com/vomattapi/application/security/WebSecurityConfig.java`
- Modify: `src/main/java/com/vomattapi/infrastructure/config/SecurityConfigurationProperties.java`
- Modify: `src/main/resources/application.properties`

- [x] **Step 1: SecurityConfigurationProperties 新增 corsAllowedOrigins**
- [x] **Step 2: application.properties 新增 CORS 設定**
- [x] **Step 3: WebSecurityConfig 改為讀取設定**
- [x] **Step 4: AuthController 移除 @CrossOrigin**

---

## Task 4: M5 — 移除驗證碼日誌 + GET 改 POST

**Files:**
- Modify: `src/main/java/com/vomattapi/application/controller/AuthController.java`

- [x] **Step 1: @GetMapping 改 @PostMapping**
- [x] **Step 2: 移除含驗證碼的 log.info**

---

## Task 5: H7 — signup 自動登入改走直接 JWT 生成（非 Controller 互呼）

**Files:**
- Modify: `src/main/java/com/vomattapi/application/controller/AuthController.java`

- [x] **Step 1: registerUser 改為直接生成 JWT + RefreshToken**
- [x] **Step 2: 移除中間的 generateVerificationCode 呼叫**

---

## Commit

```bash
git add src/main/java/com/vomattapi/application/dto/request/SignupRequest.java
git add src/main/java/com/vomattapi/application/service/SignupService.java
git add src/main/java/com/vomattapi/application/security/jwt/AuthTokenFilter.java
git add src/main/java/com/vomattapi/application/security/WebSecurityConfig.java
git add src/main/java/com/vomattapi/application/controller/AuthController.java
git add src/main/java/com/vomattapi/infrastructure/config/SecurityConfigurationProperties.java
git add src/main/resources/application.properties
git commit -m "fix(security): Phase 1 安全性修復"
```
