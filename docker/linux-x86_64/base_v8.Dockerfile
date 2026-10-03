# Copyright (c) 2021-2026. caoccao.com Sam Cao
# All rights reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# Prebuilt V8 (non-i18n monolith + temporal) for iterating on the JNI/FFM library
# without rebuilding V8. Only scripts/ is copied, so source edits keep the cache.
#
# docker build -t javet-v8-base:15.4.80.9 -f docker/linux-x86_64/base_v8.Dockerfile .
# docker run --rm -v $PWD:/home/runner/work/Javet/Javet javet-v8-base:15.4.80.9 build-v8-lib

ARG JAVET_V8_VERSION=15.4.80.9
ARG TEMPORAL_VERSION=0.1.2

FROM ubuntu:latest
ARG JAVET_V8_VERSION
ARG TEMPORAL_VERSION
ENV JAVET_V8_VERSION=${JAVET_V8_VERSION}
ENV ROOT=/home/runner/work/Javet
ENV DEBIAN_FRONTEND=noninteractive
ENV DEPOT_TOOLS_UPDATE=0
RUN apt-get update -y && \
    apt-get install -y binutils build-essential libc++-dev libc++abi-dev wget git curl unzip \
    software-properties-common lsb-release gnupg python3 python3-dev python3-apt sudo file \
    openjdk-25-jdk-headless && \
    rm -rf /var/lib/apt/lists/*
ENV JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
RUN curl -fsSL https://deno.land/install.sh | sh
ENV PATH="/root/.deno/bin:${PATH}"
RUN curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y
ENV PATH="/root/.cargo/bin:${PATH}"
RUN wget -q https://github.com/Kitware/CMake/releases/download/v3.23.5/cmake-3.23.5-linux-x86_64.tar.gz && \
    tar -xzf cmake-3.23.5-linux-x86_64.tar.gz -C /opt && \
    rm cmake-3.23.5-linux-x86_64.tar.gz
ENV PATH="/opt/cmake-3.23.5-linux-x86_64/bin:${PATH}"

WORKDIR ${ROOT}/google
ENV PATH="${ROOT}/google/depot_tools:${PATH}"
COPY scripts/shell/fetch_v8_source.sh .
RUN bash fetch_v8_source.sh
RUN cd v8 && \
    sed -i '/#include "src\/libplatform\//a #include <cstdlib>' src/libplatform/default-thread-isolated-allocator.cc && \
    sed -i '/bool KernelHasPkruFix()/a const char* env = std::getenv("JAVET_DISABLE_PKU"); if (env && std::strlen(env) > 0) { return false; }' src/libplatform/default-thread-isolated-allocator.cc && \
    sed -i 's/__attribute__((tls_model(V8_TLS_MODEL)))/ /g' src/execution/isolate.h && \
    sed -i 's/__attribute__((tls_model(V8_TLS_MODEL)))/ /g' src/heap/local-heap.h
COPY scripts/v8/gn/linux-x86_64-non-i18n-args.gn /tmp/args.gn
RUN cd depot_tools && ./ensure_bootstrap
RUN cd v8 && \
    mkdir -p out.gn.linux.non-i18n/x64.release && \
    cp /tmp/args.gn out.gn.linux.non-i18n/x64.release/args.gn && \
    gn gen out.gn.linux.non-i18n/x64.release && \
    ninja -C out.gn.linux.non-i18n/x64.release v8_monolith

COPY scripts/deno ${ROOT}/Javet/scripts/deno
RUN cd ${ROOT} && \
    git clone https://github.com/boa-dev/temporal.git && \
    cd temporal && \
    git checkout v${TEMPORAL_VERSION} && \
    deno run --allow-all ${ROOT}/Javet/scripts/deno/patch_v8_temporal.ts -p ./ && \
    cargo build --release --package temporal_capi --features compiled_data,zoneinfo64 && \
    cp target/release/libtemporal_capi.a ${ROOT}/google/v8/out.gn.linux.non-i18n/x64.release/obj/ && \
    cd .. && rm -rf temporal ${ROOT}/Javet

RUN printf '#!/bin/sh\nset -e\ncd %s/Javet/cpp\nCC=%s/google/v8/third_party/llvm-build/Release+Asserts/bin/clang CXX=%s/google/v8/third_party/llvm-build/Release+Asserts/bin/clang deno run build --os linux --arch x86_64 --v8-dir %s/google/v8 "$@"\n' \
    ${ROOT} ${ROOT} ${ROOT} ${ROOT} > /usr/local/bin/build-v8-lib && chmod +x /usr/local/bin/build-v8-lib
WORKDIR ${ROOT}/Javet
