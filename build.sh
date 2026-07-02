#!/bin/bash
# Build script for Melodix OpenAL Patch
# This script creates a patched JAR by replacing audio classes

set -e

echo "=== Melodix OpenAL Patch Builder ==="
echo ""

# Check Java
if ! command -v java &> /dev/null; then
    echo "ERROR: Java not found. Please install Java 21+."
    exit 1
fi

JAVA_VERSION=$(java -version 2>&1 | head -n 1 | cut -d'"' -f2)
echo "Java version: $JAVA_VERSION"

# Check Gradle
if ! command -v ./gradlew &> /dev/null; then
    echo "ERROR: Gradle wrapper not found."
    exit 1
fi

# Step 1: Build the project
echo ""
echo "[1/4] Building OpenAL audio classes..."
./gradlew build

if [ ! -f "build/libs/melodix-openal-1.0.0.jar" ]; then
    echo "ERROR: Build failed. Check errors above."
    exit 1
fi

echo "✅ Build successful"

# Step 2: Extract original JAR
echo ""
echo "[2/4] Extracting original JAR..."
mkdir -p build/original-extracted
rm -rf build/original-extracted/*
cd build/original-extracted
jar xf ../../original/melodix-1.0.0.jar
cd ../..

# Step 3: Replace audio classes
echo ""
echo "[3/4] Replacing audio classes..."

# Remove old audio classes
rm -f build/original-extracted/com/musicplayer/AudioEngine.class
rm -f build/original-extracted/com/musicplayer/AudioEngine\$PlaybackSlot.class
rm -f build/original-extracted/com/musicplayer/AudioEngine\$DuckStage.class
rm -f build/original-extracted/com/musicplayer/player/FullPcmDecoder.class
rm -f build/original-extracted/com/musicplayer/player/FullPcmDecoder\$Result.class
rm -f build/original-extracted/com/musicplayer/player/PcmStream.class
rm -f build/original-extracted/com/musicplayer/AudioDSP.class
rm -f build/original-extracted/com/musicplayer/AudioDSP\$AllPassFilter.class
rm -f build/original-extracted/com/musicplayer/AudioDSP\$BiQuad.class
rm -f build/original-extracted/com/musicplayer/AudioDSP\$CombFilter.class

# Extract new classes from our build
mkdir -p build/new-classes
cd build/new-classes
jar xf ../libs/melodix-openal-1.0.0.jar com/musicplayer/AudioEngine.class
jar xf ../libs/melodix-openal-1.0.0.jar com/musicplayer/AudioEngine\$PlaybackSlot.class
jar xf ../libs/melodix-openal-1.0.0.jar com/musicplayer/AudioEngine\$DuckStage.class
jar xf ../libs/melodix-openal-1.0.0.jar com/musicplayer/player/FullPcmDecoder.class
jar xf ../libs/melodix-openal-1.0.0.jar com/musicplayer/player/FullPcmDecoder\$Result.class
jar xf ../libs/melodix-openal-1.0.0.jar com/musicplayer/player/PcmStream.class
jar xf ../libs/melodix-openal-1.0.0.jar com/musicplayer/player/OpenALAudioPlayer.class
jar xf ../libs/melodix-openal-1.0.0.jar com/musicplayer/AudioDSP.class
jar xf ../libs/melodix-openal-1.0.0.jar com/musicplayer/AudioDSP\$AllPassFilter.class
jar xf ../libs/melodix-openal-1.0.0.jar com/musicplayer/AudioDSP\$BiQuad.class
jar xf ../libs/melodix-openal-1.0.0.jar com/musicplayer/AudioDSP\$CombFilter.class
cd ../..

# Copy new classes to original extracted
# Note: We keep AudioDSP as-is from original since it doesn't use javax.sound
# (Actually AudioDSP doesn't use javax.sound, so we can keep original)
# Let's only replace the ones that actually use javax.sound

cp -r build/new-classes/com build/original-extracted/

echo "✅ Classes replaced"

# Step 4: Repack JAR
echo ""
echo "[4/4] Repacking JAR..."
cd build/original-extracted

# Update fabric.mod.json to indicate patched version
if [ -f "fabric.mod.json" ]; then
    cat > fabric.mod.json << 'EOF'
{
  "schemaVersion": 1,
  "id": "musicplayer",
  "version": "1.0.0-openal",
  "name": "Melodix Music Player (OpenAL Patch)",
  "description": "Melodix patched for Android/Pojav compatibility using OpenAL backend.",
  "authors": ["distelbus-svg (original)", "OpenAL Patch"],
  "license": "MIT",
  "environment": "client",
  "icon": "assets/musicplayer/icon.png",
  "entrypoints": {
    "client": [
      "com.musicplayer.MusicPlayerMod"
    ]
  },
  "mixins": [
    "musicplayer.mixins.json"
  ],
  "depends": {
    "fabricloader": ">=0.16.0",
    "minecraft": ">=1.21.2",
    "java": ">=21",
    "fabric-api": "*"
  }
}
EOF
fi

# Remove old signature files (they'll be invalid after modification)
rm -rf META-INF/*.SF META-INF/*.RSA META-INF/*.DSA

# Create new JAR
jar cvf ../../melodix-1.0.0-openal.jar .
cd ../..

echo ""
echo "=== BUILD COMPLETE ==="
echo ""
echo "Output: melodix-1.0.0-openal.jar"
echo ""
echo "Installation:"
echo "  1. Copy melodix-1.0.0-openal.jar to your mods/ folder"
echo "  2. Remove the original melodix-1.0.0.jar"
echo "  3. Launch Minecraft"
echo ""
echo "This patched version uses OpenAL instead of javax.sound.sampled"
echo "and should work on both PC and Android (Pojav/MJ Launcher)."
