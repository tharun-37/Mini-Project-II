================================================================================
                    TRAFFIC EYE - ADVANCED SYSTEM DOCUMENTATION
================================================================================
This document provides a highly technical, end-to-end architectural mapping 
of the Traffic Eye project. It integrates core structural definitions, detailed 
native codebase mappings, training pipelines, and data architectures.

--------------------------------------------------------------------------------
1. SYSTEM PROBLEM STATEMENT & CHALLENGES
--------------------------------------------------------------------------------
The project addresses the critical issue of automated traffic enforcement for 
two-wheeler safety, specifically targetting helmet non-compliance and triple-riding. 
Standard automated enforcement systems rely heavily on fixed, high-cost CCTV 
infrastructure linked to heavy cloud servers. This approach introduces massive 
network bandwidth choke points due to continuous HD video streaming, high 
processing latency, and significant deployment costs that make scaling across 
mid-tier urban zones unfeasible. 

Furthermore, existing centralized pipelines often experience tracking splits, 
failing to properly bind a transient vehicle license plate to a distinct rider 
violation event occurring moments apart under dynamic lighting and occlusion. 
HelmetAI solves this by moving the entire multi-model inference pipeline to an 
ultra-mobile Android edge node, decoupling enforcement from static infrastructure.

--------------------------------------------------------------------------------
2. PROJECT ARCHITECTURAL OBJECTIVES
--------------------------------------------------------------------------------
The core objective is to implement a hybrid edge-cloud AI system that performs 
real-time traffic violation detection on consumer-grade mobile devices. 
- The Edge Client must capture live video, run dual deep learning networks 
  concurrently via optimized runtimes, handle localized character recognition, 
  and use a time-buffered synchronization mechanism to match vehicles to violations. 
- The Backend Objectives are to provide a low-latency administrative interface 
  for evidence validation and orchestrate automated, legally verifiable 
  notifications over ubiquitous instant messaging networks. 
- The Ultimate System Goal is reducing data transmission overhead by shifting 
  from continuous video streaming to data-thrifty, multi-frame asynchronous 
  evidence packet uploading.

--------------------------------------------------------------------------------
3. DIRECT DATA ARCHITECTURE MAP
--------------------------------------------------------------------------------
| Component | Code Implementation & Tools | Native Architecture Roles |
| :--- | :--- | :--- |
| **Android Edge Node** | Kotlin, CameraX, ONNX Runtime Android (1.18.0), Google ML Kit OCR, OkHttp | ImageAnalysis analyzer, YUV to NV21 conversion, NCHW float normalization, Dual-Engine parallel inference, Client-side IoU/NMS, Asynchronous 10s sliding-window map, Multi-part payload compression |
| **Central Web Server**| Python, Flask, SQLite3, Werkzeug, Requests | RESTful routing, Auto-initializing database schema, Secure timestamp file saving, Meta WhatsApp Media CDN integration |
| **Admin Interface**  | HTML5, CSS3 Custom Variables, Vanilla ES6 JavaScript | Dark glassmorphism UI, 5-second asynchronous fetch polling, Live DOM metric updates, Client-side carousel state controls |
| **Training Pipeline** | Python, PyTorch, CUDA, Ultralytics YOLOv8 | Custom object detection, 100-epoch optimization, Computational graph modifications (NMS baked-in vs Client-delegated NMS) |

--------------------------------------------------------------------------------
4. ANDROID EDGE CLIENT (HelmetAI) TECHNICAL CODE LOGIC
--------------------------------------------------------------------------------
The frontend architecture is implemented natively in Kotlin on Android SDK 34. 
Continuous video frames are captured via the Android Jetpack CameraX API using an 
ImageAnalysis.Analyzer instance configured to run on a single-threaded 
ExecutorService to isolate inference from the main UI thread.

The frame pipeline handles real-time format conversion and input normalization. 
Raw frames delivered in YUV_420_888 format are converted to an interim NV21 byte 
array, compressed to a JPEG format byte array via YuvImage, and decoded into an 
ARGB_8888 Bitmap. To prepare the image for neural network consumption, the pixel 
values are extracted into a float array, normalized from the [0, 255] integer range 
to [0.0f, 1.0f], and structured into a planar NCHW FloatBuffer of dimension 
[1 x 3 x Width x Height].

