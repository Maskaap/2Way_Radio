# Subscriptions & payments (PayFast)

## The plans (starting prices, all editable)

| Plan | Monthly | Yearly | Yearly saving | Channels | Users per channel |
|---|---|---|---|---|---|
| **Basic** | R150 | R1 000 | 44% | 3 | 10 |
| **Pro** | R200 | R1 500 | 38% | 10 | 50 |
| **Business** | Contact us (optional price) | Contact us | — | Unlimited | Unlimited |

**Change prices any time:** admin page → **Plans** tab → edit → **Save**. Or use Supabase → Table Editor → `plans`, where prices are in cents: R150 = 15000.

- Leave a price **empty** if that option shouldn't be sold online. Business works this way: the app then shows **Contact us**.
- To sell Business online, simply type a price.
- Price changes apply to **new** sign-ups. Existing PayFast subscriptions keep the amount they signed up at; that's how PayFast works.

## How it works

1. In the app: **Settings → Subscription & billing** (or the **Plan** button) → choose Monthly/Yearly → **Choose Pro – R200**.
2. The phone's browser opens **PayFast's secure page**. The client pays by card, Instant EFT and so on.
3. PayFast notifies the server (`/api/pay/notify`). The server checks the signature, confirms with PayFast's server, checks the amount, and activates the plan for 1 month or 1 year.
4. Every month or year PayFast charges again automatically, and the plan is extended.
5. **Cancel:** in the app (**Cancel my subscription**). Access continues until the paid period ends.

## Rules the system enforces

| Rule | Where it's set |
|---|---|
| New accounts get a **free trial** (default 14 days, Basic limits) | Admin → Settings → `trial_days`, `trial_plan` |
| **Channel limit** per plan (creating a 4th channel on Basic is refused) | Admin → Plans |
| **Users-per-channel limit**, based on the channel owner's plan | Admin → Plans |
| After the plan expires (plus `grace_days`), the user can't join channels **if** `require_subscription` = `true` | Admin → Settings |
| Admins always have full access | — |

`require_subscription` starts as **false** so you can test freely. Switch it to **true** when you start charging clients.

## Setting up PayFast

### 1. Test first (sandbox: no real money)
1. Register at **sandbox.payfast.co.za**. You get a sandbox **Merchant ID** and **Merchant Key**.
2. In the sandbox dashboard: **Settings → Developer Settings** → set a **Passphrase**. Use **letters and numbers only**. PayFast requires a passphrase for subscriptions.
3. In Vercel → **Settings → Environment Variables**, add:
   ```
   PAYFAST_MERCHANT_ID=your sandbox merchant id
   PAYFAST_MERCHANT_KEY=your sandbox merchant key
   PAYFAST_PASSPHRASE=your passphrase
   PAYFAST_SANDBOX=true
   ```
4. **Redeploy**. The status page should show `PAYFAST_MODE: set (SANDBOX – test payments only)`.
5. In the app, choose a plan and pay with the sandbox test payment method. Check that:
   - the browser shows "Payment received", and the app's Subscription screen shows your plan,
   - admin → **Subscriptions** lists the client and the payment as `complete`.

### 2. Go live (real money)
1. Register or verify your business at **www.payfast.co.za**. PayFast needs your business and bank details and FICA documents.
2. Make sure **subscriptions / recurring billing** are enabled on your account; ask PayFast support if you can't see the option.
3. Set the **live** Merchant ID, Key and Passphrase in Vercel, and `PAYFAST_SANDBOX=false`.
4. **Redeploy.** The status page shows `LIVE – real money`.
5. Do one real low-value test, e.g. temporarily set a plan price to R5, then set it back.

PayFast charges transaction fees, which are taken from each payment. See payfast.co.za for current rates.

## Giving a plan by hand

Admin → **Subscriptions** → *Give a plan by hand*: choose the client, plan, period, end date and amount → **Save**. Use this for Business clients, EFT or cash payments, or free accounts. It doesn't charge anyone.

## Security

- Card details never touch your servers; PayFast handles them.
- Each payment notification needs a valid PayFast signature **and** PayFast's server confirmation, and the amount must match. Tampered or duplicate notifications are ignored. These checks are tested.
- Only the server, using the secret key, can activate plans. App users can't call the billing functions.
