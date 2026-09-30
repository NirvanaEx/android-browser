'use strict';
// Exercise the pinned extension's real config loader/importer across reloads.
// This verifies settings compatibility, not Google requests or Android playback.
const assert = require('node:assert/strict');
const vm = require('node:vm');
const { checkedSource, settingsForRussian } = require('../prepare-translation.cjs');
const [cache] = process.argv.slice(2);
if (!cache) throw new Error('Usage: node verify-translation.cjs SOURCE_CACHE');
const languages = checkedSource(cache, 'lib/languages.js');
const config = checkedSource(cache, 'lib/config.js');
let stored = { neverTranslateLangs: ['de'], neverTranslateSites: ['example.org'], customDictionary: { hello: 'test' } };
let reloads = 0;
function startExtension() {
  const context = vm.createContext({
    structuredClone,
    chrome: {
      i18n: { getAcceptLanguages: callback => callback(['ru', 'en']), getUILanguage: () => 'ru' },
      runtime: { getManifest: () => ({ version: '10.1.5.0' }), reload: () => { reloads++; } },
      storage: {
        onChanged: { addListener() {} },
        local: {
          get: (key, callback) => callback(structuredClone(stored)),
          set: values => Object.assign(stored, structuredClone(values)),
        },
      },
    },
  }, { codeGeneration: { strings: false, wasm: false } });
  vm.runInContext(languages, context, { timeout: 3000 });
  vm.runInContext(config, context, { timeout: 3000 });
  return context;
}
const first = startExtension();
const previous = JSON.parse(vm.runInContext('twpConfig.export()', first));
const desired = settingsForRussian(languages, previous);
first.upgridSettings = JSON.stringify(desired);
vm.runInContext('twpConfig.import(upgridSettings)', first, { timeout: 3000 });
assert.equal(reloads, 1);
const second = startExtension();
const restored = JSON.parse(vm.runInContext('twpConfig.export()', second));
assert.equal(restored.pageTranslatorService, 'google');
assert.equal(restored.useAlternativeService, 'no');
assert.equal(restored.targetLanguage, 'ru');
assert.ok(restored.alwaysTranslateLangs.includes('en'));
assert.ok(restored.alwaysTranslateLangs.includes('fr'));
assert.ok(!restored.alwaysTranslateLangs.includes('ru'));
assert.ok(!restored.alwaysTranslateLangs.includes('de'));
assert.deepEqual(restored.neverTranslateSites, ['example.org']);
assert.deepEqual(restored.customDictionary, { hello: 'test' });
assert.equal(restored.translateDynamicallyCreatedContent, 'yes');
assert.equal(restored.autoTranslateWhenClickingALink, 'yes');
assert.equal(restored.alwaysTranslateLangs.length, desired.alwaysTranslateLangs.length);
console.log(JSON.stringify({ settingsImportVerified: true, persistedAfterReload: true,
  exceptionsPreserved: true, automaticLanguages: restored.alwaysTranslateLangs.length,
  googleNetworkVerified: false, androidVerified: false }, null, 2));
