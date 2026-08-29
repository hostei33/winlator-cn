FROM ubuntu:22.04

ENV DEBIAN_FRONTEND=noninteractive

# JDK 17 与基础工具（apt 使用清华 TUNA 国内源）
RUN sed -i 's|//archive.ubuntu.com|//mirrors.tuna.tsinghua.edu.cn|g; s|//security.ubuntu.com|//mirrors.tuna.tsinghua.edu.cn|g' /etc/apt/sources.list \
    && apt-get update && apt-get install -y --no-install-recommends       openjdk-17-jdk       wget       unzip       python3       ca-certificates     && rm -rf /var/lib/apt/lists/*

ENV ANDROID_SDK_ROOT=/opt/android-sdk
ENV ANDROID_HOME=/opt/android-sdk
ENV PATH=$PATH:$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$ANDROID_SDK_ROOT/platform-tools

# Android command line tools（腾讯云镜像）
RUN mkdir -p $ANDROID_SDK_ROOT/cmdline-tools     && wget -q https://mirrors.cloud.tencent.com/AndroidSDK/commandlinetools-linux-11076708_latest.zip -O /tmp/cmdline-tools.zip     && unzip -q /tmp/cmdline-tools.zip -d $ANDROID_SDK_ROOT/cmdline-tools     && mv $ANDROID_SDK_ROOT/cmdline-tools/cmdline-tools $ANDROID_SDK_ROOT/cmdline-tools/latest     && rm /tmp/cmdline-tools.zip

# SDK 组件（腾讯云镜像，手动解压，避免 sdkmanager 访问 dl.google.com）
RUN mkdir -p $ANDROID_SDK_ROOT/platforms $ANDROID_SDK_ROOT/build-tools \
    && wget -q https://mirrors.cloud.tencent.com/AndroidSDK/platform-35_r02.zip -O /tmp/p35.zip \
    && unzip -q /tmp/p35.zip -d $ANDROID_SDK_ROOT/platforms \
    && rm /tmp/p35.zip \
    && wget -q https://mirrors.cloud.tencent.com/AndroidSDK/build-tools_r34-linux.zip -O /tmp/bt34.zip \
    && mkdir -p /tmp/bt34 && unzip -q /tmp/bt34.zip -d /tmp/bt34 \
    && mv /tmp/bt34/android-14 $ANDROID_SDK_ROOT/build-tools/34.0.0 \
    && rm -rf /tmp/bt34 /tmp/bt34.zip \
    && wget -q https://mirrors.cloud.tencent.com/AndroidSDK/build-tools_r35_linux.zip -O /tmp/bt35.zip \
    && mkdir -p /tmp/bt35 && unzip -q /tmp/bt35.zip -d /tmp/bt35 \
    && mv /tmp/bt35/android-15 $ANDROID_SDK_ROOT/build-tools/35.0.0 \
    && rm -rf /tmp/bt35 /tmp/bt35.zip \
    && wget -q https://mirrors.cloud.tencent.com/AndroidSDK/platform-tools_r35.0.2-linux.zip -O /tmp/pt.zip \
    && unzip -q /tmp/pt.zip -d $ANDROID_SDK_ROOT \
    && rm /tmp/pt.zip

# 接受 SDK 许可（sdkmanager --licenses 等价物，写入 license 文件）
RUN mkdir -p $ANDROID_SDK_ROOT/licenses \
    && echo '24333f8a63b6825ea9c5514f83c2829b004d1fee' > $ANDROID_SDK_ROOT/licenses/android-sdk-license \
    && echo '84831b9409646a918e30573bab4c9c91346d8abd' > $ANDROID_SDK_ROOT/licenses/android-sdk-preview-license \
    && echo '601085b94cd77f0b54ff86406957099ebe79c4d6' > $ANDROID_SDK_ROOT/licenses/android-googletv-license
