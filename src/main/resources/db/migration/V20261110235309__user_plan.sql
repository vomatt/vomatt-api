-- A user's plan, FREE or PRO, set by the payment webhook or an admin; it decides how many community accounts they may
-- own. The Stripe columns stay empty until billing goes live
ALTER TABLE vomatt.users ADD COLUMN IF NOT EXISTS plan VARCHAR(10) NOT NULL DEFAULT 'FREE' CHECK (plan IN ('FREE', 'PRO'));
ALTER TABLE vomatt.users ADD COLUMN IF NOT EXISTS plan_period_end TIMESTAMPTZ;
ALTER TABLE vomatt.users ADD COLUMN IF NOT EXISTS stripe_customer_id VARCHAR(255);
ALTER TABLE vomatt.users ADD COLUMN IF NOT EXISTS stripe_subscription_id VARCHAR(255);
