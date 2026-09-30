"""Acceptance gate tied to the exact APK, signing identity and tested revision."""
REQUIRED = (
    'android_install_and_update', 'saved_data_preserved', 'cold_start_saved_tab',
    'empty_tab_and_menu', 'real_video_frame', 'player_enter_exit_playback',
    'manual_rotation', 'background_and_tab_pause', 'adblock', 'tampermonkey_scripts',
    'google_translation', 'translation_after_restart', 'translation_exceptions',
)


def validate(acceptance, apk, upstream):
    if not upstream.get('productionApproved'):
        raise ValueError('The pinned Chromium security base is not approved for distribution')
    if not apk.get('chromiumRevision') or apk['chromiumRevision'] != upstream.get('commit'):
        raise ValueError('The APK uses a different Chromium security base')
    for key in ('sha256', 'package', 'versionName', 'versionCode', 'signerSha256', 'headSha', 'chromiumRevision'):
        if not apk.get(key) or acceptance.get(key) != apk.get(key):
            raise ValueError('Android acceptance does not match APK field: ' + key)
    if acceptance.get('distributionApproved') is not True:
        raise ValueError('APK distribution has not passed acceptance')
    if not acceptance.get('testedAtUtc') or not acceptance.get('device'):
        raise ValueError('Real Android test context is missing')
    for name in REQUIRED:
        check = acceptance.get('checks', {}).get(name, {})
        if check.get('status') != 'passed' or not check.get('evidence'):
            raise ValueError('Required Android evidence missing: ' + name)
    return True
