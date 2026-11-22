import { test } from "node:test";
import assert from "node:assert/strict";
import { apiSignature, itnParamString, itnSignatureValid, phpUrlencode, signCheckout, randToCents, apiTimestamp } from "../lib/payfast";

// Expected values were produced by PHP's own urlencode()/md5() following PayFast's documented algorithm.
const pass = "MyPassphrase2026";

test("phpUrlencode behaves like PHP urlencode", () => {
  assert.equal(phpUrlencode("a b"), "a+b");
  assert.equal(phpUrlencode("R&D + co"), "R%26D+%2B+co");
  assert.equal(phpUrlencode("~tilde!*'()"), "%7Etilde%21%2A%27%28%29");
  assert.equal(phpUrlencode("é ü"), "%C3%A9+%C3%BC");
});

test("checkout signature matches PHP reference", () => {
  const data: Record<string, string> = {
    merchant_id: "10000100", merchant_key: "46f0cd694581a", return_url: "https://2way-radio.vercel.app/pay/success.html",
    cancel_url: "https://2way-radio.vercel.app/pay/cancelled.html", notify_url: "https://2way-radio.vercel.app/api/pay/notify",
    name_first: "Martin O'Brien", email_address: "martin+test@outlook.com", m_payment_id: "7d2c9f1e-1111-4a2b-9c3d-000000000001",
    amount: "200.00", item_name: "Infinity H Group PTT – Pro (monthly)", custom_str1: "11111111-1111-1111-1111-111111111111",
    custom_str2: "pro", custom_str3: "monthly", subscription_type: "1", recurring_amount: "200.00", frequency: "3", cycles: "0",
  };
  const signed = signCheckout(data, pass);
  assert.deepEqual(signed.at(-1), ["signature", "2412be6fa2fa0a50f2c3b3f453b91939"]);
  assert.equal(signed[0][0], "merchant_id"); // PayFast order, regardless of input order
});

test("checkout skips empty fields", () => {
  const a = signCheckout({ merchant_id: "1", merchant_key: "k", amount: "1.00", item_name: "x", name_last: "" }, pass);
  assert.ok(!a.some(([k]) => k === "name_last"));
});

const itn: [string, string][] = [["m_payment_id", "abc"], ["pf_payment_id", "1579137"], ["payment_status", "COMPLETE"],
  ["item_name", "Infinity H PTT – Pro (monthly)"], ["amount_gross", "200.00"], ["name_first", "Tom O'Neil"],
  ["email_address", "a+b@x.co"], ["token", "2afa4575-5628-051a-d0ed-4e071b56a7b0"], ["signature", "711deb1242f41f73dd7dd2ab9dae0480"]];

test("ITN signature: valid accepted, tampered or wrong passphrase rejected", () => {
  assert.equal(itnSignatureValid(itn, pass), true);
  assert.equal(itnSignatureValid(itn.map(([k, v]) => [k, k === "amount_gross" ? "2.00" : v]) as [string, string][], pass), false);
  assert.equal(itnSignatureValid(itn, "wrong"), false);
  assert.equal(itnSignatureValid(itn.slice(0, -1), pass), false);
  assert.ok(!itnParamString(itn).includes("signature"));
});

test("API signature matches PHP reference; timestamp format", () => {
  assert.equal(apiSignature({ "merchant-id": "10000100", version: "v1", timestamp: "2026-11-22T13:05:00+0000" }, pass),
    "bcbe6bbc19ed2374b2f4d10f1e904a3e");
  assert.match(apiTimestamp(new Date("2026-11-22T13:05:00Z")), /^2026-11-22T13:05:00\+0000$/);
});

test("rand/cents conversion", () => {
  assert.equal(randToCents("200.00"), 20000);
  assert.equal(randToCents("1000.00"), 100000);
  assert.equal(randToCents("149.99"), 14999);
});
