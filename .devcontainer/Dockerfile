FROM ubuntu:22.04

ENV DEBIAN_FRONTEND=noninteractive

# Install dependencies
RUN apt-get update && apt-get install -y \
    openjdk-17-jdk \
    git \
    wget \
    unzip \
    curl \
    build-essential \
    python3 \
    python3-pip \
    locales \
    && rm -rf /var/lib/apt/lists/*

# Set locale
RUN locale-gen en_US.UTF-8
ENV LANG en_US.UTF-8  
ENV LANGUAGE en_US:en  
ENV LC_ALL en_US.UTF-8

# Install Android SDK Command Line Tools
ENV ANDROID_SDK_ROOT /opt/android-sdk
RUN mkdir -p $ANDROID_SDK_ROOT/cmdline-tools
WORKDIR $ANDROID_SDK_ROOT/cmdline-tools
RUN wget https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -O tools.zip \
    && unzip tools.zip \
    && rm tools.zip \
    && mv cmdline-tools latest

ENV PATH $PATH:$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$ANDROID_SDK_ROOT/platform-tools:$ANDROID_SDK_ROOT/emulator

# Accept licenses and install build tools (adjust versions as needed)
RUN yes | sdkmanager --sdk_root=${ANDROID_SDK_ROOT} --licenses
RUN sdkmanager --sdk_root=${ANDROID_SDK_ROOT} "platform-tools" "platforms;android-34" "build-tools;34.0.0"

# Set JAVA_HOME
ENV JAVA_HOME /usr/lib/jvm/java-17-openjdk-amd64

WORKDIR /workspace