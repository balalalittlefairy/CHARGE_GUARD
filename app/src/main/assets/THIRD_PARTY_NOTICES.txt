# Third-party references and notices

CHARGE GUARD original code is licensed under the root MIT License,
Copyright (c) 2026 balalalittlefairy. Third-party portions retain their own
copyright and license notices below; the root license does not replace them.

The Gradle Wrapper scripts and wrapper JAR are build tooling, not application
code. Their upstream Apache-2.0 notices remain applicable. See the script
headers and licenses/Apache-2.0.txt (also https://www.apache.org/licenses/LICENSE-2.0).

## MiService

The Xiaomi account login flow, cloud live-device RPC request structure, and cloud request signing implementation are adapted from the MIT-licensed MiService project:

- https://github.com/Yonsm/MiService
- https://github.com/Yonsm/MiService/blob/master/miservice/miaccount.py
- https://github.com/Yonsm/MiService/blob/master/miservice/miioservice.py

MIT License

Copyright (c) 2021-2026 Yonsm

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

## Other research references

- python-miio: consulted to understand the miIO packet format and transport. This APK does not bundle or invoke the Python library. The Kotlin codec uses the Android/Java cryptographic and socket APIs.
  https://github.com/rytilahti/python-miio
- mijia-plug-lan-control: target model and MIoT property mapping reference; no Python runtime is bundled.
  https://github.com/kunminiya/mijia-plug-lan-control
- ChargeLimes: product research reference only; no source code is included.
  https://github.com/sieciowiecxyz/chargelimes

This application is an independent community implementation and is not an official Xiaomi application.


## go2rtc

The Xiaomi RC4 cloud protocol implementation is adapted from the MIT-licensed go2rtc reference:

- https://github.com/AlexxIT/go2rtc/blob/master/pkg/xiaomi/cloud.go

MIT License

Copyright (c) 2022 Alexey Khit

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


## Cloud control protocol references (0.3.0)

- MiService's `/home/rpc/<did>` endpoint and MIoT property request structure are used as protocol references; the MIT notice above applies. The APK runs Kotlin and does not bundle Python.
  https://github.com/Yonsm/MiService/blob/main/miservice/miioservice.py
- Xiaomi's plugin SDK documentation distinguishes device RPC reads from cached property reads. Consulted as protocol documentation only; no Home Assistant OAuth implementation or SDK source is bundled.
  https://github.com/MiEcosystem/miot-plugin-sdk/blob/master/miot-sdk/service/spec.js