On-device machine learning runs via two isolated Microsoft ONNX Runtime Android 
sessions (v1.18.0) leveraging CPU multi-threading optimized with 4 intra-op 
threads while keeping NNAPI disabled to maintain hardware compatibility across 
non-flagship chipsets. 
- The First Session executes a 5-class YOLOv8 nano violation detection model 
  trained at 640x640 pixels; this model features a baked-in Non-Maximum Suppression 
  (NMS) layer directly inside its ONNX execution graph, allowing the client to 
  parse output tensors containing finalized bounding boxes for classes like 
  riders, passengers, and helmet compliance states.
- The Second Session executes a 4-class ANPR location model trained at 320x320 
  pixels with raw outputs; the Kotlin code resolves these raw tensors by performing 
  a localized Intersection-over-Union (IoU) calculation and applying a custom 
  client-side Non-Maximum Suppression algorithm to identify the precise bounding 
  rectangle of the vehicle's license plate.

Once the license plate bounding box is located, the sub-bitmap is cropped by 
translating normalized box coordinates back to absolute pixel dimensions. This 
cropped region is passed directly to the Google Play Services ML Kit Text 
Recognition API (v19.0.0) for native optical character recognition (OCR).

To cleanly couple moving vehicles with their respective violations under fluctuating 
frame rates, the client implements an Asynchronous Sliding Window Matching Logic. 
A thread-safe ConcurrentHashMap named `recentViolations` keeps track of recent 
detections within a strict temporal threshold defined by SLIDING_WINDOW_MS = 10000 
(10 seconds). The matcher continuously checks two real-time states: 
- Case 1: Evaluates whether a newly extracted plate string matches an active helmet 
  violation cached within the 10-second memory window.
- Case 2: Evaluates whether a newly registered violation can be bound to a plate 
  string identified moments prior.

When a valid match occurs, the client captures three distinct graphic assets: 
the full environmental violation frame, the full license plate context frame, and 
the tight license plate crop. The state machine transitions to an upload phase 
where an OkHttp Client (v4.12.0) constructs a MultipartBody.Builder payload. 
To save network bandwidth without compromising judicial evidence, the full 
environmental and context frames are scaled down by 50% and compressed at 70% 
JPEG quality, whereas the cropped license plate image is retained at 100% quality 
to avoid artifact degradation of the alphanumeric characters. The compiled packet 
is transmitted asynchronously via OkHttp's non-blocking enqueue architecture to 
the central server. 

During live operation, an OverlayView component handles custom UI canvas rendering, 
painting bounding boxes across the screen using clear color-coded indicators—green 
for compliant riders, blue for detected license plates, and red for active traffic 
violations—along with a centered target reticle. A fast Singleton memory object 
named `ViolationCache` handles immediate data routing between activities.

--------------------------------------------------------------------------------
5. FLASK ADMIN BACKEND & DASHBOARD CODE LOGIC
--------------------------------------------------------------------------------
The centralized server layer is built using Python 3 via a Flask WSGI framework 
(v3.0.0) coupled with Flask-CORS for secure cross-origin resource sharing. Upon 
initialization, a built-in SQLite3 engine creates and structures a relational 
database named `reports.db` containing tables for violation metadata, alphanumeric 
strings, timestamps, and file storage links.

The backend exposes three core operational REST API endpoints:
1. `GET /api/reports`: Queries the SQLite database to return a complete list of 
   logged violations formatted as a JSON array, sorted in descending order by 
   the auto-incrementing report ID.
2. `POST /api/report`: Handles high-volume multi-part form submissions containing 
   three image assets (image1, image2, image3) along with accompanying text metadata. 
   The endpoint utilizes Werkzeug secure file utilities to map incoming streams to 
   localized, timestamp-keyed JPEG files inside an uploads directory before writing 
   the tracking entry into `reports.db`.
3. `POST /api/reports/<id>/status`: Validates administrative updates, toggling 
   violation flags between Pending and Processed states.
