# 検証

## ホスト側

```sh
source /workspace/android-dev/env.sh
python3 scripts/verify-models.py
python3 scripts/gradle-cloud.py --no-daemon testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
```

単体テストでは横書き・縦書き・段組みの読み順、範囲の交差、PARSeq の終端トークン・繰り返し文字・補助漢字を確認します。

## Android 16

クラウドのソフトウェアエミュレーターは次の手順で起動します。

```sh
bash scripts/setup-device.sh
bash scripts/start-device.sh
python3 scripts/gradle-cloud.py --no-daemon connectedDebugAndroidTest
```

低速環境で Gradle の端末検出がタイムアウトする場合は、ビルド済みの同じテスト APK を `python3 scripts/run-device-tests.py` で直接実行できます。実行件数と JUnit の成功・失敗を検査し、未実行や途中終了も失敗にします。

GitHub Actions では KVM を使って同じ 6 件を実行します。`Check Android` とタグからのリリースの両方で必須です。端末テストに失敗した場合は APK を公開しません。画面キャプチャと実行ログは `android-device-results` アーティファクトに保存します。

`start-device.sh` はソフトウェア実行時の OS 起動が監視タイムアウトで再起動されるのを防ぐため、エミュレーター内の `ro.hw_timeout_multiplier` を 20 にして Android フレームワークを再起動します。この設定は APK には入りません。

端末テストでは同梱モデルの日本語・英語・縦書き認識、空白画像、切り出しと座標、行のタップ・範囲のドラッグ、テキスト画面、コピー、画面再作成、カメラ撮影と戻る操作を確認します。画像の認識を含む通しのテストもあります。

テスト用の画像は Android のフォントで描画しています。撮影の確認にはエミュレーターの仮想カメラを使います。実機カメラでの画質と処理時間は別途確認が必要です。

## リリース APK

```sh
python3 scripts/verify-apk.py app/build/outputs/apk/release/app-release.apk 0.1.0
"$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --verbose app/build/outputs/apk/release/app-release.apk
"$ANDROID_HOME/build-tools/36.0.0/zipalign" -c -P 16 4 app/build/outputs/apk/release/app-release.apk
```

パッケージ名、バージョン、min/target SDK 36、通信権限がないこと、4 つのモデル、arm64 ライブラリ、ELF と ZIP の 16 KB アラインメント、署名を確認します。

## 初版の記録

- モデル・文字表 5 ファイルのチェックサム確認: 成功
- 単体テスト 7 件: 成功
- debug / release lint: エラー 0 件
- 署名付き arm64 APK とメタデータ・署名・16 KB アラインメント確認: 成功
- 端末テスト 6 件と画面確認: GitHub Actions で検証予定

ローカルの仮想化なしエミュレーターでは APK の署名検証が長時間かかるため、KVM が使える CI で端末検証を完了します。転送したデバッグ APK の SHA-256 は元ファイルと一致しています。
