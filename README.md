# 方解抄（ほうかいしょう）

Android スマホで資料を撮影すると自動で読み取り、文字を選んでコピーするアプリです。NDLOCR-Lite、PP-OCRv6 small / medium、UVDoc の ONNX モデルを同梱し、ページに合わせて端末内で処理します。

画像上の開始・終了ハンドルで抜粋範囲を指定できます。選択した文章の欄は、下から引き上げて高さを調整できます。行の位置と字下げから段落を判定し、段落内の改行は自動で結合します。画像の切り抜き範囲と回転角度も手動で調整できます。ページ番号・ルビ・表は初期設定では除外し、「読取対象」メニューのチェックボックスで切り替えます。コピー時には、識別できたページ番号を引用末尾へ付けます。

[最新版の APK](https://github.com/sasakiy84/excerpt-memo-ocr-android/releases/latest) · Android 16 以上

## 開発

Java 21、Android SDK 36、Build Tools 36.0.0 を使います。

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
./gradlew connectedDebugAndroidTest
```

クラウド環境は `bash scripts/setup-cloud.sh` で準備します。Linux のホスト OCR テストには `python3 scripts/setup-opencv-host.py` で公式 OpenCV の JNI を準備します。以降は `source /workspace/android-dev/env.sh` を実行し、`python3 scripts/gradle-cloud.py` を Gradle の代わりに使います。

検証範囲と端末テストの状況は [docs/testing.md](docs/testing.md) に記録しています。
モデルと湾曲補正の比較・採用条件・再測定の手順は [docs/ocr-comparison.md](docs/ocr-comparison.md) に記録しています。

## リリース

GitHub Actions の repository secrets に次の値を設定します。

| 名前 | 値 |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | リリース用 PKCS12 keystore の Base64 |
| `ANDROID_KEYSTORE_PASSWORD` | keystore と秘密鍵に共通のパスワード |

鍵の alias は `excerpt-memo-release`。初版と同じ鍵を継続して使います。GitHub Release の公開には Actions が発行する `GITHUB_TOKEN` を使います。

Gradle は未署名 APK を生成し、別ジョブで `scripts/sign-release.sh` を使って署名します。ビルドジョブへ署名鍵を渡しません。依存の取得元・ロック・ハッシュ検証は [依存関係の管理](docs/dependencies.md) を参照してください。

テストと画面確認はローカルで実行します。`vMAJOR.MINOR.PATCH` のタグを push すると、Actions が署名付き APK をビルドし、APK と SHA256SUMS を公開します。

## 使用モデル

[NDLOCR-Lite 1.3.0](https://github.com/ndl-lab/ndlocr-lite)（国立国会図書館、CC BY 4.0）の検出モデル・文字認識モデル・文字表を利用しています。前処理と推論を Android に移植し、読み順の整序と画面操作を追加しました。モデル本体は変更していません。

DINOv3 を蒸留した検出モデルには DINOv3 License が適用されます。各ライセンスと出典はアプリの「ライセンス」と [assets/licenses](app/src/main/assets/licenses) に収録しています。アプリ独自コードは MIT License です。

[PP-OCRv6 small](https://huggingface.co/PaddlePaddle/PP-OCRv6_small_rec_onnx) と [UVDoc](https://huggingface.co/PaddlePaddle/UVDoc_onnx) は PaddlePaddle 公式の ONNX 配布を使用しています（Apache-2.0）。画像の輪郭処理と透視変換には OpenCV 4.12.0 を使用しています。

PP-OCR の認識スコアが低い行は medium で一度だけ再確認します。small のみ・最初から medium・小型言語モデルとの比較は [選択的再OCRの比較](docs/ocr-selective-comparison.md) に記録しています。
