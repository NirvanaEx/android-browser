#!/usr/bin/env node
'use strict';
// Prepare settings for the real TWP extension. This does not install an
// extension, bypass its permission prompt, or alter a running Chromium build.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');

const sourceRoot = 'https://raw.githubusercontent.com/FilipePS/Traduzir-paginas-web/df34658/src/';
const sources = {
  'lib/config.js': '5b804cedf759192b367d71be91a8bf532837c9660757db6c60754f8ad9706eaf',
  'lib/languages.js': '162203f09d0a8a64412916e9d65361f38808b86bbdc09782831b80aac5e16721',
};
const extensionId = 'gkkkcomfmldkigajkmljnbpiajbpbgdg';

function checkedSource(cache, name) {
  const data = fs.readFileSync(path.join(cache, name));
  if (crypto.createHash('sha256').update(data).digest('hex') !== sources[name]) {
    throw new Error(`Unexpected upstream source hash: ${name}`);
  }
  return data.toString('utf8');
}

function settingsForRussian(languagesSource, previous = {}) {
  // Parse only string literals from the reviewed language table; never eval a download.
  const table = languagesSource.match(/google:\s*\[([\s\S]*?)\]/);
  if (!table) throw new Error('Google language table missing');
  const supported = [...table[1].matchAll(/"([a-zA-Z-]+)"/g)].map(match => match[1]);
  if (supported.length < 100 || !supported.includes('ru')) throw new Error('Unexpected language table');
  const excluded = new Set(['ru', 'auto', 'und', ...(previous.neverTranslateLangs || [])]);
  // Partial TWP imports merge with its settings. An exported existing profile
  // additionally preserves language exclusions and explicit site exceptions.
  return {
    ...previous,
    pageTranslatorService: 'google',
    textTranslatorService: 'google',
    useAlternativeService: 'no',
    targetLanguage: 'ru',
    targetLanguageTextTranslation: 'ru',
    targetLanguages: [...new Set(['ru', ...(previous.targetLanguages || []), 'en', 'de'])].slice(0, 3),
    alwaysTranslateLangs: [...new Set(supported)].filter(code => !excluded.has(code)),
    translateDynamicallyCreatedContent: 'yes',
    autoTranslateWhenClickingALink: 'yes',
    whenShowMobilePopup: 'when-necessary',
    dontShowIfPageLangIsTargetLang: 'yes',
  };
}

async function main() {
  const [cache, output, previousFile] = process.argv.slice(2);
  if (!cache || !output) throw new Error('Usage: node prepare-translation.cjs SOURCE_CACHE OUTPUT.txt [EXPORTED_TWP_SETTINGS.txt]');
  for (const name of Object.keys(sources)) {
    const file = path.join(cache, name);
    if (!fs.existsSync(file)) {
      const response = await fetch(sourceRoot + name, { signal: AbortSignal.timeout(30000) });
      if (!response.ok) throw new Error(`Download failed: ${name}: ${response.status}`);
      const data = Buffer.from(await response.arrayBuffer());
      if (crypto.createHash('sha256').update(data).digest('hex') !== sources[name]) {
        throw new Error(`Unexpected upstream download: ${name}`);
      }
      fs.mkdirSync(path.dirname(file), { recursive: true });
      fs.writeFileSync(file, data, { flag: 'wx' });
    }
    checkedSource(cache, name);
  }
  const previous = previousFile ? JSON.parse(fs.readFileSync(previousFile, 'utf8')) : {};
  const settings = settingsForRussian(checkedSource(cache, 'lib/languages.js'), previous);
  const contents = JSON.stringify(settings, null, 2) + '\n';
  if (fs.existsSync(output) && fs.readFileSync(output, 'utf8') !== contents) {
    throw new Error('Output already exists with different settings; choose another filename');
  }
  fs.mkdirSync(path.dirname(path.resolve(output)), { recursive: true });
  fs.writeFileSync(output, contents, 'utf8');
  console.log(JSON.stringify({
    settingsFile: path.resolve(output),
    extensionId,
    installUrl: `https://chromewebstore.google.com/detail/${extensionId}`,
    provider: 'google', targetLanguage: 'ru', automaticLanguages: settings.alwaysTranslateLangs.length,
    androidVerified: false,
  }, null, 2));
}

module.exports = { checkedSource, settingsForRussian, sources, extensionId };
if (require.main === module) main().catch(error => { console.error(error.message); process.exitCode = 1; });