4. `POST /api/reports/<id>/whatsapp`: Triggers automated legal notifications. 
   The endpoint fetches the incident records from the database, runs an HTTP POST 
   command via the Python requests library to pass the primary environmental image 
   to Meta’s WhatsApp Media API CDN, receives a verified media handle, and immediately 
   dispatches a structured WhatsApp template message with the embedded high-resolution 
   evidence image and dynamic text metadata directly to the violator’s registered 
   telephone number.

The administrative interface is a single-page web dashboard using premium dark 
glassmorphism design principles. The layout is written in structured HTML5, 
stylized via a custom CSS3 design system built on radial gradients, backdrop-filters, 
custom badges, and smooth element transformations. The interactive layer is driven 
by Vanilla ES6 JavaScript which executes an asynchronous live polling function every 
5 seconds targeting `/api/reports`. The script dynamically repopulates metric cards 
tracking total, pending, and processed counts, updates the interactive records table, 
and drives an overlay modal complete with multi-frame image carousel navigation.

--------------------------------------------------------------------------------
6. DEEP LEARNING MODEL TRAINING PIPELINE
--------------------------------------------------------------------------------
The deep learning layer uses two separate training pipelines built using the 
Ultralytics YOLOv8 framework and PyTorch, accelerated via NVIDIA CUDA computing 
architectures.

- **The Licensing Pipeline (`train_anpr.py`)**: Initializes pretrained yolov8n.pt 
  nano weights and trains across a 4-class number plate custom dataset for 100 epochs 
  at a scaled down resolution of 320x320 pixels to maximize inference speeds. 
  During export, the framework sets `nms=False`, purposefully stripping the Non-Maximum 
  Suppression layer out of the ONNX computational graph to delegate vector optimization 
  entirely to the mobile client's native logic.
- **The Violation Pipeline (`train_objdetect.py`)**: Trains yolov8n.pt across a 
  5-class custom violation dataset (focusing on riders, passengers, helmets, and 
  occupancy counts) for 100 epochs at full 640x640 pixel resolution to ensure high 
  feature preservation across clustered environments. This pipeline is compiled to 
  ONNX with `nms=True`, baking the NMS bounding box consolidation logic directly 
  into the exported graph to deliver clean, pre-filtered prediction arrays straight 
  to the Android runtime engine.

--------------------------------------------------------------------------------
7. BEHAVIORAL IMPACT & CITIZEN ACCOUNTABILITY MECHANISMS
--------------------------------------------------------------------------------
The decentralized nature of the edge architecture radically alters the psychological 
dynamic of traffic compliance by removing the predictable "enforcement blind spots" 
associated with static infrastructure. Because static CCTV cameras are fixed and 
highly visible, citizens exhibit selective compliance, obeying traffic laws only 
within the camera’s immediate field of view and reverting to reckless behaviors 
immediately after.

HelmetAI replaces this predictable surveillance model with dynamic, unpredictable 
mobile enforcement points operated by mobile authorities. This constant possibility 
of mobile detection significantly increases the perceived risk of apprehension, 
transforming traffic compliance from a localized reaction into a continuous habit.

Furthermore, the integration of the Meta Cloud API drastically shortens the 
accountability loop. Instead of receiving an e-challan weeks after a violation 
occurs—which weakens the behavioral deterrent—violators receive an instantaneous 
WhatsApp alert containing high-resolution, unmanipulable multi-frame evidence 
(the context frame, plate crop, and time-stamped violation metadata). This immediate, 
indisputable delivery of evidence forces high personal responsibility, eliminates 
civil disputes over violation validity, and drives long-term compliance through 
rapid, automated behavioral reinforcement.

--------------------------------------------------------------------------------
8. RURAL ENFORCEMENT & INFRASTRUCTURE DEMOCRATIZATION
--------------------------------------------------------------------------------
Traditional traffic monitoring systems suffer from severe geographical inequity, 
as high infrastructure costs restrict CCTV deployments almost exclusively to 
tier-1 urban intersections. Rural roads, village junctions, and periphery highways 
remain completely unmonitored, leading to significantly higher rates of severe 
two-wheeler violations, such as triple-riding and helmet non-compliance, away 
from city centers.

