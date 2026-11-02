# 検証

## ホスト側

```sh
source /workspace/android-dev/env.sh
python3 scripts/verify-models.py
python3 scripts/gradle-cloud.py --no-daemon testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
```

ホスト側は標準 58 件と、外部の提供写真を使う任意の比較・編集確認 3 件です。読み順・復号、実モデル OCR、選択・コピー、引用・フィルタ、画像編集、段落整形、文章ドロワー、処理経路の判定、PP-OCR と補正済みページの再読取りを確認します。OCR と画面には Android 16 の Robolectric と native graphics を使い、OCR は同じバージョンの ONNX Runtime の Linux CPU カーネルで実行します。認識結果をモックしていません。画面画像は `app/build/reports/ui/` に出力します。

提供写真を使う任意テストの実行方法と、ランタイム・画像補間の違いは [ocr-comparison.md](ocr-comparison.md) を参照してください。写真を指定しない通常実行では、この 1 件はスキップされます。

## Android 16

クラウドのソフトウェアエミュレーターは次の手順で起動します。

```sh
bash scripts/setup-device.sh
bash scripts/start-device.sh
python3 scripts/run-device-tests.py --root-staging
```

端末が十分高速なら `connectedDebugAndroidTest` も使えます。上記スクリプトはビルド済みの同じテスト APK を直接実行します。実行件数と JUnit の成功・失敗を検査し、未実行や途中終了も失敗にします。

テストと画面確認はローカルで実行します。GitHub Actions は署名付き APK のビルドとリリース公開に使います。

`start-device.sh` はソフトウェア実行時の OS 起動が監視タイムアウトで再起動されるのを防ぐため、エミュレーター内の `ro.hw_timeout_multiplier` を 20 にして Android フレームワークを再起動します。この設定は APK には入りません。

クラウドでは Android 16 の AOSP イメージ、AVD `excerpt-aosp`、`emulator-5556` を使います。`--root-staging` は root 化された検証用エミュレーター向けで、転送済みの完全な APK を PackageInstaller の領域へ配置します。Android の署名検証、SELinux、インストール時の検査は保持します。実端末など root が使えない環境では `--serial <端末ID> --no-incremental` を指定してください。

incremental install を使う場合、配信元 APK は `ANDROID_USER_HOME/test-apks/` に内容ハッシュ付きで保持します。後のビルドが未読込のモデルブロックを書き換えないためです。

ソフトウェア実行で変更箇所を確認するときは、対象を指定できます。

```sh
python3 scripts/run-device-tests.py --root-staging --no-ui-automation \
  --class-name com.sasakiy84.excerptmemo.PaddleInstrumentedTest --expected-tests 2
python3 scripts/run-device-tests.py --reuse-app --no-ui-automation \
  --class-name com.sasakiy84.excerptmemo.DocumentUiTest#selectCharactersSwitchToTextCopyAndRestoreAfterRotation \
  --expected-tests 1
```

新モデルの端末テストは 480 × 280 px の生成画像を使う互換性確認で、速度比較には使用しません。PP-OCR と UVDoc は独立したテストにして、それぞれの結果とタイムアウトを記録します。
UVDoc は仮想化なしのエミュレーターでは 20 分枠でも完了しなかったため、機能確認の上限を 60 分にしています。実行時間の評価は提供写真を使うホスト側の比較テストで行います。

端末テストでは同梱モデルの日本語・英語・縦書き認識、空白画像、切り出しと座標、文字のタップ・範囲のドラッグ、テキスト画面、コピー、画面再作成、カメラ撮影と戻る操作を確認します。画像の認識を含む通しのテストもあります。

テスト用の画像は Android のフォントで描画しています。撮影の確認にはエミュレーターの仮想カメラを使います。実機カメラでの画質と処理時間は別途確認が必要です。

## リリース APK

```sh
python3 scripts/verify-apk.py app/build/outputs/apk/release/app-release.apk 0.6.0
"$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --verbose app/build/outputs/apk/release/app-release.apk
"$ANDROID_HOME/build-tools/36.0.0/zipalign" -c -P 16 4 app/build/outputs/apk/release/app-release.apk
```

パッケージ名、バージョン、min/target SDK 36、通信権限がないこと、モデル 7 個と文字表 2 個のチェックサム、モデル量の 2 倍上限、arm64 ライブラリ、ELF と ZIP の 16 KB アラインメント、署名を確認します。

