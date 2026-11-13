# Repository privacy cleanup

Sensitive values and private fixtures were removed from Git history on 2026-11-13.
EXAMPLE_* values, example.invalid addresses, sample images and documents are fictional.
They do not grant access to any real service.

Use ignored local .env/key.properties files, CI secrets, or the application's existing
environment settings for real configuration. Copy the adjacent *.example files where
needed. Never commit real credentials or uploaded user data.

Private signing keys were removed from Git, not regenerated. For updates of an existing
Android installation, supply its existing signing key outside Git. A new debug key has
a different certificate fingerprint and may require a separate OAuth debug registration.
UPGRID_DEBUG_KEYSTORE can point at an existing private debug key outside the checkout.

Do not merge or push an old checkout into this cleaned history. Fresh-clone the repository
and reapply reviewed uncommitted work without restoring private files or old commits.
Published tokens still require provider-side revocation/rotation; rewriting Git does not
revoke them. GitHub pull-request refs/cached objects may require GitHub Support cleanup.
