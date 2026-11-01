# 検証

## ホスト側

```sh
source /workspace/android-dev/env.sh
python3 scripts/verify-models.py
python3 scripts/gradle-cloud.py --no-daemon testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
```

ホスト側の 30 件は、読み順・PARSeq の復号 7 件、実モデルの OCR 3 件、起動・コピー 2 件、フィルタ・自動 OCR・引用 5 件、画像上のハンドル操作 3 件、文字境界 4 件、ページ表記 6 件です。OCR と画面には Android 16 の Robolectric と native graphics を使い、OCR は同じバージョンの ONNX Runtime の Linux CPU カーネルで実行します。認識結果をモックしていません。横書き・縦書き・英語・空白・切り出しと座標を確認します。画面画像は `app/build/reports/ui/` に出力します。

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

端末テストでは同梱モデルの日本語・英語・縦書き認識、空白画像、切り出しと座標、文字のタップ・範囲のドラッグ、テキスト画面、コピー、画面再作成、カメラ撮影と戻る操作を確認します。画像の認識を含む通しのテストもあります。

テスト用の画像は Android のフォントで描画しています。撮影の確認にはエミュレーターの仮想カメラを使います。実機カメラでの画質と処理時間は別途確認が必要です。

## リリース APK

```sh
python3 scripts/verify-apk.py app/build/outputs/apk/release/app-release.apk 0.2.1
"$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --verbose app/build/outputs/apk/release/app-release.apk
"$ANDROID_HOME/build-tools/36.0.0/zipalign" -c -P 16 4 app/build/outputs/apk/release/app-release.apk
```

パッケージ名、バージョン、min/target SDK 36、通信権限がないこと、4 つのモデル、arm64 ライブラリ、ELF と ZIP の 16 KB アラインメント、署名を確認します。

## リリース前の確認

- モデル・文字表 5 ファイルのチェックサム確認: 成功
- ホスト側の 30 件（実モデル OCR・画面操作を含む）: 成功
- debug / release lint: エラー 0 件
- 署名付き arm64 APK とメタデータ・署名・16 KB アラインメント確認: 成功
- ホスト画面 3 種と Android のカメラプレビュー・撮影 JPEG: 画像を確認
- Android 16 の端末テスト 6 件: 通しの成功は未確認。ローカルの仮想化なしエミュレーターで、Android の `libcamera_metadata.so` 内の SIGSEGV と推論の長時間実行が発生。CameraX 1.6.2 に更新し、撮影を低遅延モードに変更
- 実機でのカメラ動作・推論速度: 未確認

デバッグ APK は V4 署名付き incremental install に対応し、署名検証を保持します。端末側のテストは未実行・途中終了・件数不一致も失敗として扱います。

## 0.2.0 の変更と検証

- 撮影・取込・回転後に自動で OCR を実行。実モデルを使った取込からの通しテストを追加。
- ページ番号・ルビ・表の区分を検出結果と保存データに保持。チェックボックスの操作で抜粋対象を変更する。
- 表は検出した表領域と重なる文字行を対象にする。セルの構造・罫線を再現する機能は含まない。
- 矩形指定を文字の開始・終了ハンドルに変更。横書き・縦書き・行またぎ・結合文字・補助漢字を確認。
- PARSeq は行の文字列のみを返すため、画像上の文字位置は行ボックスと文字幅から推定する。文字ごとの検出ボックスではない。
- ホストのジェスチャーテストは、OS の拡大鏡ポップアップの Surface だけを省略する。描画・座標変換・タッチ処理・選択は実装を実行する。
- OCR の文字訂正・挿入後も元のページとの対応を保持する。
- ページ表記はコピー時だけ付ける。見開きの左右を番号の位置で対応付け、選択した側の番号を使う。番号未検出・曖昧な検出では番号を補完しない。
- 上流付属の実際の見開き画像 `digidepo_2531162_0024.jpg` で folio 30/31 と引用 `（pp. 30–31）`、`digidepo_3048008_0025.jpg` で表内の文字行と folio 38/39 を別途実モデルで確認。画像はリポジトリへ追加していない。
- Android 16 の操作テスト 1 件: 成功。最終 debug APK で、画像上の文字範囲選択 → テキスト切替 → コピー → Activity 再作成後の選択保持を確認。
- 端末操作は実際の Android View にタッチイベントを送り、ボタンの表示領域・状態とコピー結果を検査する。小さいテスト APK は通常インストールし、UiAutomation を使わない次のコマンドで確認した。実モデルを使う残りの端末テストと実機確認は未完了。

```sh
python3 scripts/run-device-tests.py --class-name com.sasakiy84.excerptmemo.DocumentUiTest#selectCharactersSwitchToTextCopyAndRestoreAfterRotation --no-ui-automation --expected-tests 1
```

## 0.2.1 の画面調整

- ページ画面の文章プレビューを最大 3 行とし、テキスト画面では編集欄が残りの高さを使う。
- 読取対象のチェックボックスを文章とコピー操作の近くへ移動。配色・角丸・アイコン・操作の余白を整理。
- ローカルのホストテスト 30 件、Android 16 の選択・切替・コピー・再作成テスト 1 件: 成功。
- ホストで 411 dp / 360 dp 幅のページ・テキスト・カメラ画面を描画して確認。Android エミュレーターの実画面でも確認。
- debug / release lint、署名、モデル、APK メタデータ、16 KB アラインメント: 成功。
