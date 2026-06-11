# HelmetAI - Real-time Traffic Violation & ANPR System

HelmetAI is a high-performance Android application designed for real-time traffic enforcement. It uses a multi-stage neural pipeline to detect helmet violations, triple-riding, and mobile phone usage, followed by automated License Plate Recognition (ANPR).

## 🚀 Multi-Stage Detection Pipeline

The system operates in three distinct stages to maximize accuracy and efficiency:

### Stage 1: Primary Violation Detection
- **Model**: `model.onnx` (YOLOv8) 
- **Target**: Full camera frame.
- **Detections**: `WITHOUT_HELMET`, `MORE_THAN_TWO_PERSONS`, `USING_MOBILE`, `WITH_HELMET`, `normal`.
- **Logic**: If a violation or a general bike/rider is detected, the system triggers Stage 2.

### Stage 2: Intelligent Cropping
- **Process**: The system extracts a high-resolution crop of the detected "Rider" or bike area from the original frame to provide a clearer image for the secondary model.

### Stage 3: Secondary Validation & ANPR
- **Model**: `anpr.onnx` (YOLOv8) & Google ML Kit
- **ANPR**: Detects the exact bounding box of the number plate within the rider crop.
- **OCR**: Uses Google ML Kit Text Recognition to extract alphanumeric characters.
- **Validation**: 
    - **Regex Filter**: Validates extracted text against Indian Number Plate formats (e.g., `TN 33 AB 1234`).
    - **Front View Check**: Performs a second check for helmet status to reduce false positives.

---

## 🛠️ Build & Installation Guide

To install the app on your Android device (optimized for Nothing Phone 2a), follow these steps:

### 1. Prepare Your Phone
1. **Enable Developer Options**: Go to `Settings > About Phone` and tap `Build Number` 7 times.
2. **Enable USB Debugging**: Go to `Settings > System > Developer Options` and toggle **USB Debugging** to ON.
3. **Connect**: Plug your phone into your computer via USB and accept the prompt on the screen.

### 2. Build and Install
Run the following commands in your terminal from the project root:

```powershell
# Build and install directly to your connected phone
.\gradlew installDebug
```

### 3. Generate APK only
If you want to build the APK file for manual distribution:
```powershell
$env:JAVA_HOME = "C:\Program Files\Java\latest\jdk-21" ; & "C:\Users\tharu\.gradle\wrapper\dists\gradle-8.5-bin\5t9huq95ubn472n8rpzujfbqh\gradle-8.5\bin\gradle.bat" assembleDebug ; & "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" install -r d:\Codings\HelmetAI\app\build\outputs\apk\debug\app-debug.apk ; & "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" shell am start -n com.example.helmetai/com.example.helmetai.MainActivity
```
**APK Location**: `app/build/outputs/apk/debug/app-debug.apk`

---

## 📦 Technology Stack
- **Language**: Kotlin
- **Camera API**: CameraX
- **Inference Engine**: ONNX Runtime (Mobile)
- **OCR**: Google ML Kit Text Recognition
- **Base Model**: YOLOv8 (S)
