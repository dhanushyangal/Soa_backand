drop table if exists public.settlements, public.webhook_events, public.payments, public.ledger_entries, public.bids, public.auctions, public.wallets, public.profiles cascade;
-- BidEasy marketplace schema
create extension if not exists pgcrypto;

create table public.profiles (
  id uuid primary key default gen_random_uuid(),
  clerk_user_id text not null unique,
  email text,
  display_name text,
  avatar_url text,
  onboarded boolean not null default false,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table public.wallets (
  id uuid primary key default gen_random_uuid(),
  profile_id uuid not null unique references public.profiles(id) on delete cascade,
  clerk_user_id text not null unique,
  available_balance_cents bigint not null default 0 check (available_balance_cents >= 0),
  held_balance_cents bigint not null default 0 check (held_balance_cents >= 0),
  currency text not null default 'INR',
  version bigint not null default 0,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table public.auctions (
  id uuid primary key default gen_random_uuid(),
  seller_clerk_user_id text not null,
  title text not null,
  description text,
  image_url text,
  category text,
  condition text,
  location text,
  start_price_cents bigint not null check (start_price_cents >= 0),
  min_increment_cents bigint not null default 100 check (min_increment_cents > 0),
  current_price_cents bigint not null check (current_price_cents >= 0),
  leading_bidder_clerk_user_id text,
  leading_bid_id uuid,
  status text not null default 'OPEN' check (status in ('OPEN', 'CLOSING', 'SOLD', 'CLOSED')),
  ends_at timestamptz not null,
  version bigint not null default 0,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table public.bids (
  id uuid primary key default gen_random_uuid(),
  auction_id uuid not null references public.auctions(id) on delete cascade,
  bidder_clerk_user_id text not null,
  amount_cents bigint not null check (amount_cents > 0),
  status text not null default 'accepted' check (status in ('accepted', 'rejected', 'won', 'lost')),
  created_at timestamptz not null default now()
);

create table public.ledger_entries (
  id uuid primary key default gen_random_uuid(),
  profile_id uuid not null references public.profiles(id) on delete cascade,
  clerk_user_id text not null,
  entry_type text not null check (entry_type in ('top_up', 'hold', 'release', 'capture')),
  amount_cents bigint not null check (amount_cents > 0),
  auction_id uuid references public.auctions(id),
  bid_id uuid,
  dodo_payment_id text,
  idempotency_key text not null unique,
  created_at timestamptz not null default now()
);

create table public.payments (
  id uuid primary key default gen_random_uuid(),
  profile_id uuid not null references public.profiles(id) on delete cascade,
  clerk_user_id text not null,
  dodo_payment_id text unique,
  dodo_checkout_session_id text,
  product_id text not null default 'pdt_0No8GYiVeUpU21JfBYefp',
  amount_cents bigint not null default 0,
  currency text not null default 'INR',
  status text not null default 'open' check (status in ('open', 'succeeded', 'failed')),
  purpose text not null default 'wallet_top_up',
  metadata jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table public.webhook_events (
  id uuid primary key default gen_random_uuid(),
  webhook_id text not null unique,
  event_type text not null,
  payload jsonb not null default '{}'::jsonb,
  processed boolean not null default false,
  processed_at timestamptz,
  error_message text,
  created_at timestamptz not null default now()
);

create table public.settlements (
  id uuid primary key default gen_random_uuid(),
  auction_id uuid not null unique references public.auctions(id) on delete cascade,
  winner_clerk_user_id text,
  amount_cents bigint not null default 0,
  captured_at timestamptz not null default now()
);

create index idx_auctions_status_ends_at on public.auctions (status, ends_at);
create index idx_auctions_seller on public.auctions (seller_clerk_user_id);
create index idx_bids_auction_created on public.bids (auction_id, created_at desc);
create index idx_bids_bidder on public.bids (bidder_clerk_user_id);
create index idx_ledger_profile_created on public.ledger_entries (profile_id, created_at desc);
create index idx_payments_clerk on public.payments (clerk_user_id);
create index idx_wallets_clerk on public.wallets (clerk_user_id);

alter table public.profiles enable row level security;
alter table public.wallets enable row level security;
alter table public.ledger_entries enable row level security;
alter table public.payments enable row level security;
alter table public.webhook_events enable row level security;
alter table public.auctions enable row level security;
alter table public.bids enable row level security;
alter table public.settlements enable row level security;

create policy auctions_public_read on public.auctions
  for select
  to anon, authenticated
  using (status in ('OPEN', 'SOLD', 'CLOSED'));

create policy bids_public_read on public.bids
  for select
  to anon, authenticated
  using (
    exists (
      select 1 from public.auctions a
      where a.id = bids.auction_id
        and a.status in ('OPEN', 'SOLD', 'CLOSED')
    )
  );

