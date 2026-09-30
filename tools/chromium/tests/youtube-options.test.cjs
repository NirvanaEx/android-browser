const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, '../overlay/youtube-options.js'), 'utf8');

function fixture() {
  const actions = [];
  const player = {
    querySelector: () => video,
    getAvailableQualityLevels: () => ['hd1080', 'hd720', 'auto'],
    getOption: () => [{languageCode: 'ru', vssId: '.ru', name: {simpleText: 'Русский'}}],
    setPlaybackQualityRange: (...args) => actions.push(['quality', ...args]),
    setOption: (...args) => actions.push(['caption', ...args]),
  };
  const video = {isConnected: true, closest: () => player};
  const document = {fullscreenElement: video};
  const adapter = vm.runInNewContext(source, {document});
  return {video, player, document, actions,
    run: (command = 'list', option = '') => JSON.parse(adapter(video, command, option))};
}

test('lists options and applies only a quality actually offered by the selected player', () => {
  const f = fixture();
  assert.deepEqual(f.run().qualities, ['hd1080', 'hd720', 'auto']);
  assert.equal(f.run('quality', 'hd720').ok, true);
  assert.deepEqual(f.actions, [['quality', 'hd720', 'hd720']]);
  assert.equal(f.run('quality', 'http://unrelated.example').ok, false);
  assert.equal(f.actions.length, 1);
});

test('rejects another fullscreen element and a different player-owned video', () => {
  const f = fixture();
  f.document.fullscreenElement = {};
  assert.equal(f.run('quality', 'auto').ok, false);
  f.document.fullscreenElement = f.video;
  f.player.querySelector = () => ({});
  assert.equal(f.run('quality', 'auto').ok, false);
  assert.deepEqual(f.actions, []);
});

test('supports the selected player container fullscreen and rejects leaving it during a getter', () => {
  const f = fixture();
  f.player.contains = value => value === f.video;
  f.document.fullscreenElement = f.player;
  assert.equal(f.run('quality', 'hd720').ok, true);
  assert.deepEqual(f.actions, [['quality', 'hd720', 'hd720']]);
  f.player.getAvailableQualityLevels = () => {
    f.document.fullscreenElement = f.video;
    return ['auto'];
  };
  assert.equal(f.run('quality', 'auto').error, 'source_changed');
  assert.equal(f.actions.length, 1);
});

test('does not act on a video moved out of the fullscreen container', () => {
  const f = fixture();
  f.player.contains = () => false;
  f.document.fullscreenElement = f.player;
  assert.equal(f.run('quality', 'auto').error, 'source_changed');
  assert.deepEqual(f.actions, []);
});

test('revalidates identity after site getters run', () => {
  const f = fixture();
  f.player.getAvailableQualityLevels = () => {
    f.video.isConnected = false;
    return ['auto'];
  };
  assert.equal(f.run('quality', 'auto').ok, false);
  assert.deepEqual(f.actions, []);
});

test('uses stable caption IDs and supports explicitly disabling captions', () => {
  const f = fixture();
  assert.equal(f.run().captions[0].id, '.ru');
  assert.equal(f.run('caption', '.ru').ok, true);
  assert.equal(f.actions[0][3].languageCode, 'ru');
  assert.equal(f.run('caption', 'gone').ok, false);
  assert.equal(f.run('caption', '').ok, true);
  assert.equal(Object.keys(f.actions[1][3]).length, 0);
});

test('bounds arrays before visiting entries and contains site exceptions', () => {
  const f = fixture();
  const values = Array(33).fill('auto');
  Object.defineProperty(values, 32, {get() { throw new Error('outside limit'); }});
  f.player.getAvailableQualityLevels = () => values;
  assert.equal(f.run().qualities.length, 32);
  f.player.getOption = () => { throw new Error('site failure'); };
  assert.deepEqual(f.run(), {ok: false, error: 'site_api_failed'});
});
