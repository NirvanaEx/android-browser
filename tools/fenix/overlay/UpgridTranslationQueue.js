/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

// One queue per document inside the existing language-pair worker. Coalesce
// short paragraphs without joining their HTML or mixing their response IDs.
class UpgridTranslationQueue {
  #pending = new Map();
  #timer = null;
  #cancelled = false;
  #translateBatch;

  constructor(translateBatch) {
    this.#translateBatch = translateBatch;
  }

  runTask(translationId, sourceText, isHTML) {
    return new Promise((resolve, reject) => {
      if (this.#cancelled) return;
      this.#pending.set(translationId, { sourceText, isHTML, resolve, reject });
      this.#schedule(8);
    });
  }

  cancelTask(translationId) {
    // Match Gecko's cancellation contract: discarded requests never respond.
    this.#pending.delete(translationId);
  }

  cancelWork() {
    this.#cancelled = true;
    clearTimeout(this.#timer);
    this.#timer = null;
    this.#pending.clear();
  }

  #schedule(delay) {
    if (this.#timer !== null || this.#cancelled || !this.#pending.size) return;
    this.#timer = setTimeout(() => this.#drain(), delay);
  }

  #drain() {
    this.#timer = null;
    if (this.#cancelled || !this.#pending.size) return;
    const batch = [];
    let characters = 0;
    for (const [id, request] of this.#pending) {
      // Oversized individual paragraphs retain upstream handling, alone.
      if (batch.length && (batch.length >= 8 || characters + request.sourceText.length > 8192)) break;
      batch.push(request);
      characters += request.sourceText.length;
      this.#pending.delete(id);
    }
    try {
      const results = this.#translateBatch(batch);
      if (results.length !== batch.length) throw new Error("Translation batch response count mismatch");
      for (let i = 0; i < batch.length; i++) batch[i].resolve(results[i]);
    } catch (error) {
      for (const request of batch) request.reject(error);
    }
    // Yield between bounded inference calls so page cancellation and other
    // documents can run. Do not add another model, thread, or remote service.
    this.#schedule(0);
  }
}