## 初版のリリース前の確認

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

## 0.3.0 の画像調整

- 通常画面から読取対象の設定行を外し、縦長の資料で表示を確認。
- 画像調整は回転後の座標で切り抜く。プレビューと出力に同じ変換を使い、角・辺・範囲移動、微調整、90° 回転に対応。
- 元画像を private 領域に保持し、適用後もリセットで再編集できる。調整値・元画像は保存し、ページを閉じると両画像を削除する。
- ローカルのホストテスト 35 件: 成功。回転と切り抜きの画素検査、実モデルでの切り抜き後の自動読取りとリセット、ハンドル操作、画面再作成時の編集値保持、キャンセルを確認。
- Android 16 の操作テスト 1 件: 成功。文字選択・コピー・画面再作成に加え、画像調整画面の起動、角度変更、取消を確認。

## 0.3.1 のアイコン

- 白背景と黒の線画で Adaptive Icon を構成し、透明な前景を monochrome レイヤーにも使う。
- 512 px / 144 px の通常表示と、白黒反転したテーマ表示を Android の Drawable で描画して確認。
- ラウンドアイコンも同じ Adaptive Icon を参照する。
- ローカルのホストテスト 35 件、debug / release lint、署名・モデル・APK の検査: 成功。

## 0.4.0 の文章ドロワーと段落整形

- 選択文の欄を連続的に高さ調整できるドロワーへ変更。ドラッグ中もコピー操作を画面下に残し、文字欄は独立してスクロールする。高さの割合は画面再作成・保存後の復元で保持する。
- 同じ段落の行を結合し、前行の短い終端・次行の一文字相当の字下げ・大きな行間・列の切替を段落の境界に使う。横書きと縦書きの両方に適用し、表の行は結合しない。
- 画像上の選択座標は元の改行付き文字列を使い続ける。結合後の文章には元の文字位置を対応付け、文字訂正後の部分コピーにもページ表記を付ける。
- ローカルのホストテスト 46 件: 成功。段落の結合と区切り、英語の空白、部分選択、結合後のページ対応、ドロワーの高さ変更を確認。
- Android 16 の操作テスト 1 件: 成功。ドロワーを 100 px 引き上げて指定通りの高さになること、文字選択・テキスト切替・コピー・画面再作成時の高さ保持と画像編集の取消を確認。
- debug / release lint、署名付き APK、モデル・署名証明書・16 KB アラインメントの検査: 成功。
- Android エミュレーターで縦長の資料を表示し、通常の高さと大きく引き上げた状態を撮影。コピー操作の表示と文章の折り返しを確認。

## 0.4.1 の名称とアイコン

- アプリ名を「方解抄」に変更し、リリース名と README に反映。
- 乳白色 `#F7F4ED` と琥珀色 `#B67A32` の 2 色で Adaptive Icon を構成。縦の形を途中で分断せず、行間に沿う劈開をベクターで表す。
- 前景を 108 dp の中央に配置し、通常・丸型・monochrome レイヤーに同じ形を使用。
- ローカルのホストテスト 46 件と debug / release lint: 成功。512 / 144 / 48 px とテーマアイコンの描画を確認。
- Android 16 エミュレーターに更新 APK をインストールし、ホーム画面とアプリ一覧で丸型アイコンと「方解抄」の表示を確認。
- モデル、APK メタデータ・アプリラベル、署名証明書、16 KB アラインメントの検査: 成功。

## 0.5.0 の OCR と配色

