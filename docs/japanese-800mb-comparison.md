# 800MB以内の日本語OCR比較

2026-11-02。依存整理・英語対応・UI更新を公開した v0.7.1 を基準にする。

## 採用する構成

今回の条件では、以下を採用する。モデルを選ぶ画面は設けず、既存のページ配置判定で以下を逐次実行する。

| 資料の配置 | 前処理・行検出 | 文字認識 |
|---|---|---|
| 長い縦組みの書籍 | CLAHE＋PP-OCRv6 tiny検出 | PP-OCRv6 small |
| 湾曲した横組み | UVDoc＋PP-OCRv6 medium検出 | PP-OCRv6 small |
| その他・新聞 | NDLOCR-Lite DEIM | NDLOCR-Liteの3段階 |

英語は従来どおりsmall検出・small認識＋低スコア行だけmedium再認識。追加の言語モデルは使わない。日本語の新しい行画像では、0.95未満の行をmediumやYomitokuで再認識しても抜粋の誤り数が減らなかったため、その追加処理を省く。

コントラスト補正はOCR内部だけで使い、写真の表示や編集元は置き換えない。繰り返し読み取っても補正が積み重ならない。UVDocの補正画像は従来どおり編集・再読み取りに使える。ページ番号・ルビ・表の分類と、引用へのページ番号付与は維持する。

Yomitoku largeは精度優先の候補には残るが、全行を読む時間が大きく、スコアで絞った再認識も今回の誤りを拾えなかった。800MBを使い切ることより、精度改善と逐次処理時間の両立を優先した。

## 評価条件

実写は提供された書籍・新聞・問題集の3枚。同一写真と縮小版は独立した標本に数えない。目視転記した16抜粋、計1,302字（469 / 194 / 639字）を使う。写真、転記、切り出し画像、認識全文は公開しない。正解JSONのSHA-256は `c160bb87ea2f0e4ecf6ab0846a07104a3eb689f742233d71876fa1a3df1ec216`。

実写の指標は、前後のみを自由とする連続部分文字列との最小編集距離（抜粋CER）。NFKC・空白除去・カンマ統一は採点時だけ適用する。ページ全文CER、文字検出の再現率ではない。同じ3枚を方式選択にも使う探索的比較であり、未知の資料への優劣を確定するものではない。重複する見出しを含む抜粋もあり、字数は重複を排した全文字数ではない。

別に、今回新しく書いた文をフォント・サイズ・劣化条件を変えて描画した既知正解の行画像を使う。こちらは行全体の編集距離で採点し、境界の誤りも数える。実写とは合算しない。合成画像は実際の紙の湾曲、縦組み、ルビ、複雑な段組みを再現しない。

モデル推論は逐次実行。検出4スレッド、認識2スレッド、inter-op 1、OpenCV 1。主比較はLinux CPU上のONNX Runtime 1.24.3で、アプリと同じ版。予備的な未圧縮認識器のスクリーニングだけ1.31.1を使った。異なるランタイム間の時間差を量子化の効果として扱わない。物理Android端末の処理時間や消費電力は未測定。

## 調べた範囲

- 認識：NDLOCR-Liteの3段階、PP-OCRv6 small / medium、Yomitoku tiny / middle / large。Yomitokuは同じ公開重みからAR / NARを書き出し、tiny NAR/AR・middle AR・large ARをINT8 / FP16にも変換した。
- 前処理7種：無補正、照明むら補正、CLAHE、アンシャープ、適応二値化、UVDoc、UVDoc＋照明むら補正。
- 行検出5種：NDLOCR-Lite DEIM、PP-OCRv6 tiny / small / medium、Yomitoku DBNet。7×5×3枚＝105構成をsmall認識器でスクリーニングし、有望な構成を他の認識器で再比較する。
- 読み順3種：現行のXY-cut、Yomitokuの幾何グラフのみ、Yomitokuのレイアウトモデル＋グラフ。後者も表の構造認識・セル認識モデルは使わない。
- 同一行画像上の逐次再OCR：smallの認識スコアを使い、0.90 / 0.95 / 0.98 / 0.99未満の行だけmediumまたはYomitokuで読み直す。
- 小型言語モデルによる判定は[前回の実験](ocr-selective-comparison.md)を参照。追加の文脈判定だけでは改善せず、今回の候補へ常時組み込まない。

直積のすべてを大型認識器で再実行する方法は取らない。105構成の検出・前処理スクリーニング、認識器単体の比較、候補を絞った組み合わせ比較、最後の全工程計測の順に評価する。検出された行が違う実験と、同じ行画像上の認識器比較を分ける。

