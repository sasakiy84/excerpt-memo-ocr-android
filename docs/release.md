# リリース

GitHub Secrets は `ANDROID_KEYSTORE_BASE64`（PKCS12 ファイルの Base64）と `ANDROID_KEYSTORE_PASSWORD`。alias は `excerpt-memo-release`、鍵のパスワードも同じ値を使います。値や鍵を Git に追加しないでください。

ローカルの検証は [testing.md](testing.md) の手順で実行します。検証結果を記録して main を push し、`git tag v0.6.0`、`git push origin v0.6.0` で署名ビルドとリリースを開始します。次回はバージョンを更新してください。

Actions は APK のビルド・署名・パッケージ確認・公開だけを実行します。テスト用ジョブはありません。公開物は arm64 APK と SHA256SUMS です。

初版の署名証明書 SHA-256:

```text
61818ab656f076ffc459f9c01a1ffb08856853e129e15551c486231398145bce
```

更新版も同じ鍵で署名します。鍵とパスワードのバックアップは公開リリースに含めないでください。
