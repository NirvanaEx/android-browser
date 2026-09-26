    const startTime = ChromeUtils.now();
    let messages;
    let options;
    let responses;
    const nonempty = batch.filter(request => request.sourceText.length > 0);
    try {
      messages = new this.bergamot.VectorString();
      options = new this.bergamot.VectorResponseOptions();
      for (const { sourceText, isHTML } of nonempty) {
        messages.push_back(sourceText);
        options.push_back({ qualityScores: false, alignment: true, html: isHTML });
      }
      if (nonempty.length) {
        if (this.languageTranslationModels.length === 1) {
          responses = this.translationService.translate(this.languageTranslationModels[0], messages, options);
        } else if (this.languageTranslationModels.length === 2) {
          responses = this.translationService.translateViaPivoting(
            this.languageTranslationModels[0], this.languageTranslationModels[1], messages, options
          );
        } else {
          throw new Error("Too many models were provided to the translation worker.");
        }
        if (responses.size() !== nonempty.length) throw new Error("Translation response count mismatch");
      }
      const elapsed = ChromeUtils.now() - startTime;
      let index = 0;
      const results = batch.map(({ sourceText }) => {
        if (!sourceText) return { targetText: "", inferenceMilliseconds: 0 };
        const response = responses.get(index++);
        try {
          return {
            targetText: response.getTranslatedText(),
            inferenceMilliseconds: elapsed / nonempty.length,
          };
        } finally {
          response.delete();
        }
      });
      ChromeUtils.addProfilerMarker(
        "TranslationsWorker", { startTime, innerWindowId },
        `Upgrid translated ${batch.length} fragments in one batch.`
      );
      return results;
    } finally {
      // Embind handles own WASM memory; release the vector and each response.
      responses?.delete();
      options?.delete();
      messages?.delete();
    }