PP-OCRv6 tiny認識器は約4.5MBだが、日本語の仮名文字表を持たないため日本語の候補から外した。tiny**検出器**にはこの制限がない。small / medium認識器は18,708文字の同じ辞書を使い、英語にも引き続き使用できる。

## リリースするアプリコードでの結果

両版ともOpenCV 4.12.0、ONNX Runtime 1.24.3、Robolectric API 36 native graphicsで実測。各画像・方式でエンジンを作り直し、初回の後の3回の中央値。実際の `OcrEngine.readPage` による検出・必要な湾曲補正・切り出し・分類・認識・整序を含む。各欄は「秒 / 誤り数」。

| 画像 | v0.7.1の標準経路 | v0.8.0の標準経路 | 新検出で最初からmedium認識 |
|---|---:|---:|---:|
| 書籍469字 | 2.781 / 9 | 2.172 / 6 | 5.333 / 4 |
| 新聞194字 | 3.340 / 2 | 3.702 / 2 | 3.580 / 2 |
| 問題集639字 | 6.785 / 8 | 4.218 / 4 | 8.560 / 6 |

標準経路の合計は **19/1302（1.46%）→12/1302（0.92%）**。新聞は同じコード・モデルのままで、計測の揺れを速度改善として数えない。新構成のmedium常用は書籍を2字改善する一方、問題集では2字悪化し、時間も増えたため採用しない。書籍のページ番号32の保持と、本文への混入がないことも同じ実モデルテストで確認した。

内部ベンチマークの `SELECTIVE` は過去比較用のポリシー識別子。v0.8.0の日本語ではPaddleのsmallのみを使う。新しい `SMALL` と標準経路の結果は一致し、時間差は同じ処理の計測の揺れである。英語では既存の選択的medium再認識を維持する。

数値一覧は [japanese-800mb-results.json](japanese-800mb-results.json)。認識器19方式、前処理・検出105構成、読み順を含む492評価、逐次再認識180設定、全工程14構成を記録した。全文や行画像は含めていない。

## 容量

追加する検出器はtiny 1,780,590バイトとmedium 62,032,837バイト。既存と合わせたモデル合計は **360,203,342バイト（約360MB）**。英語用のsmall検出器・small/medium認識器も含み、800,000,000バイトの上限内。リリースAPKは約421MB。初回に私有領域へ展開するモデルの複製も含めると約781MBで、これにその他のアプリデータ等が加わる。モデル容量とRAM使用量は別であり、ホストプロセスのRSSをそのままスマホの必要RAMとは扱わない。

今回のlarge AR INT8をさらに足すとモデルは約545MBで容量内には収まるが、上述の処理時間から追加しない。レイアウトや表構造モデルも同梱しない。新たに配布するモデルはPaddlePaddle公式ONNXのままで、改変や量子化はせず、出典・固定コミット・SHA-256・Apache-2.0をモデルマニフェストへ追加した。

## 全工程を測った最終候補

ORT 1.24.3 / Linux CPUで、各構成の初回1回に続く3回の中央値。湾曲補正・NDL分類・必要な再検出・行切り出し・認識・読み順を含み、正解との照合は計測の外。写真の読込・アプリの画面描画・モデルの私有領域への展開は含まない。各欄は「秒 / 誤り数」。

| 方式 | 書籍469字 | 問題集639字 |
|---|---:|---:|
| v0.7.1経路のPython再現 | 2.668 / 9 | 6.808 / 10 |
| 採用候補（検出変更＋smallのみ） | 2.876 / 6 | 4.345 / 4 |
| 同じ検出＋small→medium | 3.600 / 6 | 6.006 / 4 |
| 書籍を最初からmedium | 6.052 / 4 | — |
| 新検出＋Yomitoku tiny AR INT8 | 4.037 / 4 | 7.075 / 4 |
| 新検出＋Yomitoku tiny NAR cloze | 3.757 / 4 | 5.809 / 11 |
| 新検出＋Yomitoku large AR INT8 | — | 25.543 / 1 |
| UVDoc＋照明補正＋DBNet＋small | — | 6.803 / 2 |

新聞のNDLOCR-Liteは同じ全工程計測で3.446秒 / 2誤り。前処理や認識器の変更でこれを上回る改善は得られず維持する。

書籍でtiny AR INT8を追加すると2字改善するが、ウォームでもsmallより遅い。この書き出しグラフでは初回が約18.2秒、small構成の約4.0秒に対して大きい。large AR INT8は問題集で1誤りまで減る一方、約25.5秒とsmall構成の約5.9倍かかる。初回は約43.6秒。初回コストはモデルの書き出し・ORT最適化方法でも変わるため、Yomitokuの全実装に共通の定数ではない。

