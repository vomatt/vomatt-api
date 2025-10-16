# 開發規範
- 參考程式現有架構
- 使用 DDD 架構開發

# Vomatt Api - 會員系統

這個專案提供Vomatt應用的會員系統後端服務，包括認證、授權、會員管理等功能。

## 技術棧

- Java 21
- Spring Boot 3.4.4
- Spring Security with JWT
- PostgreSQL
- Flyway 資料庫版本管理
- Spring Data JPA
- Resilience4j 速率限制
- Twilio SMS API
- Spring Mail

## 功能

### 會員管理

- 會員註冊、登入、登出
  - 支援使用者名稱、電子郵件的基本註冊流程
  - 密碼強度檢查與安全存儲
  - 登入失敗嘗試次數限制與帳戶鎖定機制
- 角色權限管理
  - 基於RBAC (Role-Based Access Control)的權限系統
  - 預設角色：使用者、管理員、版主
  - 可擴展的角色權限設計
- JWT 身份驗證與授權
  - 無狀態令牌驗證
  - 訪問令牌與刷新令牌分離設計
  - 令牌到期機制
- 密碼加密與安全儲存
  - 使用BCrypt進行密碼雜湊
  - 安全的密碼重設流程
- 會員資料管理
  - 個人資料更新
  - 電子郵件和手機號碼變更驗證流程
- 會員活動記錄
  - 記錄重要會員操作
  - IP和用戶代理追蹤
  - 活動時間戳記錄

### 帳戶安全

- 電子郵件驗證
  - 驗證碼郵件發送
  - 時效性驗證碼管理
  - 電子郵件變更時重新驗證機制
- 電話號碼驗證
  - SMS簡訊驗證碼發送
  - 手機號碼唯一性驗證
  - 手機號碼變更時重新驗證機制
- 登入嘗試限制與帳戶鎖定
  - 防暴力破解保護機制
  - 可配置的嘗試次數限制
  - 臨時帳戶鎖定功能
- 密碼重設
  - 安全的重設鏈接生成
  - 重設令牌時效控制
  - 電子郵件通知機制
- 令牌刷新機制
  - 長效使用者會話管理
  - 無需重新登入的令牌更新
  - 刷新令牌到期與廢止管理

### 通知服務

- 電子郵件通知
  - 歡迎信
  - 驗證碼郵件
  - 密碼重設郵件
  - 支援HTML模板郵件
- SMS 簡訊通知
  - 使用Twilio API發送簡訊
  - 手機驗證碼發送
  - 歡迎簡訊
  - 安全事件通知

## 系統需求

- JDK 21+
- PostgreSQL 14+
- Maven 3.8+

## 環境變數配置

系統支援通過環境變數配置，關鍵設定如下：

### 資料庫連線

```
JDBC_DATABASE_URL=jdbc:postgresql://localhost:5432/vomatt
JDBC_DATABASE_USERNAME=postgres
JDBC_DATABASE_PASSWORD=postgres
```

### JWT 設定

```
JWT_SECRET=your-secret-key
JWT_EXPIRATION=86400000
JWT_REFRESH_EXPIRATION=604800000
```

### 服務開關配置

可以通過以下設定開關相關服務，當不需要使用特定服務時，將其設為false可避免系統因配置不完整而出現錯誤：

```
# 電子郵件服務開關
EMAIL_ENABLED=true|false

# SMS服務開關
SMS_ENABLED=true|false

# OAuth2服務開關
OAUTH2_ENABLED=true|false
```

### 電子郵件設定

只有在EMAIL_ENABLED=true時需要配置：

```
MAIL_HOST=smtp.gmail.com
MAIL_PORT=587
MAIL_USERNAME=your-email@gmail.com
MAIL_PASSWORD=your-app-password
```

### SMS 設定 (Twilio)

只有在SMS_ENABLED=true時需要配置：

```
SMS_ACCOUNT_SID=your-account-sid
SMS_AUTH_TOKEN=your-auth-token
SMS_PHONE_NUMBER=your-twilio-phone-number
```

### OAuth2 設定

只有在OAUTH2_ENABLED=true時需要配置：

```
GOOGLE_CLIENT_ID=your-client-id
GOOGLE_CLIENT_SECRET=your-client-secret
```

## 部署指南

### 本地開發環境

1. 確保安裝了 JDK 21 和 Maven
2. 設定環境變數或修改 application.properties
3. 執行以下命令：

```bash
mvn clean package
java -jar target/vomat-api-0.0.1-SNAPSHOT.jar
```

### Docker 部署

1. 建立 Docker 映像檔

```bash
docker build -t vomat-api .
```

2. 執行容器

```bash
docker run -p 8080:8080 \
  -e JDBC_DATABASE_URL=jdbc:postgresql://host.docker.internal:5432/vomatt \
  -e JDBC_DATABASE_USERNAME=postgres \
  -e JDBC_DATABASE_PASSWORD=postgres \
  vomat-api
```

## API 端點

### 認證與授權

- `POST /api/auth/signin` - 登入
  - 接受用戶名和密碼，返回JWT令牌和刷新令牌
  - 支援速率限制，防止暴力破解
  - 成功登入時記錄登入時間和IP地址