- 標準のホストテスト 55 件と提供写真の比較 1 件: 最終コードで 56 件すべて成功。外部画像を使う任意の比較は、通常実行ではスキップし、`OCR_EVALUATION_DIR` を指定して実行する。
- PP-OCRv6 small の実モデルで横書き・縦書きを確認。補正済みページを再度変形せず、PP 認識を維持することと、手動編集で補正済みフラグをリセットすることを確認。
- 提供画像の重複を除いた 3 枚・10 抜粋・647 字では、アプリの Java コードで抜粋 CER が 12.21% から 1.24% へ低下。全 3 枚と縮小版で、時間が現行の 2 倍未満であることを確認。ホスト CPU の値であり、物理端末の性能値ではない。詳細は [比較レポート](ocr-comparison.md)。
- debug / release lint: エラー 0 件。乳白色と琥珀色の明色・暗色の画面、選択ハンドル、コピー操作をホストで描画して確認。
- Android 16 の PP-OCR 日本語・英語認識 1 件と、ドロワー・選択・コピー・画面再作成の操作 1 件: 成功。UVDoc の CPU 推論は仮想化なしのエミュレーターで 20 分の上限に達し、完走を確認できていない。試験した XNNPACK バックエンドは初期化時に異常終了したため取り除いた。配布版は CPU 実行で、実機での UVDoc の完走・速度・消費電力は未確認。
- モデル 7 個・文字表 2 個のチェックサム、署名付き APK、前版と同じ証明書、通信権限がないこと、OpenCV を含む arm64 ライブラリの ELF と ZIP の 16 KB アラインメント: 成功。
- このエミュレーターでは IncFS 経由の APK ページ読取りが失敗し、通常の FileBridge コピーも非常に遅かった。`--root-staging` で完全な APK を先に配置し、通常の PackageInstaller による検証・インストールを実行する。

## 0.5.1 の補正後編集と進捗

- 標準のホストテスト 57 件と、提供写真を使う比較・補正後編集 2 件が成功。Android 16 の切り替え・画面再作成と、選択・コピーを合わせた操作テスト 2 件も成功。
- debug / release lint、モデル・文字表 9 ファイルのチェックサム、署名付き release APK のモデル内容・SDK・ABI・16 KB 整列を確認。前版と同じ証明書を使用。

- 元画像と補正後の画像を編集の基準として切り替える。適用した範囲と角度は基準ごとに保存し、編集中の両方の設定は切り替え・Activity 再作成でも保持する。
- UVDoc の補正画像を `rectified.png` に保存し、編集済みの `page.png` と分ける。0.5.0 の保存データでは、表示中の補正画像を最初の編集基準として引き継ぐ。元画像は `source.png` に保持する。
- 補正後を切り抜いても補正済みフラグを維持し、NDLOCR の行検出と PP-OCR の認識だけを実行する。元画像への切り替え、新しい画像の読込み、ページを閉じたときの状態も確認する。
- 実モデルが通知する処理段階と件数を検査し、進捗表示に「NDLOCR-Lite を実行中、1/2」が出ることを確認する。PP-OCR の行検出・認識と UVDoc の補正もそれぞれの名前で表示する。
- ホストで編集画面を描画して確認。補正後の切り抜き、保存状態からの復元、元画像の保持は生成画像と提供写真で確認する。
- Android の対象テストは次の 2 件。通しの 9 件すべての成功や、UVDoc 自体の実端末での性能を確認したという意味ではない。

```sh
python3 scripts/run-device-tests.py --root-staging --no-ui-automation \
  --class-name 'com.sasakiy84.excerptmemo.ImageEditUiTest,com.sasakiy84.excerptmemo.DocumentUiTest#selectCharactersSwitchToTextCopyAndRestoreAfterRotation' \
  --expected-tests 2
```

## 0.6.0 の選択的再OCR

- 標準ホストテスト 58 件が成功。外部写真の比較・編集確認 3 件も実行し、旧方式との比較に加えて、同じ Java コードの small / 選択的再OCR / medium の実測を保存した。
- `SelectiveOcrBenchmarkTest` は private な `lm/gold.private.json` を使用する。入力をモックせず、認識結果と時間を `lm/java-benchmark.json` に保存する。正解文をモデルの学習に使わない。評価条件・範囲は [比較レポート](ocr-selective-comparison.md)。
- CTC の blank と重複フレームが認識スコアに入らないことを検査した。small のスコアが 0.95 未満の非空行だけ、同じ入力を medium で一度認識する。再試行前にキャンセルを確認する。
- debug / release lint、モデル・文字表 10 ファイルのチェックサム、署名付きAPK、arm64・16 KB 整列の検査を実施した。APKとモデル合計の容量上限を800MBに更新した。
- Android CPU の medium 互換性テスト 1 件が成功。幅32の小さな入力でモデル全体を実行し、出力クラス数と有限の値を検査する。速度や日本語の認識精度のテストとは分ける。
- Android CPU で medium を最初から使い、日本語「資料の文字をコピーする」を完全一致で認識するテストも成功。生成画像の文字行を切り出し、本番と同じ前処理・復号を使った。仮想化なしのエミュレーターでは 555.4 秒かかった値であり、物理端末の性能値には使わない。
- Android の通しのテストは 11 件。今回の対象は medium の追加確認 2 件で、全11件の通し成功や物理端末の性能を確認したという意味ではない。