DBNet＋smallは2誤りで精度をさらに上げるが、6.8秒と採用候補の約1.6倍。medium検出62.0MBの代わりにDBNet 101.5MBを載せる差もある。今回は書籍・問題集ともPaddleのsmallを中心にする。

最終候補の既知の抜粋順序は、XY-cutで逆転0（書籍15組・新聞3組・問題集21組）。ここに172.2MBのレイアウトモデルを足す必要は認めなかった。試したすべての資料の読み順が常に正しいという検証ではない。

PythonではCLAHE後にもNDLを再実行する保守的な経路を測った。アプリは座標が変わらないCLAHEでは元の分類結果を再利用し、Ruby・ページ番号・表を分類して保持する。Pythonとの補間・JPEG復号・分類後の行数の差があるため、リリース判断には次のアプリコードの結果を使う。

## 認識器だけを比較した結果

同じ切り出し画像を使ったスクリーニング。数値は誤り数。これらは検出器を変更する前の行画像であり、最終アプリのCERとは別である。FP32の元モデル9方式はORT 1.31.1、それ以外は1.24.3。通常の予備比較はウォーム1回の後の1回で、全工程の性能判定には使わない。

| 認識器 | モデルMB | 書籍 | 新聞 | 問題集 | 合計 |
|---|---:|---:|---:|---:|---:|
| ndl | 116.9 | 14 | 2 | 10 | 26 |
| pp-small | 21.2 | 9 | 9 | 24 | 42 |
| pp-medium | 76.6 | 4 | 8 | 12 | 24 |
| yomi-tiny-nar | 36.4 | 5 | 2 | 12 | 19 |
| yomi-tiny-ar | 39.2 | 4 | 2 | 11 | 17 |
| yomi-middle-nar | 201.3 | 5 | 2 | 16 | 23 |
| yomi-middle-ar | 204.2 | 4 | 2 | 9 | 15 |
| yomi-large-nar | 424.2 | 6 | 12 | 19 | 37 |
| yomi-large-ar | 427.1 | 3 | 2 | 5 | 10 |
| yomi-tiny-nar-int8 | 22.5 | 9 | 2 | 15 | 26 |
| yomi-tiny-nar-fp16 | 18.3 | 5 | 2 | 12 | 19 |
| yomi-tiny-ar-int8 | 26.7 | 4 | 2 | 11 | 17 |
| yomi-tiny-ar-fp16 | 21.2 | 4 | 2 | 11 | 17 |
| yomi-middle-ar-int8 | 98.7 | 3 | 2 | 9 | 14 |
| yomi-middle-ar-fp16 | 103.7 | 4 | 2 | 9 | 15 |
| yomi-large-ar-int8 | 184.5 | 3 | 2 | 5 | 10 |
| yomi-large-ar-fp16 | 215.1 | 3 | 2 | 5 | 10 |
| yomi-tiny-nar-cloze | 36.3 | 4 | 2 | 10 | 16 |
| yomi-tiny-ar-cloze | 39.2 | 4 | 2 | 10 | 16 |

tiny ARはINT8で39.2→26.7MB、large ARは427.1→184.5MBに減り、この3枚の抜粋誤り数は変わらなかった。一方、tiny NARのINT8化は19→26誤りに悪化した。FP16はこのCPU環境ではINT8より遅く、容量の小ささをそのまま速度改善とは扱えない。

## 合成行画像の結果

2フォント×3サイズ（16 / 24 / 32px）×12文×3条件（無劣化・影・軽いぼけ）で216行。正規化後5,634字。各行を1回ずつ逐次認識し、事前に1行でカーネルをウォームした。合計時間は行の読み取りだけで、検出・湾曲補正・初期化を含まない。

| 認識器 | 誤り数 | 216行の認識秒数 |
|---|---:|---:|
| ndl | 0 | 24.50 |
| pp-small | 0 | 12.67 |
| pp-medium | 1 | 50.40 |
| yomi-tiny-ar-int8 | 0 | 29.26 |
| yomi-tiny-nar-cloze | 2 | 26.84 |
| yomi-middle-ar-int8 | 0 | 90.70 |
| yomi-large-ar-int8 | 1 | 105.62 |

small、NDLOCR-Lite、tiny AR INT8、middle AR INT8はこの集合で誤り0。簡単な合成行では差が付かず、大きい認識器の一般的な優位性を示す結果にはならなかった。smallは最も短い処理時間だった。

## Yomitokuの書き出し

