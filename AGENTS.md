# 開発

- 既存のチェックアウトを使う。ユーザーから求められない限り worktree を作らない。
- グローバルの `sasakiy-chat`、`sasakiy-coding`、`sasakiy-git`、`sasakiy-ui`、`sasakiy-japanese`、`sasakiy-testing` と、該当する Android 公式スキルを読む。
- Android 16 (API 36) 以上。Java / Android Views / CameraX / ONNX Runtime。
- 利用者向けの説明文は必要な状態・操作・エラーに絞る。
- モデルは `app/src/main/assets/models-manifest.json` の上流コミットに固定する。差し替え時はライセンス・文字表・テンソル形状・実機テストを再確認する。
- クラウド: `source /workspace/android-dev/env.sh`。Gradle は `python3 scripts/gradle-cloud.py` 経由で実行する。プロキシと TLS 検証を保持する。
- 必須確認: `python3 scripts/verify-models.py`、`testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest`。OCR/操作の変更は Android 16 の `connectedDebugAndroidTest` も実行する。
- 署名情報とトークンをログ、Git、公開アセットへ入れない。
- テストと画面確認はローカルで実行する。GitHub Actions は署名付き APK のビルドとリリース公開だけに使う。
- リリース版は `ANDROID_KEYSTORE_PATH` と `ANDROID_KEYSTORE_PASSWORD` が必要。alias は `excerpt-memo-release`。
