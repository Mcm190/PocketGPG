# Third-party notices

PocketGPG bundles the libraries below. Both licences are permissive, and both require that
their copyright and permission notices travel with any distribution of the app, which is what
this file and the in-app Open source licences screen are for.

---

## Bouncy Castle

`org.bouncycastle:bcpg-jdk18on`, `org.bouncycastle:bcprov-jdk18on`

Copyright (c) 2000-2024 The Legion of the Bouncy Castle Inc. <https://www.bouncycastle.org>

Licensed under the Bouncy Castle Licence, an adaptation of the MIT X11 licence:

> Permission is hereby granted, free of charge, to any person obtaining a copy of this
> software and associated documentation files (the "Software"), to deal in the Software
> without restriction, including without limitation the rights to use, copy, modify, merge,
> publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons
> to whom the Software is furnished to do so, subject to the following conditions:
>
> The above copyright notice and this permission notice shall be included in all copies or
> substantial portions of the Software.
>
> THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
> INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR
> PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE
> FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
> OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
> DEALINGS IN THE SOFTWARE.

---

## AndroidX, Jetpack Compose and Material Components

`androidx.core:core-ktx`, `androidx.core:core-splashscreen`, `androidx.activity:activity-compose`,
`androidx.lifecycle:*`, `androidx.documentfile:documentfile`, `androidx.compose.*`,
`androidx.compose.material3:material3` and the Material Icons

Copyright The Android Open Source Project

## Kotlin standard library and coroutines

`org.jetbrains.kotlin:*`, `org.jetbrains.kotlinx:kotlinx-coroutines-android`

Copyright 2000-2024 JetBrains s.r.o. and Kotlin Programming Language contributors

Both are licensed under the Apache License, Version 2.0:

> Licensed under the Apache License, Version 2.0 (the "License"); you may not use these files
> except in compliance with the License. You may obtain a copy of the License at
>
> http://www.apache.org/licenses/LICENSE-2.0
>
> Unless required by applicable law or agreed to in writing, software distributed under the
> License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
> either express or implied. See the License for the specific language governing permissions
> and limitations under the License.

---

## Not bundled

GnuPG is **not** included in or linked by this app. It is referenced only because PocketGPG
writes and reads the same OpenPGP format, and because the test suite shells out to whatever
`gpg` binary is on the build machine to prove that interoperability.