公式ソース `b765d87c9341d817d42bdbba128b2188114bd1ef` と作者の固定コミットのsafetensorsを使った。PDF・サーバー・表構造認識などの依存は入れず、認識ネットワークだけをONNX opset 17へ書き出した。PyTorchのattentionを同じ重みのMatMul / Softmaxへ表現し直し、幅96 / 320 / 800でPyTorch出力と照合した。

tiny / middleは32px高、最大800px幅、末尾96px余白を含む8px刻みの動的幅。largeは公式設定どおり32×800の固定入力で、拡大せず縮小する。縦長の行は左へ90度回転。PARSeqはEOSで終了し、CTCのような重複文字の除去をしない。v5の文字展開表も適用する。

追加の派生実験として、refinementのclozeマスクの整数インデックスを真偽値マスクへ変更したtiny AR/NARも評価した。元の公式動作を保つ書き出しとは別の候補として記録する。これを上流の修正済みモデルや再学習モデルとは扱わない。

INT8はMatMul/Gemmの定数重みだけをper-channel動的量子化し、FP16は入出力をfloat32に保持する。FP16はCPU上で速くなるとは限らない。変換後も認識結果を採点し、容量だけで採否を決めない。

DBNet・レイアウトの実験にはMLismの固定ONNX配布を使った。作者の重みから自前で書き出した認識器とは配布経路が異なり、これらの実験用ONNXもアプリへ同梱しない。DBNetの輪郭拡張はAndroidに移植できる矩形の解析式を使うため、公式Pythonのポリゴン演算そのものとの完全一致は主張しない。

上流・派生ファイルのハッシュは [models-lock.json](../scripts/research/models-lock.json)。Yomitokuの重みはCC BY-NC-SA系の条件を持つ。実験用に取得した重みや巨大な中間ONNXを、アプリのビルド依存へ追加しない。

## 再現

新しいスクリプトは `scripts/research/`。研究専用の仮想環境へ `requirements.txt` を入れる。合成画像を生成する場合だけ `requirements-synthetic.txt` と固定ハッシュのNotoフォントを追加する。書き出し・量子化時だけ `requirements-export.txt` とCPU版Torchを追加する。通常ビルド・リリースでは不要。写真と正解JSONは以前の実験と同じ外部ディレクトリを使う。

```sh
python3 scripts/research/fetch-models.py /path/to/research-models
export OCR_RESEARCH_MODELS=/path/to/research-models
python3 scripts/research/export-official.py tiny --mode ar --export
python3 scripts/research/benchmark-japanese.py /path/to/evaluation --models "$OCR_RESEARCH_MODELS" --prepare
python3 scripts/research/benchmark-japanese.py /path/to/evaluation --models "$OCR_RESEARCH_MODELS" --recognizer yomi-tiny-ar --repeat 3
python3 scripts/research/prepare-japanese-grid.py /path/to/evaluation --models "$OCR_RESEARCH_MODELS"
python3 scripts/research/score-japanese-grid.py /path/to/evaluation --models "$OCR_RESEARCH_MODELS" --recognizer pp-small
```

スクリーニングの `estimated_seconds` は別々に計測した工程の合算で、モデル読込時間・実行順序の影響を含む。最終候補の全工程中央値やアプリでの実測と同一視しない。時間を測るプロセスは重ねて実行しない。

```sh
python3 scripts/research/prepare-synthetic.py /path/to/evaluation/japanese-v071/synthetic --fonts "$OCR_RESEARCH_MODELS"
python3 scripts/research/benchmark-synthetic.py /path/to/evaluation/japanese-v071/synthetic/gold.private.json --models "$OCR_RESEARCH_MODELS" --recognizers ndl pp-small pp-medium yomi-tiny-ar-int8
python3 scripts/research/benchmark-finalists.py /path/to/evaluation --models "$OCR_RESEARCH_MODELS" --plan scripts/research/final-plan.json --output /path/to/evaluation/japanese-v071/finalists.json
```

`quantize-recognizer.py` は `--kind int8` / `--kind fp16` で書き出したモデルを変換する。tinyのcloze派生は `export-official.py tiny --mode nar --cloze-mask --export` で再現する。計測対象のONNXと前処理・読み順を変えた際は、既存のキャッシュを別ディレクトリへ退避して再作成する。

## アプリへの組み込み確認

標準ホスト66件とdebug / release lint、ビルドが成功した。Android 16でも新しい検出器2個の32×32入力とCLAHEの実行確認が成功した。速度・精度の表はホストでの実写比較であり、この小さなAndroid互換性テストの所要時間とは比較しない。採用しなかった研究モデルのAndroid動作は検証対象に含めていない。詳細は [testing.md](testing.md#080-の日本語構成)。
