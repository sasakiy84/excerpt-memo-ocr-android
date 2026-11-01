画面をロゴに合わせた乳白色と琥珀色へ変更しました。

長い縦書きには PP-OCRv6 small、湾曲の大きい横書きには UVDoc による補正を自動で使います。新聞などの細かな段組みには NDLOCR-Lite を使います。読取りは端末内で順番に実行します。

提供写真 3 枚での速度・抜粋の文字誤り率・採用条件は [比較レポート](https://github.com/sasakiy84/excerpt-memo-ocr-android/blob/v0.5.0/docs/ocr-comparison.md) に記録しました。

PP-OCR と選択・コピーは Android エミュレーターで確認済みです。UVDoc はホストで確認しましたが、Android エミュレーターでは時間切れとなり、実端末での動作・速度・消費電力は未確認です。

Android 16 以上の arm64 端末向けです。`excerpt-memo-v0.5.0.apk` をインストールしてください。
