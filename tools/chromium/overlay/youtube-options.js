(function(video, command, option) {
  'use strict';
  const fail = error => JSON.stringify({ok: false, error});
  try {
    if (!video || !video.isConnected || document.fullscreenElement !== video)
      return fail('source_changed');
    const player = video.closest('.html5-video-player');
    if (!player || player.querySelector('video') !== video)
      return fail('unsupported');
    const stillCurrent = () => video.isConnected && document.fullscreenElement === video &&
        video.closest('.html5-video-player') === player && player.querySelector('video') === video;
    const qualities = typeof player.getAvailableQualityLevels === 'function'
        ? player.getAvailableQualityLevels() : [];
    const qualityIds = Array.isArray(qualities) ? qualities.slice(0, 32).filter(value =>
        typeof value === 'string' && /^[a-zA-Z0-9_]{1,32}$/.test(value)) : [];
    const tracks = typeof player.getOption === 'function'
        ? player.getOption('captions', 'tracklist') : [];
    const captionTracks = Array.isArray(tracks) ? tracks.slice(0, 32).filter(track => track &&
        typeof track.languageCode === 'string' && track.languageCode.length <= 64) : [];
    const trackId = track => (typeof track.vssId === 'string' && track.vssId.length <= 128)
        ? track.vssId : track.languageCode;
    if (!stillCurrent()) return fail('source_changed');
    if (command === 'quality') {
      if (!qualityIds.includes(option)) return fail('option_changed');
      if (typeof player.setPlaybackQualityRange === 'function')
        player.setPlaybackQualityRange(option, option);
      else if (typeof player.setPlaybackQuality === 'function') player.setPlaybackQuality(option);
      else return fail('unsupported');
    } else if (command === 'caption') {
      if (typeof player.setOption !== 'function') return fail('unsupported');
      const track = captionTracks.find(track => trackId(track) === option);
      if (option !== '' && !track) return fail('option_changed');
      player.setOption('captions', 'track', track || {});
    } else if (command !== 'list') {
      return fail('invalid_command');
    }
    if (!stillCurrent()) return fail('source_changed');
    const captions = captionTracks.map(track => ({
      id: trackId(track),
      label: (typeof track.name === 'string' ? track.name :
          typeof track.name?.simpleText === 'string' ? track.name.simpleText : track.languageCode).slice(0, 128)
    }));
    return JSON.stringify({ok: true, qualities: qualityIds, captions});
  } catch (_) {
    return fail('site_api_failed');
  }
})
