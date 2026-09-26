const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { setTimeout: delay } = require('node:timers/promises');

const workerPath = process.env.UPGRID_TRANSLATION_WORKER;
assert.ok(workerPath, 'Set UPGRID_TRANSLATION_WORKER to the generated Gecko worker');
const source = fs.readFileSync(workerPath, 'utf8');
const engineSource = source.slice(source.indexOf('class Engine {'), source.indexOf('class MockedEngine {'));
const queueSource = source.slice(source.indexOf('class UpgridTranslationQueue {'));

function harness({ pivot = false, fail = false, badRead = false } = {}) {
  const calls = [], handles = [];
  class Vector {
    constructor() { this.values = []; this.deleted = false; handles.push(this); }
    push_back(value) { this.values.push(value); }
    size() { return this.values.length; }
    get(index) { return this.values[index]; }
    delete() { assert.equal(this.deleted, false); this.deleted = true; }
  }
  function translate(...args) {
    const [messages, options] = args.slice(-2);
    calls.push({ messages: [...messages.values], options: [...options.values], pivot: args.length === 4 });
    if (fail) throw new Error('inference failed');
    const results = new Vector();
    results.values = messages.values.map(text => ({
      getTranslatedText() { if (badRead) throw new Error('read failed'); return `translated:${text}`; },
      delete() { this.deleted = true; },
    }));
    return results;
  }
  const context = {
    setTimeout, clearTimeout, console, trace() {},
    ChromeUtils: { now: () => performance.now(), addProfilerMarker() {} },
  };
  vm.runInNewContext(`${engineSource}\n${queueSource}\nBergamotUtils.constructSingleTranslationModel = () => ({}); this.Engine = Engine; this.Queue = UpgridTranslationQueue;`, context);
  const engine = new context.Engine('en', 'ru', {
    VectorString: Vector, VectorResponseOptions: Vector,
    BlockingService: class { translate = translate; translateViaPivoting = translate; },
  }, Array.from({ length: pivot ? 2 : 1 }, () => ({})));
  return { engine, calls, handles, Queue: context.Queue };
}

test('visible fragments use one inference call and preserve text/HTML response order', async () => {
  const { engine, calls, handles } = harness();
  const results = await Promise.all(Array.from({ length: 8 }, (_, i) => engine.translate(`<p>${i}</p>`, i % 2 === 0, 1, i)));
  assert.equal(calls.length, 1);
  assert.deepEqual(calls[0].options.map(o => o.html), [true, false, true, false, true, false, true, false]);
  assert.deepEqual(results.map(r => r.targetText), Array.from({ length: 8 }, (_, i) => `translated:<p>${i}</p>`));
  assert.ok(handles.every(h => h.deleted));
  assert.ok(handles.at(-1).values.every(r => r.deleted));
});

test('batches are bounded by fragment count and text size, oversized paragraphs run alone', async () => {
  const { engine, calls } = harness();
  await Promise.all(Array.from({ length: 19 }, (_, i) => engine.translate('x', false, 1, i)));
  assert.deepEqual(calls.map(c => c.messages.length), [8, 8, 3]);
  calls.length = 0;
  await Promise.all([5000, 4000, 9000, 1].map((size, i) => engine.translate('x'.repeat(size), false, 1, i)));
  assert.deepEqual(calls.map(c => c.messages.map(s => s.length)), [[5000], [4000], [9000], [1]]);
});

test('pivoting and empty input keep cardinality and release native handles', async () => {
  const { engine, calls, handles } = harness({ pivot: true });
  const results = await Promise.all(['first', '', 'third'].map((s, i) => engine.translate(s, false, 1, i)));
  assert.deepEqual(results.map(r => r.targetText), ['translated:first', '', 'translated:third']);
  assert.equal(calls[0].pivot, true);
  assert.ok(handles.every(h => h.deleted));
});

test('single cancellation and document disposal do not translate or answer discarded work', async () => {
  const { engine, calls } = harness();
  let discardedReplies = 0;
  engine.translate('cancel', false, 1, 1).then(() => discardedReplies++);
  engine.discardSingleTranslation(1, 1);
  engine.translate('dispose', true, 2, 1).then(() => discardedReplies++);
  engine.discardTranslations(2);
  const result = await engine.translate('keep', false, 1, 2);
  await delay(25);
  assert.equal(result.targetText, 'translated:keep');
  assert.equal(discardedReplies, 0);
  assert.deepEqual(calls.map(c => c.messages), [['keep']]);
});

test('documents with the same translation IDs remain isolated and can recreate a queue', async () => {
  const { engine, calls } = harness();
  const results = await Promise.all([engine.translate('one', false, 1, 1), engine.translate('two', false, 2, 1)]);
  assert.equal(calls.length, 2);
  assert.deepEqual(results.map(r => r.targetText), ['translated:one', 'translated:two']);
  engine.discardTranslations(1);
  assert.equal((await engine.translate('new', false, 1, 1)).targetText, 'translated:new');
});

for (const failure of ['fail', 'badRead']) {
  test(`${failure} rejects every batch member and frees native allocations`, async () => {
    const { engine, handles } = harness({ [failure]: true });
    const results = await Promise.allSettled([engine.translate('a', true, 1, 1), engine.translate('b', false, 1, 2)]);
    assert.ok(results.every(r => r.status === 'rejected'));
    assert.ok(handles.every(h => h.deleted));
    if (failure === 'badRead') assert.equal(handles.at(-1).values[0].deleted, true);
  });
}

test('queued cancellation runs between inference batches', async () => {
  const { Queue } = harness();
  let runs = 0;
  const queue = new Queue(batch => {
    runs++;
    setTimeout(() => queue.cancelWork(), 0);
    return batch.map(r => r.sourceText);
  });
  let replies = 0;
  for (let i = 0; i < 20; i++) queue.runTask(i, 'x', false).then(() => replies++);
  await delay(40);
  assert.equal(runs, 1);
  assert.equal(replies, 8);
});
