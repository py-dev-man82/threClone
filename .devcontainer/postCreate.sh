#!/bin/sh
set -e

echo "Running postCreate.sh for Threema Libre..."

# Update package lists
apt-get update

# Install required build dependencies
apt-get install -y \
    openjdk-17-jdk \
    git \
    wget \
    unzip \
    curl \
    build-essential \
    lib32stdc++6 \
    lib32z1 \
    python3 \
    python3-pip \
    protobuf-compiler

# Install Rust (cargo)
if ! command -v cargo >/dev/null 2>&1; then
  curl https://sh.rustup.rs -sSf | sh -s -- -y
  export PATH="$HOME/.cargo/bin:$PATH"
  echo 'export PATH="$HOME/.cargo/bin:$PATH"' >> /etc/profile.d/rust.sh
fi

# Install Android command line tools if not present
ANDROID_SDK_ROOT="/opt/android-sdk"
if [ ! -d "$ANDROID_SDK_ROOT" ]; then
    mkdir -p "$ANDROID_SDK_ROOT/cmdline-tools"
    cd /tmp
    wget -q https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -O cmdline-tools.zip
    unzip -q cmdline-tools.zip
    mv cmdline-tools "$ANDROID_SDK_ROOT/cmdline-tools/latest"
    rm cmdline-tools.zip
fi

# Set environment variables for Android SDK
echo "export ANDROID_SDK_ROOT=$ANDROID_SDK_ROOT" >> /etc/profile.d/android.sh
echo "export PATH=\$PATH:$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$ANDROID_SDK_ROOT/platform-tools" >> /etc/profile.d/android.sh
export ANDROID_SDK_ROOT=$ANDROID_SDK_ROOT
export PATH=$PATH:$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$ANDROID_SDK_ROOT/platform-tools

# Accept Android SDK licenses and install required SDK packages
yes | sdkmanager --licenses
sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0" "ndk;25.2.9519653"

# (Optional) Set up git hooks if present
if [ -d ".githooks" ]; then
  git config core.hooksPath .githooks
fi

# Print Java, protoc, and cargo versions for confirmation
java -version
protoc --version
cargo --version || echo "Cargo not found"

echo "postCreate.sh completed. Ready to build Threema Libre."