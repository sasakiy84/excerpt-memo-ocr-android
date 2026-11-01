# 検証

## ホスト側

```sh
source /workspace/android-dev/env.sh
python3 scripts/verify-models.py
python3 scripts/gradle-cloud.py --no-daemon testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
```

ホスト側の 12 件は、読み順・PARSeq の復号 7 件、実モデルの OCR 3 件、起動・選択・コピーの画面テスト 2 件です。OCR と画面には Android 16 の Robolectric と native graphics を使い、OCR は同じバージョンの ONNX Runtime の Linux CPU カーネルで実行します。認識結果をモックしていません。横書き・縦書き・英語・空白・切り出しと座標を確認します。画面画像は `app/build/reports/ui/` に出力します。

## Android 16

クラウドのソフトウェアエミュレーターは次の手順で起動します。

```sh
bash scripts/setup-device.sh
bash scripts/start-device.sh
python3 scripts/run-device-tests.py
```

端末が十分高速なら `connectedDebugAndroidTest` も使えます。上記スクリプトはビルド済みの同じテスト APK を直接実行します。実行件数と JUnit の成功・失敗を検査し、未実行や途中終了も失敗にします。

テストと画面確認はローカルで実行します。GitHub Actions は署名付き APK のビルドとリリース公開に使います。

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
- ホスト側の 12 件（実モデル OCR・画面操作を含む）: 成功
- debug / release lint: エラー 0 件
- 署名付き arm64 APK とメタデータ・署名・16 KB アラインメント確認: 成功
- ホスト画面 3 種と Android のカメラプレビュー・撮影 JPEG: 画像を確認
- Android 16 の端末テスト 6 件: 通しの成功は未確認。ローカルの仮想化なしエミュレーターで、Android の `libcamera_metadata.so` 内の SIGSEGV と推論の長時間実行が発生。CameraX 1.6.2 に更新し、撮影を低遅延モードに変更
- 実機でのカメラ動作・推論速度: 未確認

デバッグ APK は V4 署名付き incremental install に対応し、署名検証を保持します。端末側のテストは未実行・途中終了・件数不一致も失敗として扱います。