```sh
python3 scripts/run-device-tests.py --root-staging --no-ui-automation \
  --class-name com.sasakiy84.excerptmemo.PaddleInstrumentedTest#bundledMediumReadsJapaneseOnAndroidCpu --expected-tests 1
```

## 依存整理と標準 View 化

- OpenPNP を削除し、公式 OpenCV 4.12.0 のソースから core / imgproc / java の Linux JNI をビルドした。Android と同じ公式 Java API を使用する。ホストテストは60件成功し、私有写真が必要な3件は通常実行ではスキップした。
- Material と camera-view を削除した構成で、debug / release の lint、debug / Android テスト / 未署名 release のビルドが成功した。モデルと文字表10ファイルも検証した。
- Gradle のロックと SHA-256 検証を有効にして、ネットワークを使用しない strict モードでも上記の確認が成功した。別の検証用プロジェクトで AGP の期待 SHA-256 を意図的に変え、classpath の検証が失敗することも確認した。
- Robolectric の Android 16 用 JAR を Gradle の検証対象に含め、実行時は offline モードを使用した。
- カメラの crop / 0・90・180・270度回転 / 鏡像とフォーカス座標の往復変換、TextureView 固有の回転補正を検査した。カメラ Surface は CameraX の使用終了を待って解放する。
- Android 16 の画像編集・文字選択・コピー・画面再作成の2テストと、最終版のカメラ撮影・結果画面への移動・カメラへの復帰の1テストが成功した。撮影テストはソフトウェアエミュレーターで316.3秒。物理端末の処理速度とは分けて扱う。
- `localUiAutomation=false` を指定した撮影テストでは、画面取得 API へ接続しない。通常の instrumentation 実行では従来どおりスクリーンショットを取得する。
- Gradle から署名情報を削除し、未署名 release を生成した後に `sign-release.sh` で署名した。初版と同じ証明書、モデル内容、SDK / ABI / 16 KB 整列を確認した。

```sh
python3 scripts/run-device-tests.py --root-staging --no-ui-automation \
  --class-name 'com.sasakiy84.excerptmemo.ImageEditUiTest,com.sasakiy84.excerptmemo.DocumentUiTest#selectCharactersSwitchToTextCopyAndRestoreAfterRotation' \
  --expected-tests 2
python3 scripts/run-device-tests.py --root-staging --no-ui-automation \
  --class-name com.sasakiy84.excerptmemo.CameraFlowTest --expected-tests 1
```

## 英語対応

- 英語の3行の文章を、実際の同梱モデルと既定の選択的再OCR方針で完全一致認識した。日本語とは別に横書きを指定し、細い英字を縦書きと誤判定しない。空白・句読点・アクセント付き英字の結合と、従来の日本語の全角数字の結合も検査した。英語対応単独のホストテストは64件成功、任意の私有写真テスト3件はスキップした。
- 撮影画面と結果画面の言語ボタン、画面再作成後の選択保持を Android 16 で確認した。
- Android CPU の PP-OCRv6 small に英語文 `Read, select and copy.` を入力し、完全一致で認識した。追加した横書き指定と実際の検出・透視補正・認識を実行する。ソフトウェアエミュレーターで287.4秒だった。
- 既存の1024px NDLOCR行検出も含める通しの英語テストは、このエミュレーターで行検出中に600秒の上限へ達した。そのため通しの認識はホストで、追加した横書き処理は Android 上の PP-OCR 単独で確認した。Android で通しの英語処理や物理端末の速度を検証済みとはしない。
- 英語用の追加モデルはなく、モデル・文字表の容量は従来の296,389,915 bytesのまま。言語指定と各行の方向を保存し、以前の日本語の保存データも読み込める。

```sh
python3 scripts/run-device-tests.py --root-staging --no-ui-automation \
  --class-name com.sasakiy84.excerptmemo.DocumentUiTest#languageSwitchIsSharedByCaptureAndResultScreens --expected-tests 1
python3 scripts/run-device-tests.py --root-staging --no-ui-automation \
  --class-name com.sasakiy84.excerptmemo.PaddleInstrumentedTest#bundledSmallReadsAnEnglishSentenceOnAndroidCpu --expected-tests 1
```
