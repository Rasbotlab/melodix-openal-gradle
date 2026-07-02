# Melodix OpenAL
# (Unofficial Mod For Melodix)

A community-driven fork of Melodix Music Player focused on Android compatibility.

## Overview

Melodix OpenAL replaces the original Java Sound backend (`javax.sound.sampled`) with Minecraft's native OpenAL audio engine.

This allows Melodix to work on Android runtimes such as PojavLauncher and MJ Launcher, where Java Sound is unavailable.

## Features

- 🎵 OpenAL audio backend
- 📱 Android support (PojavLauncher & MJ Launcher)
- 💻 Windows, Linux, macOS support
- 🎧 MP3 playback
- 🎼 WAV playback
- 🚧 FLAC support (Work in Progress)
- 🚧 Streaming playback
- 🚧 Audio visualizer improvements
- 🚧 Performance optimization for low-end devices

## Project Goals

- Remove all `javax.sound.sampled` dependencies
- Use Minecraft's built-in audio engine
- Reduce memory usage
- Improve Android compatibility
- Keep compatibility with Fabric Minecraft

## Status

This project is currently under active development.

## Build

```bash
chmod +x build.sh
./build.sh
```

or

```bash
gradlew build
```

## Planned

- FLAC decoder
- AAC support
- Better seeking
- Gapless playback
- Playlist improvements
- Android optimization
- MJ Launcher compatibility
- PojavLauncher optimization

## Disclaimer

This project is an independent community fork and is not affiliated with the original Melodix developers.
