# Third-party notices

Audiobook NightForge's own code is MIT-licensed (see `LICENSE`). It builds on these
graciously open-source projects:

| Component | License | Use |
|---|---|---|
| [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) | Apache-2.0 | On-device TTS inference (`app/src/main/jniLibs/`, vendored `com.k2fsa.sherpa.onnx` Kotlin API) |
| [Kokoro-82M](https://huggingface.co/hexgrad/Kokoro-82M) | Apache-2.0 | Neural voice model (downloaded in-app from k2-fsa release assets) |
| [Piper voices](https://github.com/rhasspy/piper) | MIT | Lite English voice model |
| AndroidX / Jetpack Compose / Media3 / WorkManager | Apache-2.0 | App framework |
| kotlinx.serialization / coroutines | Apache-2.0 | Serialization & concurrency |
| commons-compress | Apache-2.0 | tar.bz2 extraction of model bundles |
| [pdfbox-android](https://github.com/TomRoush/PdfBox-Android) | Apache-2.0 | PDF text extraction |

Model bundles are downloaded at runtime directly from the
[k2-fsa sherpa-onnx release assets](https://github.com/k2-fsa/sherpa-onnx/releases/tag/tts-models)
and are not redistributed by this repository.

Note: some multilingual model bundles include `espeak-ng-data`, which originates
from the GPLv3-era eSpeak NG project as data files. If you redistribute model
bundles yourself (rather than linking to upstream downloads), review that
component's licensing for your distribution.
