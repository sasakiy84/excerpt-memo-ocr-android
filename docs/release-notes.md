PP-OCRv6 small の認識スコアが低い行を、medium モデルで一度だけ読み直すようにしました。モデルは順番に実行し、画像全体の再検出や湾曲補正は繰り返しません。

提供写真から転記した 1,302 字で、小型BERTによる判定と、最初から medium で認識する方式も比較しました。アプリの Java コードでは、抜粋の誤りが 33 字から 19 字に減りました。モデル合計は約296MBです。[比較レポート](https://github.com/sasakiy84/excerpt-memo-ocr-android/blob/v0.6.0/docs/ocr-selective-comparison.md) に採用理由と測定条件を記録しています。

速度はホストCPUでの測定で、物理端末の速度・消費電力は未測定です。Android 16 以上の arm64 端末向けの `excerpt-memo-v0.6.0.apk` をインストールしてください。
