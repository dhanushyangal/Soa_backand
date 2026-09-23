# BidEasy backend

Spring Boot microservices: Eureka, API gateway, auth, auctions, bidding, payments. Amounts are **INR**, stored as integer paise.

Repo: https://github.com/dhanushyangal/Soa_backand

---

## What you need

- **Java 17 or 21** (`java -version`)
- **Python 3** (`python3 --version`) — used by `start-all.sh`
- A filled **`.env`** next to `start-all.sh` (copy from `.env.example`)
- Free ports **8761, 8080, 8081, 8082, 8083, 8084**

Maven Wrapper is included (`./mvnw`). No Docker for the default shared-Supabase setup.

---

## Env file

```bash
cp .env.example .env
```

Required in `.env`:

```
SUPABASE_DB_URL=jdbc:postgresql://...:5432/postgres?sslmode=require
SUPABASE_DB_USERNAME=
SUPABASE_DB_PASSWORD=
CLERK_ISSUER=https://....clerk.accounts.dev
CLERK_JWKS_URL=https://....clerk.accounts.dev/.well-known/jwks.json
INTERNAL_SERVICE_TOKEN=dev-internal-token
CORS_ORIGIN=http://localhost:3000
EUREKA_URL=http://localhost:8761/eureka/
```

Optional (wallet top-up via Dodo):

```
DODO_PAYMENTS_API_KEY=
DODO_PAYMENTS_WEBHOOK_KEY=
DODO_PAYMENTS_BASE_URL=https://test.dodopayments.com
DODO_PRODUCT_ID=
DODO_RETURN_URL=http://localhost:3000/wallet
```

Do not commit `.env`.

---

## Start everything

```bash
chmod +x start-all.sh stop-all.sh
./start-all.sh
```

The script builds JARs if needed, then starts:

| Service | URL |
| --- | --- |
| Eureka | http://localhost:8761 |
| Auth | http://localhost:8081 |
| Auction | http://localhost:8082 |
| Bidding | http://localhost:8083 |
| Payment | http://localhost:8084 |
| API gateway | http://localhost:8080 |

Check lots:

```bash
curl http://localhost:8080/api/auctions
```

Logs: `logs/*.log`

Stop:

```bash
./stop-all.sh
```

---

## Manual start (optional)

```bash
./mvnw -DskipTests package
set -a && source .env && set +a
java -jar eureka-server/target/eureka-server-0.0.1-SNAPSHOT.jar
java -jar auth-service/target/auth-service-0.0.1-SNAPSHOT.jar
java -jar auction-service/target/auction-service-0.0.1-SNAPSHOT.jar
java -jar bidding-service/target/bidding-service-0.0.1-SNAPSHOT.jar
java -jar payment-service/target/payment-service-0.0.1-SNAPSHOT.jar
java -jar api-gateway/target/api-gateway-0.0.1-SNAPSHOT.jar
```

---

## API smoke test

Base: `http://localhost:8080`

Public:

```
GET /api/auctions
GET /api/auctions/<id>
```

Authenticated — sign in on the frontend, then in the browser console:

```js
await window.Clerk.session.getToken()
```

Use `Authorization: Bearer <token>` for `/api/me`, `/api/wallet`, `POST /api/bids`, `POST /api/auctions`.

Bid body (amount in paise, so ₹85,000 = `8500000`):

```json
{ "auctionId": "<AUCTION_ID>", "amountCents": 8500000 }
```
