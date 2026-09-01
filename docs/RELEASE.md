# Release process

1. Confirm the second-account validation, device matrix, notes-limit measurement,
   wearable matching, delete-after-reinstall test, and Android developer package
   registration are recorded in the release issue.
2. Configure `SYNC_HEROIC_KEYSTORE_BASE64`, `SYNC_HEROIC_STORE_PASSWORD`,
   `SYNC_HEROIC_KEY_ALIAS`, and `SYNC_HEROIC_KEY_PASSWORD` as GitHub Actions secrets.
3. Update `CHANGELOG.md`, merge a green CI build, and create an annotated SemVer tag.
4. Push the tag. The release workflow tests, signs, verifies, hashes, and publishes
   the APK. Compare the published certificate fingerprint with the prior release.

Never commit or upload the keystore outside encrypted GitHub Actions secrets.

