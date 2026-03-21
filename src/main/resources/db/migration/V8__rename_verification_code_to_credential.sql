-- 將 users.verification_code 重新命名為 credential
-- 此欄位實際儲存 BCrypt hash，在登入流程中作為使用者憑證（credential）
ALTER TABLE vomatt.users RENAME COLUMN verification_code TO credential;
