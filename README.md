# 抜粋

Android スマホで資料を撮影すると自動で読み取り、文字を選んでコピーするアプリです。NDLOCR-Lite の ONNX モデルを同梱し、端末内で処理します。

画像上の開始・終了ハンドルで抜粋範囲を指定できます。選択した文章の欄は、下から引き上げて高さを調整できます。行の位置と字下げから段落を判定し、段落内の改行は自動で結合します。画像の切り抜き範囲と回転角度も手動で調整できます。ページ番号・ルビ・表は初期設定では除外し、「読取対象」メニューのチェックボックスで切り替えます。コピー時には、識別できたページ番号を引用末尾へ付けます。

[最新版の APK](https://github.com/sasakiy84/excerpt-memo-ocr-android/releases/latest) · Android 16 以上

## 開発

Java 21、Android SDK 36、Build Tools 36.0.0 を使います。

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
./gradlew connectedDebugAndroidTest
```

クラウド環境は `bash scripts/setup-cloud.sh` で準備します。以降は `source /workspace/android-dev/env.sh` を実行し、`python3 scripts/gradle-cloud.py` を Gradle の代わりに使います。

検証範囲と端末テストの状況は [docs/testing.md](docs/testing.md) に記録しています。

## リリース

GitHub Actions の repository secrets に次の値を設定します。

| 名前 | 値 |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | リリース用 PKCS12 keystore の Base64 |
| `ANDROID_KEYSTORE_PASSWORD` | keystore と秘密鍵に共通のパスワード |

鍵の alias は `excerpt-memo-release`。初版と同じ鍵を継続して使います。GitHub Release の公開には Actions が発行する `GITHUB_TOKEN` を使います。

テストと画面確認はローカルで実行します。`vMAJOR.MINOR.PATCH` のタグを push すると、Actions が署名付き APK をビルドし、APK と SHA256SUMS を公開します。

## 使用モデル

[NDLOCR-Lite 1.3.0](https://github.com/ndl-lab/ndlocr-lite)（国立国会図書館、CC BY 4.0）の検出モデル・文字認識モデル・文字表を利用しています。前処理と推論を Android に移植し、読み順の整序と画面操作を追加しました。モデル本体は変更していません。

DINOv3 を蒸留した検出モデルには DINOv3 License が適用されます。各ライセンスと出典はアプリの「ライセンス」と [assets/licenses](app/src/main/assets/licenses) に収録しています。アプリ独自コードは MIT License です。
