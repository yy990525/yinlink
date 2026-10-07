'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { createReceiver, MAX_FRAME } = require('../server.cjs');

async function fixture(t) {
  const receiver = await createReceiver({ port: 0, host: '127.0.0.1' });
  t.after(() => receiver.close());
  const base = new URL(receiver.url).origin;
  const post = (route, body, token, type = 'application/json') => fetch(base + route, {
    method: 'POST', headers: { 'Content-Type': type, ...(token ? { Authorization: 'Bearer ' + token } : {}) },
    body: Buffer.isBuffer(body) ? body : JSON.stringify(body)
  });
  const pair = async () => {
    const result = await post('/api/pair', { code: receiver.state().pairCode, name: '测试手机' });
    assert.equal(result.status, 200); return (await result.json()).token;
  };
  return { receiver, base, post, pair };
}

test('配对 → 上传画面 → 读取画面 → 断开，旧凭据随即失效', async t => {
  const { receiver, base, post, pair } = await fixture(t);
  assert.equal((await post('/api/pair', { code: 'bad' })).status, 401);
  const token = await pair();
  assert.equal((await post('/api/pair', { code: receiver.state().pairCode })).status, 409);
  const jpeg = Buffer.from([0xff, 0xd8, 0x01, 0x02, 0xff, 0xd9]);
  assert.equal((await post('/api/frame', jpeg, token, 'image/jpeg')).status, 200);
  assert.equal(receiver.state().receivedFrames, 1);
  assert.equal(receiver.state().receivedBytes, jpeg.length);
  assert.equal(receiver.state().device, '测试手机');
  const snapshot = await fetch(base + '/api/snapshot', { headers: { Authorization: 'Bearer ' + receiver.localToken } });
  assert.equal(snapshot.status, 200);
  assert.deepEqual(Buffer.from(await snapshot.arrayBuffer()), jpeg);
  const unchanged = await fetch(base + '/api/snapshot', { headers: { Authorization: 'Bearer ' + receiver.localToken, 'If-None-Match': snapshot.headers.get('ETag') } });
  assert.equal(unchanged.status, 304);
  assert.equal((await post('/api/disconnect', {}, receiver.localToken)).status, 200);
  assert.equal(receiver.state().connected, false); assert.equal(receiver.state().hasFrame, false);
  assert.equal((await post('/api/frame', jpeg, token, 'image/jpeg')).status, 401);
  assert.equal((await post('/api/heartbeat', {}, token)).status, 401);
  const newToken = await pair(); assert.notEqual(newToken, token);
  assert.equal((await post('/api/stop', {}, newToken)).status, 200);
});

test('手机凭据不能读电脑状态、截图或管理连接', async t => {
  const { receiver, base, post, pair } = await fixture(t);
  const token = await pair();
  assert.equal((await fetch(base + '/api/state')).status, 403);
  assert.equal((await fetch(base + '/api/state', { headers: { Authorization: 'Bearer ' + token } })).status, 403);
  assert.equal((await fetch(base + '/api/snapshot', { headers: { Authorization: 'Bearer ' + token } })).status, 403);
  assert.equal((await post('/api/disconnect', {}, token)).status, 403);
  assert.equal((await post('/api/regenerate', {}, receiver.localToken)).status, 409);
  assert.equal((await post('/api/heartbeat', {}, token)).status, 200);
});

test('拒绝畸形数据、非 JPEG 和超大画面', async t => {
  const { post, pair, receiver } = await fixture(t);
  assert.equal((await post('/api/pair', null)).status, 400);
  const token = await pair();
  assert.equal((await post('/api/frame', Buffer.from('fake'), token, 'image/png')).status, 415);
  assert.equal((await post('/api/frame', Buffer.from('fake'), token, 'image/jpeg')).status, 400);
  assert.equal((await post('/api/frame', Buffer.alloc(MAX_FRAME + 1), token, 'image/jpeg')).status, 413);
  assert.equal(receiver.state().hasFrame, false);
});

test('限制配对码尝试频率', async t => {
  const { post } = await fixture(t);
  for (let i = 0; i < 10; i++) assert.equal((await post('/api/pair', { code: 'bad' })).status, 401);
  assert.equal((await post('/api/pair', { code: 'bad' })).status, 429);
});

test('并发配对最多建立一个会话', async t => {
  const { receiver, post } = await fixture(t);
  const code = receiver.state().pairCode;
  const results = await Promise.all([post('/api/pair', { code }), post('/api/pair', { code })]);
  assert.deepEqual(results.map(r => r.status).sort(), [200, 409]);
});

test('静态资源和未知路径正确响应，不泄露磁盘文件', async t => {
  const { base } = await fixture(t);
  const page = await fetch(base + '/'); assert.equal(page.status, 200);
  assert.match(page.headers.get('Content-Security-Policy'), /script-src 'self'/);
  assert.match(await page.text(), /吟链/);
  assert.equal((await fetch(base + '/server.cjs')).status, 404);
  assert.equal((await fetch(base + '/api/unknown')).status, 403);
});
