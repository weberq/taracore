# Third-Party Notices

Tara Core itself is licensed under the Apache License, Version 2.0 (see `LICENSE`).
It builds against and redistributes binaries produced from the following third-party
components.

---

## llama.cpp

- **Upstream:** https://github.com/ggml-org/llama.cpp
- **Included as:** git submodule at `third_party/llama.cpp` (pinned to tag `b4585`)
- **License:** MIT License
- **Copyright:** Copyright (c) 2023-2025 The ggml authors

```
MIT License

Copyright (c) 2023-2025 The ggml authors

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

`llama.cpp` vendors `ggml`, which is covered by the same MIT license.

---

## Model weights

Tara Core ships **no model weights**. `catalog.json` lists third-party GGUF models
that the user may choose to download at runtime. Each model carries its own license
(Gemma Terms of Use, Llama 3.2 Community License, Apache-2.0, MIT, …) which the user
accepts with the upstream publisher, not with this project. The catalog records the
license identifier for every entry.

---

## Android Jetpack, Kotlin, Ktor, OkHttp

Apache License 2.0. See each project's upstream repository for full text.
