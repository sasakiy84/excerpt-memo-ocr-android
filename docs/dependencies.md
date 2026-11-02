# 依存関係の管理

実装には Android SDK の View、CameraX、AndroidX Activity / Fragment / Lifecycle、Microsoft ONNX Runtime、OpenCV を使う。アプリにネットワーク権限は付けない。

## 今回の整理

| 対象 | 変更 |
|---|---|
| OpenPNP OpenCV 4.9.0 | 削除。公式 OpenCV 4.12.0 の Java API と、同じ版の公式ソースからビルドした Linux JNI でホストテストを実行する |
| Material Components | Toolbar、Button、ImageButton、RadioGroup、CheckBox、SeekBar、ProgressBar、Dialog など Android 標準 View に置換する |
| camera-view | TextureView と CameraX の SurfaceProvider に置換する。CameraX core / camera2 / lifecycle は保持する |
| camera-video / Media3 / Guava 本体 | camera-view の削除により、APK の実行時依存から外れる |
| Truth / Espresso | 直接依存を削除。JUnit Assert と Android 標準の画面操作を使い、既存の認識・コピー・選択・回転の検査を維持する |
| PyYAML | 削除。medium の文字表は、同一性を確認済みの固定 JSON を使う |
| pyclipper | 過去のポリゴン拡張方式を再現する専用依存へ分離する。通常の比較は Android と同じ矩形拡張を使う |
| Torch / Transformers / ONNX exporter | 採用しなかった言語モデルを書き出す研究専用環境へ分離する |

Robolectric、JUnit、AndroidX Test、CameraX 内部の Dagger、ListenableFuture は、それぞれ既存の検証や CameraX の動作に必要なため保持する。Guava は Robolectric と Android テスト実行ツールの依存には残るが、APK の実行時依存には含まれない。推移的な依存だけを強制除外して、ライブラリ内部の呼び出しを壊す方法は使わない。

## バージョンとハッシュの固定

- `buildscript-gradle.lockfile` で Gradle プラグインとその推移依存を固定する。
- `app/gradle.lockfile` で、debug / release / Android テスト / ホストテスト / lint の解決版を固定する。
- `gradle/verification-metadata.xml` で、Gradle プラグインを含む JAR・AAR・POM・module metadata の SHA-256 を検証する。通常は strict モードで実行する。
- AndroidX と Android ビルドツールは Google Maven、その他は Maven Central に取得元を限定する。追加リポジトリと Gradle Plugin Portal は使わない。
- Robolectric の Android 16 用 JAR も Gradle の検証対象にし、テスト実行時の独自ダウンロードを禁止する。
- Gradle 本体・wrapper、Android コマンドラインツール、ホスト OpenCV のソース・CMake は SHA-256 を固定する。モデルと文字表は既存の manifest と `verify-models.py` で確認する。

ハッシュの初回登録は公式配布元から取得したファイルを基準にする。更新は自動承認せず、版、配布元、ライセンス、追加された推移依存、ハッシュの差分をレビューする。ハッシュ検証は登録済みファイルからの改変を検出する仕組みであり、ライブラリのコード監査を代行するものではない。

更新時だけ、必要な版を変更して以下を実行する。

```sh
python3 scripts/gradle-cloud.py --no-daemon \
  --write-locks --write-verification-metadata sha256 \
  testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest assembleRelease lintRelease :app:dependencies
```

生成ファイルをレビューした後、通常の strict モードで同じ確認を行う。検証エラーに対して、verify-metadata の無効化や包括的な trusted-artifacts の追加で回避しない。

## ホストテストと研究環境

Linux のホスト OCR テストは次で準備する。公式ソースの commit とアーカイブ SHA-256 はスクリプトに固定している。ビルドする機能は core / imgproc / java に限定する。

```sh
python3 scripts/setup-opencv-host.py
python3 scripts/gradle-cloud.py --no-daemon --dependency-verification=strict testDebugUnitTest
```

ホスト用 JNI は Gradle のユーザーキャッシュ内に作る。Android APK へ入る OpenCV は公式 Maven AAR のまま。別の配置先を使う場合は `-PhostOpenCv=/absolute/path/libopencv_java4120.so` を指定する。

Python の研究用依存は Android ビルド・リリースでインストールしない。それぞれ専用の venv を作り、用途に応じた requirements だけを使う。

- `requirements-ocr-benchmark.txt`: 現行 Android に合わせた比較。NumPy / OpenCV / ONNX Runtime。
- `requirements-selective-ocr.txt`: 既存の選択的再OCR比較。
- `requirements-ocr-historical.txt`: 過去の比較専用。`benchmark-ocr.py --historical-unclip` と組み合わせる。
- `requirements-ocr-lm-export.txt`: 不採用の小型言語モデルを書き出す場合だけ使用する。

## 署名の分離

Gradle は未署名 APK を生成し、署名鍵・パスワードを参照しない。GitHub Actions の build ジョブには署名 secrets を渡さず、別の release ジョブで未署名 APK だけを受け取る。署名は `apksigner` で行い、その工程では Gradle を実行しない。署名鍵は署名ステップの終了時に削除する。

ローカルでも `assembleRelease` の完了後に `scripts/sign-release.sh 入力.apk 出力.apk` を実行する。`ANDROID_KEYSTORE_PATH` と `ANDROID_KEYSTORE_PASSWORD` はそのコマンドだけに渡す。初版からの証明書を維持する。

2026-11-02 に、本番構成の固定 Maven 座標を [OSV](https://osv.dev/) と照合し、一致する既知のアドバイザリはなかった。照合対象は Maven の登録情報であり、ネイティブ実装のコード監査は行っていない。