HelmetAI democratizes traffic enforcement by eliminating the prerequisite of 
grid-connected infrastructure, fiber-optic backhauls, and expensive roadside 
computing enclosures. By packaging a dual-model deep learning inference engine, 
localized character recognition, and asynchronous data caching into a 
self-contained Android edge client, the system can be deployed instantly anywhere 
by moving transit authorities or local rural police.

In areas with unstable or completely absent cellular connectivity, the system's 
local SQLite database caching and thread-safe memory buffers ensure that inference, 
plate extraction, and evidence stitching happen seamlessly entirely offline. 
The compiled, compressed multipart evidence packets are held securely in device 
storage and automatically synchronized to the Flask central server the moment the 
mobile edge device enters an area with network coverage. This portable, 
infrastructure-agnostic deployment model effectively extends automated, high-precision 
traffic enforcement to rural sectors and remote villages for the first time, 
systematically driving down the high traffic violation and fatality rates in 
previously unmonitored zones.

--------------------------------------------------------------------------------
9. WORKSPACE FILE INDEX & SPECIFIC HIGHLIGHTS
--------------------------------------------------------------------------------
D:\Codings\FINAL MINI PROJECT 2\
│
├── FLASK ADMIN/
│   ├── app.py 
│   │   - Active DB: sqlite3 connection to 'reports.db', automatic schema initialization.
│   │   - WhatsApp Credentials: Token verification, Phone ID target, WhatsApp warning dispatching logic.
│   │   - Secure Upload: Werkzeug filename filtering, chronological sequence mappings (image1, image2, image3).
│   ├── templates/index.html
│   │   - Structure: Sidebar nav menu, metrics panel, glassmorphism data table, carousel overlay modal.
│   └── static/
│       ├── script.js
│       │   - AJAX controls: setInterval 5000ms polling, carousel slide transitions (prev/next), Meta API caller.
│       └── style.css
│           - Aesthetic Tokens: --bg-color (#0f172a), --panel-bg, --accent-primary (#10b981), glassmorphism filter.
│
├── HelmetAI/
│   ├── app/src/main/java/com/example/helmetai/
│   │   ├── MainActivity.kt
│   │   │   - Execution context: SingleThreadExecutor, Volatile capturing state, 10s ConcurrentHashMap window.
│   │   │   - Google OCR: TextRecognition.getClient(), filters alphanumeric length >= 5.
│   │   ├── ObjectDetector.kt
│   │   │   - Sessions: loadModels() for model.onnx and anpr.onnx.
│   │   │   - Logic: 4 CPU threads, intraOpNumThreads config, normalized FloatBuffer, custom IOU/NMS vector calculation.
│   │   ├── ReportActivity.kt
│   │   │   - OkHttp networking: MultipartBody multipart uploads, custom compression ratios (70% full frame vs 100% crop).
│   │   ├── OverlayView.kt
│   │   │   - Live UI Painting: Draw bounding boxes in real-time with confidence scores, custom center green reticle.
│   │   ├── ImageUtils.kt
│   │   │   - Conversion utilities: NV21 YuvImage JPEG conversion, Bitmap cropping.
│   │   └── ViolationCache.kt
│   │       - Shared cache: Singleton transport mechanism for passing live bitmaps between activities.
│   └── app/build.gradle
│       - Platform: compileSdk 34, minSdk 24, NDK 27.1.12297006.
│       - Key Libraries: camera-lifecycle, onnxruntime-android, mlkit-text-recognition, okhttp.
│
└── model trainings/
    ├── train_anpr.py
    │   - Config: Ultralytics YOLOv8, imgsz=320, nms=False, simplify=True, format="onnx".
    ├── train_objdetect.py
    │   - Config: Ultralytics YOLOv8, imgsz=640, nms=True, simplify=True, format="onnx".
    └── labels.txt
        - Active classes: MORE_THAN_TWO_PERSONS, USING_MOBILE, WITHOUT_HELMET, WITH_HELMET, normal.

================================================================================
