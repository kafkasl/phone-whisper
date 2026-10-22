SHELL := /bin/bash

-include .env
PHONE_HOST ?= pixel-5
SSH_PORT   ?= 8022
APK        := app/build/outputs/apk/debug/app-debug.apk

SHERPA_VERSION := 1.12.28
SHERPA_SHA256  := 8a7342a6b2081baf93d85e1e4e5578e9d269e6bbeae2d749d978a1f15ae6278c
JNI_DIR        := app/src/main/jniLibs/arm64-v8a
JNI_LIBS       := $(JNI_DIR)/libsherpa-onnx-jni.so $(JNI_DIR)/libonnxruntime.so

export JAVA_HOME := /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME := $(HOME)/Library/Android/sdk
export PATH := $(JAVA_HOME)/bin:$(PATH)

.PHONY: build test install adb-install push-model jni-libs clean

build: $(JNI_LIBS)
	./gradlew assembleDebug
	@echo "APK: $(APK)"

test:
	./gradlew testDebugUnitTest

install: build
	scp -P $(SSH_PORT) $(APK) $(PHONE_HOST):~/storage/downloads/phone-whisper.apk
	ssh -p $(SSH_PORT) $(PHONE_HOST) "termux-open ~/storage/downloads/phone-whisper.apk"
	@echo "APK sent — approve install on phone"

adb-install: build
	$(ANDROID_HOME)/platform-tools/adb install -r $(APK)

## Download the sherpa-onnx native libs (gitignored) into jniLibs/
jni-libs: $(JNI_LIBS)

$(JNI_LIBS):
	@tmp=$$(mktemp -d) && trap 'rm -rf "$$tmp"' EXIT && \
	echo "Downloading sherpa-onnx v$(SHERPA_VERSION) Android libs..." && \
	curl -fSL -o "$$tmp/sherpa.tar.bz2" https://github.com/k2-fsa/sherpa-onnx/releases/download/v$(SHERPA_VERSION)/sherpa-onnx-v$(SHERPA_VERSION)-android.tar.bz2 && \
	echo "$(SHERPA_SHA256)  $$tmp/sherpa.tar.bz2" | shasum -a 256 -c - && \
	tar xjf "$$tmp/sherpa.tar.bz2" -C "$$tmp" ./jniLibs/arm64-v8a && \
	mkdir -p $(JNI_DIR) && \
	cp "$$tmp/jniLibs/arm64-v8a/libsherpa-onnx-jni.so" "$$tmp/jniLibs/arm64-v8a/libonnxruntime.so" $(JNI_DIR)/

## Push a model to the phone's internal storage (usage: make push-model MODEL=/path/to/model-dir)
push-model:
	@test -n "$(MODEL)" || (echo "Usage: make push-model MODEL=/path/to/model-dir" && exit 1)
	$(ANDROID_HOME)/platform-tools/adb push $(MODEL)/ /data/local/tmp/$(notdir $(MODEL))/
	$(ANDROID_HOME)/platform-tools/adb shell "run-as com.kafkasl.phonewhisper mkdir -p files/models/$(notdir $(MODEL))"
	@for f in $$(ls $(MODEL)/*.onnx $(MODEL)/*.ort $(MODEL)/*.txt 2>/dev/null); do \
		echo "  copying $$(basename $$f)..."; \
		$(ANDROID_HOME)/platform-tools/adb shell "run-as com.kafkasl.phonewhisper cp /data/local/tmp/$(notdir $(MODEL))/$$(basename $$f) files/models/$(notdir $(MODEL))/$$(basename $$f)"; \
	done
	@echo "Model pushed: $(notdir $(MODEL))"

clean:
	./gradlew clean