- `POST /api/auth/signup` - 註冊
  - 創建新會員帳戶
  - 驗證用戶名、電子郵件和手機號碼的唯一性
  - 自動分配基本用戶角色

- `POST /api/auth/refreshToken` - 刷新 JWT 令牌
  - 使用刷新令牌獲取新的訪問令牌
  - 驗證刷新令牌的有效性和到期時間

- `POST /api/auth/signout` - 登出
  - 廢除當前用戶的刷新令牌
  - 記錄登出活動

### 會員管理

- `GET /api/members/me` - 獲取當前會員資料
  - 返回已認證會員的詳細資料
  - 包含會員角色和驗證狀態

- `PUT /api/members/me` - 更新會員資料
  - 更新會員基本資料
  - 變更敏感資料（電子郵件、手機號碼）時重置驗證狀態

- `POST /api/members/verify-email` - 發送電子郵件驗證碼
  - 生成隨機驗證碼
  - 透過電子郵件發送驗證碼

- `POST /api/members/confirm-email` - 確認電子郵件驗證
  - 驗證用戶提供的驗證碼
  - 更新電子郵件驗證狀態

- `POST /api/members/verify-phone` - 發送手機驗證碼
  - 生成隨機驗證碼
  - 透過SMS發送驗證碼

- `POST /api/members/confirm-phone` - 確認手機驗證
  - 驗證用戶提供的驗證碼
  - 更新手機驗證狀態

- `POST /api/members/change-password` - 變更密碼
  - 驗證當前密碼
  - 更新到新密碼並加密存儲

- `POST /api/members/reset-password/request` - 請求密碼重設
  - 生成密碼重設令牌
  - 發送重設鏈接到用戶電子郵件

- `POST /api/members/reset-password/confirm` - 確認密碼重設
  - 驗證重設令牌
  - 設置新密碼並解鎖帳戶

## 資料庫結構

系統使用以下資料表：

- `members` - 會員基本資料
  - 存儲會員的個人資訊（ID、用戶名、電子郵件等）
  - 追蹤會員的驗證狀態
  - 管理登入嘗試和帳戶鎖定
  - 記錄積分和會員等級
  - 使用UUID作為主鍵，提高安全性

- `roles` - 角色定義
  - 定義系統中的角色類型
  - 預設包含用戶、版主和管理員角色
  - 支援擴展自定義角色

- `member_roles` - 會員角色關聯
  - 實現會員和角色之間的多對多關係
  - 允許會員擁有多個角色
  - 使用複合主鍵提高查詢效率

- `member_preferences` - 會員偏好設定
  - 存儲會員的個性化設定
  - 使用鍵值對結構靈活存儲各類設定
  - 支援擴展新的偏好類型

- `member_activities` - 會員活動記錄
  - 記錄會員的重要操作
  - 包含活動類型、描述、時間戳
  - 記錄IP地址和用戶代理，用於安全分析
  - 支援活動記錄的歷史查詢

- `refresh_tokens` - JWT 刷新令牌
  - 存儲用於獲取新訪問令牌的刷新令牌
  - 記錄令牌到期時間
  - 實現長效會話管理

- `password_reset_tokens` - 密碼重設令牌
  - 存儲密碼重設請求的令牌
  - 記錄令牌到期時間和使用狀態
  - 確保重設令牌只能使用一次

## 開發指南

### 新增自訂角色

1. 更新 `ERole` 枚舉
   - 在 `com.vomattapi.domain.user.ERole` 中添加新角色
   - 保持角色命名規則一致（ROLE_前綴）

2. 在資料表 `roles` 中插入新角色
   - 可通過SQL遷移腳本或初始化數據添加
   - 確保角色名稱與枚舉值一致

3. 更新權限控制配置
   - 在 `WebSecurityConfig` 類中配置新角色的訪問權限
   - 在控制器層使用 `@PreAuthorize` 標註基於角色的訪問控制

### 整合外部認證提供者

系統設計支援整合 OAuth2 外部認證提供者 (如 Google、Facebook 等)。若要啟用此功能，請配置 `application.properties` 中的 OAuth2 相關設定，並將 `app.oauth2.enabled` 設置為 `true`。

### 數據遷移管理

系統使用Flyway進行數據庫版本管理：

1. 新增遷移腳本
   - 在 `src/main/resources/db/migration` 目錄中創建新的SQL腳本
   - 遵循Flyway命名規則：`V{版本號}__{描述}.sql`
   
2. 遷移執行
   - 應用程序啟動時會自動執行未應用的遷移腳本
   - 可通過Maven插件手動執行遷移：`mvn flyway:migrate`

3. 開發環境重置
   - 開發環境可配置清理模式：`mvn flyway:clean`
   - 生產環境應禁用清理選項

### 安全性最佳實踐

1. 生產環境部署時：
   - 使用隨機生成的強JWT密鑰
   - 配置適當的令牌過期時間
   - 啟用HTTPS並配置安全的Cookie設置
   - 配置適當的速率限制保護登入端點

2. 敏感數據處理：
   - 所有環境變數應妥善保管，不要提交到版本控制系統
   - 生產環境使用加密的環境變數或密鑰管理服務
   - 定期輪換敏感憑證

