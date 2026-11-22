import { createHash, createHmac, randomUUID } from 'node:crypto';

/** Retry with the same eventId/path/body and a fresh timestamp. Timestamp is epoch seconds. */
export function eventHeaders({ path, body, secret, eventId = randomUUID(), timestamp = Math.floor(Date.now() / 1000) }) {
  const time = String(timestamp);
  const digest = createHash('sha256').update(body).digest('hex');
  const canonical = `GREENMART-EVENT-V1\nPOST\n${path}\n${eventId}\n${time}\n${digest}`;
  return {
    'X-Event-ID': eventId,
    'X-Event-Timestamp': time,
    'X-Signature': createHmac('sha256', secret).update(canonical).digest('hex'),
  };
}
