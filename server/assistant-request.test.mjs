import test from 'node:test';
import assert from 'node:assert/strict';
import { once } from 'node:events';
import { createAssistantServer, parseJson, validateRequest } from './assistant.mjs';

const uuid = 'e79a485e-e465-40df-9a82-15238cc0c58c';
const request = requestId => ({ request_id: requestId, question: 'Ne oldu?',
  sources: [{ id: 'S1', text: 'Güneş doğdu.' }] });
const config = { apiKey: 'controlled-provider-placeholder', model: 'controlled-model-placeholder',
  accessToken: 'controlled-server-access-code-1234' };
const wrongTypes = [[uuid], [[uuid]], null, true, 123, {}, []];

test('JSON arrays must not impersonate UUID strings through JavaScript coercion', () => {
  for (const id of wrongTypes) {
    // Pass through the actual strict wire parser: these are representable JSON values.
    const parsed = parseJson(Buffer.from(JSON.stringify(request(id))));
    assert.throws(() => validateRequest(parsed), error => error.status === 400);
  }
});

test('canonical UUID strings retain the exact existing request identity', () => {
  for (const id of [uuid, '00000000-0000-0000-0000-000000000000']) {
    const parsed = parseJson(Buffer.from(JSON.stringify(request(id))));
    assert.equal(validateRequest(parsed), parsed);
    assert.equal(parsed.request_id, id);
  }
});

test('real HTTP rejects non-string identities without spending provider calls or the valid-request quota', async () => {
  let calls = 0;
  const server = createAssistantServer(config, async req => {
    calls++;
    return { request_id: req.request_id, status: 'answered', message: '',
      statements: [{ text: 'Güneş doğdu.', citations: [{ source: 'S1', quote: 'Güneş doğdu.' }] }] };
  });
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  try {
    const url = `http://127.0.0.1:${server.address().port}/v1/book-answer`;
    // More than the 30 accepted-request quota: rejected input must not starve the next valid request.
    for (const id of Array.from({ length: 35 }, (_, index) => wrongTypes[index % wrongTypes.length])) {
      const response = await fetch(url, { method: 'POST',
        headers: { Authorization: `Bearer ${config.accessToken}`, 'Content-Type': 'application/json; charset=utf-8' },
        body: JSON.stringify(request(id)) });
      assert.equal(response.status, 400);
      assert.deepEqual(await response.json(), { error: 'assistant_unavailable' });
      assert.equal(calls, 0);
    }
    const valid = await fetch(url, { method: 'POST',
      headers: { Authorization: `Bearer ${config.accessToken}`, 'Content-Type': 'application/json; charset=utf-8' },
      body: JSON.stringify(request(uuid)) });
    assert.equal(valid.status, 200);
    assert.equal((await valid.json()).request_id, uuid);
    assert.equal(calls, 1);
  } finally {
    server.closeAllConnections();
    await new Promise(resolve => server.close(resolve));
  }
  assert.equal(calls, 1);
});
